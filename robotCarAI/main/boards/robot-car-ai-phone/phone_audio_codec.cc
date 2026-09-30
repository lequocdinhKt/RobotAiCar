#include "phone_audio_codec.h"

#include "phone_audio_bridge.h"

#include <freertos/FreeRTOS.h>

PhoneAudioCodec::PhoneAudioCodec(int input_sample_rate, int output_sample_rate) {
    duplex_ = false;
    input_sample_rate_ = input_sample_rate;
    output_sample_rate_ = output_sample_rate;
    input_channels_ = 1;
    output_channels_ = 1;
}

void PhoneAudioCodec::Start() {
    PhoneAudioBridge::GetInstance().Start();
    AudioCodec::Start();
}

void PhoneAudioCodec::EnableInput(bool enable) { AudioCodec::EnableInput(enable); }

void PhoneAudioCodec::EnableOutput(bool enable) { AudioCodec::EnableOutput(enable); }

int PhoneAudioCodec::Read(int16_t* destination, int samples) {
    if (!input_enabled_) {
        return 0;
    }
    return PhoneAudioBridge::GetInstance().ReadMicrophone(destination, samples,
                                                          pdMS_TO_TICKS(200));
}

int PhoneAudioCodec::Write(const int16_t* data, int samples) {
    if (!output_enabled_) {
        return samples;
    }
    return PhoneAudioBridge::GetInstance().QueueSpeaker(data, samples);
}
