package com.one.robotassistant.feature.remoteviewer.server

interface RemoteViewerServer {
    suspend fun start()
    suspend fun stop()
    fun getViewerUrl(): String?
}

interface RemoteFramePublisher {
    suspend fun publish(frame: RemoteViewerFrame)
}

data class RemoteViewerFrame(
    val jpegBytes: ByteArray,
    val timestampMillis: Long,
    val debugJson: String? = null,
)

// TODO(PHASE_2): keep the HTTP/MJPEG boundary replaceable by WebRTC.
