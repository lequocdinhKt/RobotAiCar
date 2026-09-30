package com.one.robotassistant.feature.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechToTextEngine(
    context: Context,
) : SpeechToTextEngine, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag("vi-VN").toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.forLanguageTag("vi-VN").toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
    }

    private val _state = MutableStateFlow(SpeechRecognitionState.IDLE)
    override val state: StateFlow<SpeechRecognitionState> = _state.asStateFlow()

    private val _transcripts = MutableSharedFlow<SpeechTranscript>(extraBufferCapacity = 16)
    override val transcripts: Flow<SpeechTranscript> = _transcripts.asSharedFlow()

    private var active = false
    private var sessionRunning = false
    private val restartRunnable = Runnable { startSessionIfNeeded() }
    private val sessionWatchdog = Runnable { recoverHungSession() }
    private val resultWatchdog = Runnable { recoverHungSession() }

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                sessionRunning = true
                _state.value = SpeechRecognitionState.LISTENING
                mainHandler.removeCallbacks(sessionWatchdog)
                mainHandler.postDelayed(sessionWatchdog, SESSION_TIMEOUT_MS)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.value = SpeechRecognitionState.PROCESSING
                mainHandler.removeCallbacks(resultWatchdog)
                mainHandler.postDelayed(resultWatchdog, RESULT_TIMEOUT_MS)
            }

            override fun onError(error: Int) {
                finishSession()
                _state.value = if (error in RECOVERABLE_ERRORS) {
                    SpeechRecognitionState.IDLE
                } else {
                    SpeechRecognitionState.ERROR
                }
                if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) BUSY_RESTART_DELAY_MS else RESTART_DELAY_MS)
                }
            }

            override fun onResults(results: Bundle?) {
                finishSession()
                results.toTranscript(isFinal = true)?.let(_transcripts::tryEmit)
                _state.value = SpeechRecognitionState.IDLE
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.toTranscript(isFinal = false)?.let(_transcripts::tryEmit)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    override fun startListening() {
        active = true
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.post(restartRunnable)
    }

    override fun stopListening() {
        active = false
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacks(sessionWatchdog)
        mainHandler.removeCallbacks(resultWatchdog)
        mainHandler.post {
            runCatching { recognizer.cancel() }
            sessionRunning = false
            _state.value = SpeechRecognitionState.IDLE
        }
    }

    private fun startSessionIfNeeded() {
        if (!active || sessionRunning) return
        mainHandler.removeCallbacks(restartRunnable)
        runCatching {
            sessionRunning = true
            recognizer.startListening(recognizerIntent)
            _state.value = SpeechRecognitionState.IDLE
        }.onFailure {
            sessionRunning = false
            _state.value = SpeechRecognitionState.ERROR
            scheduleRestart()
        }
    }

    private fun scheduleRestart(delayMillis: Long = RESTART_DELAY_MS) {
        if (!active) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMillis)
    }

    private fun finishSession() {
        sessionRunning = false
        mainHandler.removeCallbacks(sessionWatchdog)
        mainHandler.removeCallbacks(resultWatchdog)
    }

    private fun recoverHungSession() {
        if (!active || !sessionRunning) return
        runCatching { recognizer.cancel() }
        finishSession()
        _state.value = SpeechRecognitionState.IDLE
        scheduleRestart(BUSY_RESTART_DELAY_MS)
    }

    override fun close() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { recognizer.destroy() }
    }

    private fun Bundle?.toTranscript(isFinal: Boolean): SpeechTranscript? {
        val candidates = this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
        val best = candidates.firstOrNull() ?: return null
        return SpeechTranscript(
            text = best,
            isFinal = isFinal,
            alternatives = candidates.drop(1),
            confidence = this
                ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                ?.firstOrNull()
                ?.takeIf { it >= 0f },
        )
    }

    companion object {
        private const val RESTART_DELAY_MS = 350L
        private const val BUSY_RESTART_DELAY_MS = 900L
        private const val RESULT_TIMEOUT_MS = 2_500L
        private const val SESSION_TIMEOUT_MS = 15_000L
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
        )
    }
}
