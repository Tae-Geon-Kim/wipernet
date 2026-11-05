"""와이퍼 이벤트를 격자 칸별 비 세기로 모은다.

- 칸마다 최근 10분 안에 서로 다른 차 3대 이상일 때만 값을 낸다(오경보 방지).
- 차마다 그 칸의 최신 세기를 mm/h로 바꾼 뒤 중앙값을 쓴다.
- 직전 1시간을 10분 구간 6개로 나눠 누적과 포화(와이퍼 상한)를 계산한다.
"""

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta
from statistics import median

from .grid import cell_center, cell_of
from .wiper import MAX_STAGE, auto_intensity, intensity_to_mmph, switch_intensity

MIN_VEHICLES = 3
BIN = timedelta(minutes=10)
BINS_PER_HOUR = 6
RAW_RETENTION = timedelta(hours=2)


@dataclass
class WiperEvent:
    vid: str
    ts: datetime
    lat: float
    lng: float
    switch: str
    state: str
    auto_wpm: float | None = None


@dataclass
class Bin:
    start: datetime
    mmph: float | None
    saturated: bool


@dataclass
class CellEstimate:
    row: int
    col: int
    lat: float
    lng: float
    mmph: float
    acc1h_mm: float
    vehicles: int
    saturated_min: int
    bins: list[Bin]


def _intensity(e: WiperEvent) -> float | None:
    if e.switch == "AUTO":
        return auto_intensity(e.auto_wpm)
    return switch_intensity(e.switch)


def _bin(start: datetime, events: list[WiperEvent]) -> tuple[Bin, int]:
    """(start, start + 10분] 안의 이벤트로 구간 하나를 만든다. 구간의 차 수도 돌려준다."""
    latest: dict[str, tuple[datetime, float]] = {}
    for e in events:
        i = _intensity(e)
        if i is None or not (start < e.ts <= start + BIN):
            continue
        if e.vid not in latest or e.ts >= latest[e.vid][0]:
            latest[e.vid] = (e.ts, i)
    values = [i for _, i in latest.values()]
    if len(values) < MIN_VEHICLES:
        return Bin(start, None, False), len(values)
    saturated = sum(i >= MAX_STAGE for i in values) * 2 >= len(values)
    return Bin(start, median(intensity_to_mmph(i) for i in values), saturated), len(values)


class RainMap:
    def __init__(self) -> None:
        self._events: dict[tuple[int, int], list[WiperEvent]] = defaultdict(list)

    def add(self, events: Iterable[WiperEvent]) -> None:
        for e in events:
            self._events[cell_of(e.lat, e.lng)].append(e)

    def cells(self, at: datetime) -> list[CellEstimate]:
        out = []
        for (row, col), events in self._events.items():
            made = [_bin(at - (BINS_PER_HOUR - k) * BIN, events) for k in range(BINS_PER_HOUR)]
            bins = [b for b, _ in made]
            latest, vehicles = made[-1]
            if latest.mmph is None:
                continue
            saturated_bins = 0
            for b in reversed(bins):
                if not b.saturated:
                    break
                saturated_bins += 1
            lat, lng = cell_center(row, col)
            out.append(CellEstimate(
                row=row, col=col, lat=lat, lng=lng,
                mmph=latest.mmph,
                acc1h_mm=sum(b.mmph or 0.0 for b in bins) * (BIN / timedelta(hours=1)),
                vehicles=vehicles,
                saturated_min=saturated_bins * 10,
                bins=bins,
            ))
        return out

    def prune(self, at: datetime) -> None:
        """2시간보다 오래된 원시 이벤트를 지운다(개인정보 보관 기한)."""
        cutoff = at - RAW_RETENTION
        for key in list(self._events):
            kept = [e for e in self._events[key] if e.ts >= cutoff]
            if kept:
                self._events[key] = kept
            else:
                del self._events[key]

    def raw_event_count(self) -> int:
        return sum(len(v) for v in self._events.values())
