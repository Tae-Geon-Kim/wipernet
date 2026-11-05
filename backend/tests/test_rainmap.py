from datetime import datetime, timedelta, timezone

import pytest

from wipernet_server.grid import cell_center, cell_of
from wipernet_server.rainmap import RainMap, WiperEvent

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 20, 0, tzinfo=KST)


def ev(vid, sec, switch, lat=37.50, lng=127.03, auto_wpm=None):
    state = "OFF" if switch == "OFF" else "ON"
    return WiperEvent(vid, T0 + timedelta(seconds=sec), lat, lng, switch, state, auto_wpm)


def test_needs_three_vehicles():
    rm = RainMap(); rm.add([ev("a", 0, "CONTINUOUS_LEVEL_3"), ev("b", 5, "CONTINUOUS_LEVEL_3")])
    assert rm.cells(T0 + timedelta(minutes=1)) == []


def test_median_of_vehicles():
    rm = RainMap(); rm.add([ev("a", 0, "CONTINUOUS_LEVEL_1"), ev("b", 0, "CONTINUOUS_LEVEL_3"), ev("c", 0, "CONTINUOUS_LEVEL_5")])
    [c] = rm.cells(T0 + timedelta(minutes=1)); assert c.mmph == 25 and c.vehicles == 3


def test_single_stuck_vehicle_among_dry_cars_reports_zero():
    rm = RainMap(); rm.add([ev("a", 0, "CONTINUOUS_LEVEL_5"), ev("b", 0, "OFF"), ev("c", 0, "OFF"), ev("d", 0, "OFF")])
    [c] = rm.cells(T0 + timedelta(minutes=1)); assert c.mmph == 0


def test_events_outside_window_ignored():
    rm = RainMap(); rm.add([ev(v, 0, "CONTINUOUS_LEVEL_3") for v in "abc"])
    assert rm.cells(T0 + timedelta(minutes=11)) == []


def test_acc1h_sums_six_bins():
    rm = RainMap()
    for m in range(0, 60, 10): rm.add([ev(v, m * 60, "CONTINUOUS_LEVEL_3") for v in "abc"])  # 25 mm/h 1시간
    [c] = rm.cells(T0 + timedelta(minutes=59)); assert c.acc1h_mm == pytest.approx(25.0)


def test_saturated_minutes():
    rm = RainMap()
    for m in range(0, 40, 10): rm.add([ev(v, m * 60, "CONTINUOUS_LEVEL_5") for v in "abc"])
    [c] = rm.cells(T0 + timedelta(minutes=39)); assert c.saturated_min == 40 and c.bins[-1].saturated


def test_prune_drops_raw_events_older_than_2h():
    rm = RainMap(); rm.add([ev(v, 0, "CONTINUOUS_LEVEL_3") for v in "abc"])
    rm.prune(T0 + timedelta(hours=2, minutes=1)); assert rm.raw_event_count() == 0


# 계획 밖에서 더한 테스트: 규칙의 경계를 고정한다.

def test_latest_event_per_vehicle_wins():
    rm = RainMap(); rm.add([ev("a", 0, "CONTINUOUS_LEVEL_5"), ev("a", 60, "CONTINUOUS_LEVEL_3"),
                            ev("b", 0, "CONTINUOUS_LEVEL_3"), ev("c", 0, "CONTINUOUS_LEVEL_3")])
    [c] = rm.cells(T0 + timedelta(minutes=2)); assert c.mmph == 25 and c.vehicles == 3


def test_ignored_switch_does_not_count_as_vehicle():
    rm = RainMap(); rm.add([ev("a", 0, "CONTINUOUS_LEVEL_3"), ev("b", 0, "CONTINUOUS_LEVEL_3"), ev("c", 0, "MIST")])
    assert rm.cells(T0 + timedelta(minutes=1)) == []


def test_auto_uses_wipes_per_minute():
    rm = RainMap(); rm.add([ev(v, 0, "AUTO", auto_wpm=40) for v in "abc"])  # 세기 0.9 → 44 mm/h, 포화 아님
    [c] = rm.cells(T0 + timedelta(minutes=1)); assert c.mmph == pytest.approx(44.0) and c.saturated_min == 0


def test_future_events_ignored_for_past_query():
    rm = RainMap(); rm.add([ev(v, 600, "CONTINUOUS_LEVEL_3") for v in "abc"])
    assert rm.cells(T0 + timedelta(minutes=5)) == []


def test_bins_oldest_first_and_sparse_bin_is_none():
    rm = RainMap(); rm.add([ev(v, 0, "CONTINUOUS_LEVEL_3") for v in "abc"])
    [c] = rm.cells(T0 + timedelta(minutes=1))
    assert len(c.bins) == 6 and c.bins[-1].mmph == 25 and all(b.mmph is None for b in c.bins[:-1])
    assert c.bins[0].start < c.bins[-1].start


def test_cells_are_separate():
    rm = RainMap(); rm.add([ev(v, 0, "CONTINUOUS_LEVEL_3") for v in "abc"] + [ev(v, 0, "OFF", lat=37.51) for v in "def"])
    got = {(c.row, c.col): c.mmph for c in rm.cells(T0 + timedelta(minutes=1))}
    assert got == {cell_of(37.50, 127.03): 25, cell_of(37.51, 127.03): 0}


def test_cell_of_and_center():
    row, col = cell_of(37.50, 127.03)
    lat, lng = cell_center(row, col)
    assert cell_of(lat, lng) == (row, col) and abs(lat - 37.50) < 0.0045 and abs(lng - 127.03) < 0.0057
