"""지하차도 침수 위험 단계. 와이퍼 비 지도만으로 판단한다(외부 자료 합치기는 Task 4c)."""

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from .rainmap import CellEstimate

Level = Literal["NONE", "CAUTION", "WARNING", "DANGER"]
LEVEL_ORDER: list[Level] = ["NONE", "CAUTION", "WARNING", "DANGER"]

RADIUS_KM = 1.0
HIGH_RISK_FACTOR = 0.75
# (단계, 세기 mm/h 기준, 1시간 누적 mm 기준). 높은 단계부터 본다.
_THRESHOLDS: list[tuple[Level, float, float]] = [
    ("DANGER", 60, 80),
    ("WARNING", 40, 50),
    ("CAUTION", 20, 30),
]
_EARTH_KM = 6371.0


@dataclass
class Underpass:
    id: str
    name: str
    lat: float
    lng: float
    base_risk: Literal["normal", "high"]
    bypass_waypoint: dict | None
    gu: str
    warning_area: str | None


@dataclass
class UnderpassRisk:
    id: str
    name: str
    lat: float
    lng: float
    level: Level
    mmph_10m: float
    acc1h_mm: float
    bypass_waypoint: dict | None


def load_underpasses(path: str | Path) -> list[Underpass]:
    return [Underpass(**u) for u in json.loads(Path(path).read_text(encoding="utf-8"))]


def level_from(mmph: float, acc1h_mm: float, base_risk: str) -> Level:
    factor = HIGH_RISK_FACTOR if base_risk == "high" else 1.0
    for level, mmph_min, acc_min in _THRESHOLDS:
        if mmph >= mmph_min * factor or acc1h_mm >= acc_min * factor:
            return level
    return "NONE"


def _distance_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * _EARTH_KM * math.asin(math.sqrt(a))


def assess(underpasses: list[Underpass], cells: list[CellEstimate]) -> list[UnderpassRisk]:
    out = []
    for up in underpasses:
        near = [c for c in cells if _distance_km(up.lat, up.lng, c.lat, c.lng) <= RADIUS_KM]
        mmph = max((c.mmph for c in near), default=0.0)
        acc = max((c.acc1h_mm for c in near), default=0.0)
        out.append(UnderpassRisk(
            id=up.id, name=up.name, lat=up.lat, lng=up.lng,
            level=level_from(mmph, acc, up.base_risk),
            mmph_10m=mmph, acc1h_mm=acc, bypass_waypoint=up.bypass_waypoint,
        ))
    return out
