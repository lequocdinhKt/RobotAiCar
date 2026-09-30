package com.one.robotassistant.feature.camera

import android.graphics.Paint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.one.robotassistant.feature.navigation.model.NavigationPath
import java.util.concurrent.Executors
import kotlin.math.max

@Composable
fun CameraRoute(
    viewModel: CameraViewModel,
    microphoneLabel: String,
    assistantStatus: String,
    navigationPath: NavigationPath?,
    onEyesRequested: () -> Unit,
    onMicrophoneRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }

    LaunchedEffect(assistantStatus, navigationPath) {
        viewModel.updateAssistantStatus(assistantStatus, navigationPath)
    }

    DisposableEffect(controller, lifecycleOwner, executor) {
        controller.setImageAnalysisAnalyzer(executor, viewModel::analyze)
        controller.bindToLifecycle(lifecycleOwner)
        viewModel.onScreenStarted()
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
            viewModel.onScreenStopped()
        }
    }

    CameraScreen(
        controller = controller,
        state = state,
        microphoneLabel = microphoneLabel,
        assistantStatus = assistantStatus,
        navigationPath = navigationPath,
        onEyesRequested = onEyesRequested,
        onMicrophoneRequested = onMicrophoneRequested,
        modifier = modifier,
    )
}

@Composable
private fun CameraScreen(
    controller: LifecycleCameraController,
    state: CameraUiState,
    microphoneLabel: String,
    assistantStatus: String,
    navigationPath: NavigationPath?,
    onEyesRequested: () -> Unit,
    onMicrophoneRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            update = { it.controller = controller },
            modifier = Modifier.fillMaxSize(),
        )

        DetectionOverlay(
            state = state,
            navigationPath = navigationPath,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .background(Color.Black.copy(alpha = 0.68f))
                .padding(10.dp),
        ) {
            Text("CAMERA: ${state.cameraState}", color = Color.White, fontSize = 12.sp)
            Text("DETECTOR: ${state.detectorState}", color = Color.White, fontSize = 12.sp)
            Text("FPS: %.1f  AI: %d ms".format(state.framesPerSecond, state.inferenceMillis), color = Color.White, fontSize = 12.sp)
            Text("OBJECTS: ${state.detections.size}", color = Color.White, fontSize = 12.sp)
            Text(assistantStatus, color = Color.White, fontSize = 12.sp)
            state.viewerUrl?.let { Text("LAN: $it", color = Color.White, fontSize = 12.sp) }
            state.errorMessage?.let { Text("ERROR: $it", color = Color.White, fontSize = 12.sp) }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onMicrophoneRequested) {
                Text(microphoneLabel, color = Color.White)
            }
            OutlinedButton(onClick = onEyesRequested) {
                Text("EYES", color = Color.White)
            }
        }
    }
}

@Composable
private fun DetectionOverlay(
    state: CameraUiState,
    navigationPath: NavigationPath?,
    modifier: Modifier = Modifier,
) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            style = Paint.Style.FILL
        }
    }
    val labelBackground = remember {
        Paint().apply { color = android.graphics.Color.argb(190, 0, 0, 0) }
    }

    Canvas(modifier = modifier) {
        if (state.frameWidth <= 0 || state.frameHeight <= 0) return@Canvas
        val scale = max(size.width / state.frameWidth, size.height / state.frameHeight)
        val scaledWidth = state.frameWidth * scale
        val scaledHeight = state.frameHeight * scale
        val offsetX = (size.width - scaledWidth) / 2f
        val offsetY = (size.height - scaledHeight) / 2f

        state.detections.forEach { detection ->
            val box = detection.boundingBox
            val left = offsetX + box.left * scaledWidth
            val top = offsetY + box.top * scaledHeight
            val right = offsetX + box.right * scaledWidth
            val bottom = offsetY + box.bottom * scaledHeight
            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4.dp.toPx()),
            )

            val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val width = labelPaint.measureText(label)
            val labelTop = (top - labelPaint.textSize - 10f).coerceAtLeast(0f)
            drawContext.canvas.nativeCanvas.apply {
                drawRect(left, labelTop, left + width + 14f, labelTop + labelPaint.textSize + 10f, labelBackground)
                drawText(label, left + 7f, labelTop + labelPaint.textSize, labelPaint)
            }
        }

        navigationPath?.points?.takeIf { it.size >= 2 }?.let { points ->
            points.zipWithNext().forEach { (from, to) ->
                drawLine(
                    color = Color.White,
                    start = Offset(from.x * size.width, from.y * size.height),
                    end = Offset(to.x * size.width, to.y * size.height),
                    strokeWidth = 5.dp.toPx(),
                )
            }
            val start = points.first()
            val target = points.last()
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(start.x * size.width, start.y * size.height))
            drawCircle(
                color = Color.White,
                radius = 14.dp.toPx(),
                center = Offset(target.x * size.width, target.y * size.height),
                style = Stroke(width = 4.dp.toPx()),
            )
        }
    }
}
