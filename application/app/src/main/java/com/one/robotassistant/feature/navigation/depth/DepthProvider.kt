package com.one.robotassistant.feature.navigation.depth

import kotlinx.coroutines.flow.Flow

interface DepthProvider {
    val depthFrames: Flow<DepthFrame>

    fun start()
    fun stop()
}

data class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMillimeters: IntArray,
    val timestampNanos: Long,
)

// TODO(PHASE_2): implement with ARCore Raw Depth when device support is confirmed.
abstract class ArCoreDepthProvider : DepthProvider
