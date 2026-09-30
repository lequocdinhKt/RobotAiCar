#ifndef OLED_DISPLAY_H
#define OLED_DISPLAY_H

#include "lvgl_display.h"

#include <esp_lcd_panel_io.h>
#include <esp_lcd_panel_ops.h>
#include <esp_timer.h>

#include <string>


class OledDisplay : public LvglDisplay {
public:
    struct EyeStyle {
        int16_t width = 20;
        int16_t height = 20;
        int16_t width_l = 0;        // 0 → use width
        int16_t height_l = 0;       // 0 → use height
        int16_t width_r = 0;        // 0 → use width
        int16_t height_r = 0;       // 0 → use height
        int16_t offset_y = 0;
        int16_t offset_y_l = 0;     // extra Y for left eye
        int16_t offset_y_r = 0;     // extra Y for right eye
        int16_t angle_deg = 0;      // mirrored: left = -angle, right = +angle
        int16_t angle_l = 0;        // absolute override; if both angle_l/r set, use them
        int16_t angle_r = 0;
        bool use_abs_angle = false; // when true, angle_l / angle_r are absolute degrees
        int16_t look_bias = 0;      // emotion preferred look: -1 left, 0, +1 right
        int16_t gap = 0;            // 0 → default gap
        bool close_left = false;
        bool close_right = false;
    };

private:
    esp_lcd_panel_io_handle_t panel_io_ = nullptr;
    esp_lcd_panel_handle_t panel_ = nullptr;

    lv_obj_t* top_bar_ = nullptr;
    lv_obj_t* status_bar_ = nullptr;
    lv_obj_t* content_ = nullptr;
    lv_obj_t* content_left_ = nullptr;
    lv_obj_t* content_right_ = nullptr;
    lv_obj_t* container_ = nullptr;
    lv_obj_t* side_bar_ = nullptr;
    lv_obj_t* emotion_label_ = nullptr;
    lv_obj_t* chat_message_label_ = nullptr;

    // Animated eye face
    lv_obj_t* face_ = nullptr;
    lv_obj_t* left_eye_ = nullptr;
    lv_obj_t* right_eye_ = nullptr;
    esp_timer_handle_t eye_anim_timer_ = nullptr;
    EyeStyle eye_style_{};
    std::string current_emotion_ = "neutral";
    int8_t look_dir_ = 0;       // -1 left, 0 center, 1 right
    bool blinking_ = false;
    uint8_t anim_phase_ = 0;
    bool eye_face_enabled_ = false;
    bool face_only_mode_ = false;

    virtual bool Lock(int timeout_ms = 0) override;
    virtual void Unlock() override;

    void SetupUI_128x64();
    void SetupUI_128x32();

    void CreateEyeFace(lv_obj_t* parent, int face_w, int face_h);
    void StartEyeAnimation();
    void StopEyeAnimation();
    void ApplyEyeGeometry();
    void ApplyFaceOnlyLayout();
    EyeStyle ResolveEmotion(const char* emotion) const;
    static void EyeAnimTimerCallback(void* arg);

public:
    OledDisplay(esp_lcd_panel_io_handle_t panel_io, esp_lcd_panel_handle_t panel, int width,
                int height, bool mirror_x, bool mirror_y);
    ~OledDisplay();

    virtual void SetupUI() override;
    virtual void SetChatMessage(const char* role, const char* content) override;
    virtual void SetEmotion(const char* emotion) override;
    virtual void SetTheme(Theme* theme) override;
    virtual void SetStatus(const char* status) override;
    virtual void ShowNotification(const char* notification, int duration_ms = 3000) override;
    virtual void UpdateStatusBar(bool update_all = false) override;

    /** Hide status/icons/clock/chat — only the eye face remains (debug). */
    void SetFaceOnlyMode(bool enabled);
    bool IsFaceOnlyMode() const { return face_only_mode_; }
};

#endif  // OLED_DISPLAY_H
