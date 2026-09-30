package com.one.robotassistant.feature.camera.detection

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaPipeVisionDetector(context: Context) : VisionDetector, AutoCloseable {
    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setDelegate(Delegate.CPU)
                    .setModelAssetPath(MODEL_ASSET)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .build(),
    )

    override suspend fun detect(frame: ImageFrame): List<Detection> = withContext(Dispatchers.Default) {
        require(frame.format == ImageFrameFormat.RGBA_8888) {
            "MediaPipeVisionDetector expects RGBA_8888 frames"
        }
        val plane = frame.planes.firstOrNull() ?: return@withContext emptyList()
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(plane.bytes))
        val mpImage = BitmapImageBuilder(bitmap).build()
        try {
            detector.detect(mpImage).detections().mapNotNull { result ->
                val category = result.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val box = result.boundingBox()
                val left = (box.left / frame.width).coerceIn(0f, 1f)
                val top = (box.top / frame.height).coerceIn(0f, 1f)
                val right = (box.right / frame.width).coerceIn(left, 1f)
                val bottom = (box.bottom / frame.height).coerceIn(top, 1f)
                Detection(
                    label = category.displayName().ifBlank { category.categoryName() }.ifBlank { "object" },
                    confidence = category.score().coerceIn(0f, 1f),
                    boundingBox = NormalizedRect(left, top, right, bottom),
                )
            }
        } finally {
            mpImage.close()
            bitmap.recycle()
        }
    }

    override fun close() {
        detector.close()
    }

    companion object {
        private const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val SCORE_THRESHOLD = 0.45f
        private const val MAX_RESULTS = 8
    }
}
