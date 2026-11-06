"""기상청 API허브 AWS 매분자료를 데모 지역 둘레 관측소만 받아 data/raw/kma/에 그대로 저장한다(Task 5).

    cd backend && set -a && . ./.env && set +a
    .venv/bin/python scripts/fetch_kma_aws.py data/raw/kma

- 관측소 위치는 '지상관측 지점정보 → AWS'(stn_inf.php)에서 받는다. 데모 지역(위도 37.47~37.53, 경도 127.00~127.07)
  사방으로 0.08°(약 8 km) 넓힌 사각형 안의 관측소를 쓴다. 지역 안 관측소는 서초(401) 하나뿐이라 둘레 관측소로 메운다.
- 매분자료(nph-aws2_min)는 한 관측소를 하루치로 부르면 응답이 중간에 잘려(약 1,130줄) 반나절씩 부른다.
  23:50~24:00 구간을 계산하려고 다음 날 00:00까지 받는다.
키는 환경변수 KMA_API_HUBKEY(backend/.env, 커밋 안 함). 원자료(data/raw/)는 커밋하지 않는다.
"""

import argparse
import os
import sys
from pathlib import Path
from urllib.parse import urlencode

import httpx

APIHUB = "https://apihub.kma.go.kr/api/typ01"
LAT0, LAT1, LNG0, LNG1 = 37.47, 37.53, 127.00, 127.07
MARGIN = 0.08
WINDOWS = [("202208080000", "202208081159"), ("202208081200", "202208090000")]
STN_FILE = "stn_inf_aws.txt"


def get(path: str, key: str, **params: str) -> bytes:
    r = httpx.get(f"{APIHUB}/{path}?{urlencode({**params, 'authKey': key})}", timeout=120)
    r.raise_for_status()
    return r.content


def stations_in_area(stn_inf: str) -> list[str]:
    """지점정보 한 줄: STN LON LAT ... (공백 구분)."""
    ids = []
    for line in stn_inf.splitlines():
        if line.startswith("#") or not line.strip():
            continue
        f = line.split()
        lng, lat = float(f[1]), float(f[2])
        if LAT0 - MARGIN <= lat <= LAT1 + MARGIN and LNG0 - MARGIN <= lng <= LNG1 + MARGIN:
            ids.append(f[0])
    return ids


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("raw_dir", type=Path)
    args = ap.parse_args()
    key = os.environ["KMA_API_HUBKEY"]
    args.raw_dir.mkdir(parents=True, exist_ok=True)
    raw = get("url/stn_inf.php", key, inf="AWS", tm="202208081200", help="1")
    (args.raw_dir / STN_FILE).write_bytes(raw)
    ids = stations_in_area(raw.decode("cp949"))
    for stn in ids:
        for tm1, tm2 in WINDOWS:
            body = get("cgi-bin/url/nph-aws2_min", key, tm1=tm1, tm2=tm2, stn=stn, disp="0", help="1")
            (args.raw_dir / f"aws_{stn}_{tm1}.txt").write_bytes(body)
        print(f"{stn} 받음", file=sys.stderr)
    print(f"관측소 {len(ids)}곳", file=sys.stderr)


if __name__ == "__main__":
    main()
