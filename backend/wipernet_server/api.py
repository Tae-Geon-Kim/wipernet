"""HTTP API. 계약은 docs/api.md.

    .venv/bin/uvicorn wipernet_server.api:app --port 8000
"""

from dataclasses import asdict
from datetime import timedelta
from pathlib import Path

from fastapi import FastAPI
from pydantic import AwareDatetime, BaseModel

from .grid import CELL_LAT, CELL_LNG
from .rainmap import WiperEvent
from .store import Store
from .underpass import assess, load_underpasses

UNDERPASSES_PATH = Path(__file__).resolve().parents[2] / "shared" / "underpasses_gangnam.json"
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


def create_app() -> FastAPI:
    app = FastAPI(title="WiperNet")
    store = Store()
    underpasses = load_underpasses(UNDERPASSES_PATH)
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
        cells = store.cells(at) if at is not None else []
        return {"at": at, "underpasses": [asdict(r) for r in assess(underpasses, cells)]}

    @app.get("/v1/debug/vehicles")
    def debug_vehicles():
        return {"vehicles": [asdict(e) for e in store.recent_vehicles(VIEWER_RECENT)]}

    @app.post("/v1/reset")
    def reset():
        store.reset()
        return {"ok": True}

    return app


app = create_app()
