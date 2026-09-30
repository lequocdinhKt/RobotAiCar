"""Client session hub: hello, heartbeat, status broadcast."""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

from . import protocol as proto


@dataclass
class ClientSession:
    ws: WebSocket
    session_id: str
    client_id: str
    role: str = "phone"
    caps: dict[str, Any] = field(default_factory=dict)
    connected_at: float = field(default_factory=time.time)
    last_pong_at: float = field(default_factory=time.time)
    last_rtt_ms: float | None = None
    listening: bool = False
    pcm_chunks: list[bytes] = field(default_factory=list)
    last_frame_at: float = 0.0
    frame_id: int = 0


class Hub:
    def __init__(self) -> None:
        self.clients: dict[str, ClientSession] = {}
        self.pipeline_state: str = "idle"
        self.pipeline_detail: str = ""
        self._lock = asyncio.Lock()

    async def register(
        self,
        ws: WebSocket,
        client_id: str,
        role: str,
        caps: dict[str, Any],
    ) -> ClientSession:
        session_id = str(uuid.uuid4())
        session = ClientSession(
            ws=ws,
            session_id=session_id,
            client_id=client_id or session_id[:8],
            role=role or "phone",
            caps=caps or {},
        )
        async with self._lock:
            self.clients[session_id] = session
        await self.broadcast_status()
        return session

    async def unregister(self, session_id: str) -> None:
        async with self._lock:
            self.clients.pop(session_id, None)
        await self.broadcast_status()

    def get(self, session_id: str) -> ClientSession | None:
        return self.clients.get(session_id)

    async def set_pipeline(self, state: str, detail: str = "") -> None:
        self.pipeline_state = state
        self.pipeline_detail = detail
        await self.broadcast_status()

    def status_payload(self) -> dict[str, Any]:
        now = time.time()
        clients = []
        for s in self.clients.values():
            age = now - s.last_pong_at
            stable = age < 5.0
            clients.append(
                {
                    "session_id": s.session_id,
                    "client_id": s.client_id,
                    "role": s.role,
                    "connected": True,
                    "stable": stable,
                    "last_rtt_ms": s.last_rtt_ms,
                    "listening": s.listening,
                    "uptime_s": round(now - s.connected_at, 1),
                }
            )
        return {
            "type": "status",
            "connected": len(clients) > 0,
            "clients": clients,
            "pipeline": self.pipeline_state,
            "pipeline_detail": self.pipeline_detail,
            "server_time": now,
        }

    async def send_json(self, session: ClientSession, obj: dict[str, Any]) -> None:
        await session.ws.send_text(proto.dumps(obj))

    async def send_binary(self, session: ClientSession, msg_type: int, payload: bytes) -> None:
        await session.ws.send_bytes(proto.pack_binary(msg_type, payload))

    async def broadcast_status(self) -> None:
        payload = self.status_payload()
        dead: list[str] = []
        for sid, session in list(self.clients.items()):
            try:
                await self.send_json(session, payload)
            except Exception:
                dead.append(sid)
        for sid in dead:
            async with self._lock:
                self.clients.pop(sid, None)

    async def broadcast_json(self, obj: dict[str, Any], exclude: str | None = None) -> None:
        for sid, session in list(self.clients.items()):
            if exclude and sid == exclude:
                continue
            try:
                await self.send_json(session, obj)
            except Exception:
                pass


hub = Hub()
