package com.one.robotassistant.feature.navigation.depth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeDepthProvider : DepthProvider {
    private val frames = MutableSharedFlow<DepthFrame>(extraBufferCapacity = 1)
    override val depthFrames: Flow<DepthFrame> = frames.asSharedFlow()

    private var isStarted = false

    override fun start() {
        isStarted = true
    }

    override fun stop() {
        isStarted = false
    }

    fun emit(frame: DepthFrame): Boolean = isStarted && frames.tryEmit(frame)
}
