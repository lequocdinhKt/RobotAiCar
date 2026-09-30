# OneAi — Kiến trúc server riêng (Phone + Laptop + ESP32-S3)

Tài liệu tổng hợp sau khi đọc toàn bộ mã nguồn trong workspace:


| Thư mục            | Vai trò                                                                                             |
| ------------------ | --------------------------------------------------------------------------------------------------- |
| `server/`          | **MVP hiện tại:** laptop Python — browser/phone mic+camera+loa, YOLO, STT local, LLM Groq, UI debug |
| `robotCarAI/`      | Fork XiaoZhi + board phone-audio + app Android (mic/loa qua Wi-Fi)                                  |
| `pcb-lab-xiaozhi/` | Firmware PCB Lab đã nạp được: OLED 2 mắt, mic/loa on-board                                          |
| `xiaozhi-esp32/`   | Repo tham chiếu upstream: protocol, MCP, motor/servo mẫu                                            |


## Phase hiện tại (đang làm)

**Chỉ Browser/Phone ↔ Laptop** — chưa ESP32.

1. Vào `[server/README.md](server/README.md)`, tạo venv, điền `GROQ_API_KEY`, chạy server.
2. Mở `http://127.0.0.1:8765/` — giả lập điện thoại + UI debug (video + bbox, STT/LLM, status kết nối).
3. App Android viết sau theo `[server/PROTOCOL.md](server/PROTOCOL.md)`.
4. ESP (mắt/động cơ) gắn sau khi AV loop ổn.

---



## 1. Kết luận nhanh — Có làm được không?

**Có. Khả thi cao** trên cùng một mạng LAN (điện thoại + laptop + ESP32-S3).

Bạn **không cần** đám mây XiaoZhi nếu laptop tự chạy STT / LLM / TTS / vision. Phần cứng và protocol hiện có đủ làm nền; phần còn thiếu chủ yếu là **server trên laptop** và **video từ điện thoại**.


| Thành phần mục tiêu                 | Đánh giá              | Ghi chú                                                    |
| ----------------------------------- | --------------------- | ---------------------------------------------------------- |
| Phone → mic → server                | Sẵn ~80%              | Pattern RCAI trong `robotCarAI` (đổi đích từ ESP → laptop) |
| Phone → camera → server             | Chưa có               | Phải thêm vào app Android                                  |
| Phone ← loa (TTS)                   | Sẵn ~80%              | Cùng pattern PCM / hoặc WebRTC                             |
| Laptop = brain + debug video (bbox) | **Có MVP**            | `[server/](server/)` — YOLO + UI debug                     |
| ESP32 ← động cơ                     | Chưa có trên PCB Lab  | Học từ Otto / SparkBot / Electron trong `xiaozhi-esp32`    |
| ESP32 ← 2 mắt (OLED)                | **Tái sử dụng AS-IS** | `pcb-lab-xiaozhi` đã có face + `type:llm` emotion          |
| Cùng Wi-Fi LAN                      | OK                    | Laptop host IP cố định / mDNS                              |


**Độ khó ước lượng (1 người):** MVP voice + mắt ~1–2 tuần; + video debug bbox ~1 tuần; + động cơ + MCP ~1–2 tuần (tùy driver phần cứng).

---



## 2. Mô hình mục tiêu của bạn

```
┌───────────── Điện thoại ─────────────┐
│  Mic  ──PCM/Opus──┐                  │
│  Camera ─H.264/JPEG─►  Laptop server │──► Debug window: video + bbox
│  Loa   ◄─TTS PCM──┘   (STT/LLM/TTS/  │    người / vật
│                       Vision/Tools)  │
└──────────────────────────────────────┘
                              │
              WebSocket JSON (+ MCP)
                              ▼
┌──────────── ESP32-S3 (PCB Lab) ──────┐
│  OLED 2 mắt  ← emotion (llm)         │  ← firmware hiện tại: giữ nguyên
│  Động cơ     ← MCP tools (mới)       │  ← cần thêm firmware driver
│  (tuỳ chọn) mic/loa on-board         │  ← có thể tắt nếu phone làm AV
└──────────────────────────────────────┘
```

**Phân vai rõ:**

- **Điện thoại:** cảm biến AV (mic + camera) + loa phát TTS.
- **Laptop:** server não — nhận request, phân tích, xử lý, trả output; đồng thời xem video debug có khung bao quanh object/person.
- **ESP32-S3:** actuator + mắt — nhận lệnh cảm xúc / động cơ từ laptop. Không chạy LLM.

---



## 3. Hiện trạng từng repo (đã có gì)



### 3.1 `robotCarAI` — gần với “phone làm headset”

Luồng hiện tại:

```
Phone (Android)  --UDP:8764 discover / TCP:8765 PCM-->  ESP32
                                                         |
                                                         | Opus + JSON (WebSocket)
                                                         v
                                                   Cloud / XiaoZhi server
```

- Protocol RCAI: `robotCarAI/phone-app/PROTOCOL.md` (magic `RCAI`, mic 16 kHz, loa 24 kHz).
- Board `robot-car-ai-phone`: GPIO 4/5/6/7/15/16 **để trống** cho motor sau này.
- **Chưa có:** video từ phone, motor driver, process trên laptop.



### 3.2 `pcb-lab-xiaozhi` — mắt + audio on-board (đã nạp được)

- MCU: ESP32-S3-N16R8.
- OLED SH1106 128×64 I2C (SDA 41, SCL 42) — **2 mắt LVGL** (`OledDisplay::SetEmotion` / `ResolveEmotion`).
- Mic INMP441 + loa MAX98357A (nếu giữ audio trên board).
- **Không có** camera / motor trên board này.
- Server điều khiển mắt bằng JSON (không cần sửa firmware):

```json
{"session_id":"s1","type":"llm","emotion":"happy"}
```

Emotion đã hỗ trợ: `neutral`, `happy`, `sad`, `angry`, `surprised`, `thinking`, `confused`, `sleepy`, `loving`, `excited`, … (xem `oled_display.cc` / `eye_face_debug.cc`).

Tài liệu tách server sẵn có: `pcb-lab-xiaozhi/docs/huongdantachrieng.md`, protocol: `docs/websocket.md`.

### 3.3 `xiaozhi-esp32` — học hỏi (không cần fork nguyên bản)


| Học gì                            | File / board tham chiếu                                |
| --------------------------------- | ------------------------------------------------------ |
| WebSocket + MCP envelope          | `docs/websocket.md`, `docs/mcp-protocol.md`            |
| Động cơ / servo MCP               | `boards/otto-robot/otto_controller.cc` (`self.otto.*`) |
| Chassis tiến/lùi/quay             | `boards/espressif/esp-sparkbot/` (`self.chassis.*`)    |
| Queue hành động không block mạng  | Otto / Electron Bot                                    |
| Local WS điều khiển (không cloud) | Electron/Otto `websocket_control_server` ~ port 8080   |
| Emotion → animation               | ESP-HI / Otto GIF eyes                                 |


**Không nên copy nguyên:** OTA cloud `xiaozhi.me`, Opus duplex nặng trên ESP nếu phone đã làm AV, 100+ board variants.

---



## 4. Server trên laptop phải làm gì?



### 4.1 Vai trò cốt lõi

1. **Nhận kết nối** từ phone (audio + video) và từ ESP32 (control).
2. **STT** — mic PCM/Opus → text.
3. **Vision** — frame camera → detect person/object → bbox (debug UI trên laptop).
4. **LLM / agent** — hiểu yêu cầu + quyết định tool (nói gì, cảm xúc gì, motor thế nào).
5. **TTS** — text → PCM/Opus gửi về **loa điện thoại**.
6. **Gửi lệnh ESP32** — emotion mắt + MCP động cơ.
7. **Debug UI** — cửa sổ OpenCV/Qt: video live + hình chữ nhật bbox.



### 4.2 Stack kỹ thuật đề xuất (LAN, cá nhân)


| Lớp          | Gợi ý                                      | Lý do                         |
| ------------ | ------------------------------------------ | ----------------------------- |
| Runtime      | Python 3.11+                               | Nhanh prototype, nhiều lib AI |
| Net          | FastAPI / aiohttp + WebSocket              | Phone + ESP cùng kiểu WS      |
| Audio phone  | WebSocket binary PCM hoặc WebRTC           | Tái dùng ý tưởng RCAI         |
| Video phone  | WS gửi JPEG định kỳ (5–15 fps) hoặc WebRTC | Đơn giản hơn H.264 lúc đầu    |
| STT          | faster-whisper / Vosk (local) hoặc API     | Offline được nếu máy đủ mạnh  |
| Vision       | YOLOv8/v11 (Ultralytics) hoặc MediaPipe    | Bbox người/vật realtime       |
| LLM          | Ollama local / API (Grok, …)               | ESP không chạy LLM            |
| TTS          | Piper / edge-tts / API                     | Gửi PCM về phone              |
| ESP protocol | XiaoZhi WebSocket subset + MCP             | Tái dùng firmware mắt AS-IS   |
| Debug UI     | OpenCV `imshow` hoặc Gradio/Streamlit      | Overlay bbox                  |




### 4.3 Luồng xử lý 1 request (ví dụ)

```
1. Phone bấm nói → stream mic → Laptop
2. (song song) Phone stream camera → Laptop → YOLO → vẽ bbox trên màn hình laptop
3. STT → "đi tới phía trước" / "nhìn vui lên"
4. LLM quyết định:
     - emotion: "happy"     → WS JSON type=llm → ESP OLED
     - tool: chassis.forward → MCP tools/call → ESP motor
     - reply text: "Ok, mình đi đây"
5. TTS → PCM → Phone loa
```



### 4.4 Message tối thiểu Laptop → ESP32 (giữ tương thích XiaoZhi)

```json
{"session_id":"s1","type":"llm","emotion":"happy"}
```

```json
{
  "session_id": "s1",
  "type": "mcp",
  "payload": {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "self.chassis.go_forward",
      "arguments": {}
    }
  }
}
```

Nếu **không** phát TTS qua loa ESP (chỉ qua phone), server **không cần** gửi binary Opus xuống ESP — chỉ cần JSON điều khiển. Đó là cách gọn nhất cho mô hình của bạn.

### 4.5 Message tối thiểu Phone ↔ Laptop (mới)

Có thể **đổi đích** RCAI từ ESP sang laptop, hoặc định nghĩa WS mới:


| Kênh           | Nội dung                                          |
| -------------- | ------------------------------------------------- |
| Phone → Laptop | mic PCM 16 kHz, video JPEG, nút start/stop listen |
| Laptop → Phone | TTS PCM 24 kHz (hoặc Opus), status text           |


Discovery: UDP broadcast kiểu `ROBOTCARAI_DISCOVER` nhưng reply IP **laptop**, không phải ESP.

---



## 5. Tái sử dụng được gì? (checklist)



### Dùng lại gần như nguyên (không / ít sửa)


| Thành phần               | Nguồn                                       | Cách dùng                     |
| ------------------------ | ------------------------------------------- | ----------------------------- |
| OLED 2 mắt + emotion     | `pcb-lab-xiaozhi` firmware đã nạp           | Server gửi `type:llm` emotion |
| WebSocket XiaoZhi subset | `pcb-lab-xiaozhi` / `xiaozhi-esp32` docs    | Laptop giả lập server XiaoZhi |
| Pattern RCAI framing     | `robotCarAI/phone-app`                      | Đổi host → laptop             |
| UDP discovery ý tưởng    | `PROTOCOL.md`                               | Tìm server trên LAN           |
| MCP tool design          | Otto / SparkBot                             | Copy pattern khi viết motor   |
| Docs tách hệ thống       | `pcb-lab-xiaozhi/docs/huongdantachrieng.md` | Định hướng self-host          |




### Phải viết mới


| Thành phần                              | Lý do                                        |
| --------------------------------------- | -------------------------------------------- |
| Laptop server (STT/LLM/TTS/Vision/UI)   | Không có trong 3 repo                        |
| Phone camera stream                     | App hiện chỉ audio                           |
| Motor driver + MCP trên ESP             | PCB Lab chưa có GPIO motor tools             |
| Cấu hình ESP trỏ WebSocket về IP laptop | OTA/NVS hoặc sửa `OTA_URL` / NVS `websocket` |




### Không cần chỉnh (theo yêu cầu của bạn)

- **Màn hình / 2 mắt:** giữ firmware `pcb-lab-xiaozhi` — chỉ gửi emotion từ server.
- Không bắt buộc đổi code OLED.

---



## 6. Hai hướng triển khai (chọn 1)



### Hướng A — Khuyến nghị cho mô hình của bạn (Phone AV + ESP actuator)

```
Phone ──AV──► Laptop ──JSON/MCP──► ESP32 (mắt + motor)
```

- Phone: mic + camera + loa.
- ESP: **không** làm audio chính (tiết kiệm CPU); chỉ OLED + motor.
- Firmware ESP: giữ `pcb-lab-xiaozhi` + **thêm** module motor/MCP (học Otto/SparkBot).
- Ưu: đúng ý bạn; latency AV kiểm soát trên laptop; debug bbox dễ.
- Nhược: phải sửa/đổi app phone (thêm camera + đổi endpoint).



### Hướng B — Ít đụng phone hơn (giữ bridge hiện tại)

```
Phone ──PCM──► ESP ──Opus──► Laptop (xiaozhi-esp32-server self-host)
                 ▲
            OLED + (sau này) motor MCP
```

- Self-host `xinnan-tech/xiaozhi-esp32-server` trên laptop.
- Phone vẫn chỉ nói chuyện với ESP (như `robotCarAI` hiện tại).
- Ưu: tái dùng app gần như nguyên.
- Nhược: **không có video phone**; laptop khó làm debug bbox từ camera phone; ESP trung gian audio phức tạp hơn.

→ Với yêu cầu **video + bbox trên laptop**, chọn **Hướng A**.

---



## 7. Việc ESP32 cần làm thêm (khi có động cơ)

1. Chọn driver (L298N / DRV8833 / servo PCA9685 / …) và map GPIO — trên board phone-audio các chân I2S cũ (4/5/6/7/15/16) đang trống.
2. Thêm MCP tools kiểu:
  - `self.chassis.go_forward` / `go_back` / `turn_left` / `turn_right` / `stop`
3. Chạy lệnh trong FreeRTOS task riêng (đừng block WebSocket) — copy kiểu Otto queue.
4. Giữ nguyên đường `llm` → `SetEmotion` cho mắt.

Tham chiếu copy:

- `xiaozhi-esp32/main/boards/espressif/esp-sparkbot/esp_sparkbot_board.cc`
- `xiaozhi-esp32/main/boards/otto-robot/otto_controller.cc`
- `xiaozhi-esp32/docs/mcp-usage.md`

---



## 8. Việc Phone app cần làm thêm

Hiện có (`robotCarAI/phone-app`):

- UDP discover + TCP PCM mic/loa.

Cần thêm cho Hướng A:

1. Đổi endpoint discovery → tìm **laptop** (port mới, ví dụ 9000).
2. Camera2 / CameraX → encode JPEG → gửi định kỳ.
3. Giữ mic/loa như cũ (có thể nâng lên WebSocket thay TCP nếu muốn một cổng duy nhất).
4. Permission `CAMERA` + `RECORD_AUDIO` + Wi-Fi LAN.

---



## 9. Việc Laptop server cần làm (checklist MVP)

**Phase 1 — Voice loop**

- [ ] WebSocket nhận mic PCM từ phone
- [ ] STT local
- [ ] LLM trả lời text
- [ ] TTS → gửi PCM về phone
- [ ] Gửi `emotion` xuống ESP (đã flash `pcb-lab-xiaozhi`)

**Phase 2 — Vision debug**

- [ ] Nhận JPEG từ phone
- [ ] YOLO/MediaPipe → bbox
- [ ] Cửa sổ debug overlay hình chữ nhật

**Phase 3 — Motor**

- [ ] MCP `tools/call` từ LLM
- [ ] Firmware ESP motor tools
- [ ] An toàn: timeout → `stop`, clamp tốc độ

**Phase 4 — Cứng hóa**

- [ ] IP tĩnh / mDNS (`oneai.local`)
- [ ] Reconnect khi Wi-Fi tụt
- [ ] Log session (text + ảnh debug tùy chọn)

---



## 10. Truyền dữ liệu trên cùng mạng — gợi ý cổng


| Cổng                   | Ai             | Mục đích               |
| ---------------------- | -------------- | ---------------------- |
| UDP 8764 (hoặc mới)    | Phone ↔ Laptop | Discovery              |
| TCP/WS 9000            | Phone ↔ Laptop | Mic + video + TTS      |
| WS 8000 (XiaoZhi-like) | ESP ↔ Laptop   | hello / llm / mcp      |
| (tuỳ) 8080             | Debug MCP      | Như Otto local control |


Tất cả thiết bị: cùng SSID 2.4 GHz (ESP32-S3 thường không dùng 5 GHz).

---



## 11. Rủi ro & lưu ý


| Rủi ro                               | Cách giảm                                                                                     |
| ------------------------------------ | --------------------------------------------------------------------------------------------- |
| Laptop yếu → YOLO + Whisper chậm     | Giảm fps camera (5–10), model nano, hoặc STT streaming                                        |
| Wi-Fi latency audio                  | PCM chunk nhỏ, `TCP_NODELAY`, hoặc WebRTC                                                     |
| Firmware mắt đang bật eye-debug BOOT | Tắt `CONFIG_PCB_LAB_EYE_FACE_DEBUG` nếu BOOT bị chiếm                                         |
| Trỏ ESP về server riêng              | Cấu hình NVS websocket URL/token hoặc OTA JSON custom — xem `ota.cc` / `huongdantachrieng.md` |
| An toàn động cơ                      | Luôn có lệnh `stop` + watchdog mất kết nối                                                    |


---



## 12. Khuyến nghị thực thi theo thứ tự

1. **Giữ nguyên** firmware `pcb-lab-xiaozhi` (mắt).
2. Viết **laptop mini-server** nói được protocol XiaoZhi: gửi thử `emotion` → thấy mắt đổi.
3. Sửa/đổi **phone app** gửi mic (+ sau đó camera) thẳng vào laptop; TTS về loa phone.
4. Thêm **YOLO + cửa sổ debug bbox**.
5. Thêm **motor + MCP** trên ESP (học SparkBot/Otto).
6. Nối LLM tool-calling để lời nói điều khiển motor + cảm xúc.

---



## 13. Bản đồ file quan trọng (tra cứu nhanh)

```
OneAi/
├── README.md                          ← file này
├── robotCarAI/
│   ├── phone-app/PROTOCOL.md          ← framing RCAI
│   ├── phone-app/app/.../AudioBridgeClient.java
│   └── main/boards/robot-car-ai-phone/  ← phone codec + GPIO trống cho motor
├── pcb-lab-xiaozhi/
│   ├── docs/websocket.md
│   ├── docs/huongdantachrieng.md
│   ├── docs/nap-oled-eyes.md
│   └── main/display/oled_display.cc   ← 2 mắt, ResolveEmotion
└── xiaozhi-esp32/
    ├── docs/mcp-protocol.md
    ├── docs/mcp-usage.md
    └── main/boards/otto-robot/        ← mẫu servo MCP
        boards/espressif/esp-sparkbot/ ← mẫu chassis MCP
```

---



## 14. Tóm tắt một câu

Bạn **làm được server riêng trên laptop**: phone gửi mic + video và nhận loa; laptop phân tích (STT/LLM/TTS/vision + bbox debug); ESP32 giữ firmware mắt PCB Lab và chỉ nhận lệnh emotion + (sau này) MCP động cơ — tái sử dụng protocol XiaoZhi và pattern phone-bridge từ `robotCarAI`, học motor từ `xiaozhi-esp32`, không cần đụng code OLED nếu chỉ điều khiển bằng `type:llm`.