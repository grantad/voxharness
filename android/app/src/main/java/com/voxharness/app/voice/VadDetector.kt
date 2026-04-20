package com.voxharness.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Silero VAD running on-device via ONNX Runtime.
 * Detects speech start/end in streaming audio.
 */
class VadDetector(
    private val context: Context,
    private val threshold: Float = 0.5f,
    private val silenceFrames: Int = 12, // ~400ms at 512 samples/frame @ 16kHz
) {
    companion object {
        private const val TAG = "VadDetector"
        private const val FRAME_SIZE = 512 // Silero expects 512 samples at 16kHz
        private const val SAMPLE_RATE = 16000
    }

    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var h = FloatArray(2 * 1 * 64) // hidden state
    private var c = FloatArray(2 * 1 * 64) // cell state
    private var pendingSamples = ShortArray(0)
    private var isSpeaking = false
    private var silenceCount = 0
    private val audioBuffer = mutableListOf<ShortArray>()

    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: ((ShortArray) -> Unit)? = null

    fun load() {
        ortEnv = OrtEnvironment.getEnvironment()
        // Copy model from assets to cache
        val modelFile = java.io.File(context.cacheDir, "silero_vad.onnx")
        if (!modelFile.exists()) {
            context.assets.open("silero_vad.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        session = ortEnv?.createSession(modelFile.absolutePath)
        Log.i(TAG, "VAD model loaded")
    }

    fun feed(samples: ShortArray) {
        // Accumulate samples and process in FRAME_SIZE chunks
        pendingSamples = pendingSamples + samples
        while (pendingSamples.size >= FRAME_SIZE) {
            val frame = pendingSamples.copyOfRange(0, FRAME_SIZE)
            pendingSamples = pendingSamples.copyOfRange(FRAME_SIZE, pendingSamples.size)
            processFrame(frame)
        }
    }

    private fun processFrame(frame: ShortArray) {
        val session = this.session ?: return
        val env = this.ortEnv ?: return

        // Convert to float
        val floatFrame = FloatArray(FRAME_SIZE) { frame[it].toFloat() / 32768f }

        try {
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatFrame), longArrayOf(1, FRAME_SIZE.toLong()))
            val srTensor = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE.toLong()))
            val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64))
            val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64))

            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "h" to hTensor,
                "c" to cTensor,
            )

            val results = session.run(inputs)
            val output = (results[0].value as Array<FloatArray>)[0]
            val confidence = output[0]

            // Update hidden states
            val newH = results[1].value as Array<Array<FloatArray>>
            val newC = results[2].value as Array<Array<FloatArray>>
            for (i in 0 until 2) {
                for (j in 0 until 64) {
                    h[i * 64 + j] = newH[i][0][j]
                    c[i * 64 + j] = newC[i][0][j]
                }
            }

            results.close()

            // Speech detection logic
            if (confidence > threshold) {
                silenceCount = 0
                if (!isSpeaking) {
                    isSpeaking = true
                    audioBuffer.clear()
                    onSpeechStart?.invoke()
                }
                audioBuffer.add(frame)
            } else if (isSpeaking) {
                audioBuffer.add(frame)
                silenceCount++
                if (silenceCount >= silenceFrames) {
                    isSpeaking = false
                    val fullAudio = audioBuffer.flatMap { it.toList() }.toShortArray()
                    audioBuffer.clear()
                    onSpeechEnd?.invoke(fullAudio)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VAD inference error", e)
        }
    }

    fun flush() {
        if (isSpeaking && audioBuffer.isNotEmpty()) {
            isSpeaking = false
            val fullAudio = audioBuffer.flatMap { it.toList() }.toShortArray()
            audioBuffer.clear()
            onSpeechEnd?.invoke(fullAudio)
        }
        pendingSamples = ShortArray(0)
    }

    fun reset() {
        isSpeaking = false
        silenceCount = 0
        audioBuffer.clear()
        pendingSamples = ShortArray(0)
        h.fill(0f)
        c.fill(0f)
    }

    fun release() {
        session?.close()
        ortEnv?.close()
    }
}
