"""레이더: 기상청 API허브 HSR 합성 반사도를 와이퍼넷 칸별 mm/h로 바꾼다.

- 자료: API허브 '레이더 → 2.2.1 HSR에 마스킹처리'(`nph-rdr_cmp1_api`, cmp=HSR, qcd=MSK, obs=ECHO).
  문서대로 disp=B는 nx, ny(2바이트) 다음 nx*ny개 short이고, 값은 dBZ*100, 격자는 왼쪽 아래부터
  가로 우선이다. -30000은 관측 영역 밖, -25000은 에코 없음. 바이트 순서는 문서에 없어서 응답의
  격자 수(2305, 2881)로 little-endian임을 확인했다.
- 반사도 → 강우강도는 Marshall-Palmer(Z = 200 R^1.6)를 쓴다. API허브 문서에 나온 식이 아니라
  가정이다. 같은 시각 AWS와 비교한 결과는 docs/setup-status.md에 있다.
- 격자점 위경도는 '4.1 격자데이터 위경도'로 받아 데모 지역만 data/radar_grid_gangnam.csv에 둔다.
- 시뮬레이터 입력으로는 쓰지 않는다(설계 6.10.3).
"""

import csv
import struct
import time
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Protocol
from urllib.parse import urlencode

import httpx

from ..grid import cell_of

APIHUB = "https://apihub.kma.go.kr/api/typ01/cgi-bin/url"
NO_COVERAGE = -30000
NO_ECHO = -25000
FRAME = timedelta(minutes=5)  # 합성 주기
BIN = timedelta(minutes=10)  # 기록 파일 한 줄 = 장면 두 개의 평균
LIVE_CACHE_S = 300
GRID_POINTS = Path(__file__).resolve().parents[2] / "data" / "radar_grid_gangnam.csv"

Cell = tuple[int, int]


@dataclass(frozen=True)
class GridPoint:
    x: int
    y: int
    lat: float
    lng: float


def load_grid_points(path: str | Path) -> list[GridPoint]:
    with Path(path).open(newline="") as f:
        return [GridPoint(int(r["x"]), int(r["y"]), float(r["lat"]), float(r["lng"])) for r in csv.DictReader(f)]


def dbz_to_mmph(dbz: float) -> float:
    return (10 ** (dbz / 10) / 200) ** (1 / 1.6)


def frame_values(raw: bytes, points: list[GridPoint]) -> dict[tuple[float, float], float]:
    """disp=B 응답 하나에서 격자점별 mm/h. 관측 영역 밖인 점은 뺀다."""
    nx, ny = struct.unpack_from("<hh", raw)
    if len(raw) != 4 + 2 * nx * ny:
        raise ValueError(f"레이더 응답 크기가 격자 수({nx}x{ny})와 맞지 않음: {len(raw)} bytes")
    out = {}
    for p in points:
        (v,) = struct.unpack_from("<h", raw, 4 + 2 * (p.y * nx + p.x))
        if v == NO_COVERAGE:
            continue
        out[(p.lat, p.lng)] = 0.0 if v == NO_ECHO else dbz_to_mmph(v / 100)
    return out


def grid_to_cells(values: dict[tuple[float, float], float]) -> dict[Cell, float]:
    """격자점 값을 와이퍼넷 칸으로 모은다(칸 안 격자점 평균)."""
    acc: dict[Cell, list[float]] = defaultdict(list)
    for (lat, lng), v in values.items():
        acc[cell_of(lat, lng)].append(v)
    return {c: sum(vs) / len(vs) for c, vs in acc.items()}


def hsr_url(key: str, tm: datetime | None = None) -> str:
    q = {"cmp": "HSR", "qcd": "MSK", "obs": "ECHO", "map": "HB", "disp": "B"}
    if tm is not None:
        q = {"tm": tm.strftime("%Y%m%d%H%M"), **q}  # tm이 없으면 가장 최근 장면
    return f"{APIHUB}/nph-rdr_cmp1_api?{urlencode({**q, 'authKey': key})}"


def _http_get_bytes(url: str) -> bytes:
    r = httpx.get(url, timeout=60)
    r.raise_for_status()
    return r.content


class RadarSource(Protocol):
    def cells(self, at: datetime) -> dict[Cell, float]:
        """`at`에 알 수 있는 가장 최근 레이더 값(칸별 mm/h)."""
        ...

    def mmph(self, row: int, col: int, at: datetime) -> float | None: ...


class ReplayRadarSource:
    """10분 구간 기록을 서버 시계에 맞춰 낸다. 구간은 마지막 장면(시작+5분)이 찍힌 뒤부터 쓴다."""

    def __init__(self, csv_path: str | Path) -> None:
        self._bins: dict[datetime, dict[Cell, float]] = defaultdict(dict)
        with Path(csv_path).open(newline="") as f:
            for r in csv.DictReader(f):
                self._bins[datetime.fromisoformat(r["ts"])][(int(r["row"]), int(r["col"]))] = float(r["mmph"])

    def cells(self, at: datetime) -> dict[Cell, float]:
        t = at - FRAME
        start = t - timedelta(minutes=t.minute % 10, seconds=t.second, microseconds=t.microsecond)
        return dict(self._bins.get(start, {}))

    def mmph(self, row: int, col: int, at: datetime) -> float | None:
        return self.cells(at).get((row, col))


class LiveRadarSource:
    """가장 최근 장면을 5분마다 받는다. 받지 못하면 마지막 결과를 쓴다."""

    def __init__(self, apihub_key: str, grid_points: str | Path = GRID_POINTS,
                 fetch: Callable[[str], bytes] = _http_get_bytes,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
        self._key = apihub_key
        self._points = load_grid_points(grid_points)
        self._fetch = fetch
        self._monotonic = monotonic
        self._cells: dict[Cell, float] = {}
        self._fetched_at: float | None = None

    def cells(self, at: datetime) -> dict[Cell, float]:
        now = self._monotonic()
        if self._fetched_at is None or now - self._fetched_at >= LIVE_CACHE_S:
            self._fetched_at = now
            try:
                self._cells = grid_to_cells(frame_values(self._fetch(hsr_url(self._key)), self._points))
            except httpx.HTTPError:
                pass
        return dict(self._cells)

    def mmph(self, row: int, col: int, at: datetime) -> float | None:
        return self.cells(at).get((row, col))
