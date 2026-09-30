# Android companion app

The app uses Android `AudioRecord` and `AudioTrack`; no third-party runtime
library is required. It discovers the ESP32 by UDP broadcast and streams audio
over a raw low-latency TCP connection.

## Build

Open this `phone-app` directory in Android Studio and select **Build > Build
APK(s)**. The debug APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`

Install the APK on the phone, grant microphone permission, connect the phone to
the same 2.4 GHz Wi-Fi as the ESP32, then tap **Tự tìm ESP32 trong Wi-Fi**.
