from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from wipernet_server.api import create_app

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 20, 0, tzinfo=KST)
GAEPO = (37.475985, 127.058793)


@pytest.fixture
def client():
    return TestClient(create_app())


def e(vid, switch, plus_minutes=0, lat=37.50, lng=127.03, **extra):
    return {"vid": vid, "ts": (T0 + timedelta(minutes=plus_minutes)).isoformat(), "lat": lat, "lng": lng,
            "switch": switch, "state": "OFF" if switch == "OFF" else "ON", **extra}


def test_ingest_then_rain_map(client):
    client.post("/v1/wiper-events", json={"events": [e(v, "CONTINUOUS_LEVEL_3") for v in "abc"]})
    body = client.get("/v1/rain-map").json(); assert body["cells"][0]["mmph"] == 25


def test_future_event_rejected(client):
    client.post("/v1/wiper-events", json={"events": [e("a", "OFF")]})
    r = client.post("/v1/wiper-events", json={"events": [e("b", "OFF", plus_minutes=6)]}).json()
    assert r == {"accepted": 0, "rejected": 1}


def test_underpass_risk_shape(client):
    r = client.get("/v1/underpass-risk").json()
    assert {"id", "name", "lat", "lng", "level", "mmph_10m", "acc1h_mm", "bypass_waypoint"} <= r["underpasses"][0].keys()


def test_reset(client):
    client.post("/v1/wiper-events", json={"events": [e("a", "OFF")]}); client.post("/v1/reset")
    assert client.get("/v1/clock").json() == {"now": None}


def test_debug_vehicles_only_recent(client):
    for vid, m in [("old", 0), ("mid", 4), ("new", 8)]:  # 각 배치는 직전 시계 + 5분 안이라 받아들여진다
        client.post("/v1/wiper-events", json={"events": [e(vid, "OFF", plus_minutes=m)]})
    assert {v["vid"] for v in client.get("/v1/debug/vehicles").json()["vehicles"]} == {"mid", "new"}


# 계획 밖에서 더한 테스트: 계약(docs/api.md)의 세부를 고정한다.

def test_ingest_returns_202_and_counts(client):
    r = client.post("/v1/wiper-events", json={"events": [e("a", "OFF"), e("b", "OFF", plus_minutes=1)]})
    assert r.status_code == 202 and r.json() == {"accepted": 2, "rejected": 0}


def test_clock_is_latest_event_time(client):
    assert client.get("/v1/clock").json() == {"now": None}
    client.post("/v1/wiper-events", json={"events": [e("a", "OFF", plus_minutes=3), e("b", "OFF", plus_minutes=1)]})
    assert datetime.fromisoformat(client.get("/v1/clock").json()["now"]) == T0 + timedelta(minutes=3)


def test_exactly_five_minutes_ahead_is_accepted(client):
    client.post("/v1/wiper-events", json={"events": [e("a", "OFF")]})
    r = client.post("/v1/wiper-events", json={"events": [e("b", "OFF", plus_minutes=5)]}).json()
    assert r == {"accepted": 1, "rejected": 0}


def test_naive_timestamp_is_invalid(client):
    bad = e("a", "OFF") | {"ts": "2022-08-08T20:00:00"}
    assert client.post("/v1/wiper-events", json={"events": [bad]}).status_code == 422


def test_unknown_switch_accepted_but_ignored(client):
    client.post("/v1/wiper-events", json={"events": [e(v, "MIST") for v in "abc"]})
    assert client.get("/v1/rain-map").json()["cells"] == []


def test_auto_wpm_is_used(client):
    client.post("/v1/wiper-events", json={"events": [e(v, "AUTO", auto_wpm=40) for v in "abc"]})
    assert client.get("/v1/rain-map").json()["cells"][0]["mmph"] == pytest.approx(44.0)


def test_rain_map_shape_and_past_query(client):
    client.post("/v1/wiper-events", json={"events": [e(v, "CONTINUOUS_LEVEL_3") for v in "abc"]})
    body = client.get("/v1/rain-map").json()
    assert body["cell"] == {"lat": 0.0045, "lng": 0.0057}
    assert datetime.fromisoformat(body["at"]) == T0
    assert {"row", "col", "lat", "lng", "mmph", "acc1h_mm", "vehicles", "saturated_min", "bins"} <= body["cells"][0].keys()
    assert body["cells"][0]["bins"][-1].keys() == {"start", "mmph", "saturated"}
    past = (T0 - timedelta(minutes=1)).isoformat()
    assert client.get("/v1/rain-map", params={"at": past}).json()["cells"] == []


def test_empty_server_queries(client):
    assert client.get("/v1/rain-map").json()["cells"] == []
    assert {u["level"] for u in client.get("/v1/underpass-risk").json()["underpasses"]} == {"NONE"}


def test_underpass_risk_from_vehicles(client):
    client.post("/v1/wiper-events", json={"events": [e(v, "CONTINUOUS_LEVEL_4", lat=GAEPO[0], lng=GAEPO[1]) for v in "abc"]})
    risks = {u["id"]: u for u in client.get("/v1/underpass-risk").json()["underpasses"]}
    assert risks["gaepo"]["level"] == "WARNING"  # 40 mm/h, 위험도 높음 → 경고 기준 30
    assert risks["sapyeong"]["level"] == "NONE"


def test_raw_events_pruned_on_ingest(client):
    for m in range(0, 130, 5):  # 마지막 배치 125분 → 2시간 전은 5분
        client.post("/v1/wiper-events", json={"events": [e(f"v{m}", "OFF", plus_minutes=m)]})
    assert client.app.state.store.raw_event_count() == 25  # 0분 이벤트만 2시간이 지나 지워졌다


def test_reset_clears_vehicles_and_cells(client):
    client.post("/v1/wiper-events", json={"events": [e(v, "CONTINUOUS_LEVEL_3") for v in "abc"]})
    client.post("/v1/reset")
    assert client.get("/v1/debug/vehicles").json() == {"vehicles": []}
    assert client.get("/v1/rain-map").json()["cells"] == []
