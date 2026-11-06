from datetime import datetime, timedelta, timezone

import pytest

from wipernet_server.external.official import Alert, RainWarning
from wipernet_server.fusion import NoOfficial, NoRadar, fuse
from wipernet_server.grid import cell_of
from wipernet_server.rainmap import BIN, Bin, CellEstimate
from wipernet_server.underpass import Underpass

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 21, 0, tzinfo=KST)


def U(base_risk="normal", name="역삼지하차도", gu="강남구", warning_area="L1100100", lat=37.50, lng=127.03):
    return Underpass(id="u1", name=name, lat=lat, lng=lng, base_risk=base_risk,
                     bypass_waypoint=None, gu=gu, warning_area=warning_area)


def cell_near(up, mmph, acc, bins=None, saturated_min=0):
    row, col = cell_of(up.lat, up.lng)
    return CellEstimate(row=row, col=col, lat=up.lat, lng=up.lng, mmph=mmph, acc1h_mm=acc,
                        vehicles=3, saturated_min=saturated_min, bins=bins or [])


def bins_of(values, saturated):
    return [Bin(T0 - (6 - k) * BIN, v, saturated) for k, v in enumerate(values)]


def saturated_cell_near(up, minutes):
    n = minutes // 10
    bins = bins_of([20.0] * (6 - n), False)[: 6 - n] + bins_of([60.0] * 6, True)[6 - n:]
    acc = sum(b.mmph for b in bins) / 6
    return cell_near(up, 60.0, acc, bins=bins, saturated_min=minutes)


class Radar:
    def __init__(self, cells):
        self._cells = cells

    def cells(self, at):
        return dict(self._cells)

    def mmph(self, row, col, at):
        return self._cells.get((row, col))


class TimedRadar:
    """시각마다 다른 레이더 값."""

    def __init__(self, by_time):
        self._by_time = by_time

    def cells(self, at):
        return dict(self._by_time.get(at, {}))

    def mmph(self, row, col, at):
        return self._by_time.get(at, {}).get((row, col))


class Official:
    def __init__(self, warnings=(), alerts=()):
        self._w, self._a = list(warnings), list(alerts)

    def warnings(self, at):
        return [w for w in self._w if w.start <= at and (w.end is None or at < w.end)]

    def alerts(self, at, since):
        return [a for a in self._a if since <= a.sent_at <= at]


def rain_warning(area, stress, start):
    return RainWarning(area, "서울동남권", stress, start, None)


def closure_alert(name, at):
    return Alert(at, ["서울특별시 강남구"], f"[강남구] {name} 침수로 차량 통제 중", None)


def rain_alert(at):
    return Alert(at, ["서울특별시 강남구"], "[강남구청] 호우경보 발효, 저지대 침수 주의", None)


# --- 설계 6.10.4 표 ---

def test_external_never_lowers():
    up = U()
    [r] = fuse([up], [cell_near(up, mmph=60, acc=10)], radar=NoRadar(), official=NoOfficial(), at=T0)
    assert r.level == "DANGER" and r.reasons == ["vehicles"]


def test_closure_alert_is_danger_without_vehicles():
    up = U(name="역삼지하차도", gu="강남구")
    a = closure_alert("역삼지하차도", T0)
    [r] = fuse([up], [], NoRadar(), Official(alerts=[a]), at=T0)
    assert r.level == "DANGER" and r.reasons == ["alert_closure"]
    assert r.alerts == [{"sent_at": a.sent_at, "text": a.text}]


def test_rain_alert_caps_at_caution():
    up = U()
    [r] = fuse([up], [], NoRadar(), Official(alerts=[rain_alert(T0)]), at=T0)
    assert r.level == "CAUTION" and r.reasons == ["alert_rain"]


def test_alerts_older_than_two_hours_are_ignored():
    up = U()
    [r] = fuse([up], [], NoRadar(), Official(alerts=[closure_alert("역삼지하차도", T0 - timedelta(hours=2, minutes=1))]),
               at=T0)
    assert r.level == "NONE" and r.alerts == [] and r.reasons == []


def test_warning_caps_at_caution():
    up = U(warning_area="L1100200")
    [r] = fuse([up], [], NoRadar(), Official(warnings=[rain_warning("L1100200", "WARNING", T0)]), at=T0)
    assert r.level == "CAUTION" and r.reasons == ["kma_warning"]


def test_warning_for_other_area_is_ignored():
    up = U(warning_area="L1100100")
    [r] = fuse([up], [], NoRadar(), Official(warnings=[rain_warning("L1100200", "WARNING", T0)]), at=T0)
    assert r.level == "NONE"


def test_radar_only_caps_at_warning():
    up = U()
    [r] = fuse([up], [], Radar({cell_of(up.lat, up.lng): 100.0}), NoOfficial(), at=T0)
    assert r.level == "WARNING" and r.reasons == ["radar"]
    assert r.mmph_10m == 100.0  # 레이더 값도 세기에 보인다


def test_radar_far_away_is_ignored():
    up = U()
    [r] = fuse([up], [], Radar({cell_of(up.lat + 0.02, up.lng): 100.0}), NoOfficial(), at=T0)  # 약 2.2 km 북쪽
    assert r.level == "NONE"


def test_saturated_bins_use_radar():
    up = U()
    c = saturated_cell_near(up, minutes=60)  # 6구간 모두 포화, 와이퍼 60 mm/h
    [r] = fuse([up], [c], Radar({(c.row, c.col): 100.0}), NoOfficial(), at=T0)
    assert r.acc1h_mm == pytest.approx(100.0) and r.level == "DANGER"
    assert r.reasons == ["vehicles", "saturated", "radar"]


def test_only_saturated_bins_are_replaced():
    up = U()
    c = saturated_cell_near(up, minutes=20)  # 앞 4구간 20 mm/h(포화 아님), 뒤 2구간 포화
    # 레이더는 구간이 끝나는 시각 값을 쓴다. 비포화 첫 구간 끝(T0-50분)의 90은 쓰이지 않아야 한다.
    # T0 장면은 비워 레이더만 판단은 끼어들지 않게 한다
    radar = TimedRadar({t: {(c.row, c.col): 90.0} for t in (T0 - 5 * BIN, T0 - BIN)})
    [r] = fuse([up], [c], radar, NoOfficial(), at=T0)
    assert r.acc1h_mm == pytest.approx((20 * 4 + 90 + 60) / 6)  # 마지막 포화 구간은 레이더가 없어 60 그대로
    assert "saturated" not in r.reasons  # 포화 20분은 30분 기준 미만


def test_weaker_radar_does_not_replace_saturated_bins():
    up = U()
    c = saturated_cell_near(up, minutes=60)
    [r] = fuse([up], [c], Radar({(c.row, c.col): 30.0}), NoOfficial(), at=T0)
    assert r.acc1h_mm == pytest.approx(60.0) and r.mmph_10m == 60.0


def test_final_level_is_max_and_reasons_keep_every_raising_source():
    up = U()
    [r] = fuse([up], [cell_near(up, mmph=25, acc=10)], Radar({cell_of(up.lat, up.lng): 45.0}),
               Official(warnings=[rain_warning("L1100100", "WARNING", T0)], alerts=[rain_alert(T0)]), at=T0)
    assert r.level == "WARNING"
    assert r.reasons == ["vehicles", "radar", "kma_warning", "alert_rain"]
