# Display: từ emoji mặt cười → 2 mắt cảm xúc

Hướng dẫn chỉnh UI cảm xúc trên board **PCB Lab ESP32-S3** (OLED SH1106 128×64), dựa trên những gì **đã có sẵn** trong repo.

---

## 1. Hiện tại PCB Lab đang hiển thị gì?

Board dùng `OledDisplay` (LVGL), cảm xúc là **1 glyph emoji/font**, không phải 2 mắt chuyển động.

| File | Vai trò |
|------|---------|
| `main/boards/pcb-lab-esp32s3-n16r8/pcb_lab_board.cc` | Tạo `OledDisplay(...)` |
| `main/display/oled_display.cc` | Layout 128×64 / 128×32 + `SetEmotion()` |
| `main/display/oled_display.h` | `emotion_label_` = label LVGL |
| `main/application.cc` | Server/state gọi `display->SetEmotion("happy")` … |

Luồng cảm xúc:

```
Server / state machine
  → Application::SetEmotion("happy")
  → OledDisplay::SetEmotion()
  → noto_emoji_get_utf8() hoặc material_symbols
  → lv_label_set_text(emotion_label_, "😀")
```

Đoạn quan trọng:

```394:412:main/display/oled_display.cc
void OledDisplay::SetEmotion(const char* emotion) {
    // ...
    const char* utf8 = noto_emoji_get_utf8(emotion);
    // fallback Material Symbols
    lv_label_set_text(emotion_label_, utf8);  // ← emoji/font, không phải mắt
}
```

Trên OLED 128×64, emoji nằm **bên trái** (~32px), chat text bên phải — xem `SetupUI_128x64()`.

---

## 2. Trong repo đã có gì “giống 2 mắt cảm xúc”?

Có **3 hệ thống sẵn**, gần với ý bạn. **Không cái nào đang chạy trên PCB Lab OLED**, nhưng có thể học / tái dùng ý tưởng hoặc port.

### A. `esp_emote_expression` — đúng nghĩa “eye animation”

Component Espressif, đã nằm trong dependency:

- `espressif2022/esp_emote_expression`
- Wrapper firmware: `main/display/emote_display.cc` / `.h`
- Asset mẫu: `managed_components/espressif2022__esp_emote_assets/`
- Layout có object tên **`eye_anim`**

API chính:

```c
emote_set_anim_emoji(handle, "happy");  // phát animation lên mắt
```

Mapping cảm xúc (ví dụ VoCat):

`main/boards/espressif/esp-vocat/assets/360_360/emote.json`

| Emotion name | File animation |
|--------------|----------------|
| `happy`, `laughing`, `funny`… | `Happy.eaf` |
| `sad` | `Sad.eaf` |
| `angry` | `angry.eaf` |
| `sleepy` | `sleep.eaf` |
| `neutral` / `idle` | `winking.eaf` / `neutral.eaf` |
| … | … |

**Hạn chế với PCB Lab:**

- Menuconfig `USE_EMOTE_MESSAGE_STYLE` **chỉ** bật cho board: ESP-Box, ESP-VoCat, Lichuang S3, … — **không** gồm PCB Lab OLED.
- Emote engine kỳ vọng màn hình màu / độ phân giải lớn (vd 360×360), flush bitmap qua `esp_lcd_panel_draw_bitmap`.
- OLED SH1106 1-bit 128×64 không phải target thiết kế của engine này.

→ Dùng làm **tham chiếu API / tên emotion**, không gắn trực tiếp lên board OLED hiện tại.

### B. `otto-emoji-gif-component` — GIF 2 mắt hình học (rất gần ý bạn)

Component: `txp666/otto-emoji-gif-component`

- 21 GIF 240×240 đen-trắng, **hai mắt** thay đổi theo cảm xúc
- Tên file = đúng tên emotion Xiaozhi (`happy`, `sad`, `thinking`…)
- Generator vẽ mắt bằng code:

`managed_components/txp666__otto-emoji-gif-component/tools/generate_ai_emojis.py`

Script này vẽ:

- 2 ellipse / rounded eye
- chớp mắt, nghiêng, thu nhỏ, dịch pupil…
- theo từng emotion (`happy`, `sleepy`, `angry`…)

Đây là nguồn **gần nhất** với yêu cầu “2 con mắt di chuyển tạo nhiều cảm xúc”.

**Hạn chế OLED:**

- GIF 240×240 quá lớn cho 128×64
- `LcdDisplay` mới có pipeline GIF (`LvglGif` + `emoji_image_`); `OledDisplay` hiện chỉ có `emotion_label_`

### C. ESP-HI AAF eye pack

Trong `main/CMakeLists.txt` (board `ESP_HI`) tải các file kiểu:

- `blink_once.aaf`, `blink_quick.aaf`
- `happy_*.aaf`, `sad_*.aaf`, `left_*.aaf`, `right_*.aaf`…

Đây cũng là animation mắt, nhưng gắn image_player + board ESP-HI, không dùng cho PCB Lab.

---

## 3. Tên emotion chuẩn (giữ nguyên để server vẫn chạy)

Server gửi JSON `"emotion": "happy"`. **Đừng đổi tên**, chỉ đổi cách vẽ.

Danh sách thường gặp (Otto / emote.json / application):

| Name | Ý nghĩa gợi ý cho 2 mắt |
|------|-------------------------|
| `neutral` | 2 mắt tròn, nhìn thẳng, thỉnh thoảng blink |
| `happy` | mắt hơi khép / hình cung cười |
| `laughing` / `funny` | khép mạnh hơn, hơi rung |
| `sad` | mắt hẹp, nhìn xuống |
| `angry` | mắt xếch / nghiêng |
| `surprised` / `shocked` | mắt mở to |
| `sleepy` | nửa nhắm / nhắm dần |
| `thinking` / `confused` | mắt lệch / nhìn lệch một bên |
| `winking` | một mắt nhắm |
| `loving` | mắt “trái tim” hoặc sáng hơn |
| `embarrassed` | mắt nhỏ, nhìn xuống |

Ánh xạ hiện tại (emoji font) nằm trong Noto emoji / Material symbols — bạn sẽ **thay** bước map đó bằng hình mắt.

---

## 4. Hướng làm cho PCB Lab OLED (khuyến nghị)

Vì OLED 128×64 monochrome, chọn **một** trong 3 hướng:

### Hướng 1 — Vẽ 2 mắt bằng LVGL (nhẹ, realtime) ⭐ khuyến nghị

Thay `emotion_label_` bằng canvas / 2 object hình oval, animate bằng `lv_anim` / timer.

**File cần sửa:**

1. `main/display/oled_display.h` — thêm:
   - `lv_obj_t* left_eye_`, `right_eye_` (hoặc `lv_obj_t* face_canvas_`)
   - state emotion hiện tại
2. `main/display/oled_display.cc`
   - Trong `SetupUI_128x64()`: ẩn/bỏ emoji label ở `content_left_`, tạo 2 mắt
   - Viết lại `SetEmotion()`: map tên → tham số mắt (width, height, angle, offset pupil, blink)
3. (Tuỳ chọn) file mới `main/display/eye_face.cc` / `.h` để tách logic

**Gợi ý layout 128×64:**

```
┌────────────────────────────────┐
│ status bar (mạng, pin, …)      │  ~16 px
├──────────┬─────────────────────┤
│  ●    ●  │  chat / subtitle    │  content
│  mắt mắt │                     │
└──────────┴─────────────────────┘
   ~48–64 px     phần còn lại
```

Hoặc full-bleed mắt (ẩn chat khi idle) nếu muốn “mặt robot” sạch hơn.

**Tham số mỗi emotion (ví dụ):**

| Emotion | left/right size | offset Y | góc | blink |
|---------|-----------------|----------|-----|-------|
| neutral | 14×14 | 0 | 0° | mỗi 3–5s |
| happy | 16×8 | +2 | ±5° | ít |
| sad | 12×8 | +4 | 0° | chậm |
| angry | 14×10 | −2 | ±15° | không |
| sleepy | 16×4 | +6 | 0° | chậm nhắm |
| surprised | 18×18 | 0 | 0° | không |

Học cách vẽ từ `generate_ai_emojis.py` (`ellipse`, `rounded_eye`, `ring_eye`) rồi scale về tọa độ OLED.

### Hướng 2 — Bitmap / XBM từng emotion

Vẽ sẵn 1 ảnh 48×32 (2 mắt) cho mỗi emotion, embed C array, `lv_img_set_src`.

- Ưu: nhìn đẹp, kiểm soát pixel
- Nhược: nhiều asset, khó animate mượt (trừ khi nhiều frame)

Có thể export từ Otto GIF (resize 240→48) bằng script Python.

### Hướng 3 — Bật Emote style (không phù hợp PCB Lab hiện tại)

Chỉ khi bạn đổi sang **LCD màu** (ESP-Box / VoCat-like) và board nằm trong `depends on` của `USE_EMOTE_MESSAGE_STYLE`. Khi đó dùng `EmoteDisplay` + `.eaf` + `eye_anim` có sẵn — không cần tự vẽ mắt.

---

## 5. Checklist triển khai (Hướng 1)

1. **Giữ API** `Display::SetEmotion(const char*)` — Application / protocol không đổi.
2. Trong `SetupUI_128x64()`:
   - Tạo container mặt trong `content_left_`
   - 2 `lv_obj` hình ellipse (bg trắng trên nền đen OLED), hoặc 1 canvas
3. Viết `ApplyEyeEmotion(const char* name)`:
   - Lookup bảng tham số
   - `lv_obj_set_size` / `lv_obj_set_style_transform_rotation` / align
   - Start blink timer nếu cần
4. `SetEmotion()` gọi `ApplyEyeEmotion`, **không** gọi `noto_emoji_get_utf8` nữa (hoặc chỉ fallback).
5. Test từng state:
   - Idle → `neutral`
   - Listening / Speaking → server có thể gửi `thinking`, `happy`…
   - Alert → emotion trong `Application::Alert`
6. Power save: khi sleep, gọi `SetEmotion("sleepy")` (đã có sẵn ở một số path LVGL display).

---

## 6. File tham chiếu nhanh

| Mục đích | Đường dẫn |
|----------|-----------|
| UI OLED hiện tại | `main/display/oled_display.cc` |
| API Display | `main/display/display.h` |
| Emote wrapper (mắt .eaf) | `main/display/emote_display.cc` |
| Emote API | `managed_components/espressif2022__esp_emote_expression/` |
| Layout `eye_anim` | `managed_components/espressif2022__esp_emote_assets/360_360/layout.json` |
| Map emotion → eaf | `main/boards/espressif/esp-vocat/assets/360_360/emote.json` |
| Generator 2 mắt Otto | `managed_components/txp666__otto-emoji-gif-component/tools/generate_ai_emojis.py` |
| LCD GIF emotion | `main/display/lcd_display.cc` → `SetEmotion` |
| Gọi SetEmotion từ app | `main/application.cc` |
| Board PCB Lab | `main/boards/pcb-lab-esp32s3-n16r8/` |

---

## 7. Kết luận

| Câu hỏi | Trả lời |
|---------|---------|
| Repo đã có “2 mắt cảm xúc”? | **Có** — Emote `eye_anim` + Otto eye GIFs + script vẽ mắt |
| PCB Lab đang dùng cái đó? | **Không** — đang dùng emoji font trên OLED |
| Đổi emoji → 2 mắt trên OLED? | Sửa `OledDisplay::SetEmotion` + UI mắt LVGL (Hướng 1) |
| Chỉ rename emoji thành mắt? | **Không đủ** — cần thay cách render |
| Dùng nguyên Emote style trên SH1106? | **Không khuyến nghị** (board/Kconfig/độ phân giải không khớp) |

### Đã triển khai trên PCB Lab

- Code: `main/display/oled_display.cc` / `.h` — 2 mắt LVGL + blink + nhìn trái/phải.
- Nạp firmware: xem **`docs/nap-oled-eyes.md`**.
