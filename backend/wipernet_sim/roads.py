"""시뮬레이터 도로망과 차 이동(Task 6).

도로망은 osmnx MultiDiGraph다. 노드 x = 경도, y = 위도. 간선 length(m), speed_kph, 굽은 도로는 geometry(u → v 방향).
"""

import math
from random import Random

import networkx as nx
import osmnx as ox

from wipernet_server.underpass import Underpass, distance_km

SPEED_FACTOR = 0.5  # 폭우 정체 반영(가정)
DEFAULT_KPH = 30.0


def load_graph(path) -> nx.MultiDiGraph:
    return ox.load_graphml(path)


def largest_scc(g: nx.MultiDiGraph) -> nx.MultiDiGraph:
    """서로 오갈 수 있는 가장 큰 덩어리. 지역 경계에서 잘린 일방통행 때문에 차가 갇히지 않게 한다."""
    return g.subgraph(max(nx.strongly_connected_components(g), key=len)).copy()


def _edge(g, u, v) -> dict:
    return min(g[u][v].values(), key=lambda d: d["length"])


def _points(g, u, v, d) -> list[tuple[float, float]]:
    """간선 모양 (lat, lng) 목록, u → v 방향."""
    if "geometry" in d:
        return [(y, x) for x, y in d["geometry"].coords]
    return [(g.nodes[u]["y"], g.nodes[u]["x"]), (g.nodes[v]["y"], g.nodes[v]["x"])]


def _bearing(lat1, lng1, lat2, lng2) -> float:
    p1, p2, dl = math.radians(lat1), math.radians(lat2), math.radians(lng2 - lng1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.degrees(math.atan2(y, x)) % 360


def _speed_mps(d: dict) -> float:
    return float(d.get("speed_kph") or DEFAULT_KPH) * SPEED_FACTOR / 3.6


class Mover:
    """경로(노드 목록)를 따라 달리는 차 한 대. 경로 끝에 닿으면 그 자리에서 새 임의 경로를 받는다."""

    def __init__(self, graph: nx.MultiDiGraph, rng: Random):
        self.g = graph
        self.rng = rng
        self.route: list = []
        self._i = 0  # 지금 간선 = route[i] → route[i+1]
        self._pos_m = 0.0  # 지금 간선에서 간 거리

    def follow(self, route: list) -> None:
        self.route, self._i, self._pos_m = route, 0, 0.0

    def random_route(self) -> None:
        nodes = list(self.g.nodes)
        origin = self.route[-1] if self.route else self.rng.choice(nodes)
        while True:
            dest = self.rng.choice(nodes)
            if dest != origin:
                self.follow(nx.shortest_path(self.g, origin, dest, weight="length"))
                return

    def step(self, dt_s: float) -> tuple[float, float, float]:
        """dt_s초 달린 뒤 (lat, lng, heading). heading은 북쪽 0°, 시계 방향."""
        t = dt_s
        while t > 0:
            d = _edge(self.g, self.route[self._i], self.route[self._i + 1])
            v = _speed_mps(d)
            left = d["length"] - self._pos_m
            if v * t < left:
                self._pos_m += v * t
                break
            t -= left / v
            self._i, self._pos_m = self._i + 1, 0.0
            if self._i == len(self.route) - 1:
                self.random_route()
        return self._where()

    def _where(self) -> tuple[float, float, float]:
        u, v = self.route[self._i], self.route[self._i + 1]
        d = _edge(self.g, u, v)
        pts = _points(self.g, u, v, d)
        segs = [distance_km(*a, *b) * 1000 for a, b in zip(pts, pts[1:])]
        # length와 모양 길이가 조금 달라 비율로 맞춘다
        target = self._pos_m / d["length"] * sum(segs) if d["length"] else 0.0
        for (a, b), s in zip(zip(pts, pts[1:]), segs):
            if target <= s or (a, b) == (pts[-2], pts[-1]):
                f = min(target / s, 1.0) if s else 0.0
                return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, _bearing(*a, *b)
            target -= s
        raise AssertionError("간선 모양이 비어 있음")


def underpass_edges(g: nx.MultiDiGraph, up: Underpass) -> list[tuple]:
    """C2 `osm_ways`가 든 간선 (u, v). 도로망을 줄일 때 여러 way가 한 간선으로 합쳐져 osmid가 목록일 수 있다.

    좌표 거리로 찾지 않는다. 개포 300 m 안에 구룡·대모 지하차도 간선도 들어온다.
    """
    want = set(up.osm_ways)
    out = set()
    for u, v, d in g.edges(data=True):
        ids = d["osmid"] if isinstance(d["osmid"], list) else [d["osmid"]]
        if want & set(ids):
            out.add((u, v))
    return sorted(out, key=str)


def route_through(g: nx.MultiDiGraph, up: Underpass, rng: Random,
                  before_m: float = 2500, after_m: float = 1000, tries: int = 2000) -> list:
    """내 차 길안내 경로: 출발지 → 목적지 최단경로가 저절로 그 지하차도를 지나는 쌍을 고른다.

    출발지는 지하차도 입구까지 길로 before_m쯤, 목적지는 출구에서 after_m쯤(각 ±20%) 떨어진 노드다.
    지하차도를 억지로 끼워 넣지 않으므로 실제 내비가 고를 법한 경로다.
    """
    edges = underpass_edges(g, up)
    if not edges:
        raise ValueError(f"{up.name}: 도로망에 osm_ways 간선이 없음")

    def ring(dist: dict, m: float) -> list:
        return sorted((n for n, x in dist.items() if 0.8 * m <= x <= 1.2 * m), key=str)

    rev = g.reverse(copy=False)
    ends = {}
    for u, v in edges:
        to_u = nx.single_source_dijkstra_path_length(rev, u, cutoff=1.2 * before_m, weight="length")
        from_v = nx.single_source_dijkstra_path_length(g, v, cutoff=1.2 * after_m, weight="length")
        if (origins := ring(to_u, before_m)) and (dests := ring(from_v, after_m)):
            ends[(u, v)] = (origins, dests)
    keys = sorted(ends, key=str)
    for _ in range(tries if keys else 0):
        u, v = rng.choice(keys)
        origins, dests = ends[(u, v)]
        path = nx.shortest_path(g, rng.choice(origins), rng.choice(dests), weight="length")
        if any(a == u and b == v for a, b in zip(path, path[1:])):
            return path
    raise ValueError(f"{up.name}: 지하차도를 지나는 경로를 {tries}번 안에 못 찾음")
