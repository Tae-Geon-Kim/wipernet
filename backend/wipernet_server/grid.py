"""약 500 m 격자. 앱도 같은 식으로 칸을 계산한다(docs/api.md)."""

import math

CELL_LAT = 0.0045
CELL_LNG = 0.0057


def cell_of(lat: float, lng: float) -> tuple[int, int]:
    return math.floor(lat / CELL_LAT), math.floor(lng / CELL_LNG)


def cell_center(row: int, col: int) -> tuple[float, float]:
    return (row + 0.5) * CELL_LAT, (col + 0.5) * CELL_LNG
