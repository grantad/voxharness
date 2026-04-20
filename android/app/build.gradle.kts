plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.voxharness.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voxharness.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // API keys — read from gradle.properties or environment
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${findProperty("anthropic.api.key") ?: System.getenv("ANTHROPIC_API_KEY") ?: ""}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${findProperty("openai.api.key") ?: System.getenv("OPENAI_API_KEY") ?: ""}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${findProperty("elevenlabs.api.key") ?: System.getenv("ELEVENLABS_API_KEY") ?: ""}\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"${findProperty("elevenlabs.voice.id") ?: "21m00Tcm4TlvDq8ikWAM"}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ONNX Runtime for on-device inference (VAD, wake word)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.0")

    // OkHttp for API calls (LLM, TTS)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // JSON
    implementation("org.json:json:20240303")

    // Media playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
