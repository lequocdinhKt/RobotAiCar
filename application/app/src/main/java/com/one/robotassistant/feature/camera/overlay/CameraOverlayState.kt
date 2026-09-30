package com.one.robotassistant.feature.camera.overlay

import com.one.robotassistant.feature.camera.detection.Detection
import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.navigation.model.NavigationTarget

data class CameraOverlayState(
    val detections: List<Detection> = emptyList(),
    val navigationPath: NavigationPath? = null,
    val navigationTarget: NavigationTarget? = null,
    val framesPerSecond: Float = 0f,
)
