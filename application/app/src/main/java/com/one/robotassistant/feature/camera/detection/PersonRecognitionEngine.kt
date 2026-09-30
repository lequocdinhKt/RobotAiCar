package com.one.robotassistant.feature.camera.detection

interface PersonRecognitionEngine {
    suspend fun identify(face: FaceData): PersonIdentity?
}

data class FaceData(
    val frame: ImageFrame,
    val boundingBox: NormalizedRect,
)

data class PersonIdentity(
    val id: String,
    val displayName: String,
    val confidence: Float,
)

// TODO(PHASE_2): face detection -> crop -> embedding -> registered-person comparison.
