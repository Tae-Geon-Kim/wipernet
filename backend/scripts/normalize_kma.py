"""scripts/fetch_kma_aws.py로 받은 AWS 원자료를 10분 간격 CSV로 바꾼다(Task 5).

    .venv/bin/python scripts/normalize_kma.py data/raw/kma data/kma_20220808_norm.csv

원본 열 이름(응답의 '#' 머리줄):
- 지점정보 stn_inf_aws.txt (cp949): STN LON LAT STN_SP HT HT_WD LAU STN STN_KO STN_EN ...
- 매분자료 aws_{지점}_{시작}.txt: YYMMDDHHMI(실제로는 YYYYMMDDHHMI, KST) STN WD1 WS1 ... RN-15m RN-60m RN-12H RN-DAY ...
  -50 이하는 관측 없음.

출력: station_id,station_name,lat,lng,ts,mmph. ts는 10분 구간 시작(KST), mmph = 그 10분 강수량 × 6.
- 10분 강수량은 일 누적(RN-DAY)의 구간 끝 − 구간 시작이다. RN-DAY는 00:01에 0으로 돌아가므로 00:00 구간은 0부터 센다.
- 구간 양 끝 중 하나라도 값이 없거나 차이가 음수(센서 오류)면 그 구간은 쓰지 않는다.
"""

import argparse
import csv
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
DAY = datetime(2022, 8, 8, tzinfo=KST)
BIN = timedelta(minutes=10)
MISSING = -50


def columns(text: str, first: str) -> list[str]:
    for line in text.splitlines():
        cols = line.lstrip("#").split()
        if line.startswith("#") and cols and cols[0] == first:
            return cols
    raise ValueError(f"'{first}' 머리줄이 없음")


def rows(text: str):
    for line in text.splitlines():
        if line.strip() and not line.startswith("#"):
            yield line.split()


def load_stations(text: str) -> dict[str, tuple[str, float, float]]:
    c = columns(text, "STN")
    i_lon, i_lat, i_name = c.index("LON"), c.index("LAT"), c.index("STN_KO")
    return {f[0]: (f[i_name], float(f[i_lat]), float(f[i_lon])) for f in rows(text)}


def load_rn_day(paths: list[Path]) -> dict[datetime, float]:
    out = {}
    for p in paths:
        text = p.read_text(encoding="utf-8")
        c = columns(text, "YYMMDDHHMI")
        i_ts, i_rn = c.index("YYMMDDHHMI"), c.index("RN-DAY")
        for f in rows(text):
            v = float(f[i_rn])
            if v > MISSING:
                out[datetime.strptime(f[i_ts], "%Y%m%d%H%M").replace(tzinfo=KST)] = v
    return out


def ten_minute_mmph(rn_day: dict[datetime, float]) -> list[tuple[datetime, float]]:
    out = []
    for i in range(24 * 6):
        t0 = DAY + i * BIN
        start = 0.0 if t0 == DAY else rn_day.get(t0)
        end = rn_day.get(t0 + BIN)
        if start is None or end is None or end < start:
            continue
        out.append((t0, round((end - start) * 6, 1)))
    return out


def normalize_kma(raw_dir: Path, out: Path) -> None:
    stations = load_stations((raw_dir / "stn_inf_aws.txt").read_text(encoding="cp949"))
    ids = sorted({p.name.split("_")[1] for p in raw_dir.glob("aws_*.txt")}, key=int)
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["station_id", "station_name", "lat", "lng", "ts", "mmph"])
        for sid in ids:
            name, lat, lng = stations[sid]
            for t0, mmph in ten_minute_mmph(load_rn_day(sorted(raw_dir.glob(f"aws_{sid}_*.txt")))):
                w.writerow([sid, name, lat, lng, t0.isoformat(), mmph])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("raw_dir", type=Path)
    ap.add_argument("out", type=Path)
    args = ap.parse_args()
    normalize_kma(args.raw_dir, args.out)


if __name__ == "__main__":
    main()
