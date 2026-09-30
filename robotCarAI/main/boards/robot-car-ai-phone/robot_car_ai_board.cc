#include "application.h"
#include "assets/lang_config.h"
#include "button.h"
#include "config.h"
#include "display/oled_display.h"
#include "led/single_led.h"
#include "phone_audio_codec.h"
#include "wifi_board.h"

#include <driver/i2c_master.h>
#include <esp_lcd_panel_ops.h>
#include <esp_lcd_panel_vendor.h>
#include <esp_log.h>

#ifdef SH1106
#include <esp_lcd_panel_sh1106.h>
#endif

namespace {
constexpr char kTag[] = "RobotCarAiBoard";
}

class RobotCarAiBoard : public WifiBoard {
private:
    i2c_master_bus_handle_t display_i2c_bus_ = nullptr;
    esp_lcd_panel_io_handle_t panel_io_ = nullptr;
    esp_lcd_panel_handle_t panel_ = nullptr;
    Display* display_ = nullptr;
    Button boot_button_;

    void InitializeDisplayI2c() {
        i2c_master_bus_config_t bus_config = {
            .i2c_port = I2C_NUM_0,
            .sda_io_num = DISPLAY_SDA_PIN,
            .scl_io_num = DISPLAY_SCL_PIN,
            .clk_source = I2C_CLK_SRC_DEFAULT,
            .glitch_ignore_cnt = 7,
            .intr_priority = 0,
            .trans_queue_depth = 0,
            .flags = {
                .enable_internal_pullup = 1,
            },
        };
        ESP_ERROR_CHECK(i2c_new_master_bus(&bus_config, &display_i2c_bus_));
    }

    void InitializeDisplay() {
        esp_lcd_panel_io_i2c_config_t io_config = {
            .dev_addr = 0x3C,
            .scl_speed_hz = 400 * 1000,
            .control_phase_bytes = 1,
            .dc_bit_offset = 6,
            .lcd_cmd_bits = 8,
            .lcd_param_bits = 8,
            .on_color_trans_done = nullptr,
            .user_ctx = nullptr,
            .flags = {
                .dc_low_on_data = 0,
                .disable_control_phase = 0,
            },
        };
        ESP_ERROR_CHECK(esp_lcd_new_panel_io_i2c(display_i2c_bus_, &io_config, &panel_io_));

        esp_lcd_panel_dev_config_t panel_config = {};
        panel_config.reset_gpio_num = GPIO_NUM_NC;
        panel_config.bits_per_pixel = 1;
        esp_lcd_panel_ssd1306_config_t ssd1306_config = {
            .height = static_cast<uint8_t>(DISPLAY_HEIGHT),
        };
        panel_config.vendor_config = &ssd1306_config;

#ifdef SH1106
        ESP_ERROR_CHECK(esp_lcd_new_panel_sh1106(panel_io_, &panel_config, &panel_));
#else
        ESP_ERROR_CHECK(esp_lcd_new_panel_ssd1306(panel_io_, &panel_config, &panel_));
#endif
        ESP_ERROR_CHECK(esp_lcd_panel_reset(panel_));
        if (esp_lcd_panel_init(panel_) != ESP_OK) {
            ESP_LOGE(kTag, "Failed to initialize OLED");
            display_ = new NoDisplay();
            return;
        }
        ESP_ERROR_CHECK(esp_lcd_panel_invert_color(panel_, false));
        ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(panel_, true));

#ifdef SH1106
        constexpr bool kMirrorX = false;
#else
        constexpr bool kMirrorX = DISPLAY_MIRROR_X;
#endif
        display_ = new OledDisplay(panel_io_, panel_, DISPLAY_WIDTH, DISPLAY_HEIGHT, kMirrorX,
                                   DISPLAY_MIRROR_Y);
    }

    void InitializeButton() {
        boot_button_.OnClick([this]() {
            auto& application = Application::GetInstance();
            if (application.GetDeviceState() == kDeviceStateStarting) {
                EnterWifiConfigMode();
                return;
            }
            application.ToggleChatState();
        });
    }

public:
    RobotCarAiBoard() : boot_button_(BOOT_BUTTON_GPIO) {
        InitializeDisplayI2c();
        InitializeDisplay();
        InitializeButton();
    }

    Led* GetLed() override {
        static SingleLed led(BUILTIN_LED_GPIO);
        return &led;
    }

    AudioCodec* GetAudioCodec() override {
        static PhoneAudioCodec codec(AUDIO_INPUT_SAMPLE_RATE, AUDIO_OUTPUT_SAMPLE_RATE);
        return &codec;
    }

    Display* GetDisplay() override { return display_; }
};

DECLARE_BOARD(RobotCarAiBoard);
