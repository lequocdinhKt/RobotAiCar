# OneAi Server MVP

Laptop brain for phone/browser: **mic + camera + speaker**, local **STT** (faster-whisper), **LLM** (Groq), **YOLO** bbox, debug UI.

ESP32 and Android app are **out of scope** for this folder — use the browser simulator first, then implement Android against [PROTOCOL.md](PROTOCOL.md).

## Quick start (Windows)

```powershell
cd d:\Dinh\OneAi\server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy ".env copy.example" .env
# Edit .env → set GROQ_API_KEY=...
python -m app.main
```

Open Chrome/Edge: [http://127.0.0.1:8765/](http://127.0.0.1:8765/)

Allow mic + camera. Hold **Giữ để nói**, release to run STT → Groq → TTS.

On the same LAN, other devices use `http://<laptop-ip>:8765/` (allow port **8765** in Windows Firewall).

### Optional: ffmpeg for WAV TTS

`edge-tts` produces MP3. If [ffmpeg](https://ffmpeg.org/) is on `PATH` and `pydub` works, the server converts to WAV. Otherwise the browser receives MP3 (still plays).

## Layout

| Path | Role |
|------|------|
| `app/main.py` | FastAPI + `/ws` |
| `app/pipeline.py` | STT → Groq → TTS |
| `app/vision_yolo.py` | YOLOv8n on JPEG |
| `static/` | Debug UI + browser fake-phone |
| `PROTOCOL.md` | Spec for Android later |

## UI

- **Left:** live video + YOLO rectangles
- **Right:** STT / LLM / events + push-to-talk
- **Top:** connection, session, RTT, pipeline, client list

## Health

`GET /health` → `{ ok, groq_configured, clients, pipeline }`

## Notes

- First run downloads Whisper `small` + YOLOv8n (needs network; Whisper ~500MB).
- Hold PTT **≥ 1 second**, speak clearly near the mic.
- CPU is enough for demo; set `WHISPER_MODEL=base` in `.env` if too slow.
- Do not commit `.env`.
