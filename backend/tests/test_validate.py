import math
from datetime import timedelta

import pytest

from conftest import T0
from wipernet_server.fusion import NoOfficial, NoRadar
from wipernet_server.underpass import Underpass
from wipernet_sim.rainfield import RainField, Station
from wipernet_sim.validate import render, run_validation, score


def test_score():
    s = score([(30, 25), (None, 15), (12, 0), (0, 0)])
    assert s["mae"] == pytest.approx((5 + 12 + 0) / 3)   # 추정이 없는 칸은 오차에서 뺀다
    assert s["hit_rate"] == pytest.approx(1 / 2)          # 실제 비 2칸 중 1칸 감지 (None은 놓침)
    assert s["false_alarm_rate"] == pytest.approx(1 / 2)  # 감지 2칸 중 1칸 오경보


def test_score_without_rain_or_detection_is_nan():
    s = score([(None, 0.0), (0.0, 0.0)])
    assert math.isnan(s["hit_rate"]) and math.isnan(s["false_alarm_rate"]) and s["mae"] == 0


def tiny_inputs(tiny_graph):
    # 정답 단계는 직전 1시간 누적도 보므로 T0 앞 1시간(맑음)부터 둔다
    bins = [(T0 + timedelta(minutes=10 * i), v) for i, v in zip(range(-6, 6), [0.0] * 7 + [35.0, 35.0, 35.0, 0.0, 0.0])]
    stations = [Station("a", "가", 37.50, 127.030), Station("b", "나", 37.5045, 127.0357), Station("c", "다", 37.502, 127.033)]
    field = RainField(stations, {s.id: bins for s in stations})
    up = Underpass(id="u", name="테스트지하차도", lat=37.502, lng=127.033, base_risk="high",
                   bypass_waypoint=None, gu="강남구", warning_area=None)
    return field, [up]


def test_report_runs_end_to_end_on_tiny_inputs(tiny_graph):
    field, ups = tiny_inputs(tiny_graph)
    res = run_validation(field, tiny_graph, ups, NoRadar(), NoOfficial(), densities=["current"],
                         start=T0, end=T0 + timedelta(minutes=50), seed=1,
                         area=((37.499, 127.029), (37.506, 127.037)))
    cur = res["recon"]["current"]
    assert cur["coverage"] > 0 and cur["score"]["hit_rate"] > 0.5      # 141대가 작은 사각형에 몰려 비를 잡는다
    first = res["first"]["current"]["u"]
    assert first["truth"]["WARNING"] is not None and first["truth"]["DANGER"] is None   # 정답 35 mm/h, 위험도 높음(경고 30, 위험 45) → 경고까지
    assert first["wiper"]["WARNING"] is not None                                         # 와이퍼도 경고를 낸다
    assert first["external"]["WARNING"] is None                                          # 레이더·특보를 껐다
    assert {r["id"] for r in res["loo"]["stations"]} == {"a", "b", "c"}
    md = render(res)
    for heading in ["증명하는 것", "(a)", "(b)", "(c)", "(d)"]:
        assert heading in md
