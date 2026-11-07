"""시뮬레이터 차량 무리 테스트용 작은 강우장·도로망·가짜 서버. 실제 데이터 파일에 기대지 않는다."""

import json
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import networkx as nx
import pytest

from wipernet_server.underpass import distance_km
from wipernet_sim.rainfield import RainField, Station

KST = timezone(timedelta(hours=9))
T0 = datetime(2022, 8, 8, 20, 0, tzinfo=KST)


def _field(mmph: float) -> RainField:
    bins = [(T0 + timedelta(minutes=10 * i), mmph) for i in range(-6, 13)]
    return RainField([Station("s", "s", 37.50, 127.03)], {"s": bins})


@pytest.fixture
def tiny_field_dry():
    return _field(0.0)


@pytest.fixture
def tiny_field_wet():
    return _field(25.0)


@pytest.fixture
def tiny_graph():
    """약 500 m 정사각형 양방향 도로 4개."""
    g = nx.MultiDiGraph()
    corners = {"a": (37.500, 127.030), "b": (37.500, 127.0357), "c": (37.5045, 127.0357), "d": (37.5045, 127.030)}
    for n, (y, x) in corners.items():
        g.add_node(n, y=y, x=x)
    for a, b in [("a", "b"), ("b", "c"), ("c", "d"), ("d", "a")]:
        length = distance_km(*corners[a], *corners[b]) * 1000
        g.add_edge(a, b, length=length, speed_kph=40, osmid=1)
        g.add_edge(b, a, length=length, speed_kph=40, osmid=1)
    return g


class _FakeServer:
    def __init__(self):
        self.calls: list[str] = []
        self.batches: list[dict] = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                fake.calls.append(f"POST {self.path}")
                if self.path == "/v1/wiper-events":
                    fake.batches.append(json.loads(body))
                self.send_response(202)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, *args):
                pass

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._httpd.server_port}"
        threading.Thread(target=self._httpd.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()

    def close(self):
        self._httpd.shutdown()


@pytest.fixture
def fake_server():
    s = _FakeServer()
    yield s
    s.close()
