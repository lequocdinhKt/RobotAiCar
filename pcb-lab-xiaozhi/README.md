# PCB Lab XiaoZhi (ESP32-S3-N16R8)

Bản standalone của [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32), cấu trúc giống repo gốc, **chỉ giữ board PCB Lab** + phần dùng chung để build/flash/chạy.

## Cấu trúc

```
pcb-lab-xiaozhi/
├── CMakeLists.txt
├── sdkconfig.defaults*
├── partitions/
├── scripts/
├── docs/                  # gồm a-den-z.md (hướng dẫn PCB Lab)
└── main/
    ├── application.*      # core app, audio, display, protocols...
    ├── audio/
    ├── display/
    ├── led/
    ├── protocols/
    ├── boards/
    │   ├── common/        # WifiBoard, Button, ...
    │   └── pcb-lab-esp32s3-n16r8/   # board của bạn
    ├── CMakeLists.txt
    ├── Kconfig.projbuild
    └── idf_component.yml  # dependencies (esp-sr, lvgl, sh1106, ...)
```

Không copy: `build/`, `managed_components/` (IDF tải lại khi build).

## Yêu cầu

- ESP-IDF ≥ 5.5.2 (đang dùng 6.1 OK)
- Board: ESP32-S3-N16R8 + INMP441 + MAX98357A + OLED SH1106

## Build & flash

```powershell
cd C:\Dinh\OneAi\pcb-lab-xiaozhi
. C:\esp\v6.1\esp-idf\export.ps1

python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8
idf.py -p COM13 flash monitor
```

Hoặc:

```powershell
idf.py set-target esp32s3
idf.py menuconfig   # Board Type → PCB Lab ESP32-S3-N16R8; OLED → SH1106
idf.py build
idf.py -p COM13 flash monitor
```

Chi tiết GPIO / kích hoạt / troubleshooting: `docs/a-den-z.md`.  
Tách AI / web / server / giọng nói: `docs/huongdantachrieng.md`.

## Lưu ý

- `xiaozhi-esp32` gốc vẫn độc lập; folder này là bản làm việc PCB Lab của bạn.
- Kconfig vẫn liệt kê nhiều board (từ upstream); chỉ có thư mục `pcb-lab-esp32s3-n16r8` — luôn chọn board đó khi build.
- Lần build đầu sẽ tải `managed_components` (có thể mất vài phút).
