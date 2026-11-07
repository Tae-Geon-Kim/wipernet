"""차량 무리를 서버에 실제로 보내는 실행기(Task 7).

    .venv/bin/python -m wipernet_sim.runner --density future --speed 60 [--from 17:00 --to 23:00]
        [--slow-from 19:55 --slow-speed 1] [--server http://127.0.0.1:8000]

- 시작할 때 POST /v1/reset으로 서버를 비운다.
- tick마다 그 tick의 이벤트를 한 배치로 보낸다. 시뮬레이션 시각은 이벤트 시각이라 서버 시계도 따라 움직인다.
- speed는 배속(60이면 1분이 1초). 0이면 기다리지 않는다. --slow-from 이후는 --slow-speed 배속(라이브 시연용).
"""

import argparse
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

import httpx

from wipernet_server.external.official import KST

from .fleet import Fleet
from .rainfield import RainField, load_norm
from .roads import load_graph

DATA = Path(__file__).resolve().parents[1] / "data"
RAIN = DATA / "kma_20220808_norm.csv"
ROADS = DATA / "gangnam_drive.graphml"


def run(server_url: str, fleet: Fleet, start: datetime, end: datetime, speed: float = 60, tick_s: float = 10,
        slow_from: datetime | None = None, slow_speed: float = 1.0) -> None:
    with httpx.Client(base_url=server_url, timeout=60) as client:
        client.post("/v1/reset").raise_for_status()
        t, dt = start, 0.0
        while t <= end:
            began = time.monotonic()
            events = [e for _, e in fleet.step(t, dt)]
            if events:
                client.post("/v1/wiper-events", json={"events": events}).raise_for_status()
            if t.minute % 10 == 0 and t.second < tick_s:
                print(f"{t:%H:%M} 이벤트 {len(events)}건", file=sys.stderr)
            pace = slow_speed if slow_from is not None and t >= slow_from else speed
            if pace > 0:
                time.sleep(max(0.0, tick_s / pace - (time.monotonic() - began)))
            t, dt = t + timedelta(seconds=tick_s), tick_s


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8000")
    ap.add_argument("--density", choices=["current", "future"], default="current")
    ap.add_argument("--speed", type=float, default=60)
    ap.add_argument("--tick", type=float, default=10)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--date", default="2022-08-08")
    ap.add_argument("--from", dest="t_from", default="17:00")
    ap.add_argument("--to", dest="t_to", default="23:00")
    ap.add_argument("--slow-from", default=None)
    ap.add_argument("--slow-speed", type=float, default=1.0)
    args = ap.parse_args()

    def at(hhmm: str) -> datetime:
        return datetime.fromisoformat(f"{args.date}T{hhmm}").replace(tzinfo=KST)

    stations, series = load_norm(RAIN)
    fleet = Fleet(RainField(stations, series), load_graph(ROADS), args.density, seed=args.seed)
    run(args.server, fleet, at(args.t_from), at(args.t_to), speed=args.speed, tick_s=args.tick,
        slow_from=at(args.slow_from) if args.slow_from else None, slow_speed=args.slow_speed)


if __name__ == "__main__":
    main()
