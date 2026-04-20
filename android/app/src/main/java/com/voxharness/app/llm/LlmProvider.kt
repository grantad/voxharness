package com.voxharness.app.llm

import kotlinx.coroutines.flow.Flow

data class Message(
    val role: String,        // "user", "assistant", "system", "tool"
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String, // JSON string
)

data class ToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)

sealed class StreamDelta {
    data class Text(val text: String) : StreamDelta()
    data class Tool(val toolCall: ToolCall) : StreamDelta()
    data object Done : StreamDelta()
}

interface LlmProvider {
    val name: String
    fun streamChat(
        messages: List<Message>,
        system: String? = null,
        tools: List<ToolDef>? = null,
    ): Flow<StreamDelta>
}
