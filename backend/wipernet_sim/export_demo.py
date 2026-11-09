"""데모 시나리오 내보내기(Task 10, S7). 차량 앱 데모 모드가 재생하는 scenario.json(C3)을 만든다.

    .venv/bin/python -m wipernet_sim.export_demo [--density future] [--from 17:00 --to 21:00]

내 차는 내비 길안내 중이고, 그 경로가 위험도 높은 지하차도를 저절로 지난다(roads.route_through).
차량 무리를 한 번 돌리며 출발 시각(1분 간격)과 경로를 바꿔 보고, 앱이 보는 순서가
"차량 준비 → 출발 약 3분 뒤 지하차도 경고(2 km 안, 근거에 vehicles)"인 첫 조합을 고른다(설계 6.10.1-11).
경고 시점에서 우회 지점(지하차도 바로 위 지상 도로)을 거쳐 원래 목적지로 가는 경로도 함께 낸다.
우회 경로는 "가장 먼저 잠기는 지하차도를 피하는 길"이지 안전이 확인된 길이 아니다.
"""

import argparse
import copy
import json
import sys
from dataclasses import asdict
from datetime import datetime, timedelta
from pathlib import Path
from random import Random

import networkx as nx

from wipernet_server.external.official import KST, ReplayOfficialSource
from wipernet_server.external.radar import ReplayRadarSource
from wipernet_server.fusion import fuse
from wipernet_server.grid import cell_of
from wipernet_server.rainmap import RainMap, WiperEvent
from wipernet_server.underpass import LEVEL_ORDER, Underpass, distance_km, load_underpasses

from .driver import MMPH_TO_SWITCH, Driver
from .fleet import Fleet
from .rainfield import RainField, load_norm
from .roads import Mover, _bearing, _edge, load_graph, route_through, underpass_edges
from .runner import RAIN, ROADS

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "backend" / "data"
OUT = ROOT / "car-app" / "app" / "src" / "main" / "assets" / "demo" / "scenario.json"

TICK_S = 10            # 차량 무리·위험 정보 간격(실행기와 같다)
STEP_S = 5             # 내 차 위치 간격
CANDIDATE_EVERY = timedelta(minutes=1)
HORIZON = timedelta(minutes=25)  # 출발 뒤 이만큼 더 돌려 내 차·우회 경로가 끝날 때까지 위험 정보를 낸다
WARN_AT_S = (150, 210)  # 앱 첫 경고가 출발 약 3분 뒤
PREP_TIERS = (10.0, 30.0)  # 앱 차량 준비 기준: 곧 비 구간 / 곧 더 강한 비 구간(Task 12, 사용자 결정 2026-11-10)
PREP_AHEAD_S = (60, 180)
PREP_CONE_DEG = 30
TBT_FROM_M = 2000
TBT_TYPE = "TURN_ENTER_UNDER_C"  # 계획 값. Pleos NaviHelper 실제 값은 Task 11에서 에뮬레이터로 확인한다
MIN_LEFT_AT_WARN_M = 500  # 경고 때 지하차도 입구까지 이만큼은 남아야 우회를 고를 수 있다
BEFORE_M = 3500       # 출발지→입구. 2 km 안에 약 3분 뒤 들어선다(속도 6~10 m/s)
AFTER_M = 500          # 출구 뒤 목적지까지(S3 기본 1000에서 줄임)
WARNING = LEVEL_ORDER.index("WARNING")


def prep_level(lat: float, lng: float, heading: float, speed_mps: float, rain_cells: list[dict]) -> float | None:
    """앱의 차량 준비 조건(Task 12). 기준 x마다 '지금 칸 < x이고 진행 방향 ±30° 안 1~3분 거리에 x 이상 칸'을 보고,
    성립하는 가장 높은 기준을 낸다(10 = 곧 비 구간, 30 = 곧 더 강한 비 구간). 없으면 None. 값이 없는 칸은 0으로 본다."""
    here = cell_of(lat, lng)
    here_mmph = max((c["mmph"] for c in rain_cells if cell_of(c["lat"], c["lng"]) == here), default=0.0)
    near, far = (speed_mps * s for s in PREP_AHEAD_S)
    ahead = 0.0
    for c in rain_cells:
        d = distance_km(lat, lng, c["lat"], c["lng"]) * 1000
        off = abs((_bearing(lat, lng, c["lat"], c["lng"]) - heading + 180) % 360 - 180)
        if near <= d <= far and off <= PREP_CONE_DEG:
            ahead = max(ahead, c["mmph"])
    return next((x for x in reversed(PREP_TIERS) if here_mmph < x <= ahead), None)


def wiper_below(wiper: str, mmph: float) -> bool:
    """수동 운전자 규칙(S4, 개인차 0)에서 이 와이퍼 단계가 mmph 미만의 비에서만 나온다.
    '곧 비 구간' 안내 때 내 차가 이미 그 이상의 비 속이면 화면과 말이 어긋난다."""
    upper = dict((s, u) for u, s in MMPH_TO_SWITCH)
    return wiper in upper and upper[wiper] <= mmph


def bypass_route(g: nx.MultiDiGraph, start, dest, waypoint: dict, blocked: list[Underpass]) -> list:
    """start → 우회 지점에서 가장 가까운 노드 → dest. blocked 지하차도의 도로 구간은 타지 않는다."""
    free = g.copy()
    for up in blocked:
        for u, v in underpass_edges(g, up):
            free.remove_edges_from([(u, v, k) for k in list(free[u][v])])
    via = min(free.nodes, key=lambda n: distance_km(free.nodes[n]["y"], free.nodes[n]["x"], waypoint["lat"], waypoint["lng"]))
    return nx.shortest_path(free, start, via, weight="length") + nx.shortest_path(free, via, dest, weight="length")[1:]


def _risk_json(r) -> dict:
    d = asdict(r)
    d["mmph_10m"], d["acc1h_mm"] = round(d["mmph_10m"], 1), round(d["acc1h_mm"], 1)
    d["alerts"] = [{**a, "sent_at": a["sent_at"].isoformat()} for a in d["alerts"]]
    return d


class _Hazards:
    """tick 시각별 위험 정보(앱이 서버에서 받을 것). 비 지도 구간은 시각 기준이라 지난 시각도 다시 계산할 수 있다."""

    def __init__(self, rain: RainMap, ups, radar, official):
        self.rain, self.ups, self.radar, self.official = rain, ups, radar, official
        self._cache: dict[datetime, dict] = {}

    def at(self, t: datetime) -> dict:
        t -= timedelta(seconds=t.timestamp() % TICK_S)
        if t not in self._cache:
            cells = self.rain.cells(t)
            self._cache[t] = {
                "rain_cells": [{"lat": round(c.lat, 6), "lng": round(c.lng, 6), "mmph": round(c.mmph, 1)} for c in cells],
                "underpasses": [_risk_json(r) for r in fuse(self.ups, cells, self.radar, self.official, t)],
            }
        return self._cache[t]


def _frame(t: int, m: Mover, pos, field: RainField, at: datetime, tbt_left: float | None) -> dict:
    lat, lng, heading = pos
    tbt = {"type": TBT_TYPE, "distance_m": round(tbt_left)} if tbt_left is not None and 0 < tbt_left <= TBT_FROM_M else None
    wiper = Driver(auto=False, bias=0).update(field.mmph(lat, lng, at)).switch
    return {"t": t, "lat": round(lat, 6), "lng": round(lng, 6), "heading": round(heading, 1),
            "speed_mps": round(m.speed_mps(), 2), "gear": "D", "wiper": wiper, "window_open": True, "tbt": tbt}


def _arriving(m: Mover, end_m: float) -> bool:
    """다음 step 안에 목적지에 닿는다(Mover는 끝에 닿으면 새 임의 경로를 받으므로 그 전에 멈춘다)."""
    return end_m - m.traveled_m() <= m.speed_mps() * STEP_S


def _drive(g, field, hz: _Hazards, up: Underpass, route: list, start: datetime):
    """start에 출발해 route를 달린다. 앱 첫 경고(입구 2 km 안 + WARNING 이상)가 조건(준비가 먼저, 근거 vehicles,
    출발 약 3분 뒤, 입구까지 0.5 km 이상)을 만족하면 (프레임, (경고 t, 그때의 Mover, 그때의 위험 정보))를 낸다."""
    edges = set(underpass_edges(g, up))
    k = next(i for i, e in enumerate(zip(route, route[1:])) if e in edges)
    entry_m = sum(_edge(g, a, b)["length"] for a, b in zip(route[:k], route[1:k + 1]))
    end_m = sum(_edge(g, a, b)["length"] for a, b in zip(route, route[1:]))
    m = Mover(g, Random(0))
    m.follow(route)
    frames, prep_seen, warn, t, pos = [], False, None, 0, m.step(0)
    while True:
        at = start + timedelta(seconds=t)
        left = entry_m - m.traveled_m()
        frames.append(_frame(t, m, pos, field, at, left))
        h = hz.at(at)
        if warn is None:
            target = next(u for u in h["underpasses"] if u["id"] == up.id)
            if frames[-1]["tbt"] and LEVEL_ORDER.index(target["level"]) >= WARNING:
                if not (prep_seen and "vehicles" in target["reasons"] and left >= MIN_LEFT_AT_WARN_M
                        and WARN_AT_S[0] <= t <= WARN_AT_S[1]):
                    return None
                warn = (t, copy.copy(m), h)
            elif (lvl := prep_level(*pos, m.speed_mps(), h["rain_cells"])) and wiper_below(frames[-1]["wiper"], lvl):
                prep_seen = True
        if _arriving(m, end_m):
            return (frames, warn) if warn else None
        t += STEP_S
        pos = m.step(STEP_S)


def _bypass(g, field, ups, up: Underpass, dest, warn, start) -> list[dict]:
    """경고 순간부터 우회 지점을 거쳐 dest로 간다. 그때 WARNING 이상인 다른 지하차도도 피한다."""
    t, m, h = warn
    level = {u["id"]: LEVEL_ORDER.index(u["level"]) for u in h["underpasses"]}
    blocked = [up] + [u for u in ups if u.id != up.id and level[u.id] >= WARNING]
    m.reroute(bypass_route(g, m.next_node, dest, up.bypass_waypoint, blocked))
    end_m = sum(_edge(g, a, b)["length"] for a, b in zip(m.route, m.route[1:]))
    frames = []
    while not _arriving(m, end_m):
        t += STEP_S
        pos = m.step(STEP_S)
        frames.append(_frame(t, m, pos, field, start + timedelta(seconds=t), None))
    return frames


def _try_start(g, field, hz, ups, up, start, route_tries) -> dict | None:
    for rs in range(route_tries):
        route = route_through(g, up, Random(rs), before_m=BEFORE_M, after_m=AFTER_M)
        run = _drive(g, field, hz, up, route, start)
        if run is None:
            continue
        frames, warn = run
        bypass = _bypass(g, field, ups, up, route[-1], warn, start)
        last = max(frames[-1]["t"], bypass[-1]["t"])
        return {
            "start": start.isoformat(), "step_s": STEP_S, "route_underpass": up.id,
            "my_car": frames,
            "bypass": {"from_t": warn[0], "my_car": bypass},
            "hazards": [{"t": s, **hz.at(start + timedelta(seconds=s))} for s in range(0, last + TICK_S, TICK_S)],
        }
    return None


def build(field: RainField, graph, ups: list[Underpass], target_id: str, radar, official, density: str,
          start: datetime, end: datetime, seed: int = 1, route_tries: int = 20) -> dict:
    """start~end 사이 출발 시각을 1분 간격으로 시험해 조건에 맞는 첫 시나리오를 만든다. 못 찾으면 ValueError."""
    up = next(u for u in ups if u.id == target_id)
    fleet = Fleet(field, graph, density, seed=seed)
    rain = RainMap()
    hz = _Hazards(rain, ups, radar, official)
    t, dt, cand = start, 0.0, start
    while cand <= end:
        rain.add(WiperEvent(vid=e["vid"], ts=t, lat=e["lat"], lng=e["lng"], switch=e["switch"], state=e["state"],
                            auto_wpm=e.get("auto_wpm")) for _, e in fleet.step(t, dt))
        while cand <= end and cand + HORIZON <= t:  # 그 출발의 경로가 끝날 때까지 이벤트가 쌓였다
            sc = _try_start(graph, field, hz, ups, up, cand, route_tries)
            if sc is not None:
                return sc
            cand += CANDIDATE_EVERY
        rain.prune(t)
        t, dt = t + timedelta(seconds=TICK_S), TICK_S
    raise ValueError(f"{start:%H:%M}~{end:%H:%M} 출발에서 조건을 만족하는 시나리오가 없다")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--density", choices=["current", "future"], default="future")
    ap.add_argument("--underpass", default="gaepo")
    ap.add_argument("--date", default="2022-08-08")
    ap.add_argument("--from", dest="t_from", default="17:00")
    ap.add_argument("--to", dest="t_to", default="21:00")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    at = lambda hhmm: datetime.fromisoformat(f"{args.date}T{hhmm}").replace(tzinfo=KST)

    sc = build(RainField(*load_norm(RAIN)), load_graph(ROADS), load_underpasses(ROOT / "shared" / "underpasses_gangnam.json"),
               args.underpass, ReplayRadarSource(DATA / "radar_20220808_cells.csv"),
               ReplayOfficialSource(DATA / "kma_warnings_20220808.json", DATA / "alerts_20220808.json"),
               args.density, at(args.t_from), at(args.t_to), seed=args.seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(sc, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tbt = next(f for f in sc["my_car"] if f["t"] == sc["bypass"]["from_t"])["tbt"]
    print(f"썼다: {args.out} ({args.out.stat().st_size / 1e6:.1f} MB) 출발 {sc['start']}, 경로 {sc['my_car'][-1]['t']}초, "
          f"경고 {sc['bypass']['from_t']}초(입구까지 {tbt['distance_m']} m), 우회 끝 {sc['bypass']['my_car'][-1]['t']}초",
          file=sys.stderr)


if __name__ == "__main__":
    main()
