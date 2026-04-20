package com.voxharness.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

/**
 * Handles TTS audio playback (raw PCM/MP3) and music streaming.
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val TTS_SAMPLE_RATE = 44100
    }

    // ExoPlayer for music (supports streaming URLs)
    private var musicPlayer: ExoPlayer? = null

    // AudioTrack for TTS PCM playback
    private var ttsTrack: AudioTrack? = null
    private val ttsChunks = mutableListOf<ByteArray>()
    private var ttsJob: Job? = null

    // --- TTS Playback ---

    fun queueTTSChunk(mp3Data: ByteArray) {
        ttsChunks.add(mp3Data)
    }

    fun playTTS(scope: CoroutineScope) {
        if (ttsChunks.isEmpty()) return

        // Combine all chunks into one buffer
        val totalSize = ttsChunks.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0
        for (chunk in ttsChunks) {
            chunk.copyInto(combined, offset)
            offset += chunk.size
        }
        ttsChunks.clear()

        // Decode and play using Android's MediaPlayer via temp file
        ttsJob = scope.launch(Dispatchers.IO) {
            try {
                val tempFile = java.io.File.createTempFile("tts_", ".mp3", context.cacheDir)
                tempFile.writeBytes(combined)

                withContext(Dispatchers.Main) {
                    val player = ExoPlayer.Builder(context).build()
                    player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(tempFile)))
                    player.prepare()
                    player.play()

                    // Wait for completion
                    player.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == androidx.media3.common.Player.STATE_ENDED) {
                                player.release()
                                tempFile.delete()
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS playback error", e)
            }
        }
    }

    fun cancelTTS() {
        ttsChunks.clear()
        ttsJob?.cancel()
    }

    // --- Music Playback ---

    fun playMusic(url: String, volume: Float = 0.7f) {
        stopMusic()

        musicPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            this.volume = volume
            prepare()
            play()
        }
        Log.i(TAG, "Music playing: $url")
    }

    fun stopMusic() {
        musicPlayer?.stop()
        musicPlayer?.release()
        musicPlayer = null
    }

    fun release() {
        cancelTTS()
        stopMusic()
    }
}
