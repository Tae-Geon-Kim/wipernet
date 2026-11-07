import pytest

from wipernet_sim.driver import Driver, DriverOut


def test_manual_driver_levels():
    d = Driver(auto=False, bias=0)
    assert d.update(25.0) == DriverOut("CONTINUOUS_LEVEL_3", "ON", None)
    assert d.update(0.3) == DriverOut("OFF", "OFF", None)          # 0.5 미만은 꺼짐


@pytest.mark.parametrize("mmph, switch", [
    (0.49, "OFF"), (0.5, "INTERMITTENT_LEVEL_1"), (2.4, "INTERMITTENT_LEVEL_2"), (7.9, "INTERMITTENT_LEVEL_5"),
    (8.0, "CONTINUOUS_LEVEL_1"), (49.9, "CONTINUOUS_LEVEL_4"), (50.0, "CONTINUOUS_LEVEL_5"), (200.0, "CONTINUOUS_LEVEL_5"),
])
def test_manual_table_upper_bounds_are_exclusive(mmph, switch):
    assert Driver(auto=False, bias=0).update(mmph).switch == switch


def test_bias_shifts_one_level():
    assert Driver(auto=False, bias=+1).update(25.0).switch == "CONTINUOUS_LEVEL_4"
    assert Driver(auto=False, bias=-1).update(25.0).switch == "CONTINUOUS_LEVEL_2"


def test_bias_stays_within_off_and_top_level():
    assert Driver(auto=False, bias=+1).update(0.3) == DriverOut("OFF", "OFF", None)   # 비가 없으면 켜지 않는다
    assert Driver(auto=False, bias=+1).update(80.0).switch == "CONTINUOUS_LEVEL_5"
    assert Driver(auto=False, bias=-1).update(0.7) == DriverOut("OFF", "OFF", None)   # 간헐 1단 − 1 = 꺼짐


def test_auto_driver_reports_wipes_per_minute():
    d = Driver(auto=True, bias=0)
    assert d.update(6.0) == DriverOut("AUTO", "ON", 20)              # 6/12*40
    assert d.update(15.0).auto_wpm == 40 and d.update(0.2) == DriverOut("AUTO", "OFF", 0)
    assert d.update(0.5) == DriverOut("AUTO", "ON", 2)          # 0.5/12*40 = 1.67 → 2


def test_stuck_driver_ignores_rain():
    assert Driver(auto=False, bias=0, stuck=True).update(0.0) == DriverOut("CONTINUOUS_LEVEL_5", "ON", None)
