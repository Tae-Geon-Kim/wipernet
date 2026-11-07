"""HTTP API. 계약은 docs/api.md.

    .venv/bin/uvicorn wipernet_server.api:app --port 8000

외부 자료 출처는 환경 변수 WIPERNET_EXTERNAL=replay|live|off(기본 replay, 2022-08-08 기록 재생).
live는 KMA_API_HUBKEY가 있어야 한다(backend/.env).
"""

import os
from dataclasses import asdict
from datetime import timedelta
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from pydantic import AwareDatetime, BaseModel

from .external.official import LiveOfficialSource, OfficialSource, ReplayOfficialSource
from .external.radar import LiveRadarSource, RadarSource, ReplayRadarSource
from .fusion import ALERT_WINDOW, NoOfficial, NoRadar, fuse
from .grid import CELL_LAT, CELL_LNG
from .rainmap import WiperEvent
from .store import Store
from .underpass import assess, load_underpasses

UNDERPASSES_PATH = Path(__file__).resolve().parents[2] / "shared" / "underpasses_gangnam.json"
DATA = Path(__file__).resolve().parents[1] / "data"
VIEWER = Path(__file__).resolve().parent / "viewer" / "index.html"
MAX_FUTURE = timedelta(minutes=5)
VIEWER_RECENT = timedelta(minutes=5)


class EventIn(BaseModel):
    vid: str
    ts: AwareDatetime
    lat: float
    lng: float
    switch: str
    state: str
    auto_wpm: float | None = None


class EventsIn(BaseModel):
    events: list[EventIn]


def external_sources(mode: str) -> tuple[RadarSource, OfficialSource]:
    if mode == "replay":
        return (ReplayRadarSource(DATA / "radar_20220808_cells.csv"),
                ReplayOfficialSource(DATA / "kma_warnings_20220808.json", DATA / "alerts_20220808.json"))
    if mode == "live":
        key = os.environ.get("KMA_API_HUBKEY")
        if not key:
            raise RuntimeError("WIPERNET_EXTERNAL=live에는 KMA_API_HUBKEY가 필요하다 (backend/.env)")
        return LiveRadarSource(key), LiveOfficialSource(key)
    if mode == "off":
        return NoRadar(), NoOfficial()
    raise ValueError(f"WIPERNET_EXTERNAL은 replay, live, off 중 하나여야 한다: {mode!r}")


def create_app(external: str | None = None) -> FastAPI:
    app = FastAPI(title="WiperNet")
    store = Store()
    underpasses = load_underpasses(UNDERPASSES_PATH)
    radar, official = external_sources(external or os.environ.get("WIPERNET_EXTERNAL", "replay"))
    app.state.store = store

    def resolve_at(at: AwareDatetime | None):
        return at if at is not None else store.clock

    @app.post("/v1/wiper-events", status_code=202)
    def ingest(body: EventsIn):
        clock = store.clock
        accepted = [WiperEvent(**e.model_dump()) for e in body.events
                    if clock is None or e.ts <= clock + MAX_FUTURE]
        store.add(accepted)
        store.prune()
        return {"accepted": len(accepted), "rejected": len(body.events) - len(accepted)}

    @app.get("/v1/clock")
    def clock():
        return {"now": store.clock}

    @app.get("/v1/rain-map")
    def rain_map(at: AwareDatetime | None = None):
        at = resolve_at(at)
        cells = store.cells(at) if at is not None else []
        return {"at": at, "cell": {"lat": CELL_LAT, "lng": CELL_LNG}, "cells": [asdict(c) for c in cells]}

    @app.get("/v1/underpass-risk")
    def underpass_risk(at: AwareDatetime | None = None):
        at = resolve_at(at)
        if at is None:  # 아직 이벤트가 없어 시각을 모른다
            return {"at": None, "underpasses": [asdict(r) for r in assess(underpasses, [])]}
        risks = fuse(underpasses, store.cells(at), radar, official, at)
        return {"at": at, "underpasses": [asdict(r) for r in risks]}

    @app.get("/v1/external")
    def external(at: AwareDatetime | None = None):
        at = resolve_at(at)
        if at is None:
            return {"at": None, "warnings": [], "alerts": []}
        return {"at": at, "warnings": [asdict(w) for w in official.warnings(at)],
                "alerts": [asdict(a) for a in official.alerts(at, since=at - ALERT_WINDOW)]}

    @app.get("/v1/debug/vehicles")
    def debug_vehicles():
        return {"vehicles": [asdict(e) for e in store.recent_vehicles(VIEWER_RECENT)]}

    @app.get("/viewer", include_in_schema=False)
    def viewer():
        return FileResponse(VIEWER, media_type="text/html")

    @app.post("/v1/reset")
    def reset():
        store.reset()
        return {"ok": True}

    return app


app = create_app()
