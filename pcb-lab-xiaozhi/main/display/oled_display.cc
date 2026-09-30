#include "oled_display.h"
#include "assets/lang_config.h"
#include "lvgl_font.h"
#include "lvgl_theme.h"

#include <algorithm>
#include <cstring>
#include <string>

#include <esp_err.h>
#include <esp_log.h>
#include <esp_lvgl_port.h>
#include <esp_random.h>
#include <material_symbols.h>

#define TAG "OledDisplay"

LV_FONT_DECLARE(BUILTIN_TEXT_FONT);
LV_FONT_DECLARE(BUILTIN_ICON_FONT);
LV_FONT_DECLARE(font_material_symbols_30_1);
LV_FONT_DECLARE(font_noto_emoji_30_1);

namespace {

/** LVGL black → OLED lit (white). LVGL white → OLED off (black). */
void StyleOledInkBg(lv_obj_t* obj) {
    lv_obj_set_style_bg_opa(obj, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(obj, lv_color_white(), 0);
}

void StyleOledInkFg(lv_obj_t* obj) {
    lv_obj_set_style_text_color(obj, lv_color_black(), 0);
}

lv_obj_t* MakeEye(lv_obj_t* parent) {
    lv_obj_t* eye = lv_obj_create(parent);
    lv_obj_set_style_border_width(eye, 0, 0);
    lv_obj_set_style_pad_all(eye, 0, 0);
    lv_obj_set_style_bg_opa(eye, LV_OPA_COVER, 0);
    // Black LVGL → lit pixel → white stroke on black OLED background.
    lv_obj_set_style_bg_color(eye, lv_color_black(), 0);
    lv_obj_set_style_radius(eye, LV_RADIUS_CIRCLE, 0);
    lv_obj_clear_flag(eye, LV_OBJ_FLAG_SCROLLABLE);
    return eye;
}

}  // namespace

OledDisplay::OledDisplay(esp_lcd_panel_io_handle_t panel_io, esp_lcd_panel_handle_t panel,
                         int width, int height, bool mirror_x, bool mirror_y)
    : panel_io_(panel_io), panel_(panel) {
    width_ = width;
    height_ = height;

    auto text_font = std::make_shared<LvglBuiltInFont>(&BUILTIN_TEXT_FONT);
    auto icon_font = std::make_shared<LvglBuiltInFont>(&BUILTIN_ICON_FONT);
    auto large_icon_font = std::make_shared<LvglBuiltInFont>(&font_material_symbols_30_1);
    auto emoji_font = std::make_shared<LvglBuiltInFont>(&font_noto_emoji_30_1);

    auto dark_theme = new LvglTheme("dark");
    dark_theme->set_text_font(text_font);
    dark_theme->set_icon_font(icon_font);
    dark_theme->set_large_icon_font(large_icon_font);
    dark_theme->set_emoji_font(emoji_font);

    auto& theme_manager = LvglThemeManager::GetInstance();
    theme_manager.RegisterTheme("dark", dark_theme);
    current_theme_ = dark_theme;

    // OLED over I2C is slow; default LVGL tick (5ms) + ~30 FPS refresh can flicker.
    // Cap at ~15 FPS so SH1106 has time to finish each frame.
    static constexpr int kOledFps = 15;
    static constexpr int kOledPeriodMs = 1000 / kOledFps;  // 66–67 ms

    ESP_LOGI(TAG, "Initialize LVGL (OLED ~%d FPS)", kOledFps);
    lvgl_port_cfg_t port_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    port_cfg.task_priority = 1;
    port_cfg.task_stack = 6144;
    port_cfg.timer_period_ms = kOledPeriodMs;
#if CONFIG_SOC_CPU_CORES_NUM > 1
    port_cfg.task_affinity = 1;
#endif
    lvgl_port_init(&port_cfg);

    ESP_LOGI(TAG, "Adding OLED display");
    const lvgl_port_display_cfg_t display_cfg = {
        .io_handle = panel_io_,
        .panel_handle = panel_,
        .control_handle = nullptr,
        .buffer_size = static_cast<uint32_t>(width_ * height_),
        .double_buffer = false,
        .trans_size = 0,
        .hres = static_cast<uint32_t>(width_),
        .vres = static_cast<uint32_t>(height_),
        .monochrome = true,
        .rotation =
            {
                .swap_xy = false,
                .mirror_x = mirror_x,
                .mirror_y = mirror_y,
            },
        .flags =
            {
                .buff_dma = 1,
                .buff_spiram = 0,
                .sw_rotate = 0,
                .full_refresh = 0,
                .direct_mode = 0,
            },
    };

    display_ = lvgl_port_add_disp(&display_cfg);
    if (display_ == nullptr) {
        ESP_LOGE(TAG, "Failed to add display");
        return;
    }

    // Override LVGL display refresh period (default LV_DEF_REFR_PERIOD ≈ 33 ms / 30 FPS).
    if (lv_timer_t* refr = lv_display_get_refr_timer(display_); refr != nullptr) {
        lv_timer_set_period(refr, kOledPeriodMs);
    }
}

void OledDisplay::SetupUI() {
    if (setup_ui_called_) {
        ESP_LOGW(TAG, "SetupUI() called multiple times, skipping duplicate call");
        return;
    }

    Display::SetupUI();
    if (height_ == 64) {
        SetupUI_128x64();
    } else {
        SetupUI_128x32();
    }

#if CONFIG_PCB_LAB_EYE_FACE_DEBUG || CONFIG_PCB_LAB_OLED_STATIC_TEST
    // Demo UI: animated eyes + tiny WiFi / sample clock (no backend).
    SetFaceOnlyMode(true);
    SetEmotion("neutral");
#endif
}

OledDisplay::~OledDisplay() {
    StopEyeAnimation();

    if (content_ != nullptr) {
        lv_obj_del(content_);
    }

    bool is_128x64_layout = (top_bar_ != nullptr);
    if (status_bar_ != nullptr && is_128x64_layout) {
        status_label_ = nullptr;
        notification_label_ = nullptr;
        lv_obj_del(status_bar_);
    }
    if (top_bar_ != nullptr) {
        network_label_ = nullptr;
        mute_label_ = nullptr;
        battery_label_ = nullptr;
        lv_obj_del(top_bar_);
    }
    if (side_bar_ != nullptr) {
        if (!is_128x64_layout) {
            status_label_ = nullptr;
            notification_label_ = nullptr;
            network_label_ = nullptr;
            mute_label_ = nullptr;
            battery_label_ = nullptr;
        }
        lv_obj_del(side_bar_);
    }
    if (container_ != nullptr) {
        lv_obj_del(container_);
    }

    if (panel_ != nullptr) {
        esp_lcd_panel_del(panel_);
    }
    if (panel_io_ != nullptr) {
        esp_lcd_panel_io_del(panel_io_);
    }
    lvgl_port_deinit();
}

bool OledDisplay::Lock(int timeout_ms) { return lvgl_port_lock(timeout_ms); }

void OledDisplay::Unlock() { lvgl_port_unlock(); }

OledDisplay::EyeStyle OledDisplay::ResolveEmotion(const char* emotion) const {
    EyeStyle style;
    if (emotion == nullptr || emotion[0] == '\0') {
        return style;
    }

    auto set = [&](int16_t w, int16_t h, int16_t oy, int16_t ang, int16_t look = 0,
                   bool close_l = false, bool close_r = false) {
        style.width = w;
        style.height = h;
        style.offset_y = oy;
        style.angle_deg = ang;
        style.look_bias = look;
        style.close_left = close_l;
        style.close_right = close_r;
    };

    // Two-dot face: each emotion reshapes / offsets the pair of ovals.
    if (strcmp(emotion, "happy") == 0) {
        set(26, 10, 2, 10);
    } else if (strcmp(emotion, "delicious") == 0) {
        // Soft squint + look slightly up
        set(24, 8, -2, 8);
    } else if (strcmp(emotion, "relaxed") == 0) {
        set(22, 12, 3, 4);
    } else if (strcmp(emotion, "laughing") == 0) {
        set(28, 5, 4, 14);
    } else if (strcmp(emotion, "funny") == 0) {
        // Uneven squint
        set(24, 8, 2, 12);
        style.height_l = 6;
        style.height_r = 10;
        style.offset_y_r = 2;
    } else if (strcmp(emotion, "sad") == 0) {
        set(18, 10, 6, -8);
    } else if (strcmp(emotion, "crying") == 0) {
        set(14, 8, 8, -10);
        style.look_bias = 0;
        style.gap = 22;
    } else if (strcmp(emotion, "angry") == 0) {
        set(22, 12, -3, 20);
        style.gap = 14;
    } else if (strcmp(emotion, "surprised") == 0) {
        set(24, 24, 0, 0);
    } else if (strcmp(emotion, "shocked") == 0) {
        set(28, 28, -2, 0);
        style.gap = 12;
    } else if (strcmp(emotion, "sleepy") == 0) {
        set(26, 3, 6, 0);
    } else if (strcmp(emotion, "thinking") == 0) {
        set(16, 16, 0, 0, -1);
        style.offset_y_r = -2;
    } else if (strcmp(emotion, "confused") == 0) {
        // One brow higher
        set(18, 16, 0, 0, 1);
        style.use_abs_angle = true;
        style.angle_l = -12;
        style.angle_r = 18;
        style.offset_y_l = -4;
        style.offset_y_r = 3;
        style.height_l = 18;
        style.height_r = 12;
    } else if (strcmp(emotion, "winking") == 0) {
        set(22, 18, 0, 0, 0, false, true);
        style.angle_deg = 6;
    } else if (strcmp(emotion, "loving") == 0) {
        // Soft heart-ish ovals
        set(20, 16, 1, 0);
        style.use_abs_angle = true;
        style.angle_l = 18;
        style.angle_r = -18;
        style.gap = 10;
    } else if (strcmp(emotion, "kissy") == 0) {
        set(24, 4, 2, 6, 0, true, true);
    } else if (strcmp(emotion, "embarrassed") == 0) {
        set(12, 12, 5, 0);
        style.look_bias = 1;
        style.gap = 24;
    } else if (strcmp(emotion, "confident") == 0) {
        set(24, 11, 0, 8);
    } else if (strcmp(emotion, "cool") == 0) {
        // Smug: right eye thinner
        set(22, 12, 0, 4, 1);
        style.height_r = 6;
        style.width_r = 24;
    } else if (strcmp(emotion, "silly") == 0) {
        set(20, 20, 0, 0, 1);
        style.use_abs_angle = true;
        style.angle_l = 25;
        style.angle_r = -8;
        style.width_l = 16;
        style.height_l = 22;
        style.width_r = 24;
        style.height_r = 14;
        style.offset_y_l = -3;
        style.offset_y_r = 4;
    } else if (strcmp(emotion, "scared") == 0) {
        set(12, 12, -4, 0);
        style.gap = 26;
    } else if (strcmp(emotion, "bored") == 0) {
        set(24, 6, 2, 0);
        style.look_bias = 1;
    } else if (strcmp(emotion, "focused") == 0) {
        set(14, 14, 0, 0);
        style.gap = 10;
    } else if (strcmp(emotion, "excited") == 0) {
        set(18, 26, -3, 0);
        style.gap = 16;
    } else if (strcmp(emotion, "shy") == 0) {
        set(14, 10, 6, 4, -1);
        style.gap = 20;
    } else if (strcmp(emotion, "skeptical") == 0) {
        set(20, 10, 0, 0, 1);
        style.use_abs_angle = true;
        style.angle_l = -16;
        style.angle_r = 4;
        style.offset_y_l = -5;
        style.height_l = 12;
        style.height_r = 8;
    } else if (strcmp(emotion, "dizzy") == 0) {
        set(18, 18, 0, 0);
        style.use_abs_angle = true;
        style.angle_l = 35;
        style.angle_r = 35;
        style.look_bias = -1;
    } else if (strcmp(emotion, "dead") == 0) {
        // Flat X-ish bars
        set(22, 3, 0, 0);
        style.use_abs_angle = true;
        style.angle_l = 30;
        style.angle_r = -30;
    } else if (strcmp(emotion, "left_wink") == 0) {
        set(22, 18, 0, 0, 0, true, false);
        style.angle_deg = 6;
    } else if (strcmp(emotion, "narrow") == 0) {
        set(28, 8, 0, 0);
        style.gap = 8;
    } else if (strcmp(emotion, "wide") == 0) {
        set(16, 16, 0, 0);
        style.gap = 36;
    } else {
        // neutral / idle / robot_2 / unknown
        set(20, 20, 0, 0);
    }

    // Scale down slightly for 128x32
    if (height_ <= 32) {
        auto scale = [](int16_t& v) {
            if (v != 0) {
                v = static_cast<int16_t>(v * 0.55f);
            }
        };
        scale(style.width);
        scale(style.height);
        scale(style.width_l);
        scale(style.height_l);
        scale(style.width_r);
        scale(style.height_r);
        scale(style.gap);
        style.offset_y = static_cast<int16_t>(style.offset_y * 0.55f);
        style.offset_y_l = static_cast<int16_t>(style.offset_y_l * 0.55f);
        style.offset_y_r = static_cast<int16_t>(style.offset_y_r * 0.55f);
        if (style.width < 6) {
            style.width = 6;
        }
        if (style.height < 2) {
            style.height = 2;
        }
    }
    return style;
}

void OledDisplay::CreateEyeFace(lv_obj_t* parent, int face_w, int face_h) {
    face_ = lv_obj_create(parent);
    lv_obj_set_size(face_, face_w, face_h);
    lv_obj_set_style_bg_opa(face_, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(face_, 0, 0);
    lv_obj_set_style_pad_all(face_, 0, 0);
    lv_obj_set_style_radius(face_, 0, 0);
    lv_obj_clear_flag(face_, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_center(face_);

    left_eye_ = MakeEye(face_);
    right_eye_ = MakeEye(face_);

    eye_face_enabled_ = true;
    eye_style_ = ResolveEmotion("neutral");
    look_dir_ = 0;
    blinking_ = false;
    ApplyEyeGeometry();
    StartEyeAnimation();
}

void OledDisplay::ApplyEyeGeometry() {
    if (!eye_face_enabled_ || left_eye_ == nullptr || right_eye_ == nullptr || face_ == nullptr) {
        return;
    }

    int16_t gap = eye_style_.gap > 0 ? eye_style_.gap : ((height_ <= 32) ? 10 : 18);
    int16_t look_x = look_dir_ * ((height_ <= 32) ? 3 : 6);
    if (eye_style_.look_bias != 0 && look_dir_ == 0) {
        look_x = eye_style_.look_bias * ((height_ <= 32) ? 4 : 8);
    }

    auto place_eye = [&](lv_obj_t* eye, bool is_left) {
        int16_t w = is_left
                        ? (eye_style_.width_l > 0 ? eye_style_.width_l : eye_style_.width)
                        : (eye_style_.width_r > 0 ? eye_style_.width_r : eye_style_.width);
        int16_t h = is_left
                        ? (eye_style_.height_l > 0 ? eye_style_.height_l : eye_style_.height)
                        : (eye_style_.height_r > 0 ? eye_style_.height_r : eye_style_.height);

        const bool closed =
            blinking_ || (is_left ? eye_style_.close_left : eye_style_.close_right);
        if (closed) {
            h = (height_ <= 32) ? 2 : 3;
            w = static_cast<int16_t>(w * 1.1f);
        }

        lv_obj_set_size(eye, w, h);
        // Radius: circle for round, half-height for squint
        lv_coord_t radius = (h >= w) ? (w / 2) : (h / 2);
        if (radius < 1) {
            radius = 1;
        }
        lv_obj_set_style_radius(eye, radius, 0);

        int16_t angle;
        if (eye_style_.use_abs_angle) {
            angle = is_left ? eye_style_.angle_l : eye_style_.angle_r;
        } else {
            angle = is_left ? -eye_style_.angle_deg : eye_style_.angle_deg;
        }
        lv_obj_set_style_transform_rotation(eye, angle * 10, 0);
        lv_obj_set_style_transform_pivot_x(eye, w / 2, 0);
        lv_obj_set_style_transform_pivot_y(eye, h / 2, 0);

        const int16_t center_x = (is_left ? -(gap / 2 + w / 2) : (gap / 2 + w / 2)) + look_x;
        const int16_t center_y =
            eye_style_.offset_y + (is_left ? eye_style_.offset_y_l : eye_style_.offset_y_r);
        lv_obj_align(eye, LV_ALIGN_CENTER, center_x, center_y);
    };

    place_eye(left_eye_, true);
    place_eye(right_eye_, false);
}

void OledDisplay::EyeAnimTimerCallback(void* arg) {
    auto* self = static_cast<OledDisplay*>(arg);
    if (self == nullptr || !self->eye_face_enabled_) {
        return;
    }

    DisplayLockGuard lock(self);
    self->anim_phase_++;

    // Blink every ~3–5 seconds (timer fires every 200ms)
    if (self->blinking_) {
        self->blinking_ = false;
        self->ApplyEyeGeometry();
    } else {
        // Random blink window
        uint32_t r = esp_random();
        if ((self->anim_phase_ % 18) == 0 || (r % 40) == 0) {
            // Skip blink while already closed by emotion (sleepy / wink)
            if (!(self->eye_style_.height <= 5 || self->eye_style_.close_left ||
                  self->eye_style_.close_right)) {
                self->blinking_ = true;
                self->ApplyEyeGeometry();
                return;
            }
        }
    }

    // Idle look left / center / right every ~2s for neutral-like emotions
    if ((self->anim_phase_ % 10) == 0) {
        const bool allow_wander =
            self->eye_style_.look_bias == 0 && !self->eye_style_.close_left &&
            !self->eye_style_.close_right && self->eye_style_.height > 6;
        if (allow_wander) {
            int next = static_cast<int>(esp_random() % 3) - 1;  // -1,0,1
            self->look_dir_ = static_cast<int8_t>(next);
            self->ApplyEyeGeometry();
        } else if (self->look_dir_ != 0 && self->eye_style_.look_bias != 0) {
            self->look_dir_ = 0;
            self->ApplyEyeGeometry();
        }
    }
}

void OledDisplay::StartEyeAnimation() {
    if (eye_anim_timer_ != nullptr) {
        return;
    }
    esp_timer_create_args_t args = {
        .callback = &OledDisplay::EyeAnimTimerCallback,
        .arg = this,
        .dispatch_method = ESP_TIMER_TASK,
        .name = "oled_eyes",
        .skip_unhandled_events = true,
    };
    if (esp_timer_create(&args, &eye_anim_timer_) == ESP_OK) {
        esp_timer_start_periodic(eye_anim_timer_, 200 * 1000);  // 200 ms
    }
}

void OledDisplay::StopEyeAnimation() {
    if (eye_anim_timer_ != nullptr) {
        esp_timer_stop(eye_anim_timer_);
        esp_timer_delete(eye_anim_timer_);
        eye_anim_timer_ = nullptr;
    }
}

void OledDisplay::SetChatMessage(const char* role, const char* content) {
    DisplayLockGuard lock(this);
    if (chat_message_label_ == nullptr) {
        return;
    }

    std::string content_str = content ? content : "";
    std::replace(content_str.begin(), content_str.end(), '\n', ' ');

    lv_anim_delete(chat_message_label_, nullptr);
    if (content == nullptr || content[0] == '\0') {
        lv_label_set_text(chat_message_label_, "");
        if (content_right_ != nullptr) {
            lv_obj_add_flag(content_right_, LV_OBJ_FLAG_HIDDEN);
        } else {
            lv_obj_add_flag(chat_message_label_, LV_OBJ_FLAG_HIDDEN);
        }
        return;
    }

    lv_label_set_text(chat_message_label_, content_str.c_str());
    if (content_right_ != nullptr) {
        lv_obj_remove_flag(content_right_, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_remove_flag(chat_message_label_, LV_OBJ_FLAG_HIDDEN);
    }
    (void)role;
}

void OledDisplay::SetupUI_128x64() {
    DisplayLockGuard lock(this);

    auto lvgl_theme = static_cast<LvglTheme*>(current_theme_);
    auto text_font = lvgl_theme->text_font()->font();
    auto icon_font = lvgl_theme->icon_font()->font();

    auto screen = lv_screen_active();
    // White LVGL bg → black OLED; black text/eyes → white strokes.
    StyleOledInkBg(screen);
    StyleOledInkFg(screen);
    lv_obj_set_style_text_font(screen, text_font, 0);

    container_ = lv_obj_create(screen);
    lv_obj_set_size(container_, LV_HOR_RES, LV_VER_RES);
    lv_obj_set_flex_flow(container_, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_all(container_, 0, 0);
    lv_obj_set_style_border_width(container_, 0, 0);
    lv_obj_set_style_pad_row(container_, 0, 0);
    StyleOledInkBg(container_);

    /* Top bar – status icons */
    top_bar_ = lv_obj_create(container_);
    lv_obj_set_size(top_bar_, LV_HOR_RES, 12);
    lv_obj_set_style_radius(top_bar_, 0, 0);
    lv_obj_set_style_border_width(top_bar_, 0, 0);
    lv_obj_set_style_pad_all(top_bar_, 0, 0);
    lv_obj_set_flex_flow(top_bar_, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(top_bar_, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);
    lv_obj_set_scrollbar_mode(top_bar_, LV_SCROLLBAR_MODE_OFF);
    StyleOledInkBg(top_bar_);

    network_label_ = lv_label_create(top_bar_);
    lv_label_set_text(network_label_, "");
    lv_obj_set_style_text_font(network_label_, icon_font, 0);
    StyleOledInkFg(network_label_);

    lv_obj_t* right_icons = lv_obj_create(top_bar_);
    lv_obj_set_size(right_icons, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_style_bg_opa(right_icons, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(right_icons, 0, 0);
    lv_obj_set_style_pad_all(right_icons, 0, 0);
    lv_obj_set_flex_flow(right_icons, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(right_icons, LV_FLEX_ALIGN_END, LV_FLEX_ALIGN_CENTER,
                          LV_FLEX_ALIGN_CENTER);

    mute_label_ = lv_label_create(right_icons);
    lv_label_set_text(mute_label_, "");
    lv_obj_set_style_text_font(mute_label_, icon_font, 0);
    StyleOledInkFg(mute_label_);

    battery_label_ = lv_label_create(right_icons);
    lv_label_set_text(battery_label_, "");
    lv_obj_set_style_text_font(battery_label_, text_font, 0);
    StyleOledInkFg(battery_label_);

    /* Status text overlay on top bar */
    status_bar_ = lv_obj_create(screen);
    lv_obj_set_size(status_bar_, LV_HOR_RES, 12);
    lv_obj_set_style_radius(status_bar_, 0, 0);
    lv_obj_set_style_bg_opa(status_bar_, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(status_bar_, 0, 0);
    lv_obj_set_style_pad_all(status_bar_, 0, 0);
    lv_obj_set_scrollbar_mode(status_bar_, LV_SCROLLBAR_MODE_OFF);
    lv_obj_set_style_layout(status_bar_, LV_LAYOUT_NONE, 0);
    lv_obj_align(status_bar_, LV_ALIGN_TOP_MID, 0, 0);

    notification_label_ = lv_label_create(status_bar_);
    lv_obj_set_width(notification_label_, LV_HOR_RES);
    lv_obj_set_style_text_align(notification_label_, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(notification_label_, "");
    lv_obj_align(notification_label_, LV_ALIGN_CENTER, 0, 0);
    lv_obj_add_flag(notification_label_, LV_OBJ_FLAG_HIDDEN);
    StyleOledInkFg(notification_label_);

    status_label_ = lv_label_create(status_bar_);
    lv_obj_set_width(status_label_, LV_HOR_RES * 0.7);
    lv_label_set_long_mode(status_label_, LV_LABEL_LONG_CLIP);
    lv_obj_set_style_text_align(status_label_, LV_TEXT_ALIGN_CENTER, 0);
    lv_label_set_text(status_label_, Lang::Strings::INITIALIZING);
    lv_obj_align(status_label_, LV_ALIGN_CENTER, 0, 0);
    StyleOledInkFg(status_label_);

    /* Face content – full remaining height */
    content_ = lv_obj_create(container_);
    lv_obj_set_scrollbar_mode(content_, LV_SCROLLBAR_MODE_OFF);
    lv_obj_set_style_radius(content_, 0, 0);
    lv_obj_set_style_pad_all(content_, 0, 0);
    lv_obj_set_style_border_width(content_, 0, 0);
    lv_obj_set_width(content_, LV_HOR_RES);
    lv_obj_set_flex_grow(content_, 1);
    lv_obj_set_style_layout(content_, LV_LAYOUT_NONE, 0);
    StyleOledInkBg(content_);

    CreateEyeFace(content_, LV_HOR_RES, 48);

    /* Chat subtitle at bottom of face area */
    content_right_ = lv_obj_create(content_);
    lv_obj_set_size(content_right_, LV_HOR_RES, 14);
    lv_obj_set_style_pad_all(content_right_, 0, 0);
    lv_obj_set_style_border_width(content_right_, 0, 0);
    lv_obj_set_style_bg_opa(content_right_, LV_OPA_TRANSP, 0);
    lv_obj_align(content_right_, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_add_flag(content_right_, LV_OBJ_FLAG_HIDDEN);

    chat_message_label_ = lv_label_create(content_right_);
    lv_label_set_text(chat_message_label_, "");
    lv_label_set_long_mode(chat_message_label_, LV_LABEL_LONG_SCROLL_CIRCULAR);
    lv_obj_set_style_text_align(chat_message_label_, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_width(chat_message_label_, LV_HOR_RES - 4);
    lv_obj_center(chat_message_label_);
    StyleOledInkFg(chat_message_label_);

    static lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_delay(&a, 1000);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_obj_set_style_anim(chat_message_label_, &a, LV_PART_MAIN);
    lv_obj_set_style_anim_duration(chat_message_label_, lv_anim_speed_clamped(60, 300, 60000),
                                   LV_PART_MAIN);

    low_battery_popup_ = lv_obj_create(screen);
    lv_obj_set_scrollbar_mode(low_battery_popup_, LV_SCROLLBAR_MODE_OFF);
    lv_obj_set_size(low_battery_popup_, LV_HOR_RES * 0.9, text_font->line_height * 2);
    lv_obj_align(low_battery_popup_, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_set_style_bg_color(low_battery_popup_, lv_color_black(), 0);
    lv_obj_set_style_radius(low_battery_popup_, 10, 0);
    low_battery_label_ = lv_label_create(low_battery_popup_);
    lv_label_set_text(low_battery_label_, Lang::Strings::BATTERY_NEED_CHARGE);
    lv_obj_set_style_text_color(low_battery_label_, lv_color_white(), 0);
    lv_obj_center(low_battery_label_);
    lv_obj_add_flag(low_battery_popup_, LV_OBJ_FLAG_HIDDEN);

    ESP_LOGI(TAG, "OLED eye face UI ready (128x64, black bg / white ink)");
}

void OledDisplay::SetupUI_128x32() {
    DisplayLockGuard lock(this);

    auto lvgl_theme = static_cast<LvglTheme*>(current_theme_);
    auto text_font = lvgl_theme->text_font()->font();
    auto icon_font = lvgl_theme->icon_font()->font();

    auto screen = lv_screen_active();
    lv_obj_set_style_text_font(screen, text_font, 0);
    lv_obj_set_style_text_color(screen, lv_color_black(), 0);

    container_ = lv_obj_create(screen);
    lv_obj_set_size(container_, LV_HOR_RES, LV_VER_RES);
    lv_obj_set_flex_flow(container_, LV_FLEX_FLOW_ROW);
    lv_obj_set_style_pad_all(container_, 0, 0);
    lv_obj_set_style_border_width(container_, 0, 0);
    lv_obj_set_style_pad_column(container_, 0, 0);

    /* Eyes on the left */
    content_ = lv_obj_create(container_);
    lv_obj_set_size(content_, 56, 32);
    lv_obj_set_style_pad_all(content_, 0, 0);
    lv_obj_set_style_border_width(content_, 0, 0);
    lv_obj_set_style_radius(content_, 0, 0);
    lv_obj_set_style_layout(content_, LV_LAYOUT_NONE, 0);
    lv_obj_set_scrollbar_mode(content_, LV_SCROLLBAR_MODE_OFF);

    CreateEyeFace(content_, 56, 32);

    /* Right side status + chat */
    side_bar_ = lv_obj_create(container_);
    lv_obj_set_size(side_bar_, width_ - 56, 32);
    lv_obj_set_flex_flow(side_bar_, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_all(side_bar_, 0, 0);
    lv_obj_set_style_border_width(side_bar_, 0, 0);
    lv_obj_set_style_radius(side_bar_, 0, 0);
    lv_obj_set_style_pad_row(side_bar_, 0, 0);

    status_bar_ = lv_obj_create(side_bar_);
    lv_obj_set_size(status_bar_, width_ - 56, 16);
    lv_obj_set_style_radius(status_bar_, 0, 0);
    lv_obj_set_flex_flow(status_bar_, LV_FLEX_FLOW_ROW);
    lv_obj_set_style_pad_all(status_bar_, 0, 0);
    lv_obj_set_style_border_width(status_bar_, 0, 0);
    lv_obj_set_style_pad_column(status_bar_, 0, 0);

    status_label_ = lv_label_create(status_bar_);
    lv_obj_set_flex_grow(status_label_, 1);
    lv_obj_set_style_pad_left(status_label_, 2, 0);
    lv_label_set_text(status_label_, Lang::Strings::INITIALIZING);

    notification_label_ = lv_label_create(status_bar_);
    lv_obj_set_flex_grow(notification_label_, 1);
    lv_obj_set_style_pad_left(notification_label_, 2, 0);
    lv_label_set_text(notification_label_, "");
    lv_obj_add_flag(notification_label_, LV_OBJ_FLAG_HIDDEN);

    mute_label_ = lv_label_create(status_bar_);
    lv_label_set_text(mute_label_, "");
    lv_obj_set_style_text_font(mute_label_, icon_font, 0);

    network_label_ = lv_label_create(status_bar_);
    lv_label_set_text(network_label_, "");
    lv_obj_set_style_text_font(network_label_, icon_font, 0);

    battery_label_ = lv_label_create(status_bar_);
    lv_label_set_text(battery_label_, "");
    lv_obj_set_style_text_font(battery_label_, icon_font, 0);

    chat_message_label_ = lv_label_create(side_bar_);
    lv_obj_set_size(chat_message_label_, width_ - 56, LV_SIZE_CONTENT);
    lv_obj_set_style_pad_left(chat_message_label_, 2, 0);
    lv_label_set_long_mode(chat_message_label_, LV_LABEL_LONG_SCROLL_CIRCULAR);
    lv_label_set_text(chat_message_label_, "");

    static lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_delay(&a, 1000);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_obj_set_style_anim(chat_message_label_, &a, LV_PART_MAIN);
    lv_obj_set_style_anim_duration(chat_message_label_, lv_anim_speed_clamped(60, 300, 60000),
                                   LV_PART_MAIN);

    ESP_LOGI(TAG, "OLED eye face UI ready (128x32)");
}

void OledDisplay::ApplyFaceOnlyLayout() {
    // Demo chrome: tiny WiFi (left) + sample "10:00" (right), animated eyes below.
    // Hide chat / status overlay / mute — keep top_bar visible.

    if (top_bar_ != nullptr) {
        lv_obj_remove_flag(top_bar_, LV_OBJ_FLAG_HIDDEN);
        lv_obj_set_height(top_bar_, 12);
        StyleOledInkBg(top_bar_);
    }
    if (status_bar_ != nullptr) {
        lv_obj_add_flag(status_bar_, LV_OBJ_FLAG_HIDDEN);
    }
    if (content_right_ != nullptr) {
        lv_obj_add_flag(content_right_, LV_OBJ_FLAG_HIDDEN);
    }
    if (chat_message_label_ != nullptr) {
        lv_obj_add_flag(chat_message_label_, LV_OBJ_FLAG_HIDDEN);
        lv_label_set_text(chat_message_label_, "");
    }
    if (status_label_ != nullptr) {
        lv_label_set_text(status_label_, "");
    }
    if (notification_label_ != nullptr) {
        lv_label_set_text(notification_label_, "");
        lv_obj_add_flag(notification_label_, LV_OBJ_FLAG_HIDDEN);
    }
    if (mute_label_ != nullptr) {
        lv_label_set_text(mute_label_, "");
        lv_obj_add_flag(mute_label_, LV_OBJ_FLAG_HIDDEN);
    }
    if (side_bar_ != nullptr) {
        lv_obj_add_flag(side_bar_, LV_OBJ_FLAG_HIDDEN);
    }

    // Demo-only markers (no backend).
    if (network_label_ != nullptr) {
        lv_obj_remove_flag(network_label_, LV_OBJ_FLAG_HIDDEN);
        lv_label_set_text(network_label_, MATERIAL_SYMBOLS_WIFI);
        StyleOledInkFg(network_label_);
    }
    if (battery_label_ != nullptr) {
        lv_obj_remove_flag(battery_label_, LV_OBJ_FLAG_HIDDEN);
        auto lvgl_theme = static_cast<LvglTheme*>(current_theme_);
        if (lvgl_theme != nullptr && lvgl_theme->text_font() != nullptr) {
            lv_obj_set_style_text_font(battery_label_, lvgl_theme->text_font()->font(), 0);
        }
        lv_label_set_text(battery_label_, "10:00");
        StyleOledInkFg(battery_label_);
    }

    if (content_ != nullptr) {
        StyleOledInkBg(content_);
        if (height_ > 32) {
            // Leave room for the 12px top bar inside the column flex layout.
            lv_obj_set_width(content_, LV_HOR_RES);
            lv_obj_set_flex_grow(content_, 1);
        }
    }
    if (face_ != nullptr) {
        lv_obj_set_size(face_, LV_HOR_RES, height_ > 32 ? 52 : LV_VER_RES);
        lv_obj_center(face_);
    }
    ApplyEyeGeometry();
}

void OledDisplay::SetFaceOnlyMode(bool enabled) {
    DisplayLockGuard lock(this);
    face_only_mode_ = enabled;
    if (!enabled) {
        return;
    }
    ApplyFaceOnlyLayout();
    ESP_LOGI(TAG, "Demo face UI ON (WiFi + 10:00 + animated eyes, black/white)");
}

void OledDisplay::SetStatus(const char* status) {
    if (face_only_mode_) {
        return;
    }
    LvglDisplay::SetStatus(status);
}

void OledDisplay::ShowNotification(const char* notification, int duration_ms) {
    if (face_only_mode_) {
        return;
    }
    LvglDisplay::ShowNotification(notification, duration_ms);
}

void OledDisplay::UpdateStatusBar(bool update_all) {
    if (face_only_mode_) {
        // Keep demo chrome; do not re-layout every clock tick (avoids flicker).
        return;
    }
    LvglDisplay::UpdateStatusBar(update_all);
}

void OledDisplay::SetEmotion(const char* emotion) {
    DisplayLockGuard lock(this);

    if (eye_face_enabled_) {
        current_emotion_ = emotion ? emotion : "neutral";
        eye_style_ = ResolveEmotion(current_emotion_.c_str());
        if (face_only_mode_) {
            // Slightly larger eyes when chrome is hidden.
            auto grow = [](int16_t& v) {
                if (v > 0) {
                    v = static_cast<int16_t>(v * 1.35f);
                }
            };
            grow(eye_style_.width);
            grow(eye_style_.height);
            grow(eye_style_.width_l);
            grow(eye_style_.height_l);
            grow(eye_style_.width_r);
            grow(eye_style_.height_r);
            grow(eye_style_.gap);
        }
        look_dir_ = eye_style_.look_bias;
        blinking_ = false;
        ApplyEyeGeometry();
        ESP_LOGI(TAG, "Eye emotion: %s", current_emotion_.c_str());
        return;
    }

    // Legacy fallback if eye face was not created
    if (emotion_label_ == nullptr) {
        return;
    }
    lv_obj_set_style_text_font(emotion_label_,
                               static_cast<LvglTheme*>(current_theme_)->large_icon_font()->font(),
                               0);
    lv_label_set_text(emotion_label_, MATERIAL_SYMBOLS_ROBOT_2);
}

void OledDisplay::SetTheme(Theme* theme) {
    DisplayLockGuard lock(this);

    auto lvgl_theme = static_cast<LvglTheme*>(theme);
    auto text_font = lvgl_theme->text_font()->font();

    auto screen = lv_screen_active();
    lv_obj_set_style_text_font(screen, text_font, 0);
}
