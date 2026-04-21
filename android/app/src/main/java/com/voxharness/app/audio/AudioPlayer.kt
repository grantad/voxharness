package com.voxharness.app.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

/**
 * Handles TTS audio playback (MP3) and music streaming.
 * TTS playback suspends until audio finishes playing.
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var musicPlayer: ExoPlayer? = null
    private var ttsPlayer: ExoPlayer? = null

    /**
     * Play MP3 audio data and suspend until playback completes.
     */
    suspend fun playTTSAudio(mp3Data: ByteArray) {
        if (mp3Data.isEmpty()) return

        cancelTTS()

        val done = CompletableDeferred<Unit>()

        withContext(Dispatchers.IO) {
            val tempFile = java.io.File.createTempFile("tts_", ".mp3", context.cacheDir)
            tempFile.writeBytes(mp3Data)

            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(context).build()
                ttsPlayer = player

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                            player.release()
                            if (ttsPlayer === player) ttsPlayer = null
                            tempFile.delete()
                            done.complete(Unit)
                        }
                    }
                })

                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(tempFile)))
                player.prepare()
                player.play()
                Log.i(TAG, "TTS playing: ${mp3Data.size} bytes")
            }

            try {
                done.await()
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + NonCancellable) {
                    ttsPlayer?.let {
                        it.stop()
                        it.release()
                        ttsPlayer = null
                    }
                }
                tempFile.delete()
                throw e
            }
        }
    }

    fun cancelTTS() {
        ttsPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling TTS", e)
            }
        }
        ttsPlayer = null
    }

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
