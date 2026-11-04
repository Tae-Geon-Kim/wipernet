"""와이퍼 스위치 → 0~1 세기 → mm/h 변환.

스위치·상태 값은 Pleos Vehicle SDK의 와이퍼 값을 그대로 쓴다(설계 3장).
"""

from bisect import bisect_right
from typing import Literal

WiperSwitch = Literal[
    "OFF", "AUTO", "MIST",
    "INTERMITTENT_LEVEL_1", "INTERMITTENT_LEVEL_2", "INTERMITTENT_LEVEL_3",
    "INTERMITTENT_LEVEL_4", "INTERMITTENT_LEVEL_5",
    "CONTINUOUS_LEVEL_1", "CONTINUOUS_LEVEL_2", "CONTINUOUS_LEVEL_3",
    "CONTINUOUS_LEVEL_4", "CONTINUOUS_LEVEL_5",
    "SERVICE", "OTHER",
]
WiperState = Literal["ON", "OFF", "SERVICE", "OTHER"]

# MIST·SERVICE·OTHER는 비와 상관없어서, AUTO는 스위치만으로 세기를 모르므로 표에 없다.
_SWITCH_INTENSITY: dict[str, float] = {
    "OFF": 0.0,
    "INTERMITTENT_LEVEL_1": 0.10,
    "INTERMITTENT_LEVEL_2": 0.175,
    "INTERMITTENT_LEVEL_3": 0.25,
    "INTERMITTENT_LEVEL_4": 0.325,
    "INTERMITTENT_LEVEL_5": 0.40,
    "CONTINUOUS_LEVEL_1": 0.50,
    "CONTINUOUS_LEVEL_2": 0.625,
    "CONTINUOUS_LEVEL_3": 0.75,
    "CONTINUOUS_LEVEL_4": 0.875,
    "CONTINUOUS_LEVEL_5": 1.00,
}

# 이 세기면 최고 단계(수동 연속 5단)다. 포화 판정에 쓴다(설계 6.10.2).
MAX_STAGE = 1.0

# AUTO 와이퍼가 계속 켜져 있을 때의 최근 60초 횟수(설계 6.10.1-7).
AUTO_CONTINUOUS_WPM = 40
_AUTO_MAX = 0.9

# (세기, mm/h). 연속 5단 = 60 mm/h가 상한이다.
_MMPH_POINTS: list[tuple[float, float]] = [
    (0.0, 0.0), (0.10, 0.5), (0.175, 1.75), (0.25, 3.25), (0.325, 5.0),
    (0.40, 7.0), (0.50, 10.0), (0.625, 16.0), (0.75, 25.0), (0.875, 40.0), (1.0, 60.0),
]
_MMPH_XS = [x for x, _ in _MMPH_POINTS]


def switch_intensity(switch: str) -> float | None:
    return _SWITCH_INTENSITY.get(switch)


def auto_intensity(auto_wpm: float | None) -> float | None:
    """AUTO 와이퍼의 최근 60초 횟수로 세기를 추정한다. 검증하지 않은 가정이다."""
    if auto_wpm is None:
        return None
    if auto_wpm <= 0:
        return 0.0
    return min(0.1 + 0.9 * auto_wpm / AUTO_CONTINUOUS_WPM, _AUTO_MAX)


def intensity_to_mmph(i: float) -> float:
    if i <= 0.0:
        return 0.0
    if i >= 1.0:
        return _MMPH_POINTS[-1][1]
    k = bisect_right(_MMPH_XS, i)
    (x0, y0), (x1, y1) = _MMPH_POINTS[k - 1], _MMPH_POINTS[k]
    return y0 + (y1 - y0) * (i - x0) / (x1 - x0)
