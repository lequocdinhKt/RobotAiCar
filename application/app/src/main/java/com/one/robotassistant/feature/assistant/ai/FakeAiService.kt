package com.one.robotassistant.feature.assistant.ai

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

class FakeAiService : AiService {
    override suspend fun ask(message: String): AiResponse {
        delay(250)
        val normalized = message.lowercase(Locale.forLanguageTag("vi-VN"))
        val answer = when {
            "ngày" in normalized || "hôm nay" in normalized -> {
                val date = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                "Hôm nay là ngày $date."
            }
            "tên" in normalized -> "Tôi là Linh, trợ lý robot của bạn."
            else -> "Tôi đã nghe: $message. Hãy thêm GROQ_API_KEY để tôi trả lời thông minh hơn."
        }
        return AiResponse(text = answer, model = "fake-offline")
    }
}
