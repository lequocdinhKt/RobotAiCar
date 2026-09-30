# Phone audio bridge protocol v1

Transport is a single TCP connection to ESP32 port `8765`. Every frame uses a
12-byte big-endian header followed by `payload_length` bytes:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | Magic `0x52434149` (`RCAI`) |
| 4 | 1 | Version `1` |
| 5 | 1 | Type |
| 6 | 2 | Flags, currently zero |
| 8 | 4 | Payload length, maximum 4096 |

Types:

| Type | Direction | Payload |
|---:|---|---|
| 1 | ESP32 to phone | UTF-8 hello JSON |
| 2 | Phone to ESP32 | PCM S16LE mono, 16 kHz |
| 3 | ESP32 to phone | PCM S16LE mono, 24 kHz |
| 4 | Phone to ESP32 | One-byte control command |
| 5 | ESP32 to phone | Reserved UTF-8 status |

Control commands are `1=toggle conversation`, `2=start listening`, and
`3=stop listening`.

Discovery uses UDP port `8764`: the phone broadcasts ASCII
`ROBOTCARAI_DISCOVER`; the ESP32 replies `ROBOTCARAI/1` to the sender.
