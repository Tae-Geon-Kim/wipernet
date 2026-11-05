"""서버 상태를 메모리에 둔다. DB·Alembic은 공모전 범위 밖이다(설계 6.9).

저장 방식을 바꿀 때는 이 파일만 바꾼다. api.py 말고는 이 모듈을 쓰지 않는다.
"""

from collections.abc import Iterable
from datetime import datetime, timedelta

from .rainmap import RAW_RETENTION, CellEstimate, RainMap, WiperEvent


class Store:
    def __init__(self) -> None:
        self.reset()

    def reset(self) -> None:
        self._rain = RainMap()
        self._last: dict[str, WiperEvent] = {}
        self.clock: datetime | None = None

    def add(self, events: Iterable[WiperEvent]) -> None:
        events = list(events)
        self._rain.add(events)
        for e in events:
            if e.vid not in self._last or e.ts >= self._last[e.vid].ts:
                self._last[e.vid] = e
            if self.clock is None or e.ts > self.clock:
                self.clock = e.ts

    def prune(self) -> None:
        """서버 시계 기준 2시간보다 오래된 원시 이벤트와 차별 마지막 상태를 지운다."""
        if self.clock is None:
            return
        self._rain.prune(self.clock)
        cutoff = self.clock - RAW_RETENTION
        self._last = {vid: e for vid, e in self._last.items() if e.ts >= cutoff}

    def cells(self, at: datetime) -> list[CellEstimate]:
        return self._rain.cells(at)

    def recent_vehicles(self, within: timedelta) -> list[WiperEvent]:
        if self.clock is None:
            return []
        cutoff = self.clock - within
        return [e for e in self._last.values() if e.ts >= cutoff]

    def raw_event_count(self) -> int:
        return self._rain.raw_event_count()
