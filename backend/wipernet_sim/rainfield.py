"""AWS 관측소 값으로 만든 강우장(Task 5). 시뮬레이터 차들이 이 값을 보고 와이퍼를 켠다.

데모용 강우장은 관측소를 모두 쓴다. 검증(Task 9)은 관측소를 하나씩 빼고 만든 강우장을 그 자리의 실제 값과 비교한다.
"""

import csv
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from wipernet_server.underpass import distance_km

BIN_S = 600


@dataclass(frozen=True)
class Station:
    id: str
    name: str
    lat: float
    lng: float


def load_norm(path: str | Path) -> tuple[list[Station], dict[str, list[tuple[datetime, float]]]]:
    stations: dict[str, Station] = {}
    series: dict[str, list[tuple[datetime, float]]] = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            sid = r["station_id"]
            if sid not in stations:
                stations[sid] = Station(sid, r["station_name"], float(r["lat"]), float(r["lng"]))
                series[sid] = []
            series[sid].append((datetime.fromisoformat(r["ts"]), float(r["mmph"])))
    return list(stations.values()), series


def _bin(t: datetime) -> int:
    return int(t.timestamp()) // BIN_S * BIN_S


class RainField:
    """받은 관측소로 역거리가중(거듭제곱 2). 시각 t가 속한 10분 구간(시작 ts)에 값이 있는 관측소만 쓴다."""

    def __init__(self, stations: list[Station], series: dict[str, list[tuple[datetime, float]]]):
        self.stations = stations
        self._values = {s.id: {_bin(ts): v for ts, v in series[s.id]} for s in stations}

    def mmph(self, lat: float, lng: float, t: datetime) -> float:
        b = _bin(t)
        num = den = 0.0
        for s in self.stations:
            v = self._values[s.id].get(b)
            if v is None:
                continue
            d = distance_km(lat, lng, s.lat, s.lng)
            if d == 0:
                return v
            w = 1 / d**2
            num += w * v
            den += w
        if den == 0:
            raise ValueError(f"{t.isoformat()} 구간에 값이 있는 관측소가 없음")
        return num / den
