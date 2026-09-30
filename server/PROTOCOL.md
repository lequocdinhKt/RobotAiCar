# OneAi WebSocket Protocol v1

Transport: one WebSocket connection to `ws://<laptop-ip>:8765/ws`.

Browser simulator and future Android app speak the same protocol.

## Handshake

Client → Server (text JSON):

```json
{
  "type": "hello",
  "client_id": "android-xxxx",
  "role": "phone",
  "caps": { "audio": true, "video": true }
}
```

Server → Client:

```json
{
  "type": "hello_ack",
  "session_id": "uuid",
  "audio": { "rate": 16000, "channels": 1, "format": "s16le" },
  "video": { "max_fps": 8, "format": "jpeg" },
  "tts": { "rate": 24000, "format": "wav_or_mp3" }
}
```

Send `hello` before any other messages.

## Text JSON messages

| type | Direction | Purpose |
|------|-----------|---------|
| `hello` / `hello_ack` | C↔S | Session setup |
| `listen` | C→S | `{ "state": "start" \| "stop" }` push-to-talk |
| `stt` | S→C | `{ "text", "is_final": true }` |
| `llm` | S→C | `{ "text", "emotion"? }` |
| `tts` | S→C | `{ "state": "start" \| "stop" }` around binary TTS |
| `detections` | S→C | `{ "frame_id", "boxes": [{ "x","y","w","h","label","conf" }] }` — boxes normalized 0–1 |
| `status` | S→C | clients, pipeline, stability |
| `ping` / `pong` | both | heartbeat; echo `t` timestamp |
| `error` | S→C | `{ "message" }` |

### listen flow

1. `listen` `start` — clear mic buffer, pipeline → `listening`
2. Stream binary mic PCM while holding
3. `listen` `stop` — server runs STT → Groq → TTS

### status example

```json
{
  "type": "status",
  "connected": true,
  "pipeline": "stt",
  "pipeline_detail": "faster-whisper",
  "clients": [
    {
      "session_id": "...",
      "client_id": "browser-ab12",
      "role": "phone",
      "connected": true,
      "stable": true,
      "last_rtt_ms": 12.3,
      "listening": false,
      "uptime_s": 40.1
    }
  ]
}
```

`stable` is false if no `pong` within ~5 seconds.

## Binary frames (ONEA)

Every binary message starts with an 8-byte big-endian header:

| Offset | Size | Field |
|-------:|-----:|-------|
| 0 | 4 | Magic `0x4F4E4541` (`ONEA`) |
| 4 | 1 | Version `1` |
| 5 | 1 | Type |
| 6 | 2 | Flags (currently `0`) |

Then raw payload bytes.

| Type | Direction | Payload |
|-----:|-----------|---------|
| 1 | C→S | Mic PCM S16LE mono 16 kHz |
| 2 | S→C | TTS audio — WAV (`RIFF…`) or MP3 |
| 3 | C→S | JPEG image frame |

Max practical JPEG size: keep under ~200 KB (VGA quality ~0.7).

## Android implementation notes

1. Connect WebSocket to `ws://<laptop-lan-ip>:8765/ws`.
2. Send `hello`, wait for `hello_ack`.
3. Camera: JPEG encode ~5–10 fps → type `3`.
4. Mic: on press, `listen start` + stream type `1` PCM; on release `listen stop`.
5. Play type `2` payload with `MediaPlayer` / `ExoPlayer` (detect WAV vs MP3 by magic bytes).
6. Draw `detections` boxes using normalized coordinates.
7. Answer server `ping` with `pong` including the same `t`.

## Out of scope (later)

ESP32 eyes / motors use a separate XiaoZhi-compatible channel; not part of this MVP protocol.
