from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Set

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from alignment import AlignmentResult, align_frames, transform_point

app = FastAPI(title="Spatial No-Map Alignment POC", version="0.1.0")


@dataclass
class Room:
    frames: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    sockets: Dict[str, Set[WebSocket]] = field(default_factory=lambda: {"A": set(), "B": set()})
    range_measurement: Optional[Dict[str, Any]] = None
    alignment: Optional[AlignmentResult] = None
    target_wa: Optional[list[float]] = None
    last_align_started: float = 0.0
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


rooms: Dict[str, Room] = {}


@app.get("/healthz")
async def healthz():
    return {"ok": True, "rooms": len(rooms), "time": time.time()}


@app.get("/api/v1/rooms/{room_id}")
async def room_status(room_id: str):
    room = rooms.get(room_id)
    if room is None:
        return {"exists": False}
    return {
        "exists": True,
        "has_a": "A" in room.frames,
        "has_b": "B" in room.frames,
        "has_range": room.range_measurement is not None,
        "has_target": room.target_wa is not None,
        "alignment": room.alignment.as_dict() if room.alignment else None,
    }


async def broadcast(room: Room, role: str, payload: Dict[str, Any]):
    dead = []
    for ws in list(room.sockets.get(role, set())):
        try:
            await ws.send_json(payload)
        except Exception:
            dead.append(ws)
    for ws in dead:
        room.sockets[role].discard(ws)


async def emit_target_if_possible(room: Room):
    if room.alignment is None or room.target_wa is None:
        return
    p_wb = transform_point(room.alignment.T_wb_wa, room.target_wa)
    await broadcast(room, "B", {
        "type": "remote_target",
        "point_wb": p_wb.tolist(),
        "alignment": room.alignment.as_dict(),
        "server_time_ns": time.time_ns(),
    })


async def maybe_align(room: Room, force: bool = False):
    now = time.monotonic()
    if not force and now - room.last_align_started < 0.55:
        return
    if "A" not in room.frames or "B" not in room.frames:
        return

    async with room.lock:
        now = time.monotonic()
        if not force and now - room.last_align_started < 0.55:
            return
        room.last_align_started = now
        frame_a = room.frames["A"]
        frame_b = room.frames["B"]
        skew = abs(int(frame_a.get("_server_received_ns", 0)) - int(frame_b.get("_server_received_ns", 0)))
        if skew > 1_500_000_000:
            return

        range_measurement = room.range_measurement
        if range_measurement is not None:
            age = time.time_ns() - int(range_measurement.get("server_received_ns", 0))
            if age > 3_000_000_000:
                range_measurement = None

        try:
            result = await asyncio.to_thread(align_frames, frame_a, frame_b, range_measurement)
        except Exception as exc:
            payload = {"type": "diagnostic", "level": "error", "message": f"alignment exception: {exc}"}
            await broadcast(room, "A", payload)
            await broadcast(room, "B", payload)
            return

        if result is None:
            await broadcast(room, "A", {"type": "alignment", "ok": False, "reason": "insufficient common geometry/features"})
            return

        room.alignment = result
        payload = {"type": "alignment", "ok": True, **result.as_dict()}
        await broadcast(room, "A", payload)
        await broadcast(room, "B", payload)
        await emit_target_if_possible(room)


@app.websocket("/ws/{room_id}/{role}")
async def ws_endpoint(ws: WebSocket, room_id: str, role: str):
    role = role.upper()
    if role not in ("A", "B"):
        await ws.close(code=1008)
        return
    await ws.accept()
    room = rooms.setdefault(room_id, Room())
    room.sockets[role].add(ws)
    await ws.send_json({"type": "hello", "room": room_id, "role": role, "server_time_ns": time.time_ns()})
    try:
        while True:
            msg = await ws.receive_json()
            kind = msg.get("type")
            if kind == "frame":
                msg["role"] = role
                msg["_server_received_ns"] = time.time_ns()
                room.frames[role] = msg
                await maybe_align(room)
            elif kind == "range" and role == "B":
                msg["server_received_ns"] = time.time_ns()
                room.range_measurement = msg
                await broadcast(room, "A", {"type": "range", **{k: v for k, v in msg.items() if k != "type"}})
            elif kind == "target" and role == "A":
                p = msg.get("point_wa")
                if not isinstance(p, list) or len(p) != 3:
                    await ws.send_json({"type": "diagnostic", "level": "error", "message": "target requires point_wa[3]"})
                    continue
                room.target_wa = [float(v) for v in p]
                await maybe_align(room, force=True)
                if room.alignment is None:
                    await ws.send_json({"type": "diagnostic", "level": "warn", "message": "target saved; no T_WB_WA yet"})
                else:
                    await emit_target_if_possible(room)
            elif kind == "clear_target":
                room.target_wa = None
                await broadcast(room, "B", {"type": "clear_target"})
            elif kind == "ping":
                await ws.send_json({"type": "pong", "server_time_ns": time.time_ns()})
            else:
                await ws.send_json({"type": "diagnostic", "level": "warn", "message": f"unknown message type: {kind}"})
    except WebSocketDisconnect:
        pass
    finally:
        room.sockets[role].discard(ws)
