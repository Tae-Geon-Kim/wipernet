"""기상청 API허브 레이더(HSR)를 받아 데모 지역 기록 파일로 만든다(Task 4b).

    cd backend && set -a && . ./.env && set +a
    .venv/bin/python scripts/fetch_radar.py grid > data/radar_grid_gangnam.csv
    .venv/bin/python scripts/fetch_radar.py cells --date 2022-08-08 --from 17:00 --to 23:00 \\
        > data/radar_20220808_cells.csv

- grid: '4.1 격자데이터 위경도'(disp=A, 텍스트)를 받아 데모 지역에 걸친 칸 안의 격자점만 남긴다. 한 번만 하면 된다.
- cells: 5분 장면을 받는 즉시 칸별 mm/h로 바꾸고, 두 장면씩 평균해 10분 구간으로 쓴다. 원본은 남기지 않는다.
키는 환경변수 KMA_API_HUBKEY(backend/.env, 커밋 안 함).
"""

import argparse
import csv
import os
import sys
from collections import defaultdict
from datetime import datetime
from urllib.parse import urlencode

import httpx

from wipernet_server.external.official import KST
from wipernet_server.grid import cell_of
from wipernet_server.external.radar import (
    APIHUB,
    BIN,
    FRAME,
    GRID_POINTS,
    frame_values,
    grid_to_cells,
    hsr_url,
    load_grid_points,
)

# 데모 지역(위도 37.47~37.53, 경도 127.00~127.07)에 걸친 칸. 칸 경계가 지역 경계와 달라 칸 기준으로 고른다
ROW0, COL0 = cell_of(37.47, 127.00)
ROW1, COL1 = cell_of(37.53, 127.07)


def latlon_values(key: str, which: str):
    """격자 위경도 텍스트를 격자 순서대로 하나씩 낸다. 첫 줄은 격자 수, '#' 줄은 주석."""
    url = f"{APIHUB}/nph-rdr_latlon_api?{urlencode({'cmp': 'HSR', 'latlon': which, 'disp': 'A', 'authKey': key})}"
    with httpx.stream("GET", url, timeout=300) as r:
        r.raise_for_status()
        size, n = None, 0
        for line in r.iter_lines():
            if not line.strip() or line.startswith("#"):
                continue
            nums = [s for s in line.replace("=", "").split(",") if s.strip()]
            if size is None:
                size = tuple(int(s) for s in nums)
                yield size
                continue
            n += len(nums)
            yield from (float(s) for s in nums)
        if size is None or n != size[0] * size[1]:
            raise ValueError(f"격자 {which} 응답이 잘렸음: 격자 수 {size}, 값 {n}개")


def grid(key: str) -> None:
    lat_it = latlon_values(key, "lat")
    nx, _ = next(lat_it)
    lats = {i: v for i, v in enumerate(lat_it) if ROW0 <= cell_of(v, 0.0)[0] <= ROW1}
    lng_it = latlon_values(key, "lon")
    next(lng_it)
    w = csv.writer(sys.stdout, lineterminator="\n")
    w.writerow(["x", "y", "lat", "lng"])
    for i, v in enumerate(lng_it):
        if i in lats and COL0 <= cell_of(0.0, v)[1] <= COL1:
            w.writerow([i % nx, i // nx, lats[i], v])


def cells(key: str, date: str, t_from: str, t_to: str) -> None:
    start = datetime.fromisoformat(f"{date}T{t_from}").replace(tzinfo=KST)
    end = datetime.fromisoformat(f"{date}T{t_to}").replace(tzinfo=KST)
    points = load_grid_points(GRID_POINTS)
    bins: dict[datetime, dict[tuple[int, int], list[float]]] = defaultdict(lambda: defaultdict(list))
    t = start
    while t < end:
        r = httpx.get(hsr_url(key, t), timeout=120)
        r.raise_for_status()
        b = start + (t - start) // BIN * BIN
        for c, v in grid_to_cells(frame_values(r.content, points)).items():
            bins[b][c].append(v)
        print(f"{t:%H:%M} 받음", file=sys.stderr)
        t += FRAME
    w = csv.writer(sys.stdout, lineterminator="\n")
    w.writerow(["ts", "row", "col", "mmph"])
    for b in sorted(bins):
        for (row, col), vs in sorted(bins[b].items()):
            w.writerow([b.isoformat(), row, col, round(sum(vs) / len(vs), 2)])


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("grid", help="데모 지역 격자점 위경도")
    c = sub.add_parser("cells", help="10분 구간 칸별 mm/h")
    c.add_argument("--date", default="2022-08-08")
    c.add_argument("--from", dest="t_from", default="17:00")
    c.add_argument("--to", dest="t_to", default="23:00")
    args = ap.parse_args()
    key = os.environ["KMA_API_HUBKEY"]
    if args.cmd == "grid":
        grid(key)
    else:
        cells(key, args.date, args.t_from, args.t_to)


if __name__ == "__main__":
    main()
