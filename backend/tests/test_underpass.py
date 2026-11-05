from pathlib import Path

import pytest

from wipernet_server.grid import cell_of
from wipernet_server.rainmap import CellEstimate
from wipernet_server.underpass import LEVEL_ORDER, Underpass, assess, level_from, load_underpasses

SHARED = Path(__file__).resolve().parents[2] / "shared" / "underpasses_gangnam.json"


def U(base_risk="normal", lat=37.50, lng=127.03):
    return Underpass(id="u1", name="테스트지하차도", lat=lat, lng=lng, base_risk=base_risk,
                     bypass_waypoint={"lat": lat + 0.001, "lng": lng}, gu="강남구", warning_area=None)


def _cell(lat, lng, mmph, acc):
    row, col = cell_of(lat, lng)
    return CellEstimate(row=row, col=col, lat=lat, lng=lng, mmph=mmph, acc1h_mm=acc,
                        vehicles=3, saturated_min=0, bins=[])


def cell_near(up, mmph, acc):
    return _cell(up.lat, up.lng, mmph, acc)


def cell_far(up, km, mmph, acc):
    return _cell(up.lat + km / 111.195, up.lng, mmph, acc)  # 위도 1° ≈ 111.195 km


def test_levels_normal():
    up = U(base_risk="normal")
    assert assess([up], [cell_near(up, mmph=45, acc=10)])[0].level == "WARNING"
    assert assess([up], [cell_near(up, mmph=5, acc=85)])[0].level == "DANGER"


def test_high_risk_lowers_thresholds():
    up = U(base_risk="high")
    assert assess([up], [cell_near(up, mmph=45, acc=0)])[0].level == "DANGER"  # 60*0.75 = 45


def test_far_cells_ignored():
    up = U(); assert assess([up], [cell_far(up, km=1.5, mmph=80, acc=100)])[0].level == "NONE"


# 계획 밖에서 더한 테스트: 기준표 경계와 결과 모양을 고정한다.

@pytest.mark.parametrize("mmph,acc,base,level", [
    (0, 0, "normal", "NONE"),
    (19.9, 29.9, "normal", "NONE"),
    (20, 0, "normal", "CAUTION"),
    (0, 30, "normal", "CAUTION"),
    (40, 0, "normal", "WARNING"),
    (0, 50, "normal", "WARNING"),
    (60, 0, "normal", "DANGER"),
    (0, 80, "normal", "DANGER"),
    (15, 0, "high", "CAUTION"),    # 20*0.75
    (0, 37.5, "high", "WARNING"),  # 50*0.75
])
def test_level_from_thresholds(mmph, acc, base, level):
    assert level_from(mmph, acc, base) == level


def test_level_order():
    assert LEVEL_ORDER.index("NONE") < LEVEL_ORDER.index("CAUTION") < LEVEL_ORDER.index("WARNING") < LEVEL_ORDER.index("DANGER")


def test_no_cells_is_none_with_zero_values():
    [r] = assess([U()], [])
    assert (r.level, r.mmph_10m, r.acc1h_mm) == ("NONE", 0.0, 0.0)


def test_uses_max_of_cells_within_radius():
    up = U()
    [r] = assess([up], [cell_near(up, 10, 5), cell_far(up, 0.9, 30, 20), cell_far(up, 1.2, 70, 90)])
    assert (r.mmph_10m, r.acc1h_mm, r.level) == (30, 20, "CAUTION")


def test_result_carries_underpass_fields():
    up = U()
    [r] = assess([up], [cell_near(up, 45, 0)])
    assert (r.id, r.name, r.lat, r.lng, r.bypass_waypoint) == (up.id, up.name, up.lat, up.lng, up.bypass_waypoint)


def test_load_shared_underpasses():
    ups = load_underpasses(SHARED)
    assert len(ups) == 6 and {u.id for u in ups if u.base_risk == "high"} == {"gaepo"}
    assert all(u.gu in ("강남구", "서초구") and u.bypass_waypoint is not None for u in ups)
