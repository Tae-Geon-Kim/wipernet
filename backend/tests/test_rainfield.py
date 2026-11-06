from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from wipernet_sim.rainfield import RainField, Station, load_norm

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 20, 0, tzinfo=KST)
NORM = Path(__file__).resolve().parents[1] / "data" / "kma_20220808_norm.csv"


def S(id, lat, lng):
    return Station(id=id, name=id, lat=lat, lng=lng)


def test_idw_at_station_equals_station():
    f = RainField([S("a", 37.50, 127.03)], {"a": [(T0, 40.0)]})
    assert f.mmph(37.50, 127.03, T0 + timedelta(minutes=3)) == 40.0


def test_idw_between_two_stations():
    f = RainField([S("a", 37.50, 127.00), S("b", 37.50, 127.06)], {"a": [(T0, 0.0)], "b": [(T0, 60.0)]})
    assert f.mmph(37.50, 127.03, T0) == pytest.approx(30.0, rel=0.05)


def test_uses_the_10min_bin_containing_t():
    f = RainField([S("a", 37.50, 127.03)], {"a": [(T0, 10.0), (T0 + timedelta(minutes=10), 50.0)]})
    assert f.mmph(37.50, 127.03, T0 + timedelta(minutes=9, seconds=59)) == 10.0
    assert f.mmph(37.50, 127.03, T0 + timedelta(minutes=10)) == 50.0
    assert f.mmph(37.50, 127.03, (T0 + timedelta(minutes=12)).astimezone(timezone.utc)) == 50.0


def test_station_without_value_in_bin_is_skipped():
    f = RainField([S("a", 37.50, 127.00), S("b", 37.50, 127.06)], {"a": [(T0, 20.0)], "b": []})
    assert f.mmph(37.50, 127.05, T0) == 20.0


def test_leave_one_out_estimates_from_the_others():
    st = [S("a", 37.50, 127.00), S("b", 37.50, 127.03), S("c", 37.50, 127.06)]
    series = {"a": [(T0, 0.0)], "b": [(T0, 90.0)], "c": [(T0, 60.0)]}
    without_b = RainField([s for s in st if s.id != "b"], series)
    assert without_b.mmph(37.50, 127.03, T0) == pytest.approx(30.0, rel=0.05)


def test_no_station_value_raises():
    f = RainField([S("a", 37.50, 127.03)], {"a": [(T0, 10.0)]})
    with pytest.raises(ValueError):
        f.mmph(37.50, 127.03, T0 - timedelta(minutes=1))


def test_load_norm_reads_committed_csv():
    stations, series = load_norm(NORM)
    ids = {s.id for s in stations}
    assert {"400", "401"} <= ids and len(stations) == 21  # 22곳 중 오전동(366)은 그날 RN-DAY가 전부 없음
    seocho = dict(series["401"])
    demo = [T0 + timedelta(minutes=10 * i) for i in range(-18, 18)]  # 17:00~23:00 구간 36개
    assert all(t in seocho for t in demo) and all(t in dict(series["400"]) for t in demo)
    # 21:00 직전 1시간(20:00~21:00) 서초 강수량은 RN-60m 92.0 mm (docs/setup-status.md)
    hour = sum(seocho[T0 + timedelta(minutes=10 * i)] for i in range(6)) / 6
    assert hour == pytest.approx(92.0, abs=0.5)
