"""Local speech-to-text via faster-whisper (tuned for Vietnamese)."""

from __future__ import annotations

import logging
import re
import threading
from typing import TYPE_CHECKING

import numpy as np

from . import config

if TYPE_CHECKING:
    from faster_whisper import WhisperModel

logger = logging.getLogger(__name__)

_model: WhisperModel | None = None
_lock = threading.Lock()

# Bias decoding toward Vietnamese conversational speech
_INITIAL_PROMPT = (
    "Xin chào. Tôi muốn hỏi bạn một câu. "
    "Hôm nay thời tiết thế nào? Bạn có khỏe không?"
)

# Typical Whisper hallucinations on short / noisy clips
_HALLUCINATION_RE = re.compile(
    r"("
    r"subtitle|subscribe|thanks for watching|please subscribe|"
    r"đăng ký kênh|cảm ơn các bạn đã xem|"
    r"hãy theo dõi|like and share|"
    r"^[\s.。…\-–—]+$"
    r")",
    re.IGNORECASE,
)


def get_model() -> WhisperModel:
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                from faster_whisper import WhisperModel

                logger.info(
                    "Loading Whisper model=%s device=%s compute=%s",
                    config.WHISPER_MODEL,
                    config.WHISPER_DEVICE,
                    config.WHISPER_COMPUTE,
                )
                _model = WhisperModel(
                    config.WHISPER_MODEL,
                    device=config.WHISPER_DEVICE,
                    compute_type=config.WHISPER_COMPUTE,
                )
    return _model


def pcm_s16le_to_float32(pcm: bytes) -> np.ndarray:
    if not pcm:
        return np.zeros(0, dtype=np.float32)
    return np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0


def _normalize(audio: np.ndarray) -> np.ndarray:
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak < 1e-4:
        return audio
    # Soft normalize toward ~0.7 peak to help quiet phone mics
    gain = min(0.7 / peak, 8.0)
    out = audio * gain
    return np.clip(out, -1.0, 1.0).astype(np.float32)


def _trim_silence(audio: np.ndarray, sample_rate: int, thresh: float = 0.012) -> np.ndarray:
    if audio.size < sample_rate * 0.15:
        return audio
    win = max(int(sample_rate * 0.02), 1)
    energy = np.convolve(audio * audio, np.ones(win) / win, mode="same")
    mask = energy > (thresh * thresh)
    if not np.any(mask):
        return audio
    idx = np.where(mask)[0]
    pad = int(sample_rate * 0.08)
    start = max(int(idx[0]) - pad, 0)
    end = min(int(idx[-1]) + pad, audio.size)
    return audio[start:end]


def _clean_text(text: str) -> str:
    text = (text or "").strip()
    text = re.sub(r"\s+", " ", text)
    if not text or _HALLUCINATION_RE.search(text):
        return ""
    # Drop ultra-short junk (single syllable noise often 1–2 chars wrong)
    if len(text) < 2:
        return ""
    return text


def transcribe_pcm(pcm: bytes, sample_rate: int = 16000) -> str:
    audio = pcm_s16le_to_float32(pcm)
    if audio.size < sample_rate * 0.35:  # need ~350ms usable speech
        return ""

    audio = _normalize(audio)
    audio = _trim_silence(audio, sample_rate)
    if audio.size < sample_rate * 0.25:
        return ""

    model = get_model()
    language = config.WHISPER_LANGUAGE or "vi"

    segments, info = model.transcribe(
        audio,
        language=language,
        task="transcribe",
        beam_size=5,
        best_of=5,
        patience=1.0,
        temperature=0.0,
        vad_filter=True,
        vad_parameters={
            "min_silence_duration_ms": 400,
            "speech_pad_ms": 200,
        },
        condition_on_previous_text=False,
        initial_prompt=_INITIAL_PROMPT,
        no_speech_threshold=0.5,
        compression_ratio_threshold=2.4,
        log_prob_threshold=-0.8,
        word_timestamps=False,
    )

    parts: list[str] = []
    for seg in segments:
        t = (seg.text or "").strip()
        if not t:
            continue
        # Skip very low-confidence segments when available
        if seg.avg_logprob is not None and seg.avg_logprob < -1.2:
            logger.debug("drop low-logprob segment: %s (%.2f)", t, seg.avg_logprob)
            continue
        if seg.no_speech_prob is not None and seg.no_speech_prob > 0.7:
            continue
        parts.append(t)

    text = _clean_text(" ".join(parts))
    logger.info(
        "STT lang=%s duration=%.2fs -> %r",
        getattr(info, "language", language),
        audio.size / sample_rate,
        text,
    )
    return text
