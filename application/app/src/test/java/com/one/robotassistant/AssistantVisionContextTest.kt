package com.one.robotassistant

import com.one.robotassistant.feature.assistant.vision.AssistantVisionContext
import com.one.robotassistant.feature.assistant.vision.isVisionQuestion
import com.one.robotassistant.feature.camera.detection.Detection
import com.one.robotassistant.feature.camera.detection.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantVisionContextTest {
    @Test
    fun recognizesVietnameseVisionQuestions() {
        assertTrue(isVisionQuestion("Linh ơi, cái này là cái gì?"))
        assertTrue(isVisionQuestion("Bạn nhìn thấy gì trước camera?"))
    }

    @Test
    fun describesFreshDetectionsInVietnamese() {
        val now = 10_000L
        val context = AssistantVisionContext(
            cameraRunning = true,
            detections = listOf(detection("cell phone", 0.91f)),
            updatedAtMillis = now,
        )

        assertEquals("Linh nhìn thấy một chiếc điện thoại.", context.spokenDescription(now))
    }

    private fun detection(label: String, confidence: Float) = Detection(
        label = label,
        confidence = confidence,
        boundingBox = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
    )
}
