#ifndef ROBOT_CAR_AI_PHONE_AUDIO_BRIDGE_H
#define ROBOT_CAR_AI_PHONE_AUDIO_BRIDGE_H

#include <atomic>
#include <cstddef>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/message_buffer.h>
#include <freertos/stream_buffer.h>
#include <freertos/task.h>

class PhoneAudioBridge {
public:
    static PhoneAudioBridge& GetInstance();

    void Start();
    bool IsConnected() const;
    int ReadMicrophone(int16_t* destination, int samples, TickType_t timeout);
    int QueueSpeaker(const int16_t* data, int samples);

private:
    enum class PacketType : uint8_t {
        kHello = 1,
        kMicrophonePcm = 2,
        kSpeakerPcm = 3,
        kControl = 4,
        kStatus = 5,
    };

    PhoneAudioBridge();
    ~PhoneAudioBridge() = default;
    PhoneAudioBridge(const PhoneAudioBridge&) = delete;
    PhoneAudioBridge& operator=(const PhoneAudioBridge&) = delete;

    static void ServerTaskEntry(void* context);
    static void SpeakerTaskEntry(void* context);
    static void DiscoveryTaskEntry(void* context);

    void ServerTask();
    void SpeakerTask();
    void DiscoveryTask();
    void HandleControl(const uint8_t* payload, size_t size);
    void DrainBuffers();
    void DisconnectClient(int socket_fd);
    bool SendFrame(int socket_fd, PacketType type, const uint8_t* payload, size_t size);

    StreamBufferHandle_t microphone_buffer_ = nullptr;
    MessageBufferHandle_t speaker_buffer_ = nullptr;
    std::atomic<int> client_socket_{-1};
    std::atomic<bool> started_{false};
    std::atomic<uint32_t> dropped_microphone_bytes_{0};
    std::atomic<uint32_t> dropped_speaker_frames_{0};
};

#endif  // ROBOT_CAR_AI_PHONE_AUDIO_BRIDGE_H
