package com.voxharness.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous speech recognizer that listens forever and detects
 * a wake word ("Computer") before processing commands.
 *
 * Uses Android's built-in SpeechRecognizer which handles
 * mic capture, VAD, and STT all in one.
 */
class ContinuousRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "ContinuousRecognizer"
        private const val RESTART_DELAY_MS = 500L
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var isListeningForCommand = false  // true after wake word detected
    private var commandTimeoutRunnable: Runnable? = null

    var wakeWord = "computer"
    var commandTimeoutMs = 30_000L  // stay in command mode for 30s

    // Callbacks
    var onWakeWordDetected: (() -> Unit)? = null
    var onTranscript: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var isPaused = false

    fun start() {
        if (isRunning) return
        isRunning = true
        isPaused = false
        Log.i(TAG, "Starting continuous recognition, wake word: '$wakeWord'")
        startListening()
    }

    fun stop() {
        isRunning = false
        isPaused = false
        isListeningForCommand = false
        cancelCommandTimeout()
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        Log.i(TAG, "Stopped")
    }

    /**
     * Pause recognition — stops the mic so other apps (YouTube, etc.) can use audio.
     * Call resume() when the user returns to VoxHarness.
     */
    fun pause() {
        if (!isRunning || isPaused) return
        isPaused = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        Log.i(TAG, "Paused (external app launched)")
    }

    /**
     * Resume recognition after a pause.
     */
    fun resume() {
        if (!isRunning || !isPaused) return
        isPaused = false
        Log.i(TAG, "Resuming recognition")
        startListening()
    }

    /**
     * Force into command listening mode (e.g., after TTS finishes
     * and we expect a follow-up).
     */
    fun enterCommandMode() {
        isListeningForCommand = true
        resetCommandTimeout()
        onListeningStateChanged?.invoke(true)
    }

    fun exitCommandMode() {
        isListeningForCommand = false
        cancelCommandTimeout()
        onListeningStateChanged?.invoke(false)
    }

    private fun startListening() {
        if (!isRunning || isPaused) return

        handler.post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer?.setRecognitionListener(listener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    // Longer silence detection for natural speech
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                }

                recognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                scheduleRestart()
            }
        }
    }

    private fun scheduleRestart() {
        if (!isRunning || isPaused) return
        handler.postDelayed({ startListening() }, RESTART_DELAY_MS)
    }

    private fun resetCommandTimeout() {
        cancelCommandTimeout()
        commandTimeoutRunnable = Runnable {
            Log.i(TAG, "Command timeout — returning to wake word mode")
            isListeningForCommand = false
            onListeningStateChanged?.invoke(false)
        }
        handler.postDelayed(commandTimeoutRunnable!!, commandTimeoutMs)
    }

    private fun cancelCommandTimeout() {
        commandTimeoutRunnable?.let { handler.removeCallbacks(it) }
        commandTimeoutRunnable = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""

            if (text.isNotEmpty()) {
                Log.i(TAG, "Result: '$text' (commandMode=$isListeningForCommand)")
                processTranscript(text)
            }

            // Restart listening
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotEmpty()) {
                Log.d(TAG, "Partial: '$text'")
            }
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                SpeechRecognizer.ERROR_AUDIO -> "audio"
                SpeechRecognizer.ERROR_CLIENT -> "client"
                SpeechRecognizer.ERROR_NETWORK -> "network"
                SpeechRecognizer.ERROR_SERVER -> "server"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                else -> "unknown($error)"
            }

            // no_match and timeout are normal — just restart
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                Log.d(TAG, "Recognition: $errorMsg (restarting)")
            } else {
                Log.w(TAG, "Recognition error: $errorMsg")
            }

            scheduleRestart()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processTranscript(text: String) {
        val lower = text.lowercase()

        if (isListeningForCommand) {
            // Already in command mode — send everything as a command
            resetCommandTimeout()
            onTranscript?.invoke(text)
            return
        }

        // Check for wake word
        if (lower.startsWith(wakeWord.lowercase())) {
            Log.i(TAG, "Wake word detected!")
            onWakeWordDetected?.invoke()
            isListeningForCommand = true
            resetCommandTimeout()
            onListeningStateChanged?.invoke(true)

            // If there's text after the wake word, treat it as a command
            val command = text.substring(wakeWord.length).trim()
                .trimStart(',', '.', '!', ' ')
            if (command.isNotEmpty()) {
                onTranscript?.invoke(command)
            }
        }
        // Ignore everything else — waiting for wake word
    }
}
