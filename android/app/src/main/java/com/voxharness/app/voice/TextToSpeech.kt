package com.voxharness.app.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs streaming TTS client.
 */
class TextToSpeech(
    private val apiKey: String,
    private val voiceId: String = "21m00Tcm4TlvDq8ikWAM",
) {
    companion object {
        private const val TAG = "TextToSpeech"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Synthesize text to MP3 audio bytes.
     * Returns the complete MP3 byte array.
     */
    suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        try {
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_turbo_v2_5")
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
                .header("xi-api-key", apiKey)
                .header("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "TTS error ${response.code}: ${response.body?.string()}")
                return@withContext null
            }

            val audioData = response.body?.bytes()
            Log.i(TAG, "TTS synthesized ${audioData?.size ?: 0} bytes for: ${text.take(50)}")
            audioData
        } catch (e: Exception) {
            Log.e(TAG, "TTS error", e)
            null
        }
    }
}
