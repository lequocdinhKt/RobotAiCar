package com.one.robotassistant.core.network

data class OneConfiguration(
    val groqApiKey: String,
    val groqBaseUrl: String,
    val groqModel: String,
    val groqVisionModel: String,
) {
    val hasGroqApiKey: Boolean
        get() = groqApiKey.isNotBlank()
}
