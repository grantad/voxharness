package com.voxharness.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Speech-to-text using Android's built-in SpeechRecognizer.
 *
 * For on-device STT, this uses the device's speech engine.
 * Falls back gracefully — works offline on most modern Android devices.
 */
class SpeechToText(private val context: Context) {

    companion object {
        private const val TAG = "SpeechToText"
    }

    /**
     * Transcribe a short audio buffer using Android's SpeechRecognizer.
     * Note: SpeechRecognizer listens from the mic directly,
     * so for our use case we use it when VAD detects speech end.
     */
    suspend fun transcribeFromMic(): String? {
        val deferred = CompletableDeferred<String?>()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Prefer offline recognition
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                Log.i(TAG, "Transcript: $text")
                deferred.complete(text)
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Recognition error: $error")
                deferred.complete(null)
                recognizer.destroy()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(intent)
        return deferred.await()
    }

    /**
     * Transcribe from a PCM audio buffer by writing to a temp WAV
     * and using a Whisper ONNX model (if available) or falling back
     * to sending the audio for cloud STT.
     *
     * For MVP, we use Android's built-in recognizer via mic.
     * TODO: Add on-device Whisper via ONNX for buffer-based transcription.
     */
    fun transcribeBuffer(pcm16: ShortArray, sampleRate: Int = 16000): String? {
        // For now, return null — the main flow uses transcribeFromMic()
        // A full implementation would use whisper.cpp or Whisper ONNX here
        Log.w(TAG, "Buffer transcription not yet implemented, use transcribeFromMic()")
        return null
    }
}
