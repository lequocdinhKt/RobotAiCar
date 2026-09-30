package com.one.robotassistant.feature.assistant.ai

interface AiService {
    suspend fun ask(message: String): AiResponse

    suspend fun askWithImage(
        message: String,
        jpegBytes: ByteArray,
        detectorContext: String,
    ): AiResponse = ask("$message\nThông tin detector tham khảo: $detectorContext")
}

data class AiResponse(
    val text: String,
    val model: String? = null,
    val requestId: String? = null,
)
