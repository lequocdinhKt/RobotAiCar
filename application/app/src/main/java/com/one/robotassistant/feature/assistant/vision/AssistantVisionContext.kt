package com.one.robotassistant.feature.assistant.vision

import com.one.robotassistant.feature.assistant.intent.normalizeVietnamese
import com.one.robotassistant.feature.camera.detection.Detection

data class AssistantVisionContext(
    val cameraRunning: Boolean = false,
    val detections: List<Detection> = emptyList(),
    val updatedAtMillis: Long = 0L,
) {
    fun isFresh(nowMillis: Long): Boolean =
        cameraRunning && nowMillis - updatedAtMillis <= MAX_AGE_MILLIS

    fun spokenDescription(nowMillis: Long): String {
        if (!isFresh(nowMillis)) {
            return "Bạn hãy mở camera để Linh nhìn thấy vật trước mặt nhé."
        }
        val objects = detections
            .sortedByDescending(Detection::confidence)
            .distinctBy { it.label.lowercase() }
            .take(3)
        if (objects.isEmpty()) {
            return "Linh đang nhìn qua camera nhưng chưa nhận diện rõ vật nào. Bạn hãy đưa vật vào giữa khung hình và đủ sáng nhé."
        }
        val names = objects.map { translateLabel(it.label) }
        return when (names.size) {
            1 -> "Linh nhìn thấy ${names.first()}."
            2 -> "Linh nhìn thấy ${names[0]} và ${names[1]}."
            else -> "Linh nhìn thấy ${names.dropLast(1).joinToString(", ")} và ${names.last()}."
        }
    }

    fun detectorSummary(): String = detections
        .sortedByDescending(Detection::confidence)
        .take(5)
        .joinToString(", ") { "${it.label} ${(it.confidence * 100).toInt()}%" }

    companion object {
        const val MAX_AGE_MILLIS = 5_000L
    }
}

fun isVisionQuestion(text: String): Boolean {
    val normalized = normalizeVietnamese(text)
    return VISION_PHRASES.any(normalized::contains)
}

private fun translateLabel(label: String): String = LABELS_VI[label.lowercase()] ?: label

private val VISION_PHRASES = listOf(
    "cai nay la gi",
    "cai nay la cai gi",
    "cai nay la vat gi",
    "day la gi",
    "day la cai gi",
    "day la vat gi",
    "do la gi",
    "do la cai gi",
    "vat nay la gi",
    "ban thay gi",
    "linh thay gi",
    "camera thay gi",
    "nhin thay gi",
    "truoc mat co gi",
    "truoc camera co gi",
)

private val LABELS_VI = mapOf(
    "person" to "một người",
    "bicycle" to "một chiếc xe đạp",
    "car" to "một chiếc ô tô",
    "motorcycle" to "một chiếc xe máy",
    "airplane" to "một chiếc máy bay",
    "bus" to "một chiếc xe buýt",
    "train" to "một đoàn tàu",
    "truck" to "một chiếc xe tải",
    "boat" to "một chiếc thuyền",
    "bird" to "một con chim",
    "cat" to "một con mèo",
    "dog" to "một con chó",
    "backpack" to "một chiếc ba lô",
    "umbrella" to "một chiếc ô",
    "bottle" to "một cái chai",
    "wine glass" to "một chiếc ly",
    "cup" to "một chiếc cốc",
    "fork" to "một chiếc nĩa",
    "knife" to "một con dao",
    "spoon" to "một chiếc thìa",
    "bowl" to "một chiếc bát",
    "banana" to "một quả chuối",
    "apple" to "một quả táo",
    "sandwich" to "một chiếc bánh sandwich",
    "orange" to "một quả cam",
    "broccoli" to "bông cải xanh",
    "carrot" to "một củ cà rốt",
    "chair" to "một chiếc ghế",
    "couch" to "một chiếc ghế sofa",
    "potted plant" to "một chậu cây",
    "bed" to "một chiếc giường",
    "dining table" to "một chiếc bàn",
    "toilet" to "một bồn cầu",
    "tv" to "một chiếc tivi",
    "laptop" to "một chiếc máy tính xách tay",
    "mouse" to "một con chuột máy tính",
    "remote" to "một chiếc điều khiển",
    "keyboard" to "một chiếc bàn phím",
    "cell phone" to "một chiếc điện thoại",
    "microwave" to "một chiếc lò vi sóng",
    "oven" to "một chiếc lò nướng",
    "toaster" to "một chiếc máy nướng bánh",
    "sink" to "một chiếc bồn rửa",
    "refrigerator" to "một chiếc tủ lạnh",
    "book" to "một quyển sách",
    "clock" to "một chiếc đồng hồ",
    "vase" to "một chiếc bình",
    "scissors" to "một chiếc kéo",
    "teddy bear" to "một chú gấu bông",
    "hair drier" to "một chiếc máy sấy tóc",
    "toothbrush" to "một chiếc bàn chải đánh răng",
)
