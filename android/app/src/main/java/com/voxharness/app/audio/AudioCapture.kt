package com.voxharness.app.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Captures audio from the microphone as 16-bit PCM at 16kHz.
 * Emits audio frames via a SharedFlow for VAD, wake word, and STT.
 */
class AudioCapture(private val context: android.content.Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE = 1024 // samples per frame (~64ms at 16kHz)
        private const val TAG = "AudioCapture"
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private val _audioFrames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ShortArray> = _audioFrames

    var isCapturing = false
        private set

    fun start(scope: CoroutineScope) {
        if (isCapturing) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
            FRAME_SIZE * 2 * 4 // at least 4 frames
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        audioRecord?.startRecording()
        isCapturing = true
        Log.i(TAG, "Audio capture started (${SAMPLE_RATE}Hz, buffer=$bufferSize)")

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME_SIZE)
            while (isActive && isCapturing) {
                val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: -1
                if (read > 0) {
                    _audioFrames.emit(buffer.copyOf(read))
                }
            }
        }
    }

    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Audio capture stopped")
    }
}
