package com.one.robotassistant.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.one.robotassistant.feature.assistant.AssistantViewModel
import com.one.robotassistant.feature.camera.CameraRoute
import com.one.robotassistant.feature.camera.CameraRuntimeState
import com.one.robotassistant.feature.camera.CameraViewModel
import com.one.robotassistant.feature.eyes.EyesScreen
import kotlinx.coroutines.flow.collectLatest

enum class OneDestination {
    CAMERA,
    EYES,
}

@Composable
fun OneApp(
    modifier: Modifier = Modifier,
    assistantViewModel: AssistantViewModel = viewModel(),
    cameraViewModel: CameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val assistantState by assistantViewModel.uiState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(OneDestination.EYES) }
    var pendingPermissionAction by remember { mutableStateOf<PermissionAction?>(null) }

    LaunchedEffect(cameraViewModel, assistantViewModel) {
        cameraViewModel.uiState.collectLatest { cameraState ->
            assistantViewModel.updateVisionContext(
                cameraRunning = cameraState.cameraState == CameraRuntimeState.RUNNING,
                detections = cameraState.detections,
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        when (pendingPermissionAction) {
            PermissionAction.OPEN_CAMERA -> {
                if (results[Manifest.permission.CAMERA] == true || context.hasPermission(Manifest.permission.CAMERA)) {
                    destination = OneDestination.CAMERA
                } else {
                    assistantViewModel.reportPermissionDenied("camera")
                }
            }
            PermissionAction.ENABLE_MICROPHONE -> {
                if (results[Manifest.permission.RECORD_AUDIO] == true || context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    assistantViewModel.setMicrophoneEnabled(true)
                } else {
                    assistantViewModel.reportPermissionDenied("microphone")
                }
            }
            null -> Unit
        }
        pendingPermissionAction = null
    }

    LaunchedEffect(Unit) {
        if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            assistantViewModel.setMicrophoneEnabled(true)
        } else {
            pendingPermissionAction = PermissionAction.ENABLE_MICROPHONE
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    LaunchedEffect(cameraViewModel, assistantViewModel) {
        assistantViewModel.setVisionSnapshotProvider(cameraViewModel::latestVisionSnapshot)
    }

    val microphoneLabel = when {
        !assistantState.microphoneEnabled -> "MIC TẮT"
        assistantState.speechState.name == "LISTENING" -> "MIC ĐANG NGHE"
        assistantState.speechState.name == "PROCESSING" -> "MIC ĐANG XỬ LÝ"
        assistantState.speechState.name == "ERROR" -> "MIC LỖI · CHẠM THỬ LẠI"
        else -> "MIC ĐANG KHỞI ĐỘNG"
    }
    val assistantStatus = buildString {
        append("LINH: ")
        append(
            when (assistantState.oneState) {
                com.one.robotassistant.core.model.OneState.SLEEPING -> "MIC TẮT"
                com.one.robotassistant.core.model.OneState.AWAKE -> "ĐÃ THỨC"
                com.one.robotassistant.core.model.OneState.LISTENING -> "ĐANG NGHE LỆNH"
                com.one.robotassistant.core.model.OneState.THINKING -> "ĐANG SUY NGHĨ"
                com.one.robotassistant.core.model.OneState.SPEAKING -> "ĐANG TRẢ LỜI"
                com.one.robotassistant.core.model.OneState.EXECUTING_COMMAND -> "ĐANG THỰC HIỆN"
                com.one.robotassistant.core.model.OneState.NAVIGATING -> "ĐANG DI CHUYỂN"
                com.one.robotassistant.core.model.OneState.ERROR -> "GẶP LỖI"
            },
        )
        append(if (assistantState.usingGroq) " • AI GROQ" else " • AI OFFLINE")
        if (assistantState.lastTranscript.isNotBlank()) {
            append(" • “")
            append(assistantState.lastTranscript.take(48))
            append('”')
        }
        assistantState.errorMessage?.let {
            append(" • ")
            append(it.take(80))
        }
    }

    val requestMicrophone = {
        if (assistantState.microphoneEnabled && assistantState.speechState.name == "ERROR") {
            assistantViewModel.restartMicrophone()
        } else if (assistantState.microphoneEnabled) {
            assistantViewModel.setMicrophoneEnabled(false)
        } else if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            assistantViewModel.setMicrophoneEnabled(true)
        } else {
            pendingPermissionAction = PermissionAction.ENABLE_MICROPHONE
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    when (destination) {
        OneDestination.EYES -> EyesScreen(
            eyeState = assistantState.eyeState,
            visibleResponse = assistantState.visibleResponseText,
            debugControlsVisible = assistantState.debugControlsVisible,
            onToggleDebug = assistantViewModel::toggleDebugControls,
            onExpressionSelected = assistantViewModel::showExpression,
            microphoneLabel = microphoneLabel,
            statusText = assistantStatus,
            onMicrophoneRequested = requestMicrophone,
            onCameraRequested = {
                if (context.hasPermission(Manifest.permission.CAMERA)) {
                    destination = OneDestination.CAMERA
                } else {
                    pendingPermissionAction = PermissionAction.OPEN_CAMERA
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
            },
            onDebugCommandSubmitted = assistantViewModel::submitText,
            modifier = modifier,
        )

        OneDestination.CAMERA -> CameraRoute(
            viewModel = cameraViewModel,
            microphoneLabel = microphoneLabel,
            assistantStatus = assistantStatus,
            navigationPath = assistantState.navigationPath,
            onEyesRequested = { destination = OneDestination.EYES },
            onMicrophoneRequested = requestMicrophone,
            modifier = modifier,
        )
    }
}

private enum class PermissionAction { OPEN_CAMERA, ENABLE_MICROPHONE }

private fun android.content.Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
