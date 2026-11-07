from pathlib import Path
from random import Random

import networkx as nx
import pytest

from wipernet_server.underpass import Underpass, distance_km, load_underpasses
from wipernet_sim.roads import Mover, largest_scc, load_graph, route_through, underpass_edges

ROOT = Path(__file__).resolve().parents[2]
M_PER_DEG_LAT = 111195.0


def haversine_m(lat1, lng1, lat2, lng2):
    return distance_km(lat1, lng1, lat2, lng2) * 1000


def _add(g, a, b, osmid, speed_kph=36):
    length = haversine_m(g.nodes[a]["y"], g.nodes[a]["x"], g.nodes[b]["y"], g.nodes[b]["x"])
    g.add_edge(a, b, length=length, speed_kph=speed_kph, osmid=osmid)


def north_line(n, step_m, speed_kph=36, osmids=None):
    """남→북 일직선 양방향 도로. 노드 0..n-1."""
    g = nx.MultiDiGraph()
    for i in range(n):
        g.add_node(i, y=37.50 + i * step_m / M_PER_DEG_LAT, x=127.03)
    for i in range(n - 1):
        _add(g, i, i + 1, (osmids or {}).get((i, i + 1), 1000 + i), speed_kph)
        _add(g, i + 1, i, 2000 + i, speed_kph)
    return g


def line_graph(length_m, speed_kph):
    return north_line(2, length_m, speed_kph)


def U(osm_ways):
    return Underpass(id="u1", name="테스트지하차도", lat=37.52, lng=127.03, base_risk="high",
                     bypass_waypoint=None, gu="강남구", warning_area=None, osm_ways=osm_ways)


def test_mover_advances_along_edge():
    m = Mover(line_graph(length_m=1000, speed_kph=36), rng=Random(0)); m.random_route()
    lat0, lng0, _ = m.step(0); lat1, lng1, _ = m.step(10)   # 18 km/h × 10 s = 50 m
    assert haversine_m(lat0, lng0, lat1, lng1) == pytest.approx(50, rel=0.1)


def test_mover_heading_follows_road():
    m = Mover(north_line(3, 500), rng=Random(0)); m.follow([0, 1, 2])
    _, _, heading = m.step(10)
    assert heading == pytest.approx(0, abs=1)


def test_mover_carries_over_to_next_edge():
    m = Mover(north_line(3, 100), rng=Random(0)); m.follow([0, 1, 2])
    lat, _, _ = m.step(30)   # 5 m/s × 30 s = 150 m: 두 번째 간선 50 m 지점
    assert (lat - 37.50) * M_PER_DEG_LAT == pytest.approx(150, rel=0.02)


def test_mover_reroutes_at_end():
    g = line_graph(length_m=100, speed_kph=36)
    m = Mover(g, rng=Random(0)); m.random_route()
    for _ in range(100):
        lat, _, _ = m.step(10)
        assert g.nodes[0]["y"] - 1e-9 <= lat <= g.nodes[1]["y"] + 1e-9
    assert m.route  # 새 경로를 받았다


def two_way_square_plus_one_way_spur():
    g = nx.MultiDiGraph()
    for n, (y, x) in {"a": (37.50, 127.00), "b": (37.50, 127.01), "c": (37.51, 127.01),
                      "d": (37.51, 127.00), "e": (37.52, 127.00)}.items():
        g.add_node(n, y=y, x=x)
    for a, b in [("a", "b"), ("b", "c"), ("c", "d"), ("d", "a")]:
        _add(g, a, b, 1); _add(g, b, a, 1)
    _add(g, "d", "e", 2)
    return g


def test_keeps_largest_strongly_connected_part():
    g = two_way_square_plus_one_way_spur()
    assert set(largest_scc(g).nodes) == {"a", "b", "c", "d"}


def test_route_through_passes_underpass_naturally():
    g = north_line(11, 500, osmids={(5, 6): 99})   # 지하차도 = 5 → 6 (북쪽 방향만)
    path = route_through(g, U([99]), Random(0), before_m=2500, after_m=1000)
    assert (5, 6) in list(zip(path, path[1:]))
    assert path[0] in {0, 1} and path[-1] == 8     # 입구까지 2.5 km ±20%, 출구에서 1 km ±20%


def test_route_through_rejects_forced_detour():
    g = north_line(11, 500)
    g.add_node("w", y=g.nodes[5]["y"], x=127.04)   # 5 → w → 6: 동쪽으로 돌아가는 지하차도
    _add(g, 5, "w", 99); _add(g, "w", 6, 99)
    with pytest.raises(ValueError):
        route_through(g, U([99]), Random(0), tries=50)


@pytest.fixture(scope="module")
def gangnam():
    return load_graph(ROOT / "backend" / "data" / "gangnam_drive.graphml")


def test_every_underpass_has_a_natural_route(gangnam):
    for up in load_underpasses(ROOT / "shared" / "underpasses_gangnam.json"):
        edges = set(underpass_edges(gangnam, up))
        assert edges, up.id
        path = route_through(gangnam, up, Random(0))
        assert edges & set(zip(path, path[1:])), up.id
