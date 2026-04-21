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
            - For YouTube: use play_youtube with the video title or topic.
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

    var intentLauncher: ((Intent) -> Unit)? = null

    private val messageHistory = mutableListOf<Message>()
    private val allTools = DeviceTools.TOOLS

    // Track current turn so we can cancel it on new input
    private var currentTurnJob: Job? = null

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
            // Cancel any in-progress turn (prevents stale responses)
            cancelCurrentTurn()
            currentTurnJob = scope.launch { processTurn(text) }
        }

        recognizer.start()
        _state.update { it.copy(status = Status.WAITING_FOR_WAKE_WORD) }
    }

    fun stop() {
        cancelCurrentTurn()
        recognizer.stop()
        audioPlayer.release()
        scope.cancel()
    }

    /**
     * Called when the user returns to VoxHarness from another app.
     * Resumes voice recognition.
     */
    fun onResume() {
        recognizer.resume()
        _state.update { it.copy(status = Status.WAITING_FOR_WAKE_WORD) }
    }

    fun sendTextInput(text: String) {
        cancelCurrentTurn()
        currentTurnJob = scope.launch { processTurn(text) }
    }

    private fun cancelCurrentTurn() {
        currentTurnJob?.cancel()
        currentTurnJob = null
        audioPlayer.cancelTTS()
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

            // Speak the response sentence by sentence, waiting for each to finish
            if (response.isNotEmpty()) {
                _state.update { it.copy(status = Status.SPEAKING) }
                speakText(response)
            }

            // After speaking completes, go back to listening
            _state.update { it.copy(status = Status.LISTENING) }
            recognizer.enterCommandMode()

        } catch (e: CancellationException) {
            Log.i(TAG, "Turn cancelled")
            // Add placeholder tool results for any pending tool calls
            repairHistory()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Turn error", e)
            repairHistory()
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

                if (intent != null) {
                    // Pause recognition so we don't steal audio focus from the launched app
                    recognizer.pause()
                    withContext(Dispatchers.Main) {
                        try {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intentLauncher?.invoke(intent) ?: context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to launch intent", e)
                            recognizer.resume()
                        }
                    }
                }

                _state.update {
                    it.copy(messages = it.messages + ChatMessage("assistant", "→ ${tc.name}: $result"))
                }

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
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        for (sentence in sentences) {
            // Check for cancellation between sentences
            currentCoroutineContext().ensureActive()

            val audio = tts.synthesize(sentence) ?: continue
            // This suspends until playback completes
            audioPlayer.playTTSAudio(audio)
        }
    }

    /**
     * Repair message history — ensure every tool_use has a tool_result.
     */
    private fun repairHistory() {
        val clean = mutableListOf<Message>()
        var i = 0
        while (i < messageHistory.size) {
            val msg = messageHistory[i]
            clean.add(msg)

            if (msg.role == "assistant" && msg.toolCalls != null) {
                val neededIds = msg.toolCalls.map { it.id }.toMutableSet()
                i++
                // Collect existing tool results
                while (i < messageHistory.size && messageHistory[i].role == "tool") {
                    val toolMsg = messageHistory[i]
                    if (toolMsg.toolCallId in neededIds) {
                        clean.add(toolMsg)
                        neededIds.remove(toolMsg.toolCallId)
                    }
                    i++
                }
                // Add placeholders for missing
                for (id in neededIds) {
                    clean.add(Message(role = "tool", content = "[cancelled]", toolCallId = id))
                }
                continue
            }
            i++
        }
        messageHistory.clear()
        messageHistory.addAll(clean)
    }
}
