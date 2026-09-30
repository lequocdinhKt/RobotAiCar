#ifndef PCB_LAB_OLED_STATIC_TEST_H
#define PCB_LAB_OLED_STATIC_TEST_H

#include <esp_lcd_panel_ops.h>

/**
 * Minimal SH1106 isolation test (PCB Lab).
 * Draws ONE static frame via esp_lcd_panel_draw_bitmap — no LVGL, no UI, no animation.
 */
namespace OledStaticTest {

/** Draw once, log config, schedule flush-count check after 10s. */
void Run(esp_lcd_panel_handle_t panel);

/** How many times draw_bitmap was called from this test. */
unsigned FlushCount();

}  // namespace OledStaticTest

#endif  // PCB_LAB_OLED_STATIC_TEST_H
