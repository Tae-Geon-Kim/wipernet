import csv
import struct
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest

from wipernet_server.external.radar import (
    GridPoint,
    LiveRadarSource,
    ReplayRadarSource,
    dbz_to_mmph,
    frame_values,
    grid_to_cells,
    load_grid_points,
)
from wipernet_server.grid import cell_of
from wipernet_server.underpass import load_underpasses

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 21, 0, tzinfo=KST)
DATA = Path(__file__).resolve().parents[1] / "data"
SHARED = Path(__file__).resolve().parents[2] / "shared" / "underpasses_gangnam.json"


def frame(nx, ny, values):
    """API허브 disp=B 모양: nx, ny(2바이트) 다음 nx*ny개 short. 왼쪽 아래부터 가로 우선."""
    return struct.pack(f"<hh{nx * ny}h", nx, ny, *values)


# --- 변환 ---

def test_dbz_to_mmph_marshall_palmer():
    assert dbz_to_mmph(40.0) == pytest.approx(11.53, abs=0.01)  # Z=200R^1.6
    assert dbz_to_mmph(50.0) == pytest.approx(48.62, abs=0.01)


def test_frame_values_reads_points_and_special_values():
    raw = frame(3, 2, [100, 200, 300,
                       4000, -25000, -30000])
    pts = [GridPoint(0, 1, 37.50, 127.03), GridPoint(1, 1, 37.51, 127.03), GridPoint(2, 1, 37.52, 127.03)]
    vals = frame_values(raw, pts)
    assert vals[(37.50, 127.03)] == pytest.approx(dbz_to_mmph(40.0))
    assert vals[(37.51, 127.03)] == 0.0  # 에코 없음은 비가 오지 않는 것
    assert (37.52, 127.03) not in vals  # 관측 영역 밖은 모른다


def test_frame_values_rejects_unexpected_size():
    with pytest.raises(ValueError):
        frame_values(frame(3, 2, [0] * 6)[:-2], [GridPoint(0, 0, 37.5, 127.0)])


def test_grid_to_cells_averages_points_in_cell():
    vals = {(37.5001, 127.0301): 40.0, (37.5002, 127.0302): 60.0}
    assert grid_to_cells(vals) == {cell_of(37.5001, 127.0301): 50.0}


# --- 기록 재생 ---

def write_csv(tmp_path, rows):
    p = tmp_path / "r.csv"
    with p.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ts", "row", "col", "mmph"])
        for ts, row, col, mmph in rows:
            w.writerow([ts.isoformat(), row, col, mmph])
    return p


def test_replay_uses_bin_only_after_its_last_frame(tmp_path):
    src = ReplayRadarSource(write_csv(tmp_path, [(T0, 10, 20, 80.0)]))
    # T0 구간은 T0, T0+5분 장면의 평균이라 T0+5분부터 쓸 수 있다
    assert src.mmph(10, 20, T0 + timedelta(minutes=4)) is None
    assert src.mmph(10, 20, T0 + timedelta(minutes=5)) == 80.0
    assert src.mmph(10, 20, T0 + timedelta(minutes=14)) == 80.0
    assert src.mmph(10, 20, T0 + timedelta(minutes=15)) is None  # 다음 구간 차례
    assert src.cells(T0 + timedelta(minutes=9)) == {(10, 20): 80.0}


# --- 실시간 ---

PTS_CSV = "x,y,lat,lng\n1,0,37.50,127.03\n"


def test_live_fetches_latest_and_caches(tmp_path):
    pts = tmp_path / "g.csv"
    pts.write_text(PTS_CSV)
    calls = []

    def fetch(url):
        calls.append(url)
        return frame(2, 1, [0, 4000])

    clock = [0.0]
    src = LiveRadarSource("KEY", pts, fetch=fetch, monotonic=lambda: clock[0])
    c = cell_of(37.50, 127.03)
    assert src.mmph(*c, T0) == pytest.approx(dbz_to_mmph(40.0))
    src.cells(T0)
    assert len(calls) == 1  # 5분 안에는 다시 받지 않는다
    clock[0] = 301.0
    src.cells(T0)
    assert len(calls) == 2
    assert all("authKey=KEY" in u and "tm=" not in u and "disp=B" in u for u in calls)


def test_live_keeps_last_frame_when_api_fails(tmp_path):
    pts = tmp_path / "g.csv"
    pts.write_text(PTS_CSV)
    ok = [True]

    def fetch(url):
        if not ok[0]:
            raise httpx.ConnectError("down")
        return frame(2, 1, [0, 4000])

    clock = [0.0]
    src = LiveRadarSource("KEY", pts, fetch=fetch, monotonic=lambda: clock[0])
    first = src.cells(T0)
    ok[0], clock[0] = False, 600.0
    assert src.cells(T0) == first


# --- 저장소에 들어 있는 기록 ---

def test_grid_points_cover_every_underpass_cell():
    cells = {cell_of(p.lat, p.lng) for p in load_grid_points(DATA / "radar_grid_gangnam.csv")}
    for up in load_underpasses(SHARED):
        assert cell_of(up.lat, up.lng) in cells, up.id


def test_recorded_radar_shows_heavy_rain_in_evening():
    src = ReplayRadarSource(DATA / "radar_20220808_cells.csv")
    at = T0 + timedelta(minutes=5)
    cells = src.cells(at)
    assert len(cells) > 100
    assert max(cells.values()) > 30.0  # 그날 21시 강남·서초는 시간당 수십 mm였다
