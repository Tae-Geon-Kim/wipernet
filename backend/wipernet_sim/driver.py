"""시뮬레이터 운전자의 와이퍼 조작(Task 7). 차가 지금 와이퍼를 어떻게 두는지만 정한다. 보낼지는 upload_policy가 정한다."""

from dataclasses import dataclass

from wipernet_server.wiper import AUTO_CONTINUOUS_WPM

OFF_BELOW = 0.5  # 역거리가중 강우장은 지역 전체에 작은 값을 남겨서, 이보다 약하면 꺼 둔다(설계 6.10.1-6)

# (상한 mm/h, 스위치): 상한 미만이면 그 단계. 마지막 상한보다 크면 연속 5단
MMPH_TO_SWITCH: list[tuple[float, str]] = [
    (OFF_BELOW, "OFF"),
    (1, "INTERMITTENT_LEVEL_1"), (2.5, "INTERMITTENT_LEVEL_2"), (4, "INTERMITTENT_LEVEL_3"),
    (6, "INTERMITTENT_LEVEL_4"), (8, "INTERMITTENT_LEVEL_5"),
    (12, "CONTINUOUS_LEVEL_1"), (20, "CONTINUOUS_LEVEL_2"), (30, "CONTINUOUS_LEVEL_3"), (50, "CONTINUOUS_LEVEL_4"),
]
LEVELS = [s for _, s in MMPH_TO_SWITCH] + ["CONTINUOUS_LEVEL_5"]

AUTO_FULL_MMPH = 12  # AUTO 와이퍼가 쉬지 않고 도는(최근 60초 AUTO_CONTINUOUS_WPM번) 비 세기(가정)


@dataclass(frozen=True)
class DriverOut:
    switch: str
    state: str
    auto_wpm: float | None


def _level(mmph: float) -> int:
    return next((i for i, (upper, _) in enumerate(MMPH_TO_SWITCH) if mmph < upper), len(LEVELS) - 1)


class Driver:
    """수동 차는 표 + 개인차(bias, 한 단계), AUTO 차는 최근 60초 와이퍼 횟수, 고장 차는 늘 연속 5단."""

    def __init__(self, auto: bool, bias: int, stuck: bool = False):
        self.auto, self.bias, self.stuck = auto, bias, stuck

    def update(self, mmph: float) -> DriverOut:
        if self.stuck:
            return DriverOut("CONTINUOUS_LEVEL_5", "ON", None)
        if self.auto:
            if mmph < OFF_BELOW:
                return DriverOut("AUTO", "OFF", 0)
            wpm = AUTO_CONTINUOUS_WPM if mmph >= AUTO_FULL_MMPH else round(mmph / AUTO_FULL_MMPH * AUTO_CONTINUOUS_WPM)
            return DriverOut("AUTO", "ON", min(max(wpm, 1), AUTO_CONTINUOUS_WPM))
        if mmph < OFF_BELOW:
            return DriverOut("OFF", "OFF", None)
        switch = LEVELS[min(max(_level(mmph) + self.bias, 0), len(LEVELS) - 1)]
        return DriverOut(switch, "OFF" if switch == "OFF" else "ON", None)
