"""OSM 차도망을 받아 시뮬레이터 도로망 파일로 만든다(Task 6).

    .venv/bin/python scripts/fetch_roads.py data/gangnam_drive.graphml

- 범위는 데모 지역(위도 37.47~37.53, 경도 127.00~127.07)을 사방 0.015°(약 1.5 km) 넓힌 사각형이다.
  개포지하도로는 남쪽 끝이 분당내곡로(성남 방향)로 이어진다. 0.01°에서는 그 길이 사각형 밖으로 나가 돌아오지 않아
  가장 큰 덩어리에서 개포가 빠진다. 0.015°부터 개포 양방향이 남는다(2026-11-07 확인).
- 간선 속도(speed_kph)는 osmnx가 maxspeed로 채우고, 없으면 같은 도로 종류의 평균, 그것도 없으면 30 km/h.
- 서로 오갈 수 있는 가장 큰 덩어리만 남긴다(roads.largest_scc).
"""

import argparse
from pathlib import Path

import osmnx as ox

from wipernet_sim.roads import largest_scc

MARGIN = 0.015
BBOX = (127.00 - MARGIN, 37.47 - MARGIN, 127.07 + MARGIN, 37.53 + MARGIN)  # (left, bottom, right, top)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    args = ap.parse_args()
    g = ox.graph_from_bbox(BBOX, network_type="drive")
    g = ox.add_edge_speeds(g, fallback=30)
    ox.save_graphml(largest_scc(g), args.out)


if __name__ == "__main__":
    main()
