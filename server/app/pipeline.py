"""STT → Groq LLM → TTS orchestration."""

from __future__ import annotations

import asyncio
import logging

from . import config
from . import protocol as proto
from .hub import ClientSession, hub
from .llm_groq import chat as groq_chat
from .stt_whisper import transcribe_pcm
from .tts_edge import synthesize_wav

logger = logging.getLogger(__name__)

_busy = asyncio.Lock()


async def run_voice_turn(session: ClientSession) -> None:
    """Process buffered PCM for one push-to-talk turn."""
    async with _busy:
        pcm = b"".join(session.pcm_chunks)
        session.pcm_chunks.clear()
        # ~0.4s minimum — câu quá ngắn Whisper hay bịa chữ
        if len(pcm) < int(config.AUDIO_SAMPLE_RATE * 2 * 0.4):
            try:
                await hub.send_json(
                    session,
                    {"type": "error", "message": "Audio quá ngắn — giữ nút và nói rõ hơn (~1 giây)."},
                )
            except Exception:
                pass
            await hub.set_pipeline("idle")
            return

        try:
            await hub.set_pipeline("stt", f"whisper-{config.WHISPER_MODEL}")
            text = await asyncio.to_thread(transcribe_pcm, pcm, config.AUDIO_SAMPLE_RATE)
            await hub.send_json(
                session,
                {"type": "stt", "text": text or "(không nghe rõ — thử nói chậm, gần mic hơn)", "is_final": True},
            )
            await hub.broadcast_json(
                {
                    "type": "stt",
                    "text": text or "(không nghe rõ)",
                    "is_final": True,
                    "from": session.client_id,
                },
                exclude=session.session_id,
            )

            if not text:
                await hub.set_pipeline("idle")
                return

            await hub.set_pipeline("llm", config.GROQ_MODEL)
            result = await asyncio.to_thread(groq_chat, text)
            llm_msg = {"type": "llm", "text": result.text}
            if result.emotion:
                llm_msg["emotion"] = result.emotion
            await hub.send_json(session, llm_msg)
            await hub.broadcast_json({**llm_msg, "from": session.client_id}, exclude=session.session_id)

            await hub.set_pipeline("tts", config.TTS_VOICE)
            await hub.send_json(session, {"type": "tts", "state": "start"})
            wav = await synthesize_wav(result.text)
            await hub.send_binary(session, proto.BIN_TTS_AUDIO, wav)
            await hub.send_json(session, {"type": "tts", "state": "stop"})
        except Exception as exc:
            logger.exception("voice turn failed")
            try:
                await hub.send_json(session, {"type": "error", "message": str(exc)})
            except Exception:
                pass
        finally:
            await hub.set_pipeline("idle")
