import json
from pathlib import Path
from random import Random

import pytest

from wipernet_server.underpass import LEVEL_ORDER, load_underpasses
from wipernet_sim.export_demo import OUT, bypass_route, prep_level, wiper_below
from wipernet_sim.roads import load_graph, route_through, underpass_edges

ROOT = Path(__file__).resolve().parents[2]
WARNING = LEVEL_ORDER.index("WARNING")


def test_prep_level_picks_highest_tier_ahead():
    # 북쪽으로 20 m/s → 앞 1~3분 = 1.2~3.6 km
    here = {"lat": 37.5001, "lng": 127.0301}
    ahead = {"lat": 37.5180, "lng": 127.0300}
    assert prep_level(37.5, 127.03, 0.0, 20.0, [{**ahead, "mmph": 15.0}]) == 10      # 맑은 곳 → 곧 비 구간
    assert prep_level(37.5, 127.03, 0.0, 20.0, [{**here, "mmph": 15.0}, {**ahead, "mmph": 35.0}]) == 30  # 곧 더 강한 비
    assert prep_level(37.5, 127.03, 0.0, 20.0, [{**here, "mmph": 0.0}, {**ahead, "mmph": 35.0}]) == 30
    assert prep_level(37.5, 127.03, 0.0, 20.0, [{**here, "mmph": 15.0}, {**ahead, "mmph": 25.0}]) is None  # 이미 비, 앞이 강하지 않다
    assert prep_level(37.5, 127.03, 180.0, 20.0, [{**ahead, "mmph": 35.0}]) is None  # 남쪽으로 가면 비가 뒤에 있다
    assert prep_level(37.5, 127.03, 0.0, 20.0, [{**here, "mmph": 32.0}, {**ahead, "mmph": 50.0}]) is None  # 이미 강한 비 안


def test_wiper_below_follows_driver_table():
    assert wiper_below("OFF", 10) and wiper_below("INTERMITTENT_LEVEL_5", 10)
    assert not wiper_below("CONTINUOUS_LEVEL_1", 10)        # 8~12 mm/h라 10 이상일 수 있다
    assert wiper_below("CONTINUOUS_LEVEL_3", 30) and not wiper_below("CONTINUOUS_LEVEL_4", 30)
    assert not wiper_below("CONTINUOUS_LEVEL_5", 1000)


@pytest.fixture(scope="module")
def gangnam():
    return load_graph(ROOT / "backend" / "data" / "gangnam_drive.graphml")


def test_bypass_takes_surface_road_and_avoids_blocked_underpasses(gangnam):
    ups = {u.id: u for u in load_underpasses(ROOT / "shared" / "underpasses_gangnam.json")}
    up = ups["gaepo"]
    route = route_through(gangnam, up, Random(0))
    edges = set(underpass_edges(gangnam, up))
    k = next(i for i, e in enumerate(zip(route, route[1:])) if e in edges)
    blocked = [up, ups["guryong"]]
    path = bypass_route(gangnam, route[k - 3], route[-1], up.bypass_waypoint, blocked)
    steps = set(zip(path, path[1:]))
    for b in blocked:
        assert not steps & set(underpass_edges(gangnam, b)), b.id
    assert path[0] == route[k - 3] and path[-1] == route[-1]
    assert any(abs(gangnam.nodes[n]["y"] - up.bypass_waypoint["lat"]) < 1e-4
               and abs(gangnam.nodes[n]["x"] - up.bypass_waypoint["lng"]) < 1e-4 for n in path)  # 우회 지점(지상)을 지난다


# --- 아래는 커밋된 생성물(scenario.json)을 확인한다 ---

@pytest.fixture(scope="module")
def sc():
    return json.loads(OUT.read_text(encoding="utf-8"))


def _hazard_at(sc, t):
    return max((h for h in sc["hazards"] if h["t"] <= t), key=lambda h: h["t"])


def _target(h, sc):
    return next(u for u in h["underpasses"] if u["id"] == sc["route_underpass"])


def _first_warning(sc):
    """앱이 처음 경고하는 프레임: 지하차도 2 km 안(tbt)이고 단계가 WARNING 이상(Task 12)."""
    for f in sc["my_car"]:
        u = _target(_hazard_at(sc, f["t"]), sc)
        if f["tbt"] and LEVEL_ORDER.index(u["level"]) >= WARNING:
            return f, u
    raise AssertionError("경고가 없다")


def test_route_underpass_is_high_risk(sc):
    ups = {u.id: u for u in load_underpasses(ROOT / "shared" / "underpasses_gangnam.json")}
    assert ups[sc["route_underpass"]].base_risk == "high"


def test_first_warning_before_entry_comes_from_vehicles(sc):
    f, u = _first_warning(sc)
    assert f["tbt"]["distance_m"] >= 500          # 우회를 고를 거리가 남았다
    assert "vehicles" in u["reasons"]
    assert 150 <= f["t"] <= 210                    # 출발 약 3분 뒤


def test_prep_comes_before_warning(sc):
    f_warn, _ = _first_warning(sc)
    def prep(f):
        lvl = prep_level(f["lat"], f["lng"], f["heading"], f["speed_mps"], _hazard_at(sc, f["t"])["rain_cells"])
        return lvl is not None and wiper_below(f["wiper"], lvl)  # 내 차는 아직 그 비 밖에 있다
    assert any(prep(f) for f in sc["my_car"] if f["t"] < f_warn["t"])


def test_time_increases_and_tbt_counts_down(sc):
    for frames in (sc["my_car"], sc["bypass"]["my_car"]):
        ts = [f["t"] for f in frames]
        assert ts == sorted(set(ts))
    d = [f["tbt"]["distance_m"] for f in sc["my_car"] if f["tbt"]]
    assert d and d[0] <= 2000 and all(a >= b for a, b in zip(d, d[1:]))
    assert sc["my_car"][0]["window_open"] is True


def test_bypass_branches_at_warning(sc):
    f_warn, _ = _first_warning(sc)
    assert sc["bypass"]["from_t"] == f_warn["t"]
    assert sc["bypass"]["my_car"][0]["t"] > f_warn["t"]
    assert all(f["tbt"] is None for f in sc["bypass"]["my_car"])
    assert max(h["t"] for h in sc["hazards"]) >= sc["bypass"]["my_car"][-1]["t"]
