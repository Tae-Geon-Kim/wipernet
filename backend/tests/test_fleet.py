from datetime import datetime, timedelta

from conftest import T0
from wipernet_sim.fleet import FLEET_CURRENT, FLEET_FUTURE, IN_AREA_FRAC, Fleet


def run_steps(f, n, dt=10):
    return sum((f.step(T0 + timedelta(seconds=dt * k), dt if k else 0) for k in range(n)), [])


def test_fleet_size_targets_demo_area_density(tiny_field_dry, tiny_graph):
    assert len(Fleet(tiny_field_dry, tiny_graph, "current").cars) == round(FLEET_CURRENT / IN_AREA_FRAC) == 141
    assert round(FLEET_FUTURE / IN_AREA_FRAC) == 2113


def test_fleet_mix_is_exact(tiny_field_dry, tiny_graph):
    cars = Fleet(tiny_field_dry, tiny_graph, "current").cars
    assert sum(c.driver.auto for c in cars) == round(141 * 0.25)
    assert sum(c.guiding for c in cars) == round(141 * 0.4)
    assert sum(c.driver.stuck for c in cars) == 1 and not any(c.driver.stuck and c.driver.auto for c in cars)


def test_fleet_off_cars_on_guidance_report(tiny_field_dry, tiny_graph):
    f = Fleet(tiny_field_dry, tiny_graph, "current", guiding_frac=1.0, stuck_frac=0)
    evs = run_steps(f, 31)   # 5분 10초
    assert evs and all(e["state"] == "OFF" for _, e in evs)   # AUTO 차는 switch가 "AUTO"
    per_car = {}
    for car, _ in evs:
        per_car[car] = per_car.get(car, 0) + 1
    assert max(per_car.values()) <= 2 + 5   # 처음 + 300초 한 번 + 칸 바뀜 몇 번


def test_dry_cars_not_guiding_send_nothing(tiny_field_dry, tiny_graph):
    f = Fleet(tiny_field_dry, tiny_graph, "current", guiding_frac=0.0, stuck_frac=0)
    assert run_steps(f, 31) == []


def test_wet_cars_send_every_minute_with_api_fields(tiny_field_wet, tiny_graph):
    f = Fleet(tiny_field_wet, tiny_graph, "current", stuck_frac=0)
    evs = run_steps(f, 13)   # 0~120초: 차마다 0, 60, 120초
    assert len(evs) == 3 * len(f.cars)
    for _, e in evs:
        assert set(e) >= {"vid", "ts", "lat", "lng", "switch", "state"} and e["state"] == "ON"
        assert ("auto_wpm" in e) == (e["switch"] == "AUTO")


def test_vid_rotates_every_15_minutes_staggered(tiny_field_wet, tiny_graph):
    f = Fleet(tiny_field_wet, tiny_graph, "current")
    evs = run_steps(f, 6 * 20, dt=10)   # 20분
    vids_per_car = {}
    for car, e in evs:
        vids_per_car.setdefault(car, set()).add(e["vid"])
    assert all(2 <= len(v) <= 3 for v in vids_per_car.values())   # 첫 교체는 0~18분 사이 차마다 다르게
    first_rotation = {min(e["ts"] for c, e in evs if c == car and e["vid"] != evs_first_vid(evs, car))
                      for car in vids_per_car}
    assert len(first_rotation) > 1   # 모두 같은 순간에 바뀌지 않는다


def evs_first_vid(evs, car):
    return next(e["vid"] for c, e in evs if c == car)


def test_same_seed_same_events(tiny_field_wet, tiny_graph):
    a = run_steps(Fleet(tiny_field_wet, tiny_graph, "current", seed=3), 12)
    b = run_steps(Fleet(tiny_field_wet, tiny_graph, "current", seed=3), 12)
    c = run_steps(Fleet(tiny_field_wet, tiny_graph, "current", seed=4), 12)
    assert a == b and a != c


def test_vid_rotation_interval_is_random_12_to_18_minutes(tiny_field_wet, tiny_graph):
    f = Fleet(tiny_field_wet, tiny_graph, "current", stuck_frac=0)
    evs = run_steps(f, 6 * 70, dt=10)   # 70분. 켜진 차는 60초마다 보내 ID가 바뀐 시각을 1분 안으로 안다
    gaps = []
    for car in range(len(f.cars)):
        changes, prev = [], None
        for c, e in evs:
            if c == car:
                if prev is not None and e["vid"] != prev:
                    changes.append(datetime.fromisoformat(e["ts"]))
                prev = e["vid"]
        gaps += [(b - a).total_seconds() / 60 for a, b in zip(changes, changes[1:])]
    assert gaps and all(11 <= g <= 19 for g in gaps)   # 60초 전송 간격만큼 여유
    assert max(gaps) - min(gaps) > 3                    # 고정 간격이 아니다
