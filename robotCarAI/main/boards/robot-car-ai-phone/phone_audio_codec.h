#ifndef ROBOT_CAR_AI_PHONE_AUDIO_CODEC_H
#define ROBOT_CAR_AI_PHONE_AUDIO_CODEC_H

#include "audio_codec.h"

class PhoneAudioCodec : public AudioCodec {
public:
    PhoneAudioCodec(int input_sample_rate, int output_sample_rate);

    void Start() override;
    void EnableInput(bool enable) override;
    void EnableOutput(bool enable) override;

protected:
    int Read(int16_t* destination, int samples) override;
    int Write(const int16_t* data, int samples) override;
};

#endif  // ROBOT_CAR_AI_PHONE_AUDIO_CODEC_H
