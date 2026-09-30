# PCB Lab ESP32-S3-N16R8

Custom board for ESP32-S3-N16R8 44-pin (PCB Lab) with:

- INMP441 I2S microphone
- MAX98357A I2S amplifier + 4Ω speaker
- SH1106 OLED 128×64 I2C @ 0x3C (SDA=GPIO41, SCL=GPIO42)
- WS2812 RGB LED @ GPIO48
- BOOT button @ GPIO0
- DS3231 @ 0x68 on shared I2C (driver not integrated yet)

See `docs/a-den-z.md` for full wiring and build/flash instructions.

## Build

```bash
idf.py set-target esp32s3
python scripts/build.py pcb-lab-esp32s3-n16r8 --name pcb-lab-esp32s3-n16r8
```

Or menuconfig: **Xiaozhi Assistant → Board Type → PCB Lab ESP32-S3-N16R8**

## Flash

```bash
idf.py -p COM11 flash monitor
```

Replace `COM11` with your UART port.
