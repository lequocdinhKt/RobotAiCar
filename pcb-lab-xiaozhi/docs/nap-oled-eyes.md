# Nạp firmware OLED mắt cảm xúc (ESP32-S3)

Hướng dẫn build + flash sau khi đã đổi `OledDisplay` sang **2 mắt cảm xúc** (blink + nhìn trái/phải).

Board: **PCB Lab ESP32-S3-N16R8** — OLED SH1106 128×64.

---

## 0. Debug chỉ OLED — bấm BOOT đổi khuôn mặt

Dùng khi muốn **chỉ test mắt**, không cần chat / WiFi.

Logic tách riêng:

| File | Vai trò |
|------|---------|
| `main/boards/pcb-lab-esp32s3-n16r8/eye_face_debug.h` | API `EyeFaceDebug::Next()` |
| `main/boards/pcb-lab-esp32s3-n16r8/eye_face_debug.cc` | Chuỗi emotion + SetEmotion / status |
| `main/boards/pcb-lab-esp32s3-n16r8/pcb_lab_board.cc` | BOOT gọi debug khi bật Kconfig |
| `sdkconfig.eye_face_debug` | Bật sẵn `CONFIG_PCB_LAB_EYE_FACE_DEBUG=y` |

### Bật debug

**Cách 1 — menuconfig**

```powershell
cd C:\Dinh\OneAi\pcb-lab-xiaozhi
idf.py menuconfig
```

`Xiaozhi Assistant` → bật **PCB Lab: OLED eye-face debug (BOOT cycles emotions)** → Save → Exit.

**Cách 2 — append sdkconfig**

```powershell
cd C:\Dinh\OneAi\pcb-lab-xiaozhi
Get-Content sdkconfig.eye_face_debug | Add-Content sdkconfig
```

(Nếu chưa có `sdkconfig`, build một lần trước rồi append.)

### Build + nạp

```powershell
python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8 --language vi-VN --wake-word disabled
idf.py -p COM11 flash monitor
```

### Cách dùng trên máy

1. Đợi OLED hiện 2 mắt.
2. Mỗi lần **bấm BOOT** → emotion kế tiếp (`neutral` → `happy` → `laughing` → …).
3. Status/notification hiện `Eye: happy (2/21)`.
4. Serial: `EyeFaceDebug: BOOT -> emotion 'happy' (2/21)`.

### Debug: chỉ khuôn mặt

Khi `CONFIG_PCB_LAB_EYE_FACE_DEBUG=y`:

- Ẩn icon WiFi/AI, đồng hồ, chat, notification
- Màn hình chỉ còn **2 mắt**
- Bấm BOOT → đổi emotion (log serial vẫn có tên emotion)

File: `eye_face_debug.cc` + `OledDisplay::SetFaceOnlyMode()`.


menuconfig → **tắt** option trên, hoặc trong `sdkconfig` đặt:

```
CONFIG_PCB_LAB_EYE_FACE_DEBUG=n
```

Rồi build + flash lại.

---

## 1. Chuẩn bị

| Mục | Giá trị |
|-----|---------|
| Repo | `C:\Dinh\OneAi\pcb-lab-xiaozhi` |
| ESP-IDF | ≥ 5.5.2 (đã cài + môi trường `export` / ESP-IDF PowerShell) |
| Cổng UART | thường **COM11** (USB-UART TX/RX GPIO43/44) |
| OLED | SH1106 @ I2C `0x3C` (SDA=41, SCL=42) |

Kiểm tra cổng trong Device Manager nếu không chắc COM mấy.

---

## 2. Mở môi trường ESP-IDF

Mở **ESP-IDF 5.x PowerShell** (hoặc CMD đã `export.bat`), rồi:

```powershell
cd C:\Dinh\OneAi\pcb-lab-xiaozhi
```

---

## 3. Build

### Cách A — `build.py` (khuyến nghị)

```powershell
python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8 --language vi-VN
```

Lần đầu có thể thêm `--wake-word disabled` nếu chỉ muốn test OLED:

```powershell
python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8 --language vi-VN --wake-word disabled
```

### Cách B — `idf.py`

```powershell
idf.py set-target esp32s3
idf.py menuconfig
```

Trong menuconfig:

- **Xiaozhi Assistant → Board Type → PCB Lab ESP32-S3-N16R8**
- **Xiaozhi Assistant → OLED Type → SH1106 128×64**

```powershell
idf.py build
```

Build OK khi thấy `Project build complete`.

---

## 4. Nạp (flash)

Cắm USB-UART (cổng COM, không phải USB-OTG).

```powershell
idf.py -p COM11 flash monitor
```

Đổi `COM11` thành cổng máy bạn.

### Nếu ESP32 không vào download mode

1. Giữ **BOOT (GPIO0)**
2. Nhấn **RESET** rồi thả
3. Thả **BOOT**
4. Chạy lại lệnh flash

### Erase toàn bộ (lần đầu / lỗi partition / WiFi cũ)

```powershell
idf.py -p COM11 erase-flash
idf.py -p COM11 flash monitor
```

Thoát monitor: `Ctrl + ]`

---

## 5. Kiểm tra mắt trên OLED

Sau khi boot:

1. Thanh status trên cùng vẫn hiện (WiFi / pin / trạng thái).
2. Phần giữa là **2 mắt** (không còn emoji mặt cười).
3. Idle: mắt tròn, **chớp** vài giây một lần, thỉnh thoảng **nhìn trái/phải**.
4. Khi app/`SetEmotion` đổi cảm xúc:
   - `happy` / `laughing` → mắt khép ngang
   - `sad` → mắt nhỏ, hạ xuống
   - `angry` → mắt xếch
   - `surprised` → mắt to
   - `sleepy` → mắt gần như nhắm
   - `thinking` → nhìn lệch
   - `winking` → một mắt nhắm

Log serial gợi ý:

```
I (xxxx) OledDisplay: OLED eye face UI ready (128x64)
I (xxxx) OledDisplay: Eye emotion: neutral
I (xxxx) OledDisplay: Eye emotion: happy
```

Nhấn **BOOT** để bắt đầu hội thoại → server có thể gửi emotion → mắt đổi theo.

---

## 6. File đã sửa

| File | Thay đổi |
|------|----------|
| `main/display/oled_display.h` | Thêm eye face + timer animation |
| `main/display/oled_display.cc` | Vẽ 2 mắt LVGL, blink, look L/R, map emotion |
| `docs/display.md` | Tổng quan kiến trúc display |
| `docs/nap-oled-eyes.md` | File này |

---

## 7. Lỗi thường gặp

| Hiện tượng | Cách xử lý |
|------------|------------|
| Không thấy cổng COM | Đổi cáp / driver CH340-CP2102; dùng cổng UART |
| Flash timeout | BOOT+RESET như mục 4; baud thấp hơn nếu cần |
| OLED trắng/đen không mắt | Kiểm tra SH1106 trong menuconfig; dây SDA/SCL 41/42 |
| Build thiếu component | `idf.py fullclean` rồi build lại trong thư mục `pcb-lab-xiaozhi` |
| Mắt không chớp | Xem log có `OLED eye face UI ready`; chắc đang build đúng repo này |

---

## 8. Tài liệu liên quan

- `docs/a-den-z.md` — wiring + build đầy đủ PCB Lab  
- `docs/display.md` — vì sao dùng mắt thay emoji, tham chiếu Otto/Emote  
- `main/boards/pcb-lab-esp32s3-n16r8/README.md` — tóm tắt board  

Khi chỉnh thêm cảm xúc: sửa `ResolveEmotion()` trong `oled_display.cc`.
