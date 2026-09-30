package com.one.robotassistant.feature.assistant.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class AndroidTextToSpeechOutput(
    context: Context,
) : SpeechOutput, AutoCloseable {
    private val ready = CompletableDeferred<Boolean>()
    private val completions = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private var textToSpeech: TextToSpeech? = null

    private val _state = MutableStateFlow(SpeechOutputState.IDLE)
    override val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            val engine = textToSpeech
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val vietnamese = Locale.forLanguageTag("vi-VN")
                val result = engine.setLanguage(vietnamese)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.getDefault()
                }
                engine.setSpeechRate(0.95f)
                engine.setPitch(1.03f)
                if (!ready.isCompleted) ready.complete(true)
            } else {
                _state.value = SpeechOutputState.ERROR
                if (!ready.isCompleted) ready.complete(false)
            }
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.value = SpeechOutputState.SPEAKING
                }

                override fun onDone(utteranceId: String?) {
                    _state.value = SpeechOutputState.IDLE
                    utteranceId?.let { completions.remove(it)?.invoke(true) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    completeWithError(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    completeWithError(utteranceId)
                }

                private fun completeWithError(utteranceId: String?) {
                    _state.value = SpeechOutputState.ERROR
                    utteranceId?.let { completions.remove(it)?.invoke(false) }
                }
            })
        }
    }

    override suspend fun speak(text: String) {
        val initialized = withTimeoutOrNull(5_000) { ready.await() } == true
        if (!initialized) error("TextToSpeech không khởi tạo được")
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                completions[utteranceId] = { continuation.resume(Unit) }
                continuation.invokeOnCancellation { completions.remove(utteranceId) }
                val result = textToSpeech?.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    Bundle(),
                    utteranceId,
                ) ?: TextToSpeech.ERROR
                if (result == TextToSpeech.ERROR) {
                    completions.remove(utteranceId)
                    _state.value = SpeechOutputState.ERROR
                    continuation.resume(Unit)
                }
            }
        }
    }

    override fun stop() {
        textToSpeech?.stop()
        completions.values.forEach { it(false) }
        completions.clear()
        _state.value = SpeechOutputState.IDLE
    }

    override fun close() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
