# Hướng dẫn tách riêng hệ thống XiaoZhi (PCB Lab)

Tài liệu này giải thích **file quan trọng / có thể chỉnh**, và cách **tách** từng phần theo mục tiêu của bạn:


| Bạn muốn                            | Ý nghĩa                                                       |
| ----------------------------------- | ------------------------------------------------------------- |
| Phần mềm (firmware ESP32) riêng     | Repo `pcb-lab-xiaozhi` — mic, loa, OLED, WiFi, wake word      |
| AI (LLM) riêng                      | Dùng Grok / model khác — **không** chạy trên ESP32            |
| Web riêng                           | Trang cấu hình / console / chat text của **bạn**              |
| Server riêng                        | Backend bạn host — nhận Opus, gọi AI, trả TTS                 |
| **Giọng nói (voice) của “con này”** | Giữ pipeline **TTS → Opus** giống XiaoZhi (giọng phát ra loa) |


> ESP32 **không chạy LLM**. Nó chỉ thu/phát âm thanh (Opus), hiển thị OLED, và nói chuyện với server qua WebSocket hoặc MQTT+UDP.

---



## 1. Kiến trúc hiện tại (để biết chỗ nào tách được)

```
┌──────────────────────────── ESP32 (firmware) ────────────────────────────┐
│  Mic INMP441 → I2S → Opus encode                                         │
│  Wake word (ESP-SR) trên chip                                            │
│  OLED SH1106 (LVGL) — status / emotion / chat text                       │
│  Loa MAX98357A ← Opus decode ← TTS từ server                             │
│  WiFi config AP (web nhỏ trên thiết bị khi chưa có WiFi)                 │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │ WebSocket / MQTT+UDP (JSON + Opus)
                                ▼
┌──────────────────────────── Server đám mây ──────────────────────────────┐
│  OTA / kích hoạt / cấp websocket URL   (mặc định xiaozhi.me / tenclass)  │
│  STT (speech → text)                                                     │
│  LLM (chat AI)                                                           │
│  TTS (text → giọng nói → Opus)  ← đây mới là “voice” bạn nghe trên loa │
│  Console web quản lý thiết bị                                            │
└──────────────────────────────────────────────────────────────────────────┘
```



### Phân biệt quan trọng: “Voice” ≠ “AI”


| Thứ                            | Chạy ở đâu               | File / lớp liên quan                           |
| ------------------------------ | ------------------------ | ---------------------------------------------- |
| **Giọng nói (TTS)**            | Server → ESP32 phát Opus | Server TTS; firmware chỉ `audio/` decode + I2S |
| **AI (LLM nội dung trả lời)**  | Server                   | Backend bạn (Grok, …)                          |
| **Nhận diện giọng (STT)**      | Server                   | Backend bạn                                    |
| **Wake word “你好小智”**           | **Trên ESP32**           | `main/audio/` + model ESP-SR                   |
| **Giao diện OLED**             | ESP32                    | `main/display/oled_display.`*                  |
| **Web cấu hình WiFi lần đầu**  | ESP32 (AP)               | component `esp-wifi-connect` (managed)         |
| **Web quản lý AI / tài khoản** | Server / web riêng       | **Chưa có trong firmware** — phải tự làm       |


**Lưu ý:** Nếu bạn chỉ đổi LLM sang Grok nhưng vẫn muốn **cùng giọng TTS XiaoZhi**, server của bạn phải gọi **cùng (hoặc tương đương) dịch vụ TTS** mà XiaoZhi dùng — không phải “Grok nói”. Grok chỉ tạo **chữ**; TTS mới tạo **giọng**.

---



## 2. Bản đồ file quan trọng (có thể chỉnh)



### 2.1 — Board phần cứng (GPIO, mic, loa, OLED driver gắn board)


| File                                                 | Việc làm                       | Có nên sửa?                 |
| ---------------------------------------------------- | ------------------------------ | --------------------------- |
| `main/boards/pcb-lab-esp32s3-n16r8/config.h`         | Pin I2S, I2C OLED, LED, nút    | **Có** — khi đổi dây        |
| `main/boards/pcb-lab-esp32s3-n16r8/pcb_lab_board.cc` | Khởi tạo OLED/I2S/nút BOOT     | **Có** — logic board        |
| `main/boards/pcb-lab-esp32s3-n16r8/config.json`      | Tên board + sdkconfig (SH1106) | **Có** — biến thể build     |
| `main/boards/common/*`                               | WifiBoard, Button, …           | Chỉ khi cần tính năng chung |


**Lưu ý:** Đừng sửa pin của board khác. Identity board ảnh hưởng OTA — giữ `pcb-lab-esp32s3-n16r8`.

---



### 2.2 — Giao diện màn hình OLED (UI trên thiết bị)


| File                                      | Việc làm                                                                   |
| ----------------------------------------- | -------------------------------------------------------------------------- |
| `main/display/oled_display.cc` / `.h`     | Layout LVGL 128×64 / 128×32: status bar, emotion, chat message             |
| `main/display/display.cc` / `.h`          | API chung: `SetStatus`, `SetChatMessage`, `SetEmotion`, `ShowNotification` |
| `main/display/lvgl_display/*`             | Theme, font, emoji LVGL                                                    |
| `main/assets/locales/vi-VN/language.json` | Chuỗi tiếng Việt trên UI / thông báo                                       |
| `main/assets/lang_config.h`               | **Generated** — đừng sửa tay; đổi locale rồi build lại                     |


Chỗ chỉnh UI OLED thực tế nhất: `SetupUI_128x64()` / `SetChatMessage` / `SetEmotion` trong `oled_display.cc`.

**Lưu ý:**

- OLED 128×64 rất nhỏ — không làm “web UI” trên đây; chỉ status + vài dòng text / icon.
- Web đẹp (dashboard, chat, cấu hình AI) phải là **web riêng** trên server/PC/điện thoại.
- Đổi font/locale có thể phình partition assets — kiểm tra dung lượng flash.

---



### 2.3 — Âm thanh / “voice” trên thiết bị (không phải LLM)


| File / thư mục                          | Việc làm                                            |
| --------------------------------------- | --------------------------------------------------- |
| `main/audio/audio_service.*`            | Pipeline thu/phát, queue Opus                       |
| `main/audio/codecs/no_audio_codec.*`    | I2S thô (INMP441 + MAX98357A) — board bạn đang dùng |
| `main/audio/engines/afe_audio_engine.*` | Wake word + VAD (ESP-S3)                            |
| `main/audio/wake_words/*`               | Các kiểu wake word                                  |
| `main/audio/README.md`                  | Thiết kế audio                                      |
| `sdkconfig` / menuconfig                | Bật/tắt wake word, model `wn9_nihaoxiaozhi_tts`     |


**Lưu ý về “giữ voice của con này”:**

1. **Giọng phát ra loa** = file Opus TTS từ server. Firmware chỉ decode. Muốn cùng giọng → server bạn phải **tạo Opus TTS cùng chất giọng** (cùng vendor TTS / cùng voice ID).
2. **Wake word** “你好小智” nằm trên chip — đổi câu gọi dậy phải đổi model ESP-SR (menuconfig), không liên quan Grok.
3. Sample rate: mic thường 16 kHz lên server; TTS về thường 24 kHz — xem hello trong `docs/websocket.md`. Đừng đổi bừa nếu server chưa khớp.

---



### 2.4 — Kết nối server / protocol (chỗ “móc” AI + TTS của bạn)


| File                                  | Việc làm                                |
| ------------------------------------- | --------------------------------------- |
| `main/protocols/protocol.*`           | API chung: listen / TTS events / MCP    |
| `main/protocols/websocket_protocol.*` | WebSocket + Opus binary + JSON          |
| `main/protocols/mqtt_protocol.*`      | MQTT điều khiển + UDP audio             |
| `docs/websocket.md`                   | **Spec bắt buộc** nếu viết server riêng |
| `docs/mqtt-udp.md`                    | Spec MQTT+UDP                           |
| `docs/mcp-protocol.md`                | Tool điều khiển thiết bị từ AI          |


URL WebSocket **không hard-code cứng trong mọi chỗ**: thường lấy từ phản hồi OTA / NVS (`Settings("websocket")`).


| File                                 | Việc làm                                                           |
| ------------------------------------ | ------------------------------------------------------------------ |
| `main/ota.cc`                        | Gọi `CONFIG_OTA_URL`, nhận cấu hình `websocket` / MQTT / kích hoạt |
| `main/Kconfig.projbuild` → `OTA_URL` | URL kiểm tra phiên bản + bootstrap config                          |
| `main/settings.*`                    | Lưu NVS: WiFi, ota_url, websocket url/token                        |


**Lưu ý:**

- Muốn **web/AI riêng**: bạn cần **server riêng** implement đúng protocol XiaoZhi (hello / listen / stt / tts / binary Opus), rồi trỏ `OTA_URL` (hoặc ghi NVS `websocket` url) về server bạn.
- Chỉ đổi LLM sang Grok **trong server**; firmware gần như không cần biết “Grok”.
- Nếu vẫn dùng server chính thức xiaozhi.me thì AI/web/voice đều của họ — **không tách được** theo ý bạn.

---



### 2.5 — Logic ứng dụng (máy trạng thái)


| File                          | Việc làm                                                 |
| ----------------------------- | -------------------------------------------------------- |
| `main/application.*`          | Vòng đời: WiFi → kích hoạt → idle → listening → speaking |
| `main/device_state_machine.*` | Chuyển trạng thái hợp lệ                                 |
| `main/mcp_server.*`           | Tool MCP phía thiết bị (volume, …)                       |
| `main/main.cc`                | Entry                                                    |


**Lưu ý:** Đổi state qua `SetDeviceState` / `Schedule` — đừng block vòng lặp chính hoặc task audio.

---



### 2.6 — Build / cấu hình


| File                     | Việc làm                                  |
| ------------------------ | ----------------------------------------- |
| `scripts/build.py`       | Build chuẩn theo board                    |
| `main/CMakeLists.txt`    | Chọn `BOARD_DIR`, font, nguồn             |
| `main/Kconfig.projbuild` | Board, OLED, wake word, OTA URL, protocol |
| `main/idf_component.yml` | Dependency (esp-sr, lvgl, sh1106, …)      |
| `sdkconfig.defaults*`    | Flash 16MB, PSRAM S3, …                   |
| `partitions/`            | Bảng phân vùng                            |
| `docs/a-den-z.md`        | GPIO + checklist phần cứng PCB Lab        |


**Không sửa tay:** `build/`, `managed_components/`, `sdkconfig`* đã generate (trừ khi biết rõ), `lang_config.h` generated.

---



### 2.7 — “Web” trong repo này là gì?


| Loại web                                              | Ở đâu                                      | Của ai                              |
| ----------------------------------------------------- | ------------------------------------------ | ----------------------------------- |
| Trang cấu hình WiFi khi thiết bị mở AP `Xiaozhi-XXXX` | Component WiFi (HTML nhúng firmware)       | Firmware — chỉ setup mạng           |
| Console xiaozhi.me                                    | Cloud chính thức                           | Họ — quản lý thiết bị / LLM / voice |
| **Web AI / dashboard của bạn**                        | **Phải tự viết** (Next.js, …) + API server | Bạn                                 |


Firmware **không chứa** web chat Grok. Tách web riêng = project frontend/backend mới, server nói protocol với ESP32.

---



## 3. Cách tách theo từng mục tiêu của bạn



### A. Firmware / phần mềm trên ESP32 (giữ riêng)

**Giữ trong** `pcb-lab-xiaozhi`**:**

- Board PCB Lab, audio I2S, OLED, wake word, protocol client, WiFi.

**Không nhét vào firmware:**

- Grok API key, logic prompt dài, database user, UI web đẹp.

**Lưu ý:**

- API key Grok **không** đưa vào firmware (dễ lộ khi flash/OTA).
- Firmware chỉ cần biết: URL WebSocket + token do **server bạn** cấp.

---



### B. AI (LLM) riêng — ví dụ Grok

**Chỗ đặt:** service backend của bạn, ví dụ:

```
ESP32 --Opus--> Server bạn --STT--> text
                              --> Grok API --> text trả lời
                              --> TTS --> Opus --TTS audio--> ESP32
```

**Lưu ý:**

- Grok = **text in / text out**. Không thay TTS.
- Cần STT (Whisper, cloud STT, …) trước khi gọi Grok.
- Latency: STT + Grok + TTS có thể chậm hơn realtime XiaoZhi chính thức — tối ưu streaming nếu cần.
- Tuân thủ ToS / billing Grok; rate limit; lưu log hội thoại trên server bạn (GDPR/riêng tư).

---



### C. Voice (giọng) “của con XiaoZhi này”

**Ý bạn khả dĩ có 2 nghĩa — chọn rõ:**


| Nghĩa                                                        | Cách làm                                                                                                                                                    | Lưu ý                                                                                                           |
| ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| **1. Giọng TTS giống XiaoZhi** (nghe quen)                   | Server bạn gọi cùng loại TTS / voice mà hệ XiaoZhi dùng (hoặc TTS clone giọng tương đương), xuất **Opus** đúng `sample_rate` / `frame_duration` trong hello | Không có “file giọng” trong firmware; giọng nằm phía **TTS server**. Đổi LLM không đổi giọng nếu TTS giữ nguyên |
| **2. Pipeline thu–phát của thiết bị này** (mic/loa/wake)     | Giữ nguyên `main/audio/` + board PCB Lab                                                                                                                    | Đúng — phần này **nên giữ** trong firmware                                                                      |
| **3. Vẫn dùng cloud XiaoZhi chỉ để TTS**, AI Grok ở chỗ khác | Kiến trúc phức tạp: 2 backend, đồng bộ session                                                                                                              | Dễ lỗi latency/auth; chỉ nên khi đã vững protocol                                                               |


**Khuyến nghị thực tế:**  
Một **server riêng** của bạn: STT + **Grok** + **một TTS bạn chọn** (cố gắng gần giọng XiaoZhi). Firmware chỉ đổi endpoint.

**Lưu ý pháp lý / kỹ thuật:**  
Không giả định được “copy” TTS độc quyền của xiaozhi.me. Dùng TTS thương mại có license (Edge/Azure/Google/CosyVoice self-host, …) và map ra Opus.

---



### D. Web riêng (console / chat / cấu hình AI)

**Tách thành project riêng**, ví dụ `pcb-lab-web/`:


| Module web           | Chức năng                                      |
| -------------------- | ---------------------------------------------- |
| Đăng nhập user       | Tài khoản của bạn (không phụ thuộc xiaozhi.me) |
| Quản lý thiết bị     | Device-Id / token / gắn ESP32                  |
| Chọn model           | Grok / model khác, system prompt               |
| Chọn giọng TTS       | Voice ID                                       |
| Chat text (tuỳ chọn) | Test AI không cần nói                          |
| Xem log STT/TTS      | Debug                                          |


**Lưu ý:**

- Web **không** thay firmware; web gọi **API server bạn**, server nói với thiết bị.
- Trang WiFi AP trên ESP32 chỉ để vào mạng — đừng nhầm với “web AI”.
- CORS, HTTPS, auth token cho WebSocket thiết bị phải do server bạn cấp qua bước OTA/bootstrap giả lập.

---



### E. Server riêng (bắt buộc nếu muốn AI + web của bạn)

Server phải (tối thiểu):

1. **Bootstrap/OTA-like** (hoặc ghi sẵn NVS): trả về `websocket.url` + token.
  Xem `main/ota.cc` (parse JSON có section `websocket`).
2. **WebSocket** đúng `docs/websocket.md`:
  - hello hai chiều
  - nhận Opus mic / gửi Opus TTS
  - JSON: `listen`, `stt`, `tts` (`start` / `sentence_start` / `stop`), abort, MCP (nếu cần)
3. Pipeline: Opus → PCM → STT → **Grok** → text → TTS → Opus.
4. (Tuỳ chọn) MQTT+UDP nếu dùng transport đó — `docs/mqtt-udp.md`.

**Tham khảo cộng đồng:** nhiều dự án “xiaozhi-server” / open-source server tương thích protocol (Python/Node). Có thể fork rồi **thay LLM = Grok**, giữ hoặc đổi TTS.

**Lưu ý:**

- Sai một field `type` / thiếu `tts state start` → loa im hoặc UI kẹt.
- `sample_rate` / `frame_duration` phải khớp hello.
- Activation code xiaozhi.me **không** dùng được trên server bạn — tự làm đăng ký thiết bị trên web riêng.
- Tắt hoặc tự host OTA nếu không muốn thiết bị gọi `CONFIG_OTA_URL` chính thức.

---



## 4. Sơ đồ tách đề xuất (đúng ý bạn nhất)

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│  pcb-lab-xiaozhi│     │  Server riêng của bạn │     │  Web riêng      │
│  (ESP32)        │◄───►│  - Protocol XiaoZhi   │◄───►│  quản lý + chat │
│  mic/loa/OLED   │Opus │  - STT                │API  │  chọn Grok/TTS  │
│  wake word      │JSON │  - LLM: Grok          │     └─────────────────┘
└─────────────────┘     │  - TTS: giọng bạn chọn│            │
                        │    (cố gắng gần XZ)   │            │
                        └──────────┬────────────┘            │
                                   │                         │
                                   ▼                         ▼
                              Grok API                 User / admin
                              TTS provider
```

**Giữ từ “con này”:** firmware audio + protocol client + (tuỳ chọn) phong cách TTS.  
**Thay bằng của bạn:** LLM (Grok), web, server, tài khoản, OTA/activation.

---



## 5. Thứ tự làm (tránh rối)

1. **Ổn firmware PCB Lab** trên server chính thức (mic/loa/OLED/BOOT) — đã gần xong.
2. **Chạy/fork một xiaozhi-compatible server** local — thiết bị trỏ OTA/WebSocket về máy bạn (vẫn LLM mặc định của server đó).
3. **Đổi LLM → Grok** trong server; giữ TTS tạm.
4. **Chỉnh TTS** cho gần giọng mong muốn; xác nhận Opus phát đúng trên loa.
5. **Làm web riêng** quản lý prompt / device / voice.
6. Tắt phụ thuộc xiaozhi.me (activation, OTA chính thức).

**Lưu ý:** Đừng làm bước 3–5 trước khi bước 2 pass (hello + nghe + nói được).

---



## 6. Checklist “file nào đụng” theo việc


| Việc                   | Đụng firmware?             | Đụng server? | Đụng web?       |
| ---------------------- | -------------------------- | ------------ | --------------- |
| Đổi GPIO / OLED layout | Có (`boards/`, `display/`) | Không        | Không           |
| Đổi wake word          | Có (menuconfig / audio)    | Không        | Không           |
| Đổi nội dung AI (Grok) | Không                      | Có           | Có (cấu hình)   |
| Đổi giọng nói TTS      | Không (hoặc rất ít)        | Có (TTS)     | Có (chọn voice) |
| Đổi URL server         | Có (`OTA_URL` / NVS)       | Có           | Có              |
| Dashboard quản lý      | Không                      | API          | Có              |
| WiFi lần đầu           | Firmware AP                | Không        | Không           |


---



## 7. Rủi ro / lưu ý tổng hợp (đọc trước khi tách)

1. **ESP32 ≠ AI server** — nhồi Grok vào firmware sẽ thất bại (RAM/flash/key).
2. **Voice nằm ở TTS server**, không nằm trong repo board.
3. **Protocol là hợp đồng** — tự bịa JSON sẽ gãy audio. Bám `docs/websocket.md`.
4. **OTA identity board** — giữ type `pcb-lab-esp32s3-n16r8`.
5. **Bảo mật** — token/device auth trên server bạn; HTTPS/WSS.
6. **Hai repo gốc** (`xiaozhi-esp32` và `pcb-lab-xiaozhi`) — chỉnh AI/server trên stack riêng; firmware PCB Lab ưu tiên sửa trong `pcb-lab-xiaozhi`.
7. **Tiếng Việt:** STT/TTS/LLM đều phải cấu hình ngôn ngữ phía server; wake word mặc định vẫn tiếng Trung trừ khi đổi model.

---



## 8. Tài liệu liên quan trong repo


| File                   | Nội dung                                      |
| ---------------------- | --------------------------------------------- |
| `docs/a-den-z.md`      | Phần cứng PCB Lab, GPIO, flash                |
| `docs/websocket.md`    | Protocol realtime (bắt buộc cho server riêng) |
| `docs/mqtt-udp.md`     | Protocol thay thế                             |
| `docs/mcp-protocol.md` | AI điều khiển thiết bị                        |
| `docs/custom-board.md` | Thêm/sửa board                                |
| `main/audio/README.md` | Audio pipeline                                |
| `AGENTS.md`            | Quy tắc chỉnh code firmware                   |
| `README.md`            | Build board PCB Lab                           |


---



## 9. Tóm tắt một câu

> **Firmware** giữ mic–loa–OLED–wake–client protocol; **Server của bạn** làm STT + **Grok** + **TTS (giọng bạn muốn giữ/gần XiaoZhi)**; **Web của bạn** chỉ quản lý và cấu hình — không chạy trên ESP32.

Khi bắt đầu code server/web, ưu tiên implement đúng `hello` + `tts start/stop` + Opus trước, rồi mới gắn Grok.