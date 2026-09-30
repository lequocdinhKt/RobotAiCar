# Robot Car AI dùng microphone và loa của điện thoại

## Kết luận khả thi

Giải pháp dùng điện thoại làm cả microphone và loa là khả thi nếu điện thoại và
ESP32-S3 cùng một mạng Wi-Fi. Ứng dụng Android truyền PCM hai chiều bằng TCP:

- Điện thoại -> ESP32: microphone PCM mono 16-bit, 16 kHz.
- ESP32 -> điện thoại: âm thanh PCM mono 16-bit, 24 kHz.
- ESP32 vẫn xử lý giao thức XiaoZhi, Opus, kết nối máy chủ, OLED, nút BOOT và LED.
- Firmware không dùng INMP441 hoặc MAX98357A.

Không dùng trang web làm microphone vì trình duyệt điện thoại thường chặn quyền
microphone trên HTTP nội bộ không bảo mật. Bluetooth BLE của ESP32-S3 cũng không
phù hợp bằng Wi-Fi cho luồng âm thanh hai chiều liên tục.

Phiên bản hiện tại dành cho **Android**. Nếu điện thoại là iPhone, cần viết thêm
client iOS theo `phone-app/PROTOCOL.md`; APK Android không cài được trên iPhone.

## Phần cứng đang giữ nguyên

- ESP32-S3-N16R8, flash 16 MB, PSRAM 8 MB.
- OLED SH1106 128x64: SDA GPIO41, SCL GPIO42, địa chỉ `0x3C`.
- WS2812: GPIO48.
- Nút BOOT: GPIO0.
- GPIO4/5/6/7/15/16 không còn được firmware âm thanh sử dụng.

Chưa thêm điều khiển động cơ vì chưa có model driver, điện áp, sơ đồ nối và GPIO
chính xác. Không nên đoán chân motor vì có thể làm hỏng driver hoặc mạch.

## File đã build sẵn

- Firmware nạp một lần: `build/merged-binary.bin`.
- Ứng dụng Android: `phone-app/app/build/outputs/apk/debug/app-debug.apk`.
- Board mới: `main/boards/robot-car-ai-phone/`.

Hai thư mục `xiaozhi-esp32` và `pcb-lab-xiaozhi` không bị sửa. Mọi thay đổi nằm
trong thư mục `robotCarAI` này.

## Nạp firmware nhanh nhất

1. Cắm mạch bằng cáp USB có truyền dữ liệu.
2. Đóng Serial Monitor/Arduino/ứng dụng khác đang giữ cổng COM.
3. Mở PowerShell tại `D:\Dinh\OneAi\robotCarAI`.
4. Xem cổng hiện có nếu COM11 không đúng:

   ```powershell
   [System.IO.Ports.SerialPort]::GetPortNames()
   ```

5. Nạp firmware đã build:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\flash_robotCarAI.ps1 -Port COM11
   ```

Nếu tự reset vào chế độ nạp thất bại, giữ nút BOOT, nhấn-thả RESET, thả BOOT rồi
chạy lại lệnh. Có thể giảm tốc độ nếu cáp không ổn định:

```powershell
.\flash_robotCarAI.ps1 -Port COM11 -Baud 115200
```

Lệnh tương đương không dùng script:

```powershell
& "C:\Users\xiaoying\.espressif\python_env\idf6.1_py3.14_env\Scripts\python.exe" -m esptool --chip esp32s3 --port COM11 --baud 460800 write-flash 0x0 ".\build\merged-binary.bin"
```

## Cấu hình Wi-Fi lần đầu

File gộp sẽ nạp từ địa chỉ 0 nên cấu hình Wi-Fi cũ có thể bị xóa.

1. Sau khi mạch khởi động, kết nối điện thoại vào điểm phát Wi-Fi `Xiaozhi-...`.
2. Nếu trang cấu hình không tự mở, vào `http://192.168.4.1`.
3. Chọn Wi-Fi 2.4 GHz và nhập mật khẩu.
4. Chờ ESP32 kết nối mạng và hoàn thành bước kích hoạt XiaoZhi nếu màn hình yêu
   cầu.

Muốn vào lại cấu hình Wi-Fi: giữ BOOT khi bật/reset mạch.

## Cài và dùng ứng dụng điện thoại

1. Chép `phone-app/app/build/outputs/apk/debug/app-debug.apk` sang Android.
2. Cho phép cài ứng dụng từ nguồn này, cài APK và cấp quyền microphone.
3. Cho điện thoại và ESP32 vào cùng Wi-Fi. Mạng phải cho các thiết bị LAN nhìn
   thấy nhau; tắt `AP/client isolation` nếu router đang bật.
4. Mở **Robot Car AI**, bấm **Tự tìm ESP32 trong Wi-Fi** rồi **Kết nối**.
5. Bấm **Bắt đầu / dừng hội thoại**, hoặc nhấn BOOT trên mạch.
6. Điều chỉnh âm lượng bằng nút âm lượng của điện thoại.

Ứng dụng cần được giữ mở; màn hình được giữ sáng để Android không đình chỉ luồng
microphone. Mỗi ESP32 chỉ nhận một điện thoại tại một thời điểm.

## Build lại khi sửa code

APK:

```powershell
cd D:\Dinh\OneAi\robotCarAI\phone-app
.\gradlew.bat assembleDebug
```

Firmware dùng ESP-IDF 6.1 đã cài trên máy. Board build là
`robot-car-ai-phone`. Manifest đã ghim `esp_video` 2.5.0 để tránh lỗi Kconfig của
2.4.1 trên ESP-IDF 6.1. Nếu mở được **ESP-IDF PowerShell** bình thường:

```powershell
cd D:\Dinh\OneAi\robotCarAI
python scripts\build.py robot-car-ai-phone
idf.py -p COM11 flash monitor
```

Nếu `export.bat` báo thiếu `esp-clangd`, vẫn có thể dùng firmware build sẵn và
script `flash_robotCarAI.ps1`; việc nạp bằng esptool không cần clangd.

## Xử lý lỗi thường gặp

- Không tìm thấy ESP32: nhập IP ESP32 thủ công; kiểm tra hai thiết bị cùng mạng
  và router không bật client isolation.
- Kết nối được nhưng không nghe: tăng media/call volume, tắt Bluetooth headset,
  kiểm tra Android đã cấp quyền microphone.
- Âm thanh vọng: để điện thoại cách mạch/khung xe, giảm loa; ứng dụng đã bật AEC,
  khử nhiễu và AGC nếu điện thoại hỗ trợ.
- Cổng COM bận: đóng Serial Monitor và rút-cắm lại USB.
- Không vào chế độ nạp: giữ BOOT, nhấn-thả RESET, thả BOOT rồi chạy lại script.
