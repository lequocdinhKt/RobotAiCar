package com.one.robotassistant.feature.camera.detection

interface VisionDetector {
    suspend fun detect(frame: ImageFrame): List<Detection>
}

data class ImageFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
    val format: ImageFrameFormat,
    val planes: List<ImagePlane>,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be 0, 90, 180, or 270"
        }
    }
}

data class ImagePlane(
    val bytes: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
)

enum class ImageFrameFormat { YUV_420_888, RGBA_8888 }

data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: NormalizedRect,
) {
    init {
        require(confidence in 0f..1f) { "confidence must be normalized to 0..1" }
    }
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Bounding box coordinates must be normalized to 0..1"
        }
        require(left <= right && top <= bottom) { "Bounding box edges are inverted" }
    }
}
