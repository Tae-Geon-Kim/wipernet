"""공식 알림: 호우특보(기상청 API허브)와 재난문자(공개 보도로 정리한 기록).

- 특보: API허브 `wrn_met_data.php`(특보자료)와 `wrn_reg.php`(특보구역). 요청 인자와 응답 열은
  API허브 '예특보 → 기상특보' 페이지에서 확인한 것만 쓴다. 응답은 EUC-KR 텍스트다.
- 재난문자: 행정안전부 긴급재난문자 API는 2023년 이후 자료만 있고 공공누리 제4유형(상업적 이용 금지)이다.
  그래서 2022-08-08 기록은 보도로 손 정리했고, 실시간 재난문자는 받지 않는다(docs/setup-status.md).
"""

import json
import time
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal, Protocol
from urllib.parse import urlencode

import httpx

from ..underpass import Underpass

KST = timezone(timedelta(hours=9))
APIHUB = "https://apihub.kma.go.kr/api/typ01/url"
CITY = "서울특별시"  # 데모 지역의 시. 지하차도 목록에는 구만 있다
LIVE_CACHE_S = 60
LIVE_LOOKBACK = timedelta(days=7)  # 이보다 먼저 발표돼 아직 발효 중인 특보는 놓친다

Stress = Literal["ADVISORY", "WARNING"]
_STRESS_BY_LVL: dict[str, Stress] = {"2": "ADVISORY", "3": "WARNING"}  # 1(예비)은 특보가 아니다
_OPEN_CMDS = {"1", "2", "5", "6"}  # 발표, 대치, 연장, 변경
_CLOSE_CMDS = {"3", "4", "7"}  # 해제, 대치해제(자동), 변경해제

_CLOSURE_WORDS = ("통제", "침수", "진입 금지", "진입금지", "우회")
_RAIN_WORDS = ("호우", "폭우", "침수")


@dataclass
class RainWarning:
    area_code: str
    area_name: str
    stress: Stress
    start: datetime
    end: datetime | None


@dataclass
class Alert:
    sent_at: datetime
    regions: list[str]
    text: str
    source_url: str | None


class OfficialSource(Protocol):
    def warnings(self, at: datetime) -> list[RainWarning]:
        """`at`에 발효 중인 호우특보."""
        ...

    def alerts(self, at: datetime, since: datetime) -> list[Alert]:
        """`since` ~ `at`에 나온 재난문자."""
        ...


def classify_alert(alert: Alert, underpasses: list[Underpass]) -> list[tuple[str, Literal["closure", "rain"]]]:
    """문자가 어느 지하차도에 해당하는지. 본문에 이름 + 통제 낱말이면 closure, 그 구나 시 전체의 호우 안내면 rain."""
    rainy = any(w in alert.text for w in _RAIN_WORDS)
    out: list[tuple[str, Literal["closure", "rain"]]] = []
    for up in underpasses:
        if up.name in alert.text and any(w in alert.text for w in _CLOSURE_WORDS):
            out.append((up.id, "closure"))
        elif rainy and (CITY in alert.regions or f"{CITY} {up.gu}" in alert.regions):
            out.append((up.id, "rain"))
    return out


def _kst(s: str) -> datetime:
    return datetime.strptime(s, "%Y%m%d%H%M").replace(tzinfo=KST)


def _tm(t: datetime) -> str:
    return t.astimezone(KST).strftime("%Y%m%d%H%M")


def _rows(text: str) -> list[str]:
    return [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("#")]


def parse_warning_areas(text: str) -> dict[str, str]:
    """`wrn_reg.php` 응답 → {특보구역코드: 특보구역명}. 열: REG_ID TM_ST TM_ED REG_SP REG_UP REG_KO REG_NAME."""
    names = {}
    for ln in _rows(text):
        parts = ln.split()
        names[parts[0]] = parts[-1]
    return names


def parse_rain_warnings(text: str, area_names: dict[str, str]) -> list[RainWarning]:
    """`wrn_met_data.php` 응답(disp=0) → 구역별 호우주의보·경보 기간.

    열: TM_FC, TM_EF, TM_IN, STN, REG_ID, WRN, LVL, CMD, ... 발효시각(TM_EF)을 기간의 시작·끝으로 쓴다.
    단계가 바뀌면(주의보 → 경보) 앞 단계를 그 시각에 닫는다.
    """
    events = []
    for ln in _rows(text):
        c = [x.strip() for x in ln.split(",")]
        tm_ef, tm_in, reg, wrn, lvl, cmd = c[1], c[2], c[4], c[5], c[6], c[7]
        if wrn == "R" and lvl in _STRESS_BY_LVL:
            events.append((reg, tm_ef, tm_in, _STRESS_BY_LVL[lvl], cmd))
    out: list[RainWarning] = []
    current: dict[str, RainWarning] = {}
    for reg, tm_ef, _, stress, cmd in sorted(events):
        at = _kst(tm_ef)
        cur = current.get(reg)
        if cmd in _OPEN_CMDS and (cur is None or cur.stress != stress):
            if cur is not None:
                cur.end = at
            current[reg] = RainWarning(reg, area_names.get(reg, reg), stress, at, None)
            out.append(current[reg])
        elif cmd in _CLOSE_CMDS and cur is not None and cur.stress == stress:
            cur.end = at
            del current[reg]
    return out


def _active(warnings: list[RainWarning], at: datetime) -> list[RainWarning]:
    return [w for w in warnings if w.start <= at and (w.end is None or at < w.end)]


def load_warnings(path: str | Path) -> list[RainWarning]:
    return [RainWarning(w["area_code"], w["area_name"], w["stress"], datetime.fromisoformat(w["start"]),
                        datetime.fromisoformat(w["end"]) if w["end"] else None)
            for w in json.loads(Path(path).read_text(encoding="utf-8"))]


def load_alerts(path: str | Path) -> list[Alert]:
    return [Alert(datetime.fromisoformat(a["sent_at"]), a["regions"], a["text"], a["source_url"])
            for a in json.loads(Path(path).read_text(encoding="utf-8"))]


class ReplayOfficialSource:
    """파일 기록을 재생한다. `at`보다 늦게 알 수 있는 것(늦은 문자, 해제 시각)은 감춘다."""

    def __init__(self, warnings_path: str | Path, alerts_path: str | Path) -> None:
        self._warnings = load_warnings(warnings_path)
        self._alerts = load_alerts(alerts_path)

    def warnings(self, at: datetime) -> list[RainWarning]:
        return [replace(w, end=None) for w in _active(self._warnings, at)]

    def alerts(self, at: datetime, since: datetime) -> list[Alert]:
        return [a for a in self._alerts if since <= a.sent_at <= at]


def _http_get(url: str) -> str:
    r = httpx.get(url, timeout=10)
    r.raise_for_status()  # 활용신청 안 한 API는 403, 잘못된 키는 401
    return r.content.decode("cp949", errors="replace")


def apihub_url(path: str, key: str, **params: str) -> str:
    return f"{APIHUB}/{path}?{urlencode({**params, 'authKey': key})}"


def fetch_rain_warnings(key: str, tmfc1: datetime, tmfc2: datetime,
                        fetch: Callable[[str], str] = _http_get) -> list[RainWarning]:
    """발표시각이 tmfc1 ~ tmfc2인 호우특보를 받아 기간으로 만든다(기록 스크립트와 실시간이 같이 쓴다)."""
    names = parse_warning_areas(fetch(apihub_url("wrn_reg.php", key, tmfc="0")))
    text = fetch(apihub_url("wrn_met_data.php", key, wrn="R", tmfc1=_tm(tmfc1), tmfc2=_tm(tmfc2),
                            disp="0", help="0"))
    return parse_rain_warnings(text, names)


class LiveOfficialSource:
    """API허브 특보를 60초 캐시로 조회한다. 실패하면 마지막 결과를 쓴다. 재난문자는 받지 않는다(모듈 설명)."""

    def __init__(self, apihub_key: str, fetch: Callable[[str], str] = _http_get,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
        self._key, self._fetch, self._monotonic = apihub_key, fetch, monotonic
        self._cache: list[RainWarning] = []
        self._fetched_at: float | None = None

    def _refresh(self, at: datetime) -> None:
        now = self._monotonic()
        if self._fetched_at is not None and now - self._fetched_at < LIVE_CACHE_S:
            return
        self._fetched_at = now
        try:
            self._cache = fetch_rain_warnings(self._key, at - LIVE_LOOKBACK, at, self._fetch)
        except httpx.HTTPError:
            pass  # 외부 API 장애. 마지막 결과를 계속 쓴다

    def warnings(self, at: datetime) -> list[RainWarning]:
        self._refresh(at)
        return _active(self._cache, at)

    def alerts(self, at: datetime, since: datetime) -> list[Alert]:
        return []
