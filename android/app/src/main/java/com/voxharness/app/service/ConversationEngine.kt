package com.voxharness.app.service

import android.content.Context
import android.util.Log
import com.voxharness.app.BuildConfig
import com.voxharness.app.audio.AudioCapture
import com.voxharness.app.audio.AudioPlayer
import com.voxharness.app.llm.*
import com.voxharness.app.voice.SpeechToText
import com.voxharness.app.voice.TextToSpeech
import com.voxharness.app.voice.VadDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * The conversation engine orchestrates the full voice loop:
 * Mic -> VAD -> STT -> LLM -> TTS -> Speaker
 */

data class ConversationState(
    val status: Status = Status.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val currentTranscript: String = "",
    val currentResponse: String = "",
)

enum class Status {
    IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING
}

data class ChatMessage(
    val role: String, // "user" or "assistant"
    val content: String,
)

class ConversationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ConversationEngine"
        private val SYSTEM_PROMPT = """
            You are a voice-controlled AI assistant on an Android phone.
            Keep responses to 1-2 short sentences. Be terse.
            Do NOT narrate what you're about to do. Just do it, then briefly confirm.
            Summarize information briefly — never read raw data aloud.
        """.trimIndent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Components
    val audioCapture = AudioCapture(context)
    private val audioPlayer = AudioPlayer(context)
    private val vad = VadDetector(context)
    private val stt = SpeechToText(context)
    private val tts = TextToSpeech(
        apiKey = BuildConfig.ELEVENLABS_API_KEY,
        voiceId = BuildConfig.ELEVENLABS_VOICE_ID,
    )
    private val llm: LlmProvider = AnthropicProvider(
        apiKey = BuildConfig.ANTHROPIC_API_KEY,
    )

    // State
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val messageHistory = mutableListOf<Message>()

    fun start() {
        Log.i(TAG, "Starting conversation engine")

        // Load VAD model
        scope.launch(Dispatchers.IO) {
            try {
                vad.load()
                Log.i(TAG, "VAD loaded")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load VAD", e)
            }
        }

        // Set up VAD callbacks
        vad.onSpeechStart = {
            Log.i(TAG, "Speech start detected")
            _state.update { it.copy(status = Status.LISTENING) }
        }

        vad.onSpeechEnd = { audio ->
            Log.i(TAG, "Speech end detected: ${audio.size} samples")
            scope.launch { processVoiceInput() }
        }

        // Connect audio capture to VAD
        scope.launch {
            audioCapture.audioFrames.collect { frame ->
                vad.feed(frame)
            }
        }

        // Start mic capture
        audioCapture.start(scope)
        _state.update { it.copy(status = Status.IDLE) }
    }

    fun stop() {
        audioCapture.stop()
        audioPlayer.release()
        vad.release()
        scope.cancel()
    }

    fun pushToTalkStart() {
        if (!audioCapture.isCapturing) {
            audioCapture.start(scope)
        }
        _state.update { it.copy(status = Status.LISTENING) }
    }

    fun pushToTalkEnd() {
        vad.flush()
    }

    fun sendTextInput(text: String) {
        scope.launch { processTurn(text) }
    }

    private suspend fun processVoiceInput() {
        _state.update { it.copy(status = Status.TRANSCRIBING) }

        // Use Android's speech recognizer
        withContext(Dispatchers.Main) {
            val transcript = stt.transcribeFromMic()
            if (transcript.isNullOrBlank()) {
                _state.update { it.copy(status = Status.IDLE) }
                return@withContext
            }
            processTurn(transcript)
        }
    }

    private suspend fun processTurn(userText: String) {
        // Add user message
        val userMsg = ChatMessage("user", userText)
        _state.update {
            it.copy(
                status = Status.THINKING,
                messages = it.messages + userMsg,
                currentTranscript = userText,
                currentResponse = "",
            )
        }
        messageHistory.add(Message(role = "user", content = userText))

        // Stream LLM response
        var fullResponse = ""
        val sentenceBuffer = StringBuilder()
        val sentences = mutableListOf<String>()

        try {
            llm.streamChat(
                messages = messageHistory,
                system = SYSTEM_PROMPT,
            ).collect { delta ->
                when (delta) {
                    is StreamDelta.Text -> {
                        fullResponse += delta.text
                        sentenceBuffer.append(delta.text)
                        _state.update { it.copy(currentResponse = fullResponse) }

                        // Check for sentence boundaries
                        val text = sentenceBuffer.toString()
                        val parts = text.split(Regex("(?<=[.!?])\\s+"))
                        if (parts.size > 1) {
                            for (i in 0 until parts.size - 1) {
                                val s = parts[i].trim()
                                if (s.isNotEmpty()) sentences.add(s)
                            }
                            sentenceBuffer.clear()
                            sentenceBuffer.append(parts.last())
                        }
                    }
                    is StreamDelta.Tool -> {
                        // TODO: Handle tool calls
                        Log.i(TAG, "Tool call: ${delta.toolCall.name}")
                    }
                    is StreamDelta.Done -> {}
                }
            }

            // Add remaining text
            val remaining = sentenceBuffer.toString().trim()
            if (remaining.isNotEmpty()) sentences.add(remaining)

            // Add to history
            messageHistory.add(Message(role = "assistant", content = fullResponse))
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage("assistant", fullResponse),
                    currentResponse = "",
                )
            }

            // Speak sentences
            _state.update { it.copy(status = Status.SPEAKING) }
            for (sentence in sentences) {
                val audio = tts.synthesize(sentence) ?: continue
                audioPlayer.queueTTSChunk(audio)
            }
            audioPlayer.playTTS(scope)

        } catch (e: Exception) {
            Log.e(TAG, "Turn error", e)
            _state.update {
                it.copy(
                    status = Status.IDLE,
                    messages = it.messages + ChatMessage("assistant", "Error: ${e.message}"),
                )
            }
        }

        _state.update { it.copy(status = Status.IDLE) }
    }
}
