"""ONEA WebSocket framing and JSON message helpers."""

from __future__ import annotations

import json
import struct
from typing import Any

MAGIC = 0x4F4E4541  # "ONEA"
VERSION = 1
HEADER_SIZE = 8

BIN_MIC_PCM = 1
BIN_TTS_AUDIO = 2
BIN_JPEG = 3

# Binary header: magic u32 BE, version u8, type u8, flags u16 BE
_HEADER_STRUCT = struct.Struct(">IBBH")


def pack_binary(msg_type: int, payload: bytes, flags: int = 0) -> bytes:
    return _HEADER_STRUCT.pack(MAGIC, VERSION, msg_type, flags) + payload


def unpack_binary(data: bytes) -> tuple[int, int, bytes]:
    if len(data) < HEADER_SIZE:
        raise ValueError("binary frame too short")
    magic, version, msg_type, flags = _HEADER_STRUCT.unpack_from(data)
    if magic != MAGIC:
        raise ValueError(f"bad magic: {magic:#x}")
    if version != VERSION:
        raise ValueError(f"unsupported version: {version}")
    return msg_type, flags, data[HEADER_SIZE:]


def dumps(obj: dict[str, Any]) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def loads(text: str) -> dict[str, Any]:
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError("JSON root must be object")
    return data
