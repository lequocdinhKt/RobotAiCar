package com.one.robotassistant.feature.assistant.ai

import com.one.robotassistant.core.network.OneConfiguration
import java.io.IOException
import android.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GroqAiService(
    private val configuration: OneConfiguration,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) : AiService {
    override suspend fun ask(message: String): AiResponse = withContext(Dispatchers.IO) {
        check(configuration.hasGroqApiKey) { "GROQ_API_KEY chưa được cấu hình" }
        val payload = JSONObject()
            .put("model", configuration.groqModel)
            .put("temperature", 0.5)
            .put("max_completion_tokens", 512)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "Bạn tên là Linh, là trợ lý robot nhỏ trong ứng dụng ONE. " +
                                    "Trả lời tiếng Việt tự nhiên, ngắn gọn, " +
                                    "thân thiện và phù hợp để đọc thành tiếng. Không tự tạo lệnh motor.",
                            ),
                    )
                    .put(JSONObject().put("role", "user").put("content", message)),
            )

        execute(payload)
    }

    override suspend fun askWithImage(
        message: String,
        jpegBytes: ByteArray,
        detectorContext: String,
    ): AiResponse = withContext(Dispatchers.IO) {
        check(configuration.hasGroqApiKey) { "GROQ_API_KEY chưa được cấu hình" }
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val prompt = buildString {
            append("Hãy quan sát chính ảnh camera này và trả lời câu hỏi bằng tiếng Việt, ngắn gọn để đọc thành tiếng: ")
            append(message)
            append(". Nhãn MediaPipe chỉ để tham khảo, có thể sai: ")
            append(detectorContext.ifBlank { "không có nhãn" })
            append(". Chỉ mô tả vật thực sự thấy trong ảnh, không suy đoán thêm vật nằm ngoài khung hình.")
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl)),
            )
        val payload = JSONObject()
            .put("model", configuration.groqVisionModel)
            .put("temperature", 0.2)
            .put("max_completion_tokens", 256)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "Bạn tên là Linh, là trợ lý robot thị giác. Trả lời trung thực dựa trên ảnh."),
                    )
                    .put(JSONObject().put("role", "user").put("content", content)),
            )
        execute(payload)
    }

    private fun execute(payload: JSONObject): AiResponse {
        val request = Request.Builder()
            .url(configuration.groqBaseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${configuration.groqApiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val apiMessage = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IOException(apiMessage?.takeIf { it.isNotBlank() } ?: "Groq HTTP ${response.code}")
            }
            val json = JSONObject(body)
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val text = choice.getJSONObject("message").getString("content").trim()
            return AiResponse(
                text = text,
                model = json.optString("model").takeIf { it.isNotBlank() },
                requestId = json.optString("id").takeIf { it.isNotBlank() },
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
