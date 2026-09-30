#include "phone_audio_bridge.h"

#include "application.h"
#include "config.h"

#include <cerrno>
#include <cinttypes>
#include <cstring>

#include <esp_log.h>
#include <lwip/inet.h>
#include <lwip/sockets.h>
#include <lwip/tcp.h>

namespace {

constexpr char kTag[] = "PhoneAudioBridge";
constexpr uint32_t kProtocolMagic = 0x52434149;  // "RCAI"
constexpr uint8_t kProtocolVersion = 1;
constexpr size_t kHeaderSize = 12;
constexpr size_t kMaximumPayloadSize = 4096;

enum class ControlCommand : uint8_t {
    kToggleConversation = 1,
    kStartListening = 2,
    kStopListening = 3,
};

uint32_t ReadBigEndian32(const uint8_t* value) {
    return (static_cast<uint32_t>(value[0]) << 24) |
           (static_cast<uint32_t>(value[1]) << 16) |
           (static_cast<uint32_t>(value[2]) << 8) |
           static_cast<uint32_t>(value[3]);
}

void WriteBigEndian32(uint8_t* destination, uint32_t value) {
    destination[0] = static_cast<uint8_t>(value >> 24);
    destination[1] = static_cast<uint8_t>(value >> 16);
    destination[2] = static_cast<uint8_t>(value >> 8);
    destination[3] = static_cast<uint8_t>(value);
}

bool ReceiveAll(int socket_fd, uint8_t* destination, size_t size) {
    size_t received = 0;
    while (received < size) {
        int result = recv(socket_fd, destination + received, size - received, 0);
        if (result == 0) {
            return false;
        }
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        received += static_cast<size_t>(result);
    }
    return true;
}

bool SendAll(int socket_fd, const uint8_t* source, size_t size) {
    size_t sent = 0;
    while (sent < size) {
        int result = send(socket_fd, source + sent, size - sent, 0);
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (result == 0) {
            return false;
        }
        sent += static_cast<size_t>(result);
    }
    return true;
}

}  // namespace

PhoneAudioBridge& PhoneAudioBridge::GetInstance() {
    static PhoneAudioBridge instance;
    return instance;
}

PhoneAudioBridge::PhoneAudioBridge() {
    microphone_buffer_ = xStreamBufferCreate(PHONE_MIC_BUFFER_BYTES, sizeof(int16_t));
    speaker_buffer_ = xMessageBufferCreate(PHONE_SPEAKER_BUFFER_BYTES);
    if (microphone_buffer_ == nullptr || speaker_buffer_ == nullptr) {
        ESP_LOGE(kTag, "Failed to allocate phone audio buffers");
    }
}

void PhoneAudioBridge::Start() {
    if (started_.exchange(true)) {
        return;
    }

    if (microphone_buffer_ == nullptr || speaker_buffer_ == nullptr) {
        ESP_LOGE(kTag, "Phone audio bridge cannot start without buffers");
        return;
    }

    xTaskCreate(ServerTaskEntry, "phone_audio_rx", 6144, this, 5, nullptr);
    xTaskCreate(SpeakerTaskEntry, "phone_audio_tx", 4096, this, 5, nullptr);
    xTaskCreate(DiscoveryTaskEntry, "phone_discovery", 3072, this, 3, nullptr);
    ESP_LOGI(kTag, "Phone bridge starting: TCP %d, discovery UDP %d", PHONE_AUDIO_TCP_PORT,
             PHONE_DISCOVERY_UDP_PORT);
}

bool PhoneAudioBridge::IsConnected() const { return client_socket_.load() >= 0; }

int PhoneAudioBridge::ReadMicrophone(int16_t* destination, int samples, TickType_t timeout) {
    if (!IsConnected() || destination == nullptr || samples <= 0) {
        return 0;
    }

    const size_t required = static_cast<size_t>(samples) * sizeof(int16_t);
    size_t received = 0;
    const TickType_t started_at = xTaskGetTickCount();
    while (received < required && IsConnected()) {
        TickType_t elapsed = xTaskGetTickCount() - started_at;
        if (elapsed >= timeout) {
            break;
        }
        size_t count = xStreamBufferReceive(microphone_buffer_,
                                            reinterpret_cast<uint8_t*>(destination) + received,
                                            required - received, timeout - elapsed);
        if (count == 0) {
            break;
        }
        received += count;
    }

    if (received != required) {
        return 0;
    }
    return samples;
}

int PhoneAudioBridge::QueueSpeaker(const int16_t* data, int samples) {
    if (data == nullptr || samples <= 0 || !IsConnected()) {
        return samples;
    }

    const size_t bytes = static_cast<size_t>(samples) * sizeof(int16_t);
    if (bytes > kMaximumPayloadSize || xMessageBufferSend(speaker_buffer_, data, bytes, 0) != bytes) {
        uint32_t dropped = ++dropped_speaker_frames_;
        if (dropped == 1 || dropped % 100 == 0) {
            ESP_LOGW(kTag, "Speaker queue full; dropped %" PRIu32 " frame(s)", dropped);
        }
    }
    return samples;
}

void PhoneAudioBridge::ServerTaskEntry(void* context) {
    static_cast<PhoneAudioBridge*>(context)->ServerTask();
    vTaskDelete(nullptr);
}

void PhoneAudioBridge::SpeakerTaskEntry(void* context) {
    static_cast<PhoneAudioBridge*>(context)->SpeakerTask();
    vTaskDelete(nullptr);
}

void PhoneAudioBridge::DiscoveryTaskEntry(void* context) {
    static_cast<PhoneAudioBridge*>(context)->DiscoveryTask();
    vTaskDelete(nullptr);
}

void PhoneAudioBridge::ServerTask() {
    int server_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (server_socket < 0) {
        ESP_LOGE(kTag, "Cannot create TCP server socket: errno=%d", errno);
        return;
    }

    int reuse_address = 1;
    setsockopt(server_socket, SOL_SOCKET, SO_REUSEADDR, &reuse_address, sizeof(reuse_address));

    sockaddr_in address = {};
    address.sin_family = AF_INET;
    address.sin_port = htons(PHONE_AUDIO_TCP_PORT);
    address.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(server_socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
        listen(server_socket, 1) != 0) {
        ESP_LOGE(kTag, "Cannot bind/listen on TCP %d: errno=%d", PHONE_AUDIO_TCP_PORT, errno);
        close(server_socket);
        return;
    }

    ESP_LOGI(kTag, "Waiting for the Android app on TCP %d", PHONE_AUDIO_TCP_PORT);
    while (true) {
        sockaddr_in client_address = {};
        socklen_t client_address_size = sizeof(client_address);
        int client = accept(server_socket, reinterpret_cast<sockaddr*>(&client_address),
                            &client_address_size);
        if (client < 0) {
            ESP_LOGE(kTag, "accept failed: errno=%d", errno);
            vTaskDelay(pdMS_TO_TICKS(500));
            continue;
        }

        int no_delay = 1;
        setsockopt(client, IPPROTO_TCP, TCP_NODELAY, &no_delay, sizeof(no_delay));
        DrainBuffers();

        constexpr char kHello[] =
            "{\"device\":\"robot-car-ai-phone\",\"input_rate\":16000,"
            "\"output_rate\":24000,\"pcm\":\"s16le-mono\"}";
        if (!SendFrame(client, PacketType::kHello,
                       reinterpret_cast<const uint8_t*>(kHello), sizeof(kHello) - 1)) {
            close(client);
            continue;
        }

        client_socket_.store(client);
        ESP_LOGI(kTag, "Phone connected from %s", inet_ntoa(client_address.sin_addr));

        uint8_t header[kHeaderSize];
        uint8_t payload[kMaximumPayloadSize];
        while (client_socket_.load() == client && ReceiveAll(client, header, sizeof(header))) {
            const uint32_t magic = ReadBigEndian32(header);
            const uint8_t version = header[4];
            const auto type = static_cast<PacketType>(header[5]);
            const uint32_t payload_size = ReadBigEndian32(header + 8);

            if (magic != kProtocolMagic || version != kProtocolVersion ||
                payload_size > kMaximumPayloadSize) {
                ESP_LOGW(kTag, "Invalid phone frame: magic=%08" PRIx32 " version=%u size=%" PRIu32,
                         magic, version, payload_size);
                break;
            }
            if (payload_size > 0 && !ReceiveAll(client, payload, payload_size)) {
                break;
            }

            if (type == PacketType::kMicrophonePcm) {
                if ((payload_size & 1U) != 0U) {
                    ESP_LOGW(kTag, "Ignoring odd PCM payload size: %" PRIu32, payload_size);
                    continue;
                }
                size_t written = xStreamBufferSend(microphone_buffer_, payload, payload_size, 0);
                if (written != payload_size) {
                    uint32_t dropped = dropped_microphone_bytes_.fetch_add(payload_size - written) +
                                       payload_size - written;
                    if (dropped < payload_size * 2 || dropped % 32000 < payload_size) {
                        ESP_LOGW(kTag, "Microphone buffer full; dropped %" PRIu32 " byte(s)",
                                 dropped);
                    }
                }
            } else if (type == PacketType::kControl) {
                HandleControl(payload, payload_size);
            }
        }

        DisconnectClient(client);
    }
}

void PhoneAudioBridge::SpeakerTask() {
    uint8_t payload[kMaximumPayloadSize];
    while (true) {
        size_t size = xMessageBufferReceive(speaker_buffer_, payload, sizeof(payload), portMAX_DELAY);
        if (size == 0) {
            continue;
        }
        int client = client_socket_.load();
        if (client >= 0 && !SendFrame(client, PacketType::kSpeakerPcm, payload, size)) {
            DisconnectClient(client);
        }
    }
}

void PhoneAudioBridge::DiscoveryTask() {
    int discovery_socket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (discovery_socket < 0) {
        ESP_LOGE(kTag, "Cannot create discovery socket: errno=%d", errno);
        return;
    }

    int reuse_address = 1;
    setsockopt(discovery_socket, SOL_SOCKET, SO_REUSEADDR, &reuse_address, sizeof(reuse_address));

    sockaddr_in address = {};
    address.sin_family = AF_INET;
    address.sin_port = htons(PHONE_DISCOVERY_UDP_PORT);
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(discovery_socket, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        ESP_LOGE(kTag, "Cannot bind discovery UDP %d: errno=%d", PHONE_DISCOVERY_UDP_PORT, errno);
        close(discovery_socket);
        return;
    }

    uint8_t request[64];
    while (true) {
        sockaddr_in sender = {};
        socklen_t sender_size = sizeof(sender);
        int size = recvfrom(discovery_socket, request, sizeof(request), 0,
                            reinterpret_cast<sockaddr*>(&sender), &sender_size);
        constexpr char kRequest[] = "ROBOTCARAI_DISCOVER";
        if (size == static_cast<int>(sizeof(kRequest) - 1) &&
            memcmp(request, kRequest, sizeof(kRequest) - 1) == 0) {
            constexpr char kResponse[] = "ROBOTCARAI/1";
            sendto(discovery_socket, kResponse, sizeof(kResponse) - 1, 0,
                   reinterpret_cast<sockaddr*>(&sender), sender_size);
        }
    }
}

void PhoneAudioBridge::HandleControl(const uint8_t* payload, size_t size) {
    if (payload == nullptr || size != 1) {
        ESP_LOGW(kTag, "Invalid control packet size: %u", static_cast<unsigned>(size));
        return;
    }

    auto command = static_cast<ControlCommand>(payload[0]);
    auto& application = Application::GetInstance();
    switch (command) {
        case ControlCommand::kToggleConversation:
            application.ToggleChatState();
            break;
        case ControlCommand::kStartListening:
            application.StartListening();
            break;
        case ControlCommand::kStopListening:
            application.StopListening();
            break;
        default:
            ESP_LOGW(kTag, "Unknown phone control command: %u", payload[0]);
            break;
    }
}

void PhoneAudioBridge::DrainBuffers() {
    uint8_t temporary[kMaximumPayloadSize];
    while (xStreamBufferReceive(microphone_buffer_, temporary, sizeof(temporary), 0) > 0) {
    }
    while (xMessageBufferReceive(speaker_buffer_, temporary, sizeof(temporary), 0) > 0) {
    }
}

void PhoneAudioBridge::DisconnectClient(int socket_fd) {
    int expected = socket_fd;
    if (client_socket_.compare_exchange_strong(expected, -1)) {
        shutdown(socket_fd, SHUT_RDWR);
        close(socket_fd);
        ESP_LOGW(kTag, "Phone disconnected");
    }
}

bool PhoneAudioBridge::SendFrame(int socket_fd, PacketType type, const uint8_t* payload,
                                 size_t size) {
    if (size > kMaximumPayloadSize) {
        return false;
    }

    uint8_t header[kHeaderSize] = {};
    WriteBigEndian32(header, kProtocolMagic);
    header[4] = kProtocolVersion;
    header[5] = static_cast<uint8_t>(type);
    WriteBigEndian32(header + 8, static_cast<uint32_t>(size));
    return SendAll(socket_fd, header, sizeof(header)) &&
           (size == 0 || SendAll(socket_fd, payload, size));
}
