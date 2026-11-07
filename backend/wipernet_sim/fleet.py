"""가상 차량 무리(Task 7). 실행기(runner), 검증(Task 9), 데모 내보내기(Task 10)가 모두 이것을 쓴다."""

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from random import Random
from typing import Literal

import networkx as nx

from wipernet_server.grid import cell_of

from .driver import Driver
from .rainfield import RainField
from .roads import Mover
from .upload_policy import LastSent, should_send

# 데모 지역 안 대수(가정치, 설계 6.10.1). 현재 = 지금 Pleos 차 수준, 미래 = 보급이 늘었을 때
FLEET_CURRENT = 100
FLEET_FUTURE = 1500
# 도로망이 데모 지역보다 넓어 임의 경로 차의 이만큼만 지역 안에 있다(2026-11-07, 100대 1시간 측정, Task 6)
IN_AREA_FRAC = 0.71
# 임시 ID를 바꾸는 간격. 매번 이 사이에서 무작위로 고른다(C-ITS 가명 교체처럼 고정값을 피해 옛 ID와 새 ID를 잇기 어렵게)
VID_ROTATE_MIN, VID_ROTATE_MAX = timedelta(minutes=12), timedelta(minutes=18)


@dataclass
class Car:
    mover: Mover
    driver: Driver
    guiding: bool
    vid: str = ""
    rotate_at: datetime | None = None
    last: LastSent | None = None


class Fleet:
    def __init__(self, field: RainField, graph: nx.MultiDiGraph, density: Literal["current", "future"], seed: int = 1,
                 guiding_frac: float = 0.4, auto_frac: float = 0.25, stuck_frac: float = 0.01):
        self.field = field
        self._rng = Random(seed)
        n = round((FLEET_CURRENT if density == "current" else FLEET_FUTURE) / IN_AREA_FRAC)
        # 비율을 확률이 아니라 정확한 대수로 나눈다. 고장 차는 수동 차 가운데서 고른다
        order = self._rng.sample(range(n), n)
        n_auto, n_stuck = round(n * auto_frac), round(n * stuck_frac)
        auto, stuck = set(order[:n_auto]), set(order[n_auto:n_auto + n_stuck])
        guiding = set(self._rng.sample(range(n), round(n * guiding_frac)))
        self.cars: list[Car] = []
        for i in range(n):
            mover = Mover(graph, Random(self._rng.getrandbits(32)))
            mover.random_route()
            driver = Driver(auto=i in auto, bias=self._rng.choice([-1, 0, 1]), stuck=i in stuck)
            self.cars.append(Car(mover, driver, i in guiding))

    def _new_vid(self) -> str:
        return str(uuid.UUID(int=self._rng.getrandbits(128), version=4))

    def _rotate_gap(self) -> timedelta:
        return VID_ROTATE_MIN + self._rng.random() * (VID_ROTATE_MAX - VID_ROTATE_MIN)

    def step(self, t: datetime, dt_s: float) -> list[tuple[int, dict]]:
        """모든 차를 dt_s초 움직이고, 시각 t에 보낼 이벤트를 (차 번호, API 이벤트)로 낸다. 차 번호는 서버로 보내지 않는다."""
        out = []
        now_s = t.timestamp()
        for i, car in enumerate(self.cars):
            if car.rotate_at is None:  # 첫 step: 첫 교체 시점을 차마다 0~18분 사이로 엇갈린다
                car.vid, car.rotate_at = self._new_vid(), t + self._rng.random() * VID_ROTATE_MAX
            elif t >= car.rotate_at:
                car.vid, car.rotate_at = self._new_vid(), car.rotate_at + self._rotate_gap()
            lat, lng, _ = car.mover.step(dt_s)
            d = car.driver.update(self.field.mmph(lat, lng, t))
            cell = cell_of(lat, lng)
            if not should_send(now_s, car.guiding, d, cell, car.last):
                continue
            car.last = LastSent(now_s, d.switch, d.state, d.auto_wpm, cell)
            ev = {"vid": car.vid, "ts": t.isoformat(), "lat": round(lat, 6), "lng": round(lng, 6),
                  "switch": d.switch, "state": d.state}
            if d.switch == "AUTO":
                ev["auto_wpm"] = d.auto_wpm
            out.append((i, ev))
        return out
