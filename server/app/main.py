"""FastAPI entry: static debug UI + WebSocket /ws."""

from __future__ import annotations

import asyncio
import logging
import time
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from . import config
from . import protocol as proto
from .hub import hub
from .pipeline import run_voice_turn
from .vision_yolo import detect_jpeg

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("oneai")

ROOT = Path(__file__).resolve().parent.parent
STATIC = ROOT / "static"

app = FastAPI(title="OneAi Server MVP", version="0.1.0")
app.mount("/static", StaticFiles(directory=STATIC), name="static")


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/health")
async def health() -> dict:
    return {
        "ok": True,
        "groq_configured": bool(config.GROQ_API_KEY),
        "clients": len(hub.clients),
        "pipeline": hub.pipeline_state,
    }


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket) -> None:
    await ws.accept()
    session = None
    ping_task: asyncio.Task | None = None

    async def ping_loop() -> None:
        while True:
            await asyncio.sleep(2.0)
            if session is None:
                continue
            try:
                await hub.send_json(session, {"type": "ping", "t": time.time()})
            except Exception:
                break

    try:
        while True:
            message = await ws.receive()
            if message.get("type") == "websocket.disconnect":
                break

            if "text" in message and message["text"] is not None:
                data = proto.loads(message["text"])
                msg_type = data.get("type")

                if msg_type == "hello":
                    session = await hub.register(
                        ws,
                        client_id=str(data.get("client_id") or ""),
                        role=str(data.get("role") or "phone"),
                        caps=dict(data.get("caps") or {}),
                    )
                    await hub.send_json(
                        session,
                        {
                            "type": "hello_ack",
                            "session_id": session.session_id,
                            "audio": {"rate": config.AUDIO_SAMPLE_RATE, "channels": 1, "format": "s16le"},
                            "video": {"max_fps": config.VIDEO_MAX_FPS, "format": "jpeg"},
                            "tts": {"rate": config.TTS_SAMPLE_RATE, "format": "wav_or_mp3"},
                        },
                    )
                    if ping_task is None:
                        ping_task = asyncio.create_task(ping_loop())
                    continue

                if session is None:
                    await ws.send_text(proto.dumps({"type": "error", "message": "send hello first"}))
                    continue

                if msg_type == "pong":
                    t0 = data.get("t")
                    now = time.time()
                    session.last_pong_at = now
                    if isinstance(t0, (int, float)):
                        session.last_rtt_ms = round((now - float(t0)) * 1000, 1)
                    await hub.broadcast_status()
                    continue

                if msg_type == "ping":
                    await hub.send_json(session, {"type": "pong", "t": data.get("t", time.time())})
                    continue

                if msg_type == "listen":
                    state = data.get("state")
                    if state == "start":
                        session.listening = True
                        session.pcm_chunks.clear()
                        await hub.set_pipeline("listening")
                    elif state == "stop":
                        session.listening = False
                        asyncio.create_task(run_voice_turn(session))
                    continue

                logger.debug("ignored json type=%s", msg_type)
                continue

            if "bytes" in message and message["bytes"] is not None:
                if session is None:
                    continue
                raw = message["bytes"]
                try:
                    bin_type, _flags, payload = proto.unpack_binary(raw)
                except ValueError as exc:
                    logger.warning("bad binary: %s", exc)
                    continue

                if bin_type == proto.BIN_MIC_PCM:
                    if session.listening:
                        session.pcm_chunks.append(payload)
                    continue

                if bin_type == proto.BIN_JPEG:
                    now = time.time()
                    min_interval = 1.0 / max(config.VIDEO_MAX_FPS, 1)
                    if now - session.last_frame_at < min_interval:
                        continue
                    session.last_frame_at = now
                    session.frame_id += 1
                    frame_id = session.frame_id
                    boxes = await asyncio.to_thread(detect_jpeg, payload)
                    await hub.send_json(
                        session,
                        {
                            "type": "detections",
                            "frame_id": frame_id,
                            "boxes": boxes,
                        },
                    )
                    continue

    except WebSocketDisconnect:
        logger.info("client disconnected")
    except Exception:
        logger.exception("websocket error")
    finally:
        if ping_task:
            ping_task.cancel()
        if session:
            await hub.unregister(session.session_id)


def main() -> None:
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=config.HOST,
        port=config.PORT,
        reload=False,
        log_level="info",
    )


if __name__ == "__main__":
    main()
