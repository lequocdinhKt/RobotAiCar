# Hướng dẫn A–Z: XiaoZhi trên PCB Lab ESP32-S3-N16R8

Tài liệu này dành cho dự án AI assistant cá nhân:

- **Board:** ESP32-S3-N16R8, module 44-pin PCB Lab
- **Ngoại vi:** INMP441, MAX98357A, loa 4Ω 2W, OLED 1.3" 128×64 I2C (SH1106, 0x3C), DS3231, pin LiPo 3.7V + mạch sạc/bảo vệ
- **Repo:** `C:\Dinh\OneAi\xiaozhi-esp32`

> **Trạng thái GPIO:** Đã xác định từ **sơ đồ kết nối phần cứng** (không lấy giả định từ board khác). Bước tiếp theo: tạo board config `pcb-lab-esp32s3-n16r8` trong repo và build/flash.

---

## Mục lục

1. [Tổng quan: firmware làm gì, AI ở đâu](#1-tổng-quan-firmware-làm-gì-ai-ở-đâu)
2. [GPIO đã xác nhận (PCB Lab)](#2-gpio-đã-xác-nhận-pcb-lab)
3. [Phân tích xung đột GPIO](#3-phân-tích-xung-đột-gpio)
4. [Bản đồ file trong repo](#4-bản-đồ-file-trong-repo)
5. [Board mẫu copy & khác biệt](#5-board-mẫu-copy--khác-biệt)
6. [Tạo board custom từ A đến Z](#6-tạo-board-custom-từ-a-đến-z)
7. [Cài đặt môi trường (Windows)](#7-cài-đặt-môi-trường-windows)
8. [Build firmware](#8-build-firmware)
9. [Nạp firmware (flash)](#9-nạp-firmware-flash)
10. [Lần đầu sau khi nạp](#10-lần-đầu-sau-khi-nạp)
11. [Kiểm thử từng phần cứng](#11-kiểm-thử-từng-phần-cứng)
12. [Xử lý sự cố thường gặp](#12-xử-lý-sự-cố-thường-gặp)
13. [DS3231 và tính năng tương lai](#13-ds3231-và-tính-năng-tương-lai)
14. [Thông tin đã có / còn thiếu](#14-thông-tin-đã-có--còn-thiếu)
15. [Checklist tiến độ](#15-checklist-tiến-độ)
16. [Tài liệu tham khảo](#16-tài-liệu-tham-khảo)

---

## 1. Tổng quan: firmware làm gì, AI ở đâu

### ESP32 **không** chạy LLM

| Chức năng | Chạy ở đâu |
|---|---|
| Wake word (đánh thức) | Trên ESP32-S3 (ESP-SR / AFE) |
| Thu âm mic → Opus | Trên ESP32 (`audio_service`) |
| Gửi/nhận qua mạng | Trên ESP32 (`protocols/`) |
| STT, LLM, TTS (hội thoại AI) | **Trên server XiaoZhi (cloud)** |
| Hiển thị OLED | Trên ESP32 (`oled_display` + LVGL) |

### Luồng hoạt động

```
Bạn nói → INMP441 → I2S → ESP32 encode Opus → WiFi → Server XiaoZhi
Server XiaoZhi → STT → LLM → TTS → Opus → WiFi → ESP32 decode → MAX98357A → Loa
```

### Bạn chỉ cần sửa phần nào?

| Phần | Sửa? |
|---|---|
| GPIO, khởi tạo OLED/I2S/button/LED | **Có** — thư mục board `pcb-lab-esp32s3-n16r8` |
| AI, server, Opus, UI chính | **Không** — dùng chung toàn project |

---

## 2. GPIO đã xác nhận (PCB Lab)

**Nguồn:** Sơ đồ kết nối phần cứng bạn cung cấp, đối chiếu thứ tự chân ESP32-S3-N16R8 44-pin (trái: GPIO4/5/6/7/15/16…; phải: GPIO42/41…).

### 2.1 — Audio I2S Simplex

Kiến trúc: **2 bus I2S riêng** — phù hợp INMP441 + MAX98357A (`AUDIO_I2S_METHOD_SIMPLEX`).

#### INMP441 (mic)

| Tín hiệu module | GPIO ESP32-S3 | Dây (sơ đồ) | Macro `config.h` |
|---|---|---|---|
| SCK / BCLK | **GPIO5** | Vàng | `AUDIO_I2S_MIC_GPIO_SCK` |
| WS / LRCK | **GPIO4** | Xanh dương | `AUDIO_I2S_MIC_GPIO_WS` |
| SD / DATA OUT | **GPIO6** | Xanh lá | `AUDIO_I2S_MIC_GPIO_DIN` |
| L/R | **GND** | — | Chọn kênh **Left** |

#### MAX98357A (loa)

| Tín hiệu module | GPIO ESP32-S3 | Dây (sơ đồ) | Macro `config.h` |
|---|---|---|---|
| BCLK | **GPIO15** | Vàng | `AUDIO_I2S_SPK_GPIO_BCLK` |
| LRC / WS | **GPIO16** | Xanh dương | `AUDIO_I2S_SPK_GPIO_LRCK` |
| DIN | **GPIO7** | Xanh lá | `AUDIO_I2S_SPK_GPIO_DOUT` |
| SD / EN | **NC** | Không nối | — |
| GAIN | **NC** | Không nối | — |

#### Tóm tắt nhanh

```
MIC (INMP441):
  GPIO4 = WS
  GPIO5 = SCK
  GPIO6 = DIN

SPEAKER (MAX98357A):
  GPIO7  = DOUT
  GPIO15 = BCLK
  GPIO16 = LRCK
```

### 2.2 — I2C (OLED + DS3231 chung bus)

| Thiết bị | SDA | SCL | Địa chỉ I2C |
|---|---|---|---|
| OLED SH1106 128×64 | **GPIO41** | **GPIO42** | **0x3C** |
| DS3231 | **GPIO41** (chung) | **GPIO42** (chung) | **0x68** |

```
ESP32 GPIO41 ───── OLED SDA
             └──── DS3231 SDA

ESP32 GPIO42 ───── OLED SCL
             └──── DS3231 SCL
```

Không xung đột địa chỉ: `0x3C` ≠ `0x68`.

### 2.3 — Nút, LED, nguồn, USB

| Chức năng | GPIO / ghi chú |
|---|---|
| Nút BOOT | **GPIO0** (BOOT onboard) |
| LED RGB WS2812 | **GPIO48** (`BUILTIN_LED_GPIO`) |
| Công tắc nguồn | **Không có GPIO** — chỉ đóng/cắt nguồn |
| USB-UART (nạp/log) | TX **GPIO43**, RX **GPIO44** (UART0) |
| USB native (OTG) | D- **GPIO19**, D+ **GPIO20** |

### 2.4 — Nút **không** có trên board

| Chức năng bread-compact | GPIO bread-compact | PCB Lab |
|---|---|---|
| Touch (PTT) | GPIO47 | **Không có** → `GPIO_NUM_NC` |
| Volume+ | GPIO40 | **Không có** → `GPIO_NUM_NC` |
| Volume- | GPIO39 | **Không có** → `GPIO_NUM_NC` |
| Lamp MCP demo | GPIO18 | **Không có** → bỏ hoặc `GPIO_NUM_NC` |

Hành vi điều khiển: chỉ dùng **nút BOOT (GPIO0)** — bật/tắt hội thoại; giữ BOOT khi boot → cấu hình WiFi.

### 2.5 — `config.h` hoàn chỉnh (copy vào board mới)

```c
#ifndef _BOARD_CONFIG_H_
#define _BOARD_CONFIG_H_

#include <driver/gpio.h>

#define AUDIO_INPUT_SAMPLE_RATE  16000
#define AUDIO_OUTPUT_SAMPLE_RATE 24000

#define AUDIO_I2S_METHOD_SIMPLEX

#define AUDIO_I2S_MIC_GPIO_WS   GPIO_NUM_4
#define AUDIO_I2S_MIC_GPIO_SCK  GPIO_NUM_5
#define AUDIO_I2S_MIC_GPIO_DIN  GPIO_NUM_6
#define AUDIO_I2S_SPK_GPIO_DOUT GPIO_NUM_7
#define AUDIO_I2S_SPK_GPIO_BCLK GPIO_NUM_15
#define AUDIO_I2S_SPK_GPIO_LRCK GPIO_NUM_16

#define BUILTIN_LED_GPIO        GPIO_NUM_48   // WS2812 RGB, 1 LED
#define BOOT_BUTTON_GPIO        GPIO_NUM_0
#define TOUCH_BUTTON_GPIO       GPIO_NUM_NC
#define VOLUME_UP_BUTTON_GPIO   GPIO_NUM_NC
#define VOLUME_DOWN_BUTTON_GPIO GPIO_NUM_NC

#define DISPLAY_SDA_PIN GPIO_NUM_41
#define DISPLAY_SCL_PIN GPIO_NUM_42
#define DISPLAY_WIDTH   128

#if CONFIG_OLED_SH1106_128X64
#define DISPLAY_HEIGHT  64
#define SH1106
#else
#error "Chọn CONFIG_OLED_SH1106_128X64 trong config.json hoặc menuconfig"
#endif

#define DISPLAY_MIRROR_X true
#define DISPLAY_MIRROR_Y true

#endif // _BOARD_CONFIG_H_
```

> **LED WS2812:** XiaoZhi dùng class `SingleLed` — bên trong đã cấu hình `LED_MODEL_WS2812`, `max_leds = 1` (`main/led/single_led.cc`). Không cần driver riêng.

---

## 3. Phân tích xung đột GPIO

### GPIO đang dùng (peripheral)

| GPIO | Vai trò |
|---|---|
| 0 | BOOT button (strapping — cẩn thận khi thiết kế mạch) |
| 4, 5, 6 | INMP441 I2S |
| 7, 15, 16 | MAX98357A I2S |
| 41, 42 | I2C (OLED + DS3231) |
| 48 | WS2812 RGB LED |

### Không trùng nhau

Nhóm audio mic (4/5/6), audio loa (7/15/16), I2C (41/42), LED (48) **không overlap** — thiết kế sơ đồ hợp lý.

### GPIO hệ thống — không gán cho ngoại vi

| GPIO | Lý do tránh |
|---|---|
| **19, 20** | USB native D-/D+ |
| **43, 44** | UART0 TX/RX — cổng COM nạp firmware & serial monitor |
| **26–37** (tùy module) | Flash / Octal PSRAM trên N16R8 — không breakout hoặc không dùng |
| **0** | Boot strapping — chỉ dùng cho nút BOOT (đã có) |

### Hai cổng USB trên board

| Cổng | Dùng cho | Ghi chú |
|---|---|---|
| **COM** (USB-UART) | `idf.py flash monitor` qua **COM11** | GPIO43/44 |
| **USB&OTG** | USB native ESP32-S3 | GPIO19/20 — **không** thay thế COM khi flash bằng `idf.py -p COMx` |

---

## 4. Bản đồ file trong repo

### Điểm vào

| File | Vai trò |
|---|---|
| `main/main.cc` | `app_main()` → `Application` |
| `main/application.cc` | WiFi, server, mic/loa/màn hình, trạng thái máy |
| `main/application.h` | `ToggleChatState()`, `StartListening()`, ... |

### Mic + Loa

| File | Vai trò |
|---|---|
| `main/boards/pcb-lab-esp32s3-n16r8/config.h` | GPIO (mục 2.5) |
| `main/boards/pcb-lab-esp32s3-n16r8/pcb_lab_board.cc` | `NoAudioCodecSimplex` |
| `main/audio/codecs/no_audio_codec.cc` | I2S port 0 = loa, port 1 = mic |
| `main/audio/audio_service.cc` | Opus encode/decode, hàng đợi audio |
| `main/audio/engines/afe_audio_engine.cc` | Wake word trên S3 |

### Màn hình

| File | Vai trò |
|---|---|
| `pcb_lab_board.cc` | `InitializeDisplayI2c()` + SH1106 @ 0x3C |
| `main/display/oled_display.cc` | UI LVGL |

### Server

| File | Vai trò |
|---|---|
| `main/protocols/websocket_protocol.cc` | WebSocket + Opus |
| `main/protocols/mqtt_protocol.cc` | MQTT (một số cấu hình) |
| `main/ota.cc` | OTA, URL server |
| `docs/websocket.md` | Giao thức chi tiết |

### LED

| File | Vai trò |
|---|---|
| `main/led/single_led.cc` | WS2812, 1 LED — trạng thái idle/listening/speaking |
| `pcb_lab_board.cc` | `GetLed()` → `SingleLed(BUILTIN_LED_GPIO)` |

---

## 5. Board mẫu copy & khác biệt

### Copy từ: `bread-compact-wifi`

**Path:** `main/boards/bread-compact-wifi/`

| Hạng mục | bread-compact-wifi | PCB Lab (bạn) |
|---|---|---|
| Audio GPIO 4/5/6/7/15/16 | Có | **Giống** |
| I2C OLED 41/42 | Có | **Giống** |
| BOOT GPIO0, LED GPIO48 | Có | **Giống** (LED bạn là WS2812 — `SingleLed` hỗ trợ sẵn) |
| Touch / Vol+ / Vol- | GPIO47/40/39 | **Không có** — bỏ trong `pcb_lab_board.cc` |
| Lamp MCP GPIO18 | Có | **Bỏ** `InitializeTools()` / `LAMP_GPIO` |
| DS3231 | Không | **Sau này** — cùng bus I2C |

> GPIO audio + I2C **trùng** bread-compact-wifi là **trùng hợp có thật** từ sơ đồ của bạn, không phải copy mù. Vẫn **phải** tạo board riêng `pcb-lab-esp32s3-n16r8` (OTA identity, bỏ nút thừa).

### Không dùng pinout

| Board | Lý do |
|---|---|
| `wk-esp32s3-dev` | Mic/amp onboard, GPIO audio 40/41/42/47/48/21 |
| Các board LCD SPI / ES8311 | Kiến trúc khác |

---

## 6. Tạo board custom từ A đến Z

### Bước 6.1 — Tạo thư mục

```text
main/boards/pcb-lab-esp32s3-n16r8/
```

### Bước 6.2 — Copy file

| Copy từ | Đặt tên mới |
|---|---|
| `bread-compact-wifi/config.h` | `config.h` → thay bằng mục [2.5](#25--configh-hoàn-chỉnh-copy-vào-board-mới) |
| `bread-compact-wifi/config.json` | `config.json` (sửa type/name) |
| `bread-compact-wifi/compact_wifi_board.cc` | `pcb_lab_board.cc` |

### Bước 6.3 — `config.json`

```json
{
    "type": "pcb-lab-esp32s3-n16r8",
    "target": "esp32s3",
    "builds": [
        {
            "name": "pcb-lab-esp32s3-n16r8",
            "sdkconfig_append": [
                "CONFIG_OLED_SH1106_128X64=y"
            ]
        }
    ]
}
```

N16R8: project mặc định **16MB flash** + **Octal PSRAM 8MB** — không cần `sdkconfig_append` thêm.

### Bước 6.4 — Sửa `pcb_lab_board.cc`

1. Class `PcbLabBoard` thay `CompactWifiBoard`
2. `DECLARE_BOARD(PcbLabBoard)`
3. **Bỏ** `touch_button_`, `volume_up_button_`, `volume_down_button_` và handler tương ứng
4. **Bỏ** `InitializeTools()` / `LampController` (không có GPIO18)
5. Constructor chỉ còn:

```cpp
PcbLabBoard() : boot_button_(BOOT_BUTTON_GPIO) {
    InitializeDisplayI2c();
    InitializeSsd1306Display();
    InitializeButtons();  // chỉ BOOT
}
```

6. `InitializeButtons()` — chỉ BOOT: toggle chat + WiFi config khi starting
7. Giữ `NoAudioCodecSimplex` và `SingleLed(GPIO48)` như bread-compact

### Bước 6.5 — `main/Kconfig.projbuild`

```kconfig
config BOARD_TYPE_PCB_LAB_ESP32S3_N16R8
    bool "PCB Lab ESP32-S3-N16R8"
    depends on IDF_TARGET_ESP32S3
```

### Bước 6.6 — `main/CMakeLists.txt`

```cmake
elseif(CONFIG_BOARD_TYPE_PCB_LAB_ESP32S3_N16R8)
    set(BOARD_DIR "pcb-lab-esp32s3-n16r8")
    set(BUILTIN_TEXT_FONT font_noto_sans_basic_14_1)
    set(BUILTIN_ICON_FONT font_material_symbols_14_1)
```

### Bước 6.7 — DS3231 (phase 2)

Hardware sẵn sàng (bus I2C chung). Firmware: thêm driver đọc 0x68, hiển thị giờ trên OLED — xem [mục 13](#13-ds3231-và-tính-năng-tương-lai).

---

## 7. Cài đặt môi trường (Windows)

| Thành phần | Ghi chú |
|---|---|
| ESP-IDF | v5.4+ hoặc v6.x |
| OS | Windows 10 |
| Repo | `C:\Dinh\OneAi\xiaozhi-esp32` |
| COM đã dùng | **COM11** (USB-UART) |

```powershell
cd C:\Dinh\OneAi\xiaozhi-esp32
idf.py --version
```

Cài ESP-IDF: [Windows Setup Guide](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/get-started/windows-setup.html)

---

## 8. Build firmware

### Cách A — `build.py` (khuyến nghị)

```powershell
cd C:\Dinh\OneAi\xiaozhi-esp32

python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8
```

Gợi ý lần build đầu (debug audio trước wake word):

```powershell
python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8 --language vi-VN --wake-word disabled
```

### Cách B — `idf.py`

```powershell
idf.py set-target esp32s3
idf.py menuconfig
# Xiaozhi Assistant → Board Type → PCB Lab ESP32-S3-N16R8
# Xiaozhi Assistant → OLED Type → SH1106 128*64
idf.py build
```

---

## 9. Nạp firmware (flash)

### Cổng nạp

| Thông số | Giá trị |
|---|---|
| Cổng | **COM11** (USB-UART, GPIO43/44) |
| Lệnh mẫu | `idf.py -p COM11 flash monitor` |

> Dùng cổng **COM** (UART), không dùng cổng **USB&OTG** cho `idf.py -p ...` trừ khi cấu hình USB-JTAG riêng.

### Nạp + log

```powershell
cd C:\Dinh\OneAi\xiaozhi-esp32
idf.py -p COM11 flash monitor
```

Thoát monitor: `Ctrl + ]`

### Vào download mode (nếu cần)

Giữ **BOOT (GPIO0)** → nhấn **RESET** → thả RESET → thả BOOT.

### Erase toàn bộ (lần đầu hoặc lỗi partition)

```powershell
idf.py -p COM11 erase-flash
idf.py -p COM11 flash monitor
```

### Flash / PSRAM (N16R8)

| Cấu hình | Giá trị | Nguồn |
|---|---|---|
| Flash | 16MB | `sdkconfig.defaults` |
| Partition | `partitions/v2/16m.csv` | `sdkconfig.defaults` |
| PSRAM | Octal 80MHz | `sdkconfig.defaults.esp32s3` |

---

## 10. Lần đầu sau khi nạp

1. Bật công tắc nguồn (không qua GPIO)
2. OLED hiện trạng thái khởi động
3. Chưa có WiFi → AP `Xiaozhi-XXXX`
4. Điện thoại → cấu hình WiFi
5. Hiện **mã kích hoạt** → kích hoạt trên nền tảng XiaoZhi
6. Nhấn **BOOT** → bắt đầu/dừng hội thoại AI
7. WS2812 (GPIO48) đổi màu theo trạng thái (idle / listening / speaking)

### Điều khiển trên PCB Lab

| Input | Hành vi |
|---|---|
| BOOT (GPIO0) | Toggle hội thoại; giữ khi boot → WiFi config |
| Công tắc nguồn | Bật/tắt nguồn cứng |
| Volume / PTT | **Không có** — điều chỉnh volume qua giọng nói / app sau khi có MCP |

---

## 11. Kiểm thử từng phần cứng

| # | Test | Kỳ vọng |
|---|---|---|
| 1 | `idf.py -p COM11 monitor` | Log không panic |
| 2 | OLED | Status bar, icon WiFi; driver SH1106 |
| 3 | WS2812 GPIO48 | LED đổi màu khi đổi trạng thái |
| 4 | WiFi | AP → kết nối → icon WiFi |
| 5 | Kích hoạt XiaoZhi | Mã activation OK |
| 6 | Mic INMP441 | Nói → server STT nhận |
| 7 | Loa MAX98357A | TTS phát ra loa 4Ω |
| 8 | Hội thoại đầy đủ | BOOT → hỏi → trả lời giọng nói |
| 9 | Wake word | Bật lại sau khi audio ổn |

---

## 12. Xử lý sự cố thường gặp

| Triệu chứng | Nguyên nhân | Xử lý |
|---|---|---|
| Không thấy COM11 | Driver, cáp, chưa bật nguồn | Device Manager; thử cổng COM |
| Flash fail | Chưa download mode | BOOT + RESET |
| OLED trắng/đen | Sai driver | `CONFIG_OLED_SH1106_128X64=y` |
| Mic im lặng | L/R chưa GND | Kiểm tra INMP441 L/R → GND |
| Loa im lặng | Sai BCLK/LRC/DIN | GPIO15/16/7; nguồn amp |
| LED không sáng | GPIO48 / WS2812 | `SingleLed`; kiểm tra nguồn 5V/3V3 LED |
| Server không kết nối | Chưa activation | Hoàn tất mã kích hoạt |
| Nhầm cổng USB | Dùng USB&OTG thay COM | Flash qua **COM11** (UART) |

---

## 13. DS3231 và tính năng tương lai

| Tính năng | Trạng thái | Ghi chú |
|---|---|---|
| DS3231 đọc giờ | **Chưa có** trong firmware | Bus I2C sẵn: SDA41/SCL42, addr 0x68 |
| Hiển thị giờ OLED | Kế hoạch | Sau khi có driver DS3231 |
| % pin LiPo | **Chưa xác định** | Chưa biết GPIO ADC / IC sạc |
| MCP điều khiển ngoại vi | Tùy chọn | Sau khi core ổn định |
| Wake word | Có thể bật | Sau khi mic OK |
| Bluetooth | Có trên chip | XiaoZhi chủ yếu WiFi |

Tham khảo RTC khác: `main/boards/zectrix/zectrix-s3-epaper-4.2/rtc_pcf8563.cc`

---

## 14. Thông tin đã có / còn thiếu

### 14.1 — Đã có (đủ để tạo board config)

- [x] Bảng GPIO audio, I2C, BOOT, LED, USB (mục 2)
- [x] Sơ đồ kết nối phần cứng (ảnh)
- [x] ESP32-S3-N16R8 44-pin, 16MB Flash, 8MB PSRAM
- [x] INMP441, MAX98357A, loa 4Ω 2W
- [x] OLED SH1106 128×64 @ 0x3C
- [x] DS3231 @ 0x68 (chung I2C) — hardware only
- [x] COM11, Windows, repo path
- [x] Kỳ vọng: WiFi + AI voice + hiển thị giờ (sau)

### 14.2 — Còn thiếu (không chặn build v1)

| Hạng mục | Cần khi nào |
|---|---|
| Schematic chính thức PCB Lab (PDF) | Tùy chọn — đối chiếu sản xuất |
| Datasheet pinout chính thức 44-pin | Tùy chọn |
| ESP-IDF version (`idf.py --version`) | Ghi vào đây khi cài xong |
| IC mạch sạc pin (TP4056, …) | Khi làm % pin |
| GPIO ADC đọc pin | Khi làm % pin |
| DS3231 SQW/INT | Chỉ nếu dùng alarm/interrupt |
| Tài khoản XiaoZhi đã kích hoạt | Lần test server đầu tiên |

### 14.3 — Kỳ vọng tính năng

| Tính năng | Trạng thái |
|---|---|
| WiFi | Có |
| INMP441 + MAX98357A + loa | Có |
| OLED SH1106 | Có |
| AI voice (XiaoZhi server) | Có |
| DS3231 + hiển thị giờ | Phase 2 |
| % pin OLED | Phase 2 (cần ADC) |
| Wake word | Sau audio test |
| MCP | Tùy chọn |
| 4G | Không (chỉ WiFi) |

---

## 15. Checklist tiến độ

### Chuẩn bị

- [x] GPIO xác định từ sơ đồ
- [x] Đọc tài liệu này
- [ ] Cài ESP-IDF, ghi version
- [ ] Xác nhận COM11 khi cắm board

### Tạo board trong repo

- [x] `main/boards/pcb-lab-esp32s3-n16r8/config.h` (mục 2.5)
- [x] `config.json` (SH1106)
- [x] `pcb_lab_board.cc` (bỏ nút thừa, giữ BOOT + WS2812)
- [x] `Kconfig.projbuild` + `CMakeLists.txt`

### Build & Flash

- [ ] `python scripts/build.py pcb-lab-esp32s3-n16r8 ...`
- [ ] `idf.py -p COM11 flash monitor`
- [ ] Serial log OK

### Kiểm thử

- [ ] OLED + WS2812
- [ ] WiFi + activation
- [ ] Mic + loa + hội thoại AI

### Phase 2

- [ ] DS3231 driver + đồng hồ OLED
- [ ] % pin (sau khi có ADC)
- [ ] Wake word
- [ ] MCP (nếu cần)

---

## 16. Tài liệu tham khảo

| File | Nội dung |
|---|---|
| `docs/custom-board.md` | Hướng dẫn tạo board (EN) |
| `docs/websocket.md` | Giao thức server |
| `main/audio/README.md` | Kiến trúc audio |
| `main/boards/bread-compact-wifi/` | Board mẫu copy (GPIO audio/I2C trùng) |
| `main/led/single_led.cc` | WS2812 1 LED |

---

## Ghi chú cuối

1. **Nạp board `pcb-lab-esp32s3-n16r8`** — không nạp `wk-esp32s3-dev` (GPIO audio khác hoàn toàn).
2. Có thể tạm build `bread-compact-wifi` để **thử nhanh** vì GPIO audio/I2C/BOOT/LED **trùng** sơ đồ của bạn — nhưng vẫn nên dùng board riêng (bỏ nút 47/40/39 không tồn tại).
3. **AI trên server** — ESP32 là client thu/phát + hiển thị.
4. Bước tiếp: tạo file board trong repo → build → `idf.py -p COM11 flash monitor`.

---

*Cập nhật: GPIO xác nhận từ sơ đồ PCB Lab ESP32-S3-N16R8 44-pin.*
