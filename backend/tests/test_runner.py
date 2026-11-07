from datetime import timedelta

from conftest import T0
from wipernet_sim.fleet import FLEET_CURRENT, Fleet
from wipernet_sim.runner import run


def test_runner_resets_first_and_vids_rotate(fake_server, tiny_field_wet, tiny_graph):
    run(fake_server.url, Fleet(tiny_field_wet, tiny_graph, "current"), T0, T0 + timedelta(minutes=20), speed=0)
    assert fake_server.calls[0] == "POST /v1/reset"
    assert set(fake_server.calls[1:]) == {"POST /v1/wiper-events"}
    vids = {e["vid"] for b in fake_server.batches for e in b["events"]}
    assert len(vids) > FLEET_CURRENT    # 15분 회전


def test_runner_posts_one_batch_per_tick_in_time_order(fake_server, tiny_field_wet, tiny_graph):
    run(fake_server.url, Fleet(tiny_field_wet, tiny_graph, "current"), T0, T0 + timedelta(minutes=2), speed=0)
    stamps = [{e["ts"] for e in b["events"]} for b in fake_server.batches]
    assert all(len(s) == 1 for s in stamps)               # 한 배치 = 한 tick
    flat = [next(iter(s)) for s in stamps]
    assert flat == sorted(flat) and flat[0] == T0.isoformat()
