package com.one.robotassistant

import android.app.Application
import com.one.robotassistant.core.network.OneConfiguration

class OneApplication : Application() {
    val configuration: OneConfiguration by lazy {
        OneConfiguration(
            groqApiKey = BuildConfig.GROQ_API_KEY,
            groqBaseUrl = BuildConfig.GROQ_BASE_URL,
            groqModel = BuildConfig.GROQ_MODEL,
            groqVisionModel = BuildConfig.GROQ_VISION_MODEL,
        )
    }
}
