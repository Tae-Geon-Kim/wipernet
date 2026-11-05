"""기상청 API허브에서 호우특보를 받아 기록 파일로 만든다(Task 4a, 기록 재생용).

    cd backend && set -a && . ./.env && set +a
    .venv/bin/python scripts/fetch_kma_warnings.py > data/kma_warnings_20220808.json

키는 환경변수 KMA_API_HUBKEY(backend/.env, 커밋 안 함). API허브에서 '기상특보 → 1.1 특보구역,
1.2 특보자료' 활용신청이 필요하다. 응답 해석은 wipernet_server.external.official에 있다.
"""

import argparse
import json
import os
from datetime import datetime

from wipernet_server.external.official import KST, fetch_rain_warnings


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="tmfc1", default="202208070000", help="발표시각 시작(KST, YYYYMMDDHHMI)")
    ap.add_argument("--to", dest="tmfc2", default="202208110000", help="발표시각 끝(KST, YYYYMMDDHHMI)")
    ap.add_argument("--reg-prefix", default="L110", help="특보구역코드 앞부분(L110 = 서울)")
    args = ap.parse_args()

    def kst(s: str) -> datetime:
        return datetime.strptime(s, "%Y%m%d%H%M").replace(tzinfo=KST)

    ws = fetch_rain_warnings(os.environ["KMA_API_HUBKEY"], kst(args.tmfc1), kst(args.tmfc2))
    rows = [{"area_code": w.area_code, "area_name": w.area_name, "stress": w.stress,
             "start": w.start.isoformat(), "end": w.end.isoformat() if w.end else None}
            for w in ws if w.area_code.startswith(args.reg_prefix)]
    print(json.dumps(rows, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
