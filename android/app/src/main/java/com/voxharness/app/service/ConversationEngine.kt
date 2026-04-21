package com.voxharness.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.voxharness.app.BuildConfig
import com.voxharness.app.audio.AudioPlayer
import com.voxharness.app.llm.*
import com.voxharness.app.voice.ContinuousRecognizer
import com.voxharness.app.voice.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class ConversationState(
    val status: Status = Status.WAITING_FOR_WAKE_WORD,
    val messages: List<ChatMessage> = emptyList(),
    val currentResponse: String = "",
)

enum class Status {
    WAITING_FOR_WAKE_WORD, LISTENING, TRANSCRIBING, THINKING, SPEAKING
}

data class ChatMessage(
    val role: String,
    val content: String,
)

class ConversationEngine(private val context: Context) {

    companion object {
        private const val TAG = "ConversationEngine"
        private val SYSTEM_PROMPT = """
            You are a voice-controlled AI assistant on an Android phone.
            You have full access to the phone's features: camera, maps, apps,
            files, settings, calls, messages, alarms, music, and more.

            VOICE RULES:
            - Keep responses to 1-2 short sentences. Be terse.
            - Don't narrate plans. Just do it, then briefly confirm.
            - Don't ask clarifying questions unless truly ambiguous.
            - Summarize results briefly — never read raw data aloud.

            TOOL RULES:
            - Use tools directly when asked to do something on the phone.
            - For multi-step tasks, chain tool calls, then report the result.
        """.trimIndent()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val recognizer = ContinuousRecognizer(context)
    private val audioPlayer = AudioPlayer(context)
    private val tts = TextToSpeech(
        apiKey = BuildConfig.ELEVENLABS_API_KEY,
        voiceId = BuildConfig.ELEVENLABS_VOICE_ID,
    )
    private val llm: LlmProvider = AnthropicProvider(
        apiKey = BuildConfig.ANTHROPIC_API_KEY,
    )

    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    // Intent launcher — set by Activity
    var intentLauncher: ((Intent) -> Unit)? = null

    private val messageHistory = mutableListOf<Message>()
    private val allTools = DeviceTools.TOOLS

    fun start() {
        Log.i(TAG, "Starting conversation engine")

        recognizer.wakeWord = "computer"

        recognizer.onWakeWordDetected = {
            Log.i(TAG, "Wake word detected!")
            _state.update { it.copy(status = Status.LISTENING) }
        }

        recognizer.onListeningStateChanged = { listening ->
            if (!listening) {
                _state.update { it.copy(status = Status.WAITING_FOR_WAKE_WORD) }
            }
        }

        recognizer.onTranscript = { text ->
            Log.i(TAG, "Command: $text")
            scope.launch { processTurn(text) }
        }

        recognizer.onError = { error ->
            Log.e(TAG, "Recognizer error: $error")
        }

        recognizer.start()
        _state.update { it.copy(status = Status.WAITING_FOR_WAKE_WORD) }
    }

    fun stop() {
        recognizer.stop()
        audioPlayer.release()
        scope.cancel()
    }

    fun sendTextInput(text: String) {
        scope.launch { processTurn(text) }
    }

    private suspend fun processTurn(userText: String) {
        val userMsg = ChatMessage("user", userText)
        _state.update {
            it.copy(
                status = Status.THINKING,
                messages = it.messages + userMsg,
                currentResponse = "",
            )
        }
        messageHistory.add(Message(role = "user", content = userText))

        try {
            val response = streamLlmResponse()

            // Speak the response
            if (response.isNotEmpty()) {
                _state.update { it.copy(status = Status.SPEAKING) }
                speakText(response)
            }

            // After speaking, stay in command mode for follow-ups
            recognizer.enterCommandMode()

        } catch (e: Exception) {
            Log.e(TAG, "Turn error", e)
            _state.update {
                it.copy(
                    status = Status.LISTENING,
                    messages = it.messages + ChatMessage("assistant", "Error: ${e.message}"),
                )
            }
            recognizer.enterCommandMode()
        }
    }

    private suspend fun streamLlmResponse(): String {
        var fullText = ""
        val pendingToolCalls = mutableListOf<ToolCall>()

        llm.streamChat(
            messages = messageHistory,
            system = SYSTEM_PROMPT,
            tools = allTools,
        ).collect { delta ->
            when (delta) {
                is StreamDelta.Text -> {
                    fullText += delta.text
                    _state.update { it.copy(currentResponse = fullText) }
                }
                is StreamDelta.Tool -> {
                    pendingToolCalls.add(delta.toolCall)
                }
                is StreamDelta.Done -> {}
            }
        }

        // Add assistant message to history
        messageHistory.add(Message(
            role = "assistant",
            content = fullText,
            toolCalls = pendingToolCalls.ifEmpty { null },
        ))

        // Update UI with final response
        if (fullText.isNotEmpty()) {
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage("assistant", fullText),
                    currentResponse = "",
                )
            }
        }

        // Handle tool calls
        if (pendingToolCalls.isNotEmpty()) {
            for (tc in pendingToolCalls) {
                val args = try {
                    val json = JSONObject(tc.arguments)
                    json.keys().asSequence().associateWith { json.get(it) }
                } catch (e: Exception) {
                    emptyMap()
                }

                Log.i(TAG, "Tool call: ${tc.name} args=$args")
                val (result, intent) = DeviceTools.handleTool(context, tc.name, args)

                // Launch intent if provided
                if (intent != null) {
                    withContext(Dispatchers.Main) {
                        try {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intentLauncher?.invoke(intent) ?: context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to launch intent", e)
                        }
                    }
                }

                // Add tool call status to UI
                _state.update {
                    it.copy(messages = it.messages + ChatMessage("assistant", "→ ${tc.name}: $result"))
                }

                // Add tool result to history
                messageHistory.add(Message(
                    role = "tool",
                    content = result,
                    toolCallId = tc.id,
                ))
            }

            // Continue conversation with tool results
            return streamLlmResponse()
        }

        return fullText
    }

    private suspend fun speakText(text: String) {
        // Split into sentences for sequential TTS
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        for (sentence in sentences) {
            val audio = tts.synthesize(sentence) ?: continue
            audioPlayer.queueTTSChunk(audio)
        }
        audioPlayer.playTTS(scope)
    }
}
