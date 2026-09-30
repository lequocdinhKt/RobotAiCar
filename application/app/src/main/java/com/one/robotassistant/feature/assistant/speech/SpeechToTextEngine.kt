package com.one.robotassistant.feature.assistant.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SpeechToTextEngine {
    val state: StateFlow<SpeechRecognitionState>
    val transcripts: Flow<SpeechTranscript>

    fun startListening()
    fun stopListening()
}

data class SpeechTranscript(
    val text: String,
    val isFinal: Boolean,
    val alternatives: List<String> = emptyList(),
    val confidence: Float? = null,
)

enum class SpeechRecognitionState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR,
}

interface SpeechOutput {
    val state: StateFlow<SpeechOutputState>

    suspend fun speak(text: String)
    fun stop()
}

enum class SpeechOutputState { IDLE, SPEAKING, ERROR }
