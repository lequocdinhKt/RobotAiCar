"""Text-to-speech via edge-tts → WAV bytes."""

from __future__ import annotations

import asyncio
import io
import logging
import wave

import edge_tts

from . import config

logger = logging.getLogger(__name__)


async def synthesize_wav(text: str, voice: str | None = None) -> bytes:
    voice = voice or config.TTS_VOICE
    communicate = edge_tts.Communicate(text, voice)
    buf = io.BytesIO()
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            buf.write(chunk["data"])
    mp3_data = buf.getvalue()
    if not mp3_data:
        raise RuntimeError("edge-tts returned empty audio")
    # Convert MP3 → WAV PCM via pydub if available, else return MP3 with flag
    return await asyncio.to_thread(_mp3_to_wav, mp3_data)


def _mp3_to_wav(mp3_data: bytes) -> bytes:
    """Prefer WAV for Android; fall back to MP3 if pydub/ffmpeg unavailable."""
    try:
        from pydub import AudioSegment

        audio = AudioSegment.from_file(io.BytesIO(mp3_data), format="mp3")
        audio = audio.set_frame_rate(config.TTS_SAMPLE_RATE).set_channels(1).set_sample_width(2)
        out = io.BytesIO()
        audio.export(out, format="wav")
        return out.getvalue()
    except Exception as exc:
        logger.warning("WAV convert failed (%s) — sending MP3", exc)
        return mp3_data


def wav_to_pcm_s16le(wav_bytes: bytes) -> bytes | None:
    """Extract raw PCM from WAV; return None if not a WAV container."""
    if len(wav_bytes) < 12 or wav_bytes[:4] != b"RIFF":
        return None
    with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
        return wf.readframes(wf.getnframes())
