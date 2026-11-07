import sys
import threading
from datetime import datetime, timedelta, timezone

from wipernet_server.rainmap import WiperEvent
from wipernet_server.store import Store

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 20, 0, tzinfo=KST)


def test_reads_while_ingesting_do_not_crash():
    """api.py의 def 엔드포인트는 스레드 풀에서 돈다. 뷰어 조회와 이벤트 수신이 겹쳐도 깨지면 안 된다."""
    store, errors, done = Store(), [], threading.Event()

    def ingest():
        for k in range(40):
            t = T0 + timedelta(seconds=k)
            store.add(WiperEvent(vid=f"v{k}-{i}", ts=t, lat=37.47 + i * 0.0005, lng=127.03, switch="CONTINUOUS_LEVEL_3",
                                 state="ON", auto_wpm=None) for i in range(60))
            store.prune()
        done.set()

    def read():
        while not done.is_set():
            try:
                store.cells(store.clock or T0)
                store.recent_vehicles(timedelta(minutes=5))
            except Exception as e:   # noqa: BLE001 - 어떤 예외든 실패로 센다
                errors.append(e)
                return

    threads = [threading.Thread(target=ingest)] + [threading.Thread(target=read) for _ in range(3)]
    old = sys.getswitchinterval()
    sys.setswitchinterval(1e-5)   # 스레드를 자주 바꿔 겹침을 확실히 만든다
    try:
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    finally:
        sys.setswitchinterval(old)
    assert errors == []
