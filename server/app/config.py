"""Load server settings from environment / .env."""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

_ROOT = Path(__file__).resolve().parent.parent
load_dotenv(_ROOT / ".env")

HOST: str = os.getenv("ONEAI_HOST", "0.0.0.0")
PORT: int = int(os.getenv("ONEAI_PORT", "8765"))

GROQ_API_KEY: str = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL: str = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
GROQ_BASE_URL: str = os.getenv("GROQ_BASE_URL", "https://api.groq.com/openai/v1")

# `small` is much more accurate for Vietnamese than `base` (still OK on CPU).
WHISPER_MODEL: str = os.getenv("WHISPER_MODEL", "small")
WHISPER_DEVICE: str = os.getenv("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE: str = os.getenv("WHISPER_COMPUTE", "int8")
WHISPER_LANGUAGE: str = os.getenv("WHISPER_LANGUAGE", "vi")

YOLO_MODEL: str = os.getenv("YOLO_MODEL", "yolov8n.pt")
YOLO_CONF: float = float(os.getenv("YOLO_CONF", "0.4"))
VIDEO_MAX_FPS: int = int(os.getenv("VIDEO_MAX_FPS", "8"))

TTS_VOICE: str = os.getenv("TTS_VOICE", "vi-VN-HoaiMyNeural")
AUDIO_SAMPLE_RATE: int = 16000
TTS_SAMPLE_RATE: int = 24000

SYSTEM_PROMPT: str = os.getenv(
    "ONEAI_SYSTEM_PROMPT",
    "Bạn là trợ lý OneAi trên robot. Trả lời ngắn gọn bằng tiếng Việt. "
    "Nếu phù hợp, kết thúc bằng một dòng JSON duy nhất dạng "
    '{"emotion":"happy|neutral|sad|thinking|surprised|angry"} '
    "để điều khiển biểu cảm (có thể bỏ qua nếu không chắc).",
)
