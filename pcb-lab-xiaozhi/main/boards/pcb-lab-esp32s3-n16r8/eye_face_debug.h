#ifndef PCB_LAB_EYE_FACE_DEBUG_H
#define PCB_LAB_EYE_FACE_DEBUG_H

#include "display.h"

/**
 * OLED eye-face demo helper (PCB Lab only).
 * BOOT → next emotion (32 two-dot faces). Layout: tiny WiFi + sample 10:00 + animated eyes.
 */
class EyeFaceDebug {
public:
    static EyeFaceDebug& GetInstance();

    /** Hide chrome once, apply emotion, advance index. */
    void Next(Display* display);

    /** Emotion name currently shown. */
    const char* CurrentName() const;

    static int Count();

private:
    EyeFaceDebug() = default;

    void EnsureFaceOnly(Display* display);

    int index_ = 0;
    bool face_only_ready_ = false;
};

#endif  // PCB_LAB_EYE_FACE_DEBUG_H
