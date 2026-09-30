#include "eye_face_debug.h"

#include "display/oled_display.h"

#include <esp_log.h>

#define TAG "EyeFaceDebug"

namespace {

// Keep names identical to OledDisplay::ResolveEmotion / server emotion strings.
// BOOT cycles through all of these (two-dot OLED face).
constexpr const char* kEmotions[] = {
    "neutral",   "happy",     "laughing",  "funny",      "sad",
    "crying",    "angry",     "surprised", "shocked",    "scared",
    "sleepy",    "bored",     "thinking",  "confused",   "skeptical",
    "winking",   "left_wink", "loving",    "kissy",      "embarrassed",
    "shy",       "confident", "cool",      "silly",      "dizzy",
    "excited",   "focused",   "relaxed",   "delicious",  "narrow",
    "wide",      "dead",
};

constexpr int kEmotionCount = static_cast<int>(sizeof(kEmotions) / sizeof(kEmotions[0]));

}  // namespace

EyeFaceDebug& EyeFaceDebug::GetInstance() {
    static EyeFaceDebug instance;
    return instance;
}

int EyeFaceDebug::Count() { return kEmotionCount; }

const char* EyeFaceDebug::CurrentName() const {
    int shown = index_ - 1;
    if (shown < 0) {
        shown = kEmotionCount - 1;
    }
    if (shown < 0 || shown >= kEmotionCount) {
        return "neutral";
    }
    return kEmotions[shown];
}

void EyeFaceDebug::EnsureFaceOnly(Display* display) {
    if (face_only_ready_ || display == nullptr) {
        return;
    }
    auto* oled = dynamic_cast<OledDisplay*>(display);
    if (oled != nullptr) {
        oled->SetFaceOnlyMode(true);
        // Clear any leftover robot / chat emotion label path.
        oled->SetChatMessage("system", "");
    }
    face_only_ready_ = true;
    ESP_LOGI(TAG, "Face-only UI armed (icons + clock hidden)");
}

void EyeFaceDebug::Next(Display* display) {
    if (display == nullptr) {
        return;
    }

    EnsureFaceOnly(display);

    const char* name = kEmotions[index_];
    // Only the face — do not SetStatus / ShowNotification (those drew the clock text & overlays).
    display->SetEmotion(name);

    ESP_LOGI(TAG, "BOOT -> emotion '%s' (%d/%d)", name, index_ + 1, kEmotionCount);

    index_ = (index_ + 1) % kEmotionCount;
}
