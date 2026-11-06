"""와이퍼 판단과 외부 자료(레이더·특보·재난문자)를 합친다(설계 6.10.4).

위험을 올리는 신호는 어느 출처든 받고, 내리는 신호는 받지 않는다. 최종 단계는 출처별 단계의 최댓값이다.

| 출처 | 단계 | 근거 |
|---|---|---|
| 와이퍼 (포화 구간은 레이더가 더 크면 레이더 값) | NONE~DANGER | `vehicles`, 30분 넘게 포화면 `saturated` |
| 레이더만 | 최대 WARNING | `radar` |
| 지하차도 특보 구역의 호우특보 | CAUTION | `kma_warning` |
| 재난문자: 지하차도 이름 + 통제·침수 | DANGER | `alert_closure` |
| 재난문자: 그 구 또는 시 전체의 호우 안내 | CAUTION | `alert_rain` |
"""

from dataclasses import replace
from datetime import datetime, timedelta

from .external.official import Alert, OfficialSource, RainWarning, classify_alert
from .external.radar import RadarSource
from .grid import cell_center
from .rainmap import BIN, BINS_PER_HOUR, CellEstimate
from .underpass import LEVEL_ORDER, RADIUS_KM, Level, Underpass, UnderpassRisk, assess, distance_km, level_from

ALERT_WINDOW = timedelta(hours=2)  # 가정치. 이보다 오래된 재난문자는 지난 일로 본다
SATURATED_REASON_MIN = 30


class NoRadar:
    def cells(self, at: datetime) -> dict[tuple[int, int], float]:
        return {}

    def mmph(self, row: int, col: int, at: datetime) -> float | None:
        return None


class NoOfficial:
    def warnings(self, at: datetime) -> list[RainWarning]:
        return []

    def alerts(self, at: datetime, since: datetime) -> list[Alert]:
        return []


def _max(*levels: Level) -> Level:
    return max(levels, key=LEVEL_ORDER.index)


def _with_radar(c: CellEstimate, radar: RadarSource) -> CellEstimate:
    """포화 구간을 그 구간이 끝날 때의 레이더 값으로 바꾼다(레이더가 더 클 때만)."""
    bins = []
    for b in c.bins:
        r = radar.mmph(c.row, c.col, b.start + BIN) if b.saturated else None
        bins.append(replace(b, mmph=r) if r is not None and r > (b.mmph or 0.0) else b)
    if bins == c.bins:
        return c
    return replace(c, bins=bins, mmph=bins[-1].mmph if bins[-1].mmph is not None else c.mmph,
                   acc1h_mm=sum(b.mmph or 0.0 for b in bins) * (BIN / timedelta(hours=1)))


def _radar_near(up: Underpass, radar: RadarSource, at: datetime) -> tuple[float, float]:
    """반경 안 레이더 칸의 최대 세기와 최대 1시간 누적(10분 구간 6개)."""
    mmph = acc = 0.0
    for (row, col), v in radar.cells(at).items():
        lat, lng = cell_center(row, col)
        if distance_km(up.lat, up.lng, lat, lng) > RADIUS_KM:
            continue
        past = (radar.mmph(row, col, at - k * BIN) for k in range(BINS_PER_HOUR))
        mmph = max(mmph, v)
        acc = max(acc, sum(p or 0.0 for p in past) * (BIN / timedelta(hours=1)))
    return mmph, acc


def fuse(underpasses: list[Underpass], cells: list[CellEstimate], radar: RadarSource,
         official: OfficialSource, at: datetime) -> list[UnderpassRisk]:
    fixed = [_with_radar(c, radar) for c in cells]
    warned_areas = {w.area_code for w in official.warnings(at)}
    alerts = official.alerts(at, since=at - ALERT_WINDOW)
    out = []
    for up, wiper in zip(underpasses, assess(underpasses, fixed)):
        reasons, linked = [], []
        levels: list[Level] = [wiper.level]
        if wiper.level != "NONE":
            reasons.append("vehicles")
            if any(c.saturated_min >= SATURATED_REASON_MIN for c in cells
                   if distance_km(up.lat, up.lng, c.lat, c.lng) <= RADIUS_KM):
                reasons.append("saturated")

        r_mmph, r_acc = _radar_near(up, radar, at)
        r_level = min(level_from(r_mmph, r_acc, up.base_risk), "WARNING", key=LEVEL_ORDER.index)
        if r_level != "NONE":
            levels.append(r_level)
            reasons.append("radar")

        if up.warning_area in warned_areas:
            levels.append("CAUTION")
            reasons.append("kma_warning")

        kinds = set()
        for a in alerts:
            for _, kind in classify_alert(a, [up]):
                kinds.add(kind)
                linked.append({"sent_at": a.sent_at, "text": a.text})
        if "closure" in kinds:
            levels.append("DANGER")
            reasons.append("alert_closure")
        if "rain" in kinds:
            levels.append("CAUTION")
            reasons.append("alert_rain")

        out.append(replace(wiper, level=_max(*levels), mmph_10m=max(wiper.mmph_10m, r_mmph),
                           acc1h_mm=max(wiper.acc1h_mm, r_acc), reasons=reasons, alerts=linked))
    return out
