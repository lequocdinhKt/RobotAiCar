package com.one.robotassistant.feature.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.one.robotassistant.OneApplication
import com.one.robotassistant.core.debug.AiDebugState
import com.one.robotassistant.core.debug.ComponentState
import com.one.robotassistant.core.debug.DebugState
import com.one.robotassistant.core.debug.Esp32DebugState
import com.one.robotassistant.core.debug.NavigationDebugState
import com.one.robotassistant.core.model.OneState
import com.one.robotassistant.core.util.AndroidOneLogger
import com.one.robotassistant.feature.assistant.ai.AiService
import com.one.robotassistant.feature.assistant.ai.FakeAiService
import com.one.robotassistant.feature.assistant.ai.GroqAiService
import com.one.robotassistant.feature.assistant.intent.LocalIntentRouter
import com.one.robotassistant.feature.assistant.intent.OneIntent
import com.one.robotassistant.feature.assistant.intent.type
import com.one.robotassistant.feature.assistant.speech.AndroidSpeechToTextEngine
import com.one.robotassistant.feature.assistant.speech.AndroidTextToSpeechOutput
import com.one.robotassistant.feature.assistant.speech.SpeechRecognitionState
import com.one.robotassistant.feature.assistant.speech.SpeechTranscript
import com.one.robotassistant.feature.assistant.vision.AssistantVisionContext
import com.one.robotassistant.feature.assistant.vision.isVisionQuestion
import com.one.robotassistant.feature.camera.detection.Detection
import com.one.robotassistant.feature.eyes.model.EyeExpression
import com.one.robotassistant.feature.eyes.model.EyeState
import com.one.robotassistant.feature.eyes.model.EyeTarget
import com.one.robotassistant.feature.navigation.FakeNavigationEngine
import com.one.robotassistant.feature.navigation.model.NavigationPath
import com.one.robotassistant.feature.robot.command.RobotCommand
import com.one.robotassistant.feature.robot.controller.DebugRobotController
import com.one.robotassistant.feature.robot.controller.execute
import com.one.robotassistant.feature.robot.controller.spokenConfirmation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class AssistantUiState(
    val oneState: OneState = OneState.SLEEPING,
    val eyeState: EyeState = EyeState(),
    val responseText: String = "",
    val visibleResponseText: String = "",
    val microphoneEnabled: Boolean = false,
    val speechState: SpeechRecognitionState = SpeechRecognitionState.IDLE,
    val lastTranscript: String = "",
    val debugControlsVisible: Boolean = false,
    val usingGroq: Boolean = false,
    val errorMessage: String? = null,
    val navigationPath: NavigationPath? = null,
    val debug: DebugState = DebugState(),
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val logger = AndroidOneLogger()
    private val configuration = getApplication<OneApplication>().configuration
    private val speechEngine = AndroidSpeechToTextEngine(application)
    private val speechOutput = AndroidTextToSpeechOutput(application)
    private val intentRouter = LocalIntentRouter()
    private val robotController = DebugRobotController(logger)
    private val navigationEngine = FakeNavigationEngine()
    private val fakeAiService = FakeAiService()
    private val aiService: AiService = if (configuration.hasGroqApiKey) {
        GroqAiService(configuration)
    } else {
        fakeAiService
    }

    private val _uiState = MutableStateFlow(
        AssistantUiState(
            usingGroq = configuration.hasGroqApiKey,
            debug = DebugState(esp32 = Esp32DebugState.DEBUG),
        ),
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var responseJob: Job? = null
    private var utteranceJob: Job? = null
    private var utteranceBuffer = ""
    private var visionContext = AssistantVisionContext()
    private var visionSnapshotProvider: (() -> ByteArray?)? = null

    init {
        viewModelScope.launch {
            speechEngine.transcripts.collect { transcript ->
                onTranscript(transcript)
            }
        }
        viewModelScope.launch {
            speechEngine.state.collectLatest { state ->
                _uiState.update { current ->
                    current.copy(
                        speechState = state,
                        debug = current.debug.copy(
                            microphone = when {
                                !current.microphoneEnabled -> ComponentState.OFF
                                state == SpeechRecognitionState.LISTENING -> ComponentState.LISTENING
                                state == SpeechRecognitionState.ERROR -> ComponentState.ERROR
                                else -> ComponentState.RUNNING
                            },
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(Random.nextLong(3_200L, 7_500L))
                val current = _uiState.value
                if (current.microphoneEnabled && current.oneState == OneState.LISTENING) {
                    val expressions = listOf(
                        EyeExpression.NORMAL,
                        EyeExpression.NORMAL,
                        EyeExpression.HAPPY,
                        EyeExpression.CONFUSED,
                        EyeExpression.SAD,
                    )
                    _uiState.update {
                        it.copy(
                            eyeState = EyeState(
                                expression = expressions.random(),
                                target = EyeTarget(
                                    horizontal = Random.nextFloat() * 1.4f - 0.7f,
                                    vertical = Random.nextFloat() * 0.5f - 0.25f,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        if (enabled == _uiState.value.microphoneEnabled) return
        if (enabled) {
            speechEngine.startListening()
            _uiState.update { current ->
                current.copy(
                    microphoneEnabled = true,
                    oneState = OneState.LISTENING,
                    eyeState = current.eyeState.copy(expression = EyeExpression.LISTENING),
                    errorMessage = null,
                    debug = current.debug.copy(
                        oneState = OneState.LISTENING,
                        microphone = ComponentState.LISTENING,
                        eyes = EyeExpression.LISTENING,
                    ),
                )
            }
            logger.debug("STT", "Mic liên tục đã bật; xử lý sau 2 giây im lặng")
        } else {
            utteranceJob?.cancel()
            utteranceBuffer = ""
            speechEngine.stopListening()
            speechOutput.stop()
            responseJob?.cancel()
            _uiState.update { current ->
                current.copy(
                    microphoneEnabled = false,
                    oneState = OneState.SLEEPING,
                    eyeState = EyeState(),
                    visibleResponseText = "",
                    debug = current.debug.copy(
                        oneState = OneState.SLEEPING,
                        microphone = ComponentState.OFF,
                        eyes = EyeExpression.NORMAL,
                    ),
                )
            }
        }
    }

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        utteranceJob?.cancel()
        utteranceBuffer = ""
        speechEngine.stopListening()
        processCommand(trimmed)
    }

    fun restartMicrophone() {
        if (!_uiState.value.microphoneEnabled) return
        speechEngine.stopListening()
        viewModelScope.launch {
            delay(300)
            if (_uiState.value.microphoneEnabled) speechEngine.startListening()
        }
    }

    fun showExpression(expression: EyeExpression) {
        _uiState.update { current ->
            current.copy(
                eyeState = current.eyeState.copy(expression = expression),
                debug = current.debug.copy(eyes = expression),
            )
        }
        logger.debug("EYES", expression.name)
    }

    fun lookAt(target: EyeTarget) {
        _uiState.update { current -> current.copy(eyeState = current.eyeState.copy(target = target)) }
    }

    fun toggleDebugControls() {
        _uiState.update { it.copy(debugControlsVisible = !it.debugControlsVisible) }
    }

    fun reportPermissionDenied(permissionName: String) {
        _uiState.update { it.copy(errorMessage = "Cần quyền $permissionName để dùng chức năng này.") }
    }

    fun updateVisionContext(cameraRunning: Boolean, detections: List<Detection>) {
        val now = System.currentTimeMillis()
        if (
            cameraRunning &&
            detections.isEmpty() &&
            visionContext.cameraRunning &&
            visionContext.detections.isNotEmpty() &&
            now - visionContext.updatedAtMillis <= VISION_RESULT_HOLD_MILLIS
        ) {
            return
        }
        visionContext = AssistantVisionContext(
            cameraRunning = cameraRunning,
            detections = detections,
            updatedAtMillis = now,
        )
    }

    fun setVisionSnapshotProvider(provider: () -> ByteArray?) {
        visionSnapshotProvider = provider
    }

    private fun onTranscript(transcript: SpeechTranscript) {
        val text = transcript.text
        _uiState.update { current ->
            current.copy(
                lastTranscript = text,
                debug = current.debug.copy(lastSpeech = text),
            )
        }
        if (!transcript.isFinal) return
        logger.debug("STT", text)
        if (shouldIgnoreTranscript(transcript)) return
        val normalizedNew = text.trim()
        if (!utteranceBuffer.endsWith(normalizedNew, ignoreCase = true)) {
            utteranceBuffer = listOf(utteranceBuffer, normalizedNew)
                .filter(String::isNotBlank)
                .joinToString(" ")
        }
        utteranceJob?.cancel()
        utteranceJob = viewModelScope.launch {
            delay(UTTERANCE_SILENCE_MILLIS)
            val command = utteranceBuffer.trim()
            utteranceBuffer = ""
            if (command.isNotEmpty() && _uiState.value.microphoneEnabled) {
                speechEngine.stopListening()
                processCommand(command)
            }
        }
    }

    private fun processCommand(text: String) {
        if (isVisionQuestion(text)) {
            viewModelScope.launch { handleVisionQuestion(text) }
            return
        }
        val intent = intentRouter.route(text)
        logger.debug("INTENT", intent.type.name)
        _uiState.update { current ->
            current.copy(debug = current.debug.copy(lastIntent = intent.type))
        }

        viewModelScope.launch {
            when (intent) {
                is OneIntent.Conversation -> handleConversation(intent.text)
                is OneIntent.Emotion -> handleEmotion(intent.expression)
                is OneIntent.RobotMovement -> handleRobotCommand(intent.command)
                is OneIntent.Navigation -> {
                    transitionTo(OneState.NAVIGATING, EyeExpression.THINKING)
                    _uiState.update { current ->
                        current.copy(debug = current.debug.copy(navigation = NavigationDebugState.PLANNING))
                    }
                    navigationEngine.navigateTo(intent.target)
                    _uiState.update { current -> current.copy(navigationPath = navigationEngine.currentPath.value) }
                    _uiState.update { current ->
                        current.copy(debug = current.debug.copy(navigation = NavigationDebugState.NAVIGATING))
                    }
                    renderAndSpeak("Tôi đang lập đường đi tới vị trí của bạn.")
                }
            }
        }
    }

    private suspend fun handleVisionQuestion(text: String) {
        val now = System.currentTimeMillis()
        if (!visionContext.isFresh(now)) {
            renderAndSpeak(visionContext.spokenDescription(now))
            return
        }
        val snapshot = visionSnapshotProvider?.invoke()
        if (snapshot == null || !configuration.hasGroqApiKey) {
            renderAndSpeak(visionContext.spokenDescription(now))
            return
        }
        transitionTo(OneState.THINKING, EyeExpression.THINKING)
        _uiState.update { current -> current.copy(debug = current.debug.copy(ai = AiDebugState.THINKING)) }
        val response = runCatching {
            aiService.askWithImage(text, snapshot, visionContext.detectorSummary())
        }.onFailure { error ->
            logger.error("VISION", error.message ?: "Groq Vision failed", error)
            _uiState.update { it.copy(errorMessage = "Vision lỗi, dùng MediaPipe: ${error.message}") }
        }.getOrElse {
            com.one.robotassistant.feature.assistant.ai.AiResponse(visionContext.spokenDescription(now))
        }
        _uiState.update { current -> current.copy(debug = current.debug.copy(ai = AiDebugState.IDLE)) }
        renderAndSpeak(response.text)
    }

    private fun shouldIgnoreTranscript(transcript: SpeechTranscript): Boolean {
        val normalized = com.one.robotassistant.feature.assistant.intent.normalizeVietnamese(transcript.text)
        if (normalized.length < 2 || normalized in NOISE_UTTERANCES) return true
        return transcript.confidence?.let { it in 0f..MIN_ACCEPTED_CONFIDENCE } == true
    }

    private suspend fun handleConversation(text: String) {
        transitionTo(OneState.THINKING, EyeExpression.THINKING)
        _uiState.update { current ->
            current.copy(debug = current.debug.copy(ai = AiDebugState.THINKING))
        }
        val response = runCatching { aiService.ask(text) }
            .onFailure { error ->
                logger.error("AI", error.message ?: "Groq failed", error)
                _uiState.update { it.copy(errorMessage = "Groq lỗi, đang dùng trả lời offline: ${error.message}") }
            }
            .getOrElse { fakeAiService.ask(text) }
        _uiState.update { current -> current.copy(debug = current.debug.copy(ai = AiDebugState.IDLE)) }
        renderAndSpeak(response.text)
    }

    private suspend fun handleRobotCommand(command: RobotCommand) {
        transitionTo(OneState.EXECUTING_COMMAND, EyeExpression.LISTENING)
        if (command == RobotCommand.STOP) {
            navigationEngine.stop()
            _uiState.update { current ->
                current.copy(
                    navigationPath = null,
                    debug = current.debug.copy(navigation = NavigationDebugState.IDLE),
                )
            }
        }
        robotController.execute(command)
        _uiState.update { current ->
            current.copy(debug = current.debug.copy(lastRobotCommand = command))
        }
        renderAndSpeak(command.spokenConfirmation())
    }

    private fun handleEmotion(expression: EyeExpression) {
        val response = when (expression) {
            EyeExpression.HAPPY -> "Tôi vui lên rồi đây."
            EyeExpression.SAD -> "Tôi đang hơi buồn."
            EyeExpression.CONFUSED -> "Tôi đang thắc mắc."
            EyeExpression.DIZZY -> "Tôi chóng mặt rồi."
            EyeExpression.NORMAL -> "Tôi bình thường lại rồi."
            else -> "Tôi đã đổi biểu cảm."
        }
        renderAndSpeak(response, finalExpression = expression)
    }

    private fun renderAndSpeak(
        text: String,
        finalExpression: EyeExpression = EyeExpression.NORMAL,
    ) {
        speechOutput.stop()
        responseJob?.cancel()
        responseJob = viewModelScope.launch {
            speechEngine.stopListening()
            _uiState.update { current ->
                current.copy(
                    oneState = OneState.SPEAKING,
                    eyeState = EyeState(
                        expression = EyeExpression.SPEAKING,
                        target = EyeTarget(horizontal = 0.22f, vertical = 0.72f),
                    ),
                    responseText = text,
                    visibleResponseText = "",
                    debug = current.debug.copy(
                        oneState = OneState.SPEAKING,
                        eyes = EyeExpression.SPEAKING,
                    ),
                )
            }
            logger.debug("TTS", text)

            val typing = launch {
                text.indices.forEach { index ->
                    val lineProgress = ((index + 1) % 32) / 31f
                    val lineIndex = ((index + 1) / 32).coerceAtMost(3)
                    val readingTarget = EyeTarget(
                        horizontal = -0.34f + lineProgress * 0.68f,
                        vertical = 0.56f + lineIndex * 0.07f,
                    )
                    _uiState.update {
                        it.copy(
                            visibleResponseText = text.substring(0, index + 1),
                            eyeState = it.eyeState.copy(target = readingTarget),
                        )
                    }
                    val character = text[index]
                    delay(if (character in ".,!?;") 95 else 34)
                }
            }
            runCatching { speechOutput.speak(text) }
                .onFailure { error ->
                    logger.error("TTS", error.message ?: "TTS failed", error)
                    _uiState.update { it.copy(errorMessage = "Không đọc được loa: ${error.message}") }
                }
            typing.join()
            delay(650)

            if (_uiState.value.microphoneEnabled) {
                _uiState.update { current ->
                    current.copy(
                        oneState = OneState.LISTENING,
                        eyeState = EyeState(expression = finalExpression),
                        responseText = "",
                        visibleResponseText = "",
                        debug = current.debug.copy(
                            oneState = OneState.LISTENING,
                            eyes = finalExpression,
                        ),
                    )
                }
                speechEngine.startListening()
            } else {
                _uiState.update { current ->
                    current.copy(
                        oneState = OneState.SLEEPING,
                        eyeState = EyeState(expression = finalExpression),
                        responseText = "",
                        visibleResponseText = "",
                        debug = current.debug.copy(
                            oneState = OneState.SLEEPING,
                            eyes = finalExpression,
                        ),
                    )
                }
            }
        }
    }

    private fun transitionTo(state: OneState, expression: EyeExpression) {
        _uiState.update { current ->
            current.copy(
                oneState = state,
                eyeState = EyeState(expression = expression),
                debug = current.debug.copy(oneState = state, eyes = expression),
            )
        }
    }

    override fun onCleared() {
        responseJob?.cancel()
        utteranceJob?.cancel()
        speechEngine.close()
        speechOutput.close()
        super.onCleared()
    }

    companion object {
        // Android's recognizer already waits for end-of-speech; this brings total silence-to-submit near 2 seconds.
        private const val UTTERANCE_SILENCE_MILLIS = 750L
        private const val VISION_RESULT_HOLD_MILLIS = 3_000L
        private const val MIN_ACCEPTED_CONFIDENCE = 0.30f
        private val NOISE_UTTERANCES = setOf("a", "ah", "o", "oh", "u", "uh", "um", "hmm")
    }
}
