import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.one.robotassistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.one.robotassistant"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "GROQ_API_KEY",
            localProperties.getProperty("GROQ_API_KEY", "").asBuildConfigString(),
        )
        buildConfigField("String", "GROQ_BASE_URL", "https://api.groq.com/openai/v1/".asBuildConfigString())
        buildConfigField(
            "String",
            "GROQ_MODEL",
            localProperties.getProperty("GROQ_MODEL", "llama-3.3-70b-versatile").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "GROQ_VISION_MODEL",
            localProperties.getProperty("GROQ_VISION_MODEL", "qwen/qwen3.8-27b").asBuildConfigString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.mediapipe.tasks.vision)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
