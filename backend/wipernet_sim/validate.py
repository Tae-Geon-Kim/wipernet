"""검증 리포트(Task 9, S6). 서버 없이 시뮬레이터와 서버 계산 모듈을 직접 묶어 돌리고 docs/validation.md를 쓴다.

    .venv/bin/python -m wipernet_sim.validate [--out ../docs/validation.md] [--from 17:00 --to 23:00]

(a) 재현: 차들의 와이퍼만으로 서버가 시뮬레이터 강우장을 얼마나 복원하나(밀도 current / future)
(b) 현실성: AWS 관측소를 하나씩 빼고 나머지로 메웠을 때의 오차
(c) 독립 비교: 서버 와이퍼 추정 vs 기상청 레이더
(d) 출처 비교: 지하차도마다 와이퍼만 / 외부 자료만 / 둘 다로 처음 경고·위험이 된 시각
"""

import argparse
import math
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path
from statistics import median

from wipernet_server.external.official import KST, ReplayOfficialSource
from wipernet_server.external.radar import ReplayRadarSource
from wipernet_server.fusion import NoOfficial, NoRadar, fuse
from wipernet_server.grid import cell_center, cell_of
from wipernet_server.rainmap import BINS_PER_HOUR, MIN_VEHICLES, RainMap, WiperEvent
from wipernet_server.underpass import LEVEL_ORDER, RADIUS_KM, distance_km, level_from, load_underpasses
from wipernet_server.wiper import auto_intensity, intensity_to_mmph, switch_intensity

from .fleet import FLEET_CURRENT, FLEET_FUTURE, IN_AREA_FRAC, Fleet
from .rainfield import RainField, load_norm
from .roads import load_graph
from .runner import RAIN, ROADS

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "backend" / "data"
DEMO_AREA = ((37.47, 127.00), (37.53, 127.07))
TICK_S = 10.0
EVAL_EVERY = timedelta(minutes=10)
FIRST_EVERY = timedelta(minutes=1)
THRESH = 10.0  # 적중·오경보를 가르는 비 세기(mm/h)
SPLIT = 60.0   # 와이퍼 상한. 이보다 센 실제 비는 따로 오차를 낸다
NEAR_KM = RADIUS_KM + 0.5  # (d)는 지하차도 근처 이벤트만 따로 모아 1분마다 빨리 계산한다
GAEPO_CLOSURE = "21:00~21:30께 양방향 통제 (한국일보·경향신문, shared/underpasses_gangnam.md)"
GAEPO_CLOSED_AT = datetime(2022, 8, 8, 21, 0, tzinfo=KST)


def score(pairs: list[tuple[float | None, float]], thresh: float = THRESH) -> dict[str, float]:
    """(추정 또는 None, 실제) 쌍. 추정이 없는 칸은 오차에서 빼고, 적중률에서는 놓친 것으로 센다."""
    with_est = [(e, r) for e, r in pairs if e is not None]
    rain = [(e, r) for e, r in pairs if r >= thresh]
    detected = [(e, r) for e, r in with_est if e >= thresh]
    nan = float("nan")
    return {
        "mae": sum(abs(e - r) for e, r in with_est) / len(with_est) if with_est else nan,
        "hit_rate": sum(e is not None and e >= thresh for e, _ in rain) / len(rain) if rain else nan,
        "false_alarm_rate": sum(r < thresh for _, r in detected) / len(detected) if detected else nan,
    }


def _area_cells(area) -> list[tuple[int, int]]:
    (lat0, lng0), (lat1, lng1) = area
    r0, c0 = cell_of(lat0, lng0)
    r1, c1 = cell_of(lat1, lng1)
    return [(r, c) for r in range(r0, r1 + 1) for c in range(c0, c1 + 1)]


VARIANTS = ("truth", "wiper", "external", "both")


def _radius_cells(up) -> list[tuple[float, float]]:
    """서버 assess와 같게, 칸 중심이 반경 안인 칸들의 중심."""
    r, c = cell_of(up.lat, up.lng)
    centers = (cell_center(r + dr, c + dc) for dr in range(-4, 5) for dc in range(-4, 5))
    return [ll for ll in centers if distance_km(up.lat, up.lng, *ll) <= RADIUS_KM]


def _truth_level(field, up, centers, at) -> str:
    """정답(시뮬레이터 강우장) 기준 단계: 반경 안 칸의 최대 세기·최대 1시간 누적으로 서버와 같은 기준표를 쓴다."""
    t = at - timedelta(seconds=1)
    mmph = max(field.mmph(lat, lng, t) for lat, lng in centers)
    acc = max(sum(field.mmph(lat, lng, t - k * EVAL_EVERY) for k in range(BINS_PER_HOUR)) / BINS_PER_HOUR
              for lat, lng in centers)
    return level_from(mmph, acc, up.base_risk)


def _first_times(field, ups, radius, cells, radar, official, at, first):
    levels = {"truth": [_truth_level(field, up, radius[up.id], at) for up in ups]}
    variants = {"wiper": (cells, NoRadar(), NoOfficial()), "external": ([], radar, official), "both": (cells, radar, official)}
    for name, (cs, rd, of) in variants.items():
        levels[name] = [r.level for r in fuse(ups, cs, rd, of, at)]
    for name, lv in levels.items():
        for up, level_now in zip(ups, lv):
            for level in ("WARNING", "DANGER"):
                if LEVEL_ORDER.index(level_now) >= LEVEL_ORDER.index(level) and first[up.id][name][level] is None:
                    first[up.id][name][level] = at


def _event_mmph(w: WiperEvent) -> float | None:
    i = auto_intensity(w.auto_wpm) if w.switch == "AUTO" else switch_intensity(w.switch)
    return None if i is None else intensity_to_mmph(i)


def _median_without(entries, t, drop) -> float | None:
    """서버 _bin과 같은 계산(차별 최신값의 중앙값, 3대 미만이면 없음)을 drop 차를 빼고 다시 한다."""
    latest = {}
    for ts, vid, car, v in entries:
        if car in drop or not (t - EVAL_EVERY < ts <= t):
            continue
        if vid not in latest or ts >= latest[vid][0]:
            latest[vid] = (ts, v)
    vals = [v for _, v in latest.values()]
    return median(vals) if len(vals) >= MIN_VEHICLES else None


def simulate(field, graph, ups, radar, official, density, start, end, seed, area) -> tuple[dict, dict]:
    """한 밀도로 start~end를 돌려 (a)·(c) 결과와 (d) 처음 시각을 낸다."""
    fleet = Fleet(field, graph, density, seed=seed)
    stuck = {i for i, c in enumerate(fleet.cars) if c.driver.stuck}
    demo = _area_cells(area)
    rain, near = RainMap(), RainMap()
    window: dict[tuple[int, int], list] = defaultdict(list)  # 칸 → (시각, vid, 차 번호, mm/h)
    pairs, low, high, radar_pairs = [], [], [], []
    covered = total = enough = only_by_double = stuck_cells = sum_vids = sum_cars = 0
    first = {up.id: {v: {"WARNING": None, "DANGER": None} for v in VARIANTS} for up in ups}
    radius = {up.id: _radius_cells(up) for up in ups}
    t, dt = start, 0.0
    next_eval, next_first = start + EVAL_EVERY, start
    while t <= end:
        stepped = fleet.step(t, dt)
        evs = []
        for car, e in stepped:
            w = WiperEvent(vid=e["vid"], ts=t, lat=e["lat"], lng=e["lng"], switch=e["switch"], state=e["state"],
                           auto_wpm=e.get("auto_wpm"))
            evs.append(w)
            v = _event_mmph(w)
            if v is not None:
                window[cell_of(w.lat, w.lng)].append((t, w.vid, car, v))
        rain.add(evs)
        near.add(w for w in evs if any(distance_km(w.lat, w.lng, u.lat, u.lng) <= NEAR_KM for u in ups))

        if t >= next_first:
            _first_times(field, ups, radius, near.cells(t), radar, official, t, first)
            next_first += FIRST_EVERY

        if t >= next_eval:
            est = {(c.row, c.col): c.mmph for c in rain.cells(t)}
            for rc in demo:
                lat, lng = cell_center(*rc)
                real = field.mmph(lat, lng, t - timedelta(seconds=1))  # 서버 창 (t-10분, t]와 같은 구간
                e = est.get(rc)
                pairs.append((e, real))
                (low if real <= SPLIT else high).append((e, real))
                total += 1
                covered += e is not None
                recent = [x for x in window[rc] if t - EVAL_EVERY < x[0] <= t]
                vids, cars = {x[1] for x in recent}, {x[2] for x in recent}
                if e is not None:
                    enough += 1
                    sum_vids, sum_cars = sum_vids + len(vids), sum_cars + len(cars)
                    only_by_double += len(cars) < MIN_VEHICLES
                    if e >= THRESH and real < THRESH and cars & stuck:  # 고장 차를 빼면 오경보가 사라지는가
                        cf = _median_without(recent, t, stuck)
                        stuck_cells += cf is None or cf < THRESH
                    r = radar.mmph(*rc, t)
                    if r is not None:
                        radar_pairs.append((e, r))
            for rc in list(window):
                window[rc] = [x for x in window[rc] if x[0] > t - EVAL_EVERY]
            rain.prune(t)
            near.prune(t)
            next_eval += EVAL_EVERY
        t, dt = t + timedelta(seconds=TICK_S), TICK_S
    recon = {
        "cars": len(fleet.cars), "in_area": FLEET_CURRENT if density == "current" else FLEET_FUTURE,
        "score": score(pairs), "mae_low": score(low)["mae"], "mae_high": score(high)["mae"],
        "n_high": len(high), "coverage": covered / total if total else float("nan"),
        "double_rate": sum_vids / sum_cars - 1 if sum_cars else float("nan"),
        "only_by_double": only_by_double, "stuck_false_cells": stuck_cells, "cell_bins": total,
        "radar": score(radar_pairs), "radar_n": len(radar_pairs),
        "radar_mean": (sum(r for _, r in radar_pairs) / len(radar_pairs)) if radar_pairs else float("nan"),
        "wiper_mean": (sum(e for e, _ in radar_pairs) / len(radar_pairs)) if radar_pairs else float("nan"),
    }
    return recon, first


def leave_one_out(field: RainField, start: datetime, end: datetime) -> dict:
    """관측소마다 그 관측소를 빼고 나머지로 만든 강우장 값을 그 자리의 실제 값과 비교한다."""
    rows, all_pairs = [], []
    heavy0 = start.replace(hour=20, minute=0)
    for s in field.stations:
        others = RainField([o for o in field.stations if o.id != s.id], field.series)
        pairs, heavy = [], []
        for ts, real in field.series[s.id]:
            if not (start <= ts < end):
                continue
            try:
                est = others.mmph(s.lat, s.lng, ts)
            except ValueError:
                continue
            pairs.append((est, real))
            if heavy0 <= ts < heavy0 + timedelta(hours=1):
                heavy.append((est, real))
        sc = score(pairs)
        rows.append({"id": s.id, "name": s.name, "n": len(pairs), "mae": sc["mae"],
                     "real_mean": _mean(r for _, r in pairs), "est_mean": _mean(e for e, _ in pairs),
                     "heavy_real": _mean(r for _, r in heavy), "heavy_est": _mean(e for e, _ in heavy)})
        all_pairs += pairs
    return {"stations": rows, "score": score(all_pairs), "n": len(all_pairs)}


def _mean(xs) -> float:
    xs = list(xs)
    return sum(xs) / len(xs) if xs else float("nan")


def run_validation(field, graph, ups, radar, official, densities, start, end, seed=1, area=DEMO_AREA) -> dict:
    res = {"start": start, "end": end, "seed": seed, "recon": {}, "first": {}, "underpasses": ups}
    for d in densities:
        print(f"{d} 밀도 실행 중", file=sys.stderr)
        res["recon"][d], res["first"][d] = simulate(field, graph, ups, radar, official, d, start, end, seed, area)
    res["loo"] = leave_one_out(field, start, end)
    return res


# ---------- 문서 ----------

def _pct(x: float) -> str:
    return "—" if math.isnan(x) else f"{x * 100:.0f}%"


def _num(x: float, nd: int = 1) -> str:
    return "—" if math.isnan(x) else f"{x:.{nd}f}"


def _hm(t: datetime | None) -> str:
    return "없음" if t is None else t.strftime("%H:%M")


def render(res: dict) -> str:
    start, end = res["start"], res["end"]
    L = [
        "# 와이퍼넷 검증 리포트",
        "",
        f"> 만든 방법: `cd backend && .venv/bin/python -m wipernet_sim.validate` (2022-08-08 {start:%H:%M}~{end:%H:%M} KST, 시드 {res['seed']}, 10초 간격). 손으로 고치지 않는다.",
        "",
        "## 이 검증이 증명하는 것 / 증명하지 않는 것 (설계 6.5-1)",
        "",
        "- **증명하는 것:** 잡음 섞인 와이퍼 신호만으로 서버가 비 지도를 얼마나 복원하는지(a), 차가 많아지면 얼마나 나아지는지(a), "
        "관측소만으로 관측소 사이를 메우면 얼마나 틀리는지(b), 와이퍼 추정이 따로 잰 레이더와 얼마나 다른지(c), "
        "지하차도 경고가 출처별로 언제 나오는지(d).",
        "- **증명하지 않는 것:** (a)의 정답은 AWS 관측값으로 만든 시뮬레이터 강우장이다. 그래서 \"와이퍼가 실제 비를 맞힌다\"나 "
        "\"와이퍼가 관측소가 놓친 비를 잡는다\"는 이 검증으로 증명하지 않는다. 실제 차의 와이퍼가 비 세기를 따른다는 근거는 미시간대 연구를 인용한다. "
        "\"와이퍼가 레이더보다 정확하다\"는 주장도 시뮬레이션으로 증명할 수 없으므로 같은 연구를 인용하는 데 그친다. (c)는 두 값이 얼마나 다른지만 보여 준다. "
        "지하차도 위험 단계(d)는 실제 통제 기록을 찾은 개포지하차도만 시각을 비교하고, 나머지 5곳은 예시 시나리오로 정확도를 주장하지 않는다. "
        "차량 대수, AUTO 비율, 운전자 반응은 가정치다(설계 6.10.1). 시드 하나의 결과다.",
        "",
        "## (a) 재현: 와이퍼만으로 비 지도 복원",
        "",
        f"데모 지역 칸(약 500 m)마다 10분 간격으로 서버 추정과 그 칸 중심의 시뮬레이터 강우장을 비교했다. "
        f"적중률 분모는 실제 {THRESH:.0f} mm/h 이상인 **모든** 칸(추정이 없으면 놓침), 오경보율 분모는 {THRESH:.0f} mm/h 이상으로 감지한 칸이다.",
        "",
        "| 밀도 | 전체 / 지역 안 차 | 칸 커버리지 | 적중률 | 오경보율 | 오차(≤60 mm/h) | 오차(>60 mm/h, 칸-구간 수) |",
        "|---|---|---|---|---|---|---|",
    ]
    names = {"current": "현재", "future": "미래"}
    for d, r in res["recon"].items():
        s = r["score"]
        L.append(f"| {names.get(d, d)} | {r['cars']:,} / {r['in_area']:,}대 | {_pct(r['coverage'])} | {_pct(s['hit_rate'])} | "
                 f"{_pct(s['false_alarm_rate'])} | {_num(r['mae_low'])} mm/h | {_num(r['mae_high'])} mm/h ({r['n_high']}) |")
    L += [
        "",
        f"- 지역 안 대수는 도로망이 데모 지역보다 넓어 전체의 약 {IN_AREA_FRAC:.0%}만 지역 안에 있다는 측정값으로 맞췄다(S3).",
        "- 60 mm/h를 넘는 비는 와이퍼 상한(연속 5단 = 60) 때문에 낮게 잡힌다. 서버는 포화 구간을 레이더로 보정하지만(V9) 이 표는 와이퍼만의 값이다.",
        "",
        "| 밀도 | 추정을 낸 칸-구간 | 임시 ID 회전으로 부풀려진 차 수 | 실제 차가 3대 미만인데 값이 나온 칸-구간 | 고장 차를 빼면 사라지는 오경보 칸-구간 |",
        "|---|---|---|---|---|",
    ]
    for d, r in res["recon"].items():
        enough = round(r["coverage"] * r["cell_bins"])
        L.append(f"| {names.get(d, d)} | {enough:,} | {_pct(r['double_rate'])} | {r['only_by_double']:,} | {r['stuck_false_cells']:,} |")
    loo = res["loo"]
    L += [
        "",
        "- 부풀려진 차 수 = 칸-구간마다 (임시 ID 수 ÷ 실제 차 수) − 1을 모두 더해 낸 비율. ID가 12~18분마다 바뀌어 10분 창 안에서 한 차가 두 ID로 보일 수 있다(익명성의 대가).",
        "- 고장 차 오경보는 그 칸에서 고장 차(연속 5단 고정) 이벤트를 빼고 서버와 같은 중앙값을 다시 계산했을 때 감지가 사라지는 경우만 센다.",
        "",
        "## (b) 현실성: AWS 관측소 하나씩 빼 보기",
        "",
        f"관측소 {len(loo['stations'])}곳마다 그 관측소를 빼고 나머지로 역거리가중 강우장을 만들어, 그 자리의 실제 10분 강수(mm/h)와 비교했다"
        f"({start:%H:%M}~{end:%H:%M}, {loo['n']:,}쌍). 전체 오차 {_num(loo['score']['mae'])} mm/h, 적중률 {_pct(loo['score']['hit_rate'])}, "
        f"오경보율 {_pct(loo['score']['false_alarm_rate'])}.",
        "",
        "| 관측소 | 오차 | 평균 실제 | 평균 추정 | 20~21시 실제 | 20~21시 추정 |",
        "|---|---|---|---|---|---|",
    ]
    rows = sorted(loo["stations"], key=lambda r: (r["id"] not in ("401", "400"), r["id"]))
    for r in rows:
        bold = "**" if r["id"] in ("400", "401") else ""
        L.append(f"| {bold}{r['name']}({r['id']}){bold} | {_num(r['mae'])} | {_num(r['real_mean'])} | {_num(r['est_mean'])} | "
                 f"{_num(r['heavy_real'])} | {_num(r['heavy_est'])} |")
    L += [
        "",
        "- 단위는 mm/h. 데모 지역 안 관측소는 서초(401) 하나, 강남(400)은 동쪽 경계 바로 밖이다.",
        "- 2022-08-08 비는 좁게 몰려, 관측소 사이를 메운 값이 실제보다 낮게 나오기 쉽다. 관측소 사이를 촘촘히 재는 수단(와이퍼)이 필요한 근거다.",
        "",
        "## (c) 독립 비교: 와이퍼 추정 vs 기상청 레이더",
        "",
        "같은 칸, 같은 시각(10분 간격)에서 서버 와이퍼 추정과 레이더(HSR, Marshall-Palmer) 값을 비교했다. 레이더는 시뮬레이터 입력이 아니라 같은 폭우를 따로 잰 자료다.",
        "",
        "| 밀도 | 비교한 칸-구간 | 와이퍼 평균 | 레이더 평균 | 차이(오차) | 레이더 기준 적중률 | 레이더 기준 오경보율 |",
        "|---|---|---|---|---|---|---|",
    ]
    for d, r in res["recon"].items():
        rs = r["radar"]
        L.append(f"| {names.get(d, d)} | {r['radar_n']:,} | {_num(r['wiper_mean'])} | {_num(r['radar_mean'])} | {_num(rs['mae'])} | "
                 f"{_pct(rs['hit_rate'])} | {_pct(rs['false_alarm_rate'])} |")
    L += [
        "",
        "- 이날 레이더는 센 비를 AWS의 약 40%로 적게 잡는다(`docs/setup-status.md` 레이더 절). 시뮬레이터 차는 AWS 강우장으로 와이퍼를 돌리므로 와이퍼 평균이 레이더보다 높게 나오는 것이 자연스럽다. "
        "레이더를 AWS에 맞춰 고치지 않는다(자기 자신과 비교하는 구조가 되므로).",
        "",
        "## (d) 출처 비교: 지하차도별 처음 경고·위험 시각 (1분 간격)",
        "",
        "- 정답: 시뮬레이터 강우장으로 같은 기준표를 쓴 단계. 와이퍼만: 주변 차량 와이퍼(레이더 보정 없음). "
        "외부 자료만: 레이더(최대 경고) + 호우특보(주의) + 재난문자. 둘 다: 실제 서버와 같은 합치기(V9, 미래 밀도).",
        "- 각 칸은 `경고 / 위험` 처음 시각이다.",
        "",
        "| 지하차도 | 위험도 | 정답 | 와이퍼만(현재) | 와이퍼만(미래) | 외부 자료만 | 둘 다(미래) |",
        "|---|---|---|---|---|---|---|",
    ]
    fd = res["first"]
    last = list(fd)[-1]
    for up in res["underpasses"]:
        cell = lambda d, v: f"{_hm(fd[d][up.id][v]['WARNING'])} / {_hm(fd[d][up.id][v]['DANGER'])}" if d in fd else "—"
        L.append(f"| {up.name} | {'높음' if up.base_risk == 'high' else '보통'} | {cell(last, 'truth')} | {cell('current', 'wiper')} | "
                 f"{cell('future', 'wiper')} | {cell(last, 'external')} | {cell(last, 'both')} |")
    L += [
        "",
        "- 와이퍼만이 정답보다 이르면 과대 추정이다. 차가 3대뿐인 칸은 개인차(±1단계)와 고장 차 하나에도 크게 흔들린다.",
        f"- 실제 기록: 개포지하차도 {GAEPO_CLOSURE}.",
    ]
    if "gaepo" in fd[last]:
        def lead(t):
            if t is None:
                return "없음"
            m = int((GAEPO_CLOSED_AT - t).total_seconds() // 60)
            return f"{_hm(t)}(통제 {abs(m)}분 {'전' if m >= 0 else '뒤'})"
        L.append(f"  이 표의 개포 위험 시각은 정답 {lead(fd[last]['gaepo']['truth']['DANGER'])}, "
                 f"둘 다 {lead(fd[last]['gaepo']['both']['DANGER'])}이다. 통제 시각은 사람이 판단해 실행한 시각이라 위험 시각이 맞았는지 재는 정답은 아니고, "
                 "위험 안내가 실제 통제보다 늦지 않았는지만 보여 준다.")
    L += [
        "- 다른 5곳은 그날 통제 보도를 찾지 못했다. 이 5곳의 시각은 예시 시나리오이며 정확도를 주장하지 않는다.",
        "- 외부 자료만으로는 레이더가 최대 경고까지만 낸다. 2022-08-08에는 지하차도 통제 재난문자를 찾지 못해(`backend/data/README.md`) 외부 자료만의 위험은 나오지 않는다.",
        "",
    ]
    return "\n".join(L)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=ROOT / "docs" / "validation.md")
    ap.add_argument("--date", default="2022-08-08")
    ap.add_argument("--from", dest="t_from", default="17:00")
    ap.add_argument("--to", dest="t_to", default="23:00")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    at = lambda hhmm: datetime.fromisoformat(f"{args.date}T{hhmm}").replace(tzinfo=KST)

    field = RainField(*load_norm(RAIN))
    radar = ReplayRadarSource(DATA / "radar_20220808_cells.csv")
    official = ReplayOfficialSource(DATA / "kma_warnings_20220808.json", DATA / "alerts_20220808.json")
    ups = load_underpasses(ROOT / "shared" / "underpasses_gangnam.json")
    res = run_validation(field, load_graph(ROADS), ups, radar, official, ["current", "future"],
                         at(args.t_from), at(args.t_to), seed=args.seed)
    args.out.write_text(render(res), encoding="utf-8")
    print(f"썼다: {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
