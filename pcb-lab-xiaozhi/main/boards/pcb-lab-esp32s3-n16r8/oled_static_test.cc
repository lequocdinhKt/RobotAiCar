#include "oled_static_test.h"

#include "config.h"

#include <cstring>

#include <esp_log.h>
#include <esp_timer.h>

#define TAG "OledStaticTest"

namespace OledStaticTest {
namespace {

constexpr int kWidth = 128;
constexpr int kHeight = 64;
constexpr int kBufSize = kWidth * kHeight / 8;  // 1024

unsigned g_flush_count = 0;
esp_timer_handle_t g_report_timer = nullptr;

// SH1106 + this board: bit 1 = pixel lit (white), bit 0 = off (black).
void SetPixel(uint8_t* buf, int x, int y, bool lit) {
    if (x < 0 || x >= kWidth || y < 0 || y >= kHeight) {
        return;
    }
    uint8_t* p = buf + kWidth * (y >> 3) + x;
    const uint8_t mask = static_cast<uint8_t>(1u << (y & 7));
    if (lit) {
        *p |= mask;
    } else {
        *p &= static_cast<uint8_t>(~mask);
    }
}

void FillCircle(uint8_t* buf, int cx, int cy, int r, bool lit) {
    const int r2 = r * r;
    for (int y = cy - r; y <= cy + r; ++y) {
        for (int x = cx - r; x <= cx + r; ++x) {
            const int dx = x - cx;
            const int dy = y - cy;
            if (dx * dx + dy * dy <= r2) {
                SetPixel(buf, x, y, lit);
            }
        }
    }
}

void StrokeCircle(uint8_t* buf, int cx, int cy, int r, bool lit) {
    const int r2_outer = r * r;
    const int r_inner = r - 2;
    const int r2_inner = r_inner > 0 ? r_inner * r_inner : 0;
    for (int y = cy - r; y <= cy + r; ++y) {
        for (int x = cx - r; x <= cx + r; ++x) {
            const int dx = x - cx;
            const int dy = y - cy;
            const int d2 = dx * dx + dy * dy;
            if (d2 <= r2_outer && d2 >= r2_inner) {
                SetPixel(buf, x, y, lit);
            }
        }
    }
}

/** Tiny 3×5 glyphs: '0','1',':' — rows top→bottom, LSB = left. */
uint8_t Glyph3x5(char c, int row) {
    // Each value is 3 bits wide (columns 0..2).
    static constexpr uint8_t k0[5] = {0b111, 0b101, 0b101, 0b101, 0b111};
    static constexpr uint8_t k1[5] = {0b010, 0b110, 0b010, 0b010, 0b111};
    static constexpr uint8_t kColon[5] = {0b000, 0b010, 0b000, 0b010, 0b000};
    if (row < 0 || row > 4) {
        return 0;
    }
    switch (c) {
        case '0':
            return k0[row];
        case '1':
            return k1[row];
        case ':':
            return kColon[row];
        default:
            return 0;
    }
}

void DrawChar3x5(uint8_t* buf, int x, int y, char c, bool lit) {
    for (int row = 0; row < 5; ++row) {
        const uint8_t bits = Glyph3x5(c, row);
        for (int col = 0; col < 3; ++col) {
            if (bits & (1 << (2 - col))) {
                SetPixel(buf, x + col, y + row, lit);
            }
        }
    }
}

void DrawText3x5(uint8_t* buf, int x, int y, const char* text, bool lit) {
    int cx = x;
    for (const char* p = text; *p; ++p) {
        DrawChar3x5(buf, cx, y, *p, lit);
        cx += 4;  // 3px glyph + 1px gap
    }
}

/** Very small WiFi mark (arcs + center), ~7×7 at top-left. */
void DrawWifiIcon(uint8_t* buf, int x, int y, bool lit) {
    // Center dot
    SetPixel(buf, x + 3, y + 6, lit);
    // Inner arc
    SetPixel(buf, x + 2, y + 4, lit);
    SetPixel(buf, x + 3, y + 4, lit);
    SetPixel(buf, x + 4, y + 4, lit);
    SetPixel(buf, x + 1, y + 5, lit);
    SetPixel(buf, x + 5, y + 5, lit);
    // Outer arc
    SetPixel(buf, x + 1, y + 2, lit);
    SetPixel(buf, x + 2, y + 1, lit);
    SetPixel(buf, x + 3, y + 1, lit);
    SetPixel(buf, x + 4, y + 1, lit);
    SetPixel(buf, x + 5, y + 2, lit);
    SetPixel(buf, x + 0, y + 3, lit);
    SetPixel(buf, x + 6, y + 3, lit);
}

void DrawStaticDemo(uint8_t* buf) {
    // Black background (all pixels off).
    std::memset(buf, 0x00, kBufSize);

    // White face (lit pixels).
    StrokeCircle(buf, 64, 38, 20, true);
    FillCircle(buf, 55, 32, 3, true);   // left eye
    FillCircle(buf, 73, 32, 3, true);   // right eye
    for (int x = 54; x <= 74; ++x) {
        const int y = 46 + ((x - 64) * (x - 64)) / 90;
        SetPixel(buf, x, y, true);
        SetPixel(buf, x, y + 1, true);
    }

    // Tiny status chrome (demo only — no backend).
    DrawWifiIcon(buf, 2, 1, true);          // top-left
    DrawText3x5(buf, 108, 2, "10:00", true);  // top-right (~20px wide)
}

void ReportTimerCb(void* /*arg*/) {
    ESP_LOGI(TAG, "=== 10s CHECK ===");
    ESP_LOGI(TAG, "flush_count = %u (must stay 1 if static test is correct)", g_flush_count);
    if (g_flush_count == 1) {
        ESP_LOGI(TAG, "STATIC OLED TEST: no extra flushes after initial draw");
    } else {
        ESP_LOGW(TAG, "STATIC OLED TEST: unexpected flush_count=%u", g_flush_count);
    }
}

}  // namespace

unsigned FlushCount() { return g_flush_count; }

void Run(esp_lcd_panel_handle_t panel) {
    ESP_LOGI(TAG, "OLED INIT");
    ESP_LOGI(TAG, "Driver = SH1106");
    ESP_LOGI(TAG, "Resolution = %dx%d", kWidth, kHeight);
    ESP_LOGI(TAG, "I2C Address = 0x3C");
    ESP_LOGI(TAG, "SDA = GPIO%d", static_cast<int>(DISPLAY_SDA_PIN));
    ESP_LOGI(TAG, "SCL = GPIO%d", static_cast<int>(DISPLAY_SCL_PIN));
    ESP_LOGI(TAG, "I2C Frequency = %d", 400 * 1000);
    ESP_LOGI(TAG, "DISPLAY_HEIGHT compile-time = %d", DISPLAY_HEIGHT);
    ESP_LOGI(TAG, "Demo: black bg, white face, tiny WiFi + 10:00 (no backend)");

#if !defined(SH1106)
#error "PCB_LAB_OLED_STATIC_TEST requires CONFIG_OLED_SH1106_128X64 (SH1106)"
#endif
    if (DISPLAY_HEIGHT != 64 || DISPLAY_WIDTH != 128) {
        ESP_LOGE(TAG, "Bad resolution macros W=%d H=%d — expected 128x64", DISPLAY_WIDTH,
                 DISPLAY_HEIGHT);
    }

    // Do NOT call esp_lcd_panel_mirror(true,…): this SH1106 driver maps
    // mirror_x to color invert (A7), which would swap black/white.

    static uint8_t frame[kBufSize];
    DrawStaticDemo(frame);

    esp_err_t err = esp_lcd_panel_draw_bitmap(panel, 0, 0, kWidth, kHeight, frame);
    g_flush_count = 1;
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "draw_bitmap failed: %s", esp_err_to_name(err));
        return;
    }

    ESP_LOGI(TAG, "STATIC FRAME DRAWN");
    ESP_LOGI(TAG, "flush_count = %u", g_flush_count);
    ESP_LOGI(TAG, "No further OLED updates from this test. Observe for flicker.");

    if (g_report_timer == nullptr) {
        esp_timer_create_args_t args = {
            .callback = &ReportTimerCb,
            .arg = nullptr,
            .dispatch_method = ESP_TIMER_TASK,
            .name = "oled_static_chk",
            .skip_unhandled_events = true,
        };
        if (esp_timer_create(&args, &g_report_timer) == ESP_OK) {
            esp_timer_start_once(g_report_timer, 10 * 1000 * 1000ULL);
        }
    }
}

}  // namespace OledStaticTest
