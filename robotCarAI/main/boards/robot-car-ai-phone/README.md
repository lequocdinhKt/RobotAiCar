# Robot Car AI - PCB Lab ESP32-S3 with phone audio

This board variant keeps the PCB Lab ESP32-S3-N16R8 OLED, BOOT button, RGB LED,
Wi-Fi, XiaoZhi protocol, activation, Opus, and MCP stack. Audio hardware is
replaced by a low-latency Wi-Fi bridge to the companion Android app.

## Hardware retained

- ESP32-S3-N16R8
- SH1106 OLED 128x64 at `0x3C`, SDA GPIO41, SCL GPIO42
- WS2812 at GPIO48
- BOOT button at GPIO0

The INMP441 and MAX98357A are not accessed by this firmware. Their former pins
GPIO4/5/6/7/15/16 remain free for a future motor driver after its exact wiring
has been confirmed.

## Phone audio protocol

- Discovery: UDP port 8764, request `ROBOTCARAI_DISCOVER`
- Audio/control: TCP port 8765
- Microphone: signed 16-bit little-endian mono PCM, 16 kHz
- Speaker: signed 16-bit little-endian mono PCM, 24 kHz
- Framing: see `phone_audio_bridge.cc` and `phone-app/PROTOCOL.md`

Only one phone is accepted at a time. Both devices must be on the same Wi-Fi
network and client isolation must be disabled.
