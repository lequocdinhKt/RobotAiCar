"""Groq chat completions (OpenAI-compatible)."""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass

from openai import OpenAI

from . import config

logger = logging.getLogger(__name__)

_EMOTION_RE = re.compile(
    r'\{\s*"emotion"\s*:\s*"(happy|neutral|sad|thinking|surprised|angry|confused|sleepy|excited|loving)"\s*\}',
    re.IGNORECASE,
)


@dataclass
class LlmResult:
    text: str
    emotion: str | None = None


def _client() -> OpenAI:
    if not config.GROQ_API_KEY:
        raise RuntimeError("GROQ_API_KEY is missing — set it in server/.env")
    return OpenAI(api_key=config.GROQ_API_KEY, base_url=config.GROQ_BASE_URL)


def chat(user_text: str) -> LlmResult:
    client = _client()
    resp = client.chat.completions.create(
        model=config.GROQ_MODEL,
        messages=[
            {"role": "system", "content": config.SYSTEM_PROMPT},
            {"role": "user", "content": user_text},
        ],
        temperature=0.6,
        max_tokens=512,
    )
    raw = (resp.choices[0].message.content or "").strip()
    emotion = None
    match = _EMOTION_RE.search(raw)
    if match:
        emotion = match.group(1).lower()
        raw = _EMOTION_RE.sub("", raw).strip()
    # Also strip a trailing bare JSON line if model formatted differently
    lines = raw.splitlines()
    if lines and lines[-1].strip().startswith("{") and "emotion" in lines[-1]:
        try:
            obj = json.loads(lines[-1].strip())
            if isinstance(obj, dict) and "emotion" in obj:
                emotion = str(obj["emotion"]).lower()
                raw = "\n".join(lines[:-1]).strip()
        except json.JSONDecodeError:
            pass
    return LlmResult(text=raw or "...", emotion=emotion)
