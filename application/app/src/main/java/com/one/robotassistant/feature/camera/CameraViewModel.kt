package com.one.robotassistant.feature.camera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.one.robotassistant.feature.camera.detection.Detection
import com.one.robotassistant.feature.camera.detection.ImageFrame
import com.one.robotassistant.feature.camera.detection.ImageFrameFormat
import com.one.robotassistant.feature.camera.detection.ImagePlane
import com.one.robotassistant.feature.camera.detection.MediaPipeVisionDetector
import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.remoteviewer.server.MjpegRemoteViewerServer
import com.one.robotassistant.feature.remoteviewer.server.RemoteViewerFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class CameraUiState(
    val cameraState: CameraRuntimeState = CameraRuntimeState.STOPPED,
    val detectorState: DetectorRuntimeState = DetectorRuntimeState.STARTING,
    val detections: List<Detection> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val framesPerSecond: Float = 0f,
    val inferenceMillis: Long = 0,
    val viewerUrl: String? = null,
    val errorMessage: String? = null,
)

enum class CameraRuntimeState { STOPPED, STARTING, RUNNING, ERROR }
enum class DetectorRuntimeState { STARTING, RUNNING, ERROR }

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val processingFrame = AtomicBoolean(false)
    private val viewerServer = MjpegRemoteViewerServer()
    private var detector: MediaPipeVisionDetector? = null
    private var frameCount = 0
    private var fpsWindowStart = System.currentTimeMillis()
    @Volatile
    private var assistantStatus: String = "SLEEPING"
    @Volatile
    private var navigationPath: NavigationPath? = null
    @Volatile
    private var latestVisionJpeg: ByteArray? = null
    private var lastVisionSnapshotAtMillis = 0L

    fun updateAssistantStatus(status: String, path: NavigationPath?) {
        assistantStatus = status
        navigationPath = path
    }

    fun latestVisionSnapshot(): ByteArray? = latestVisionJpeg?.copyOf()

    fun onScreenStarted() {
        _uiState.update { it.copy(cameraState = CameraRuntimeState.STARTING) }
        viewModelScope.launch(Dispatchers.IO) {
            viewerServer.start()
            _uiState.update { current ->
                current.copy(
                    viewerUrl = viewerServer.getViewerUrl(),
                    errorMessage = viewerServer.lastError ?: current.errorMessage,
                )
            }
        }
    }

    fun onScreenStopped() {
        _uiState.update { it.copy(cameraState = CameraRuntimeState.STOPPED) }
        viewModelScope.launch(Dispatchers.IO) { viewerServer.stop() }
    }

    fun analyze(image: ImageProxy) {
        if (!processingFrame.compareAndSet(false, true)) {
            image.close()
            return
        }

        val rotationDegrees = image.imageInfo.rotationDegrees
        val sourceBitmap = runCatching { image.toRgbaBitmap() }
            .onFailure { error ->
                image.close()
                processingFrame.set(false)
                _uiState.update {
                    it.copy(
                        cameraState = CameraRuntimeState.ERROR,
                        errorMessage = error.message ?: "Không đọc được camera frame",
                    )
                }
            }
            .getOrNull() ?: return
        image.close()

        viewModelScope.launch(Dispatchers.Default) {
            var rotatedBitmap: Bitmap? = null
            try {
                rotatedBitmap = sourceBitmap.rotate(rotationDegrees)
                val frame = rotatedBitmap.toImageFrame()
                val start = System.currentTimeMillis()
                val results = detector().detect(frame)
                val inferenceMillis = System.currentTimeMillis() - start
                val fps = updateFps()
                val newState = CameraUiState(
                    cameraState = CameraRuntimeState.RUNNING,
                    detectorState = DetectorRuntimeState.RUNNING,
                    detections = results,
                    frameWidth = rotatedBitmap.width,
                    frameHeight = rotatedBitmap.height,
                    framesPerSecond = fps,
                    inferenceMillis = inferenceMillis,
                    viewerUrl = viewerServer.getViewerUrl(),
                    errorMessage = viewerServer.lastError,
                )
                _uiState.value = newState
                publishRemoteFrame(rotatedBitmap, newState)
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        detectorState = DetectorRuntimeState.ERROR,
                        errorMessage = error.message ?: "Object detector gặp lỗi",
                    )
                }
            } finally {
                if (rotatedBitmap !== sourceBitmap) rotatedBitmap?.recycle()
                sourceBitmap.recycle()
                processingFrame.set(false)
            }
        }
    }

    private fun detector(): MediaPipeVisionDetector = detector ?: MediaPipeVisionDetector(
        getApplication<Application>().applicationContext,
    ).also { detector = it }

    private fun updateFps(): Float {
        frameCount += 1
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        val previous = _uiState.value.framesPerSecond
        if (elapsed < 1_000) return previous
        val fps = frameCount * 1_000f / elapsed.coerceAtLeast(1)
        frameCount = 0
        fpsWindowStart = now
        return fps
    }

    private suspend fun publishRemoteFrame(bitmap: Bitmap, state: CameraUiState) {
        updateVisionSnapshot(bitmap)
        val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (bitmap.width / 220f).coerceAtLeast(3f)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (bitmap.width / 32f).coerceAtLeast(18f)
            style = Paint.Style.FILL
        }
        val backgroundPaint = Paint().apply { color = Color.argb(190, 0, 0, 0) }
        state.detections.forEach { detection ->
            val box = detection.boundingBox
            val left = box.left * bitmap.width
            val top = box.top * bitmap.height
            val right = box.right * bitmap.width
            val bottom = box.bottom * bitmap.height
            canvas.drawRect(left, top, right, bottom, linePaint)
            val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val labelWidth = textPaint.measureText(label)
            val labelTop = (top - textPaint.textSize - 8f).coerceAtLeast(0f)
            canvas.drawRect(left, labelTop, left + labelWidth + 12f, labelTop + textPaint.textSize + 8f, backgroundPaint)
            canvas.drawText(label, left + 6f, labelTop + textPaint.textSize, textPaint)
        }
        navigationPath?.points?.takeIf { it.size >= 2 }?.let { points ->
            points.zipWithNext().forEach { (from, to) ->
                canvas.drawLine(
                    from.x * bitmap.width,
                    from.y * bitmap.height,
                    to.x * bitmap.width,
                    to.y * bitmap.height,
                    linePaint,
                )
            }
            val target = points.last()
            canvas.drawCircle(target.x * bitmap.width, target.y * bitmap.height, 18f, linePaint)
        }
        canvas.drawText(
            "FPS %.1f  AI %d ms".format(state.framesPerSecond, state.inferenceMillis),
            16f,
            bitmap.height - 20f,
            textPaint,
        )
        val output = ByteArrayOutputStream()
        annotated.compress(Bitmap.CompressFormat.JPEG, 72, output)
        annotated.recycle()

        val debug = JSONObject()
            .put("camera", state.cameraState.name)
            .put("detector", state.detectorState.name)
            .put("fps", state.framesPerSecond)
            .put("inference_ms", state.inferenceMillis)
            .put("objects", state.detections.size)
            .put("assistant", assistantStatus)
            .put("navigation", if (navigationPath == null) "IDLE" else "NAVIGATING")
            .toString()
        val jpegBytes = output.toByteArray()
        viewerServer.publish(
            RemoteViewerFrame(
                jpegBytes = jpegBytes,
                timestampMillis = System.currentTimeMillis(),
                debugJson = debug,
            ),
        )
    }

    private fun updateVisionSnapshot(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastVisionSnapshotAtMillis < VISION_SNAPSHOT_INTERVAL_MILLIS) return
        lastVisionSnapshotAtMillis = now
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        val scale = (VISION_SNAPSHOT_MAX_DIMENSION.toFloat() / maxDimension).coerceAtMost(1f)
        val visionBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        visionBitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
        if (visionBitmap !== bitmap) visionBitmap.recycle()
        latestVisionJpeg = output.toByteArray()
    }

    override fun onCleared() {
        detector?.close()
        detector = null
        super.onCleared()
    }

    companion object {
        private const val VISION_SNAPSHOT_INTERVAL_MILLIS = 600L
        private const val VISION_SNAPSHOT_MAX_DIMENSION = 1_280
    }
}

private fun ImageProxy.toRgbaBitmap(): Bitmap {
    val plane = planes.firstOrNull() ?: error("Camera frame has no RGBA plane")
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    require(pixelStride == 4) { "Expected RGBA pixel stride 4, got $pixelStride" }
    val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
    val paddedWidth = width + rowPadding / pixelStride
    plane.buffer.rewind()
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(plane.buffer)
    if (paddedWidth == width) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}

private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return this
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.toImageFrame(): ImageFrame {
    val buffer = ByteBuffer.allocate(byteCount)
    copyPixelsToBuffer(buffer)
    return ImageFrame(
        width = width,
        height = height,
        rotationDegrees = 0,
        timestampNanos = System.nanoTime(),
        format = ImageFrameFormat.RGBA_8888,
        planes = listOf(
            ImagePlane(
                bytes = buffer.array(),
                rowStride = width * 4,
                pixelStride = 4,
            ),
        ),
    )
}
