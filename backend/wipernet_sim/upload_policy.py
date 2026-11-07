"""와이퍼 이벤트를 언제 보내나(Task 7). 차량 앱 A16 `UploadPolicy`와 같은 규칙이고, 둘 다 shared/upload_policy_cases.json으로 테스트한다."""

from dataclasses import dataclass

from .driver import DriverOut

ON_RESEND_S = 60
OFF_GUIDING_RESEND_S = 300


@dataclass(frozen=True)
class LastSent:
    at_s: float
    switch: str
    state: str
    auto_wpm: float | None
    cell: tuple[int, int]


def should_send(now_s: float, guiding: bool, out: DriverOut, cell: tuple[int, int], last: LastSent | None) -> bool:
    """- 켜짐: 스위치·상태·auto_wpm(0 ↔ 양수)이 바뀔 때와 60초마다. AUTO 횟수가 양수끼리 바뀐 것은 바뀜이 아니다.
    - 켜짐 → 꺼짐: 한 번.
    - 꺼짐 + 길안내 중: 칸이 바뀔 때와 300초마다. 꺼짐 + 길안내 아님: 안 보냄.
    """
    if out.state == "ON":
        if last is None or last.state != "ON" or last.switch != out.switch:
            return True
        if ((last.auto_wpm or 0) > 0) != ((out.auto_wpm or 0) > 0):
            return True
        return now_s - last.at_s >= ON_RESEND_S
    if last is not None and last.state == "ON":
        return True
    if not guiding:
        return False
    return last is None or last.cell != cell or now_s - last.at_s >= OFF_GUIDING_RESEND_S
