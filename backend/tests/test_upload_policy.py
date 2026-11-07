import json
from pathlib import Path

from wipernet_sim.driver import DriverOut
from wipernet_sim.upload_policy import LastSent, should_send

CASES = Path(__file__).resolve().parents[2] / "shared" / "upload_policy_cases.json"


def args_from(c):
    last = c["last"]
    return dict(
        now_s=c["now_s"], guiding=c["guiding"],
        out=DriverOut(c["switch"], c["state"], c["auto_wpm"]), cell=tuple(c["cell"]),
        last=None if last is None else LastSent(last["at_s"], last["switch"], last["state"], last["auto_wpm"],
                                                tuple(last["cell"])),
    )


def test_policy_matches_shared_cases():
    cases = json.loads(CASES.read_text(encoding="utf-8"))
    assert len(cases) >= 10
    for c in cases:
        assert should_send(**args_from(c)) == c["send"], c["name"]
