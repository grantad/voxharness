package com.voxharness.app.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-20250514",
) : LlmProvider {

    override val name = "anthropic"

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun streamChat(
        messages: List<Message>,
        system: String?,
        tools: List<ToolDef>?,
    ): Flow<StreamDelta> = flow {
        val body = buildRequestBody(messages, system, tools)

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e("Anthropic", "API error ${response.code}: $errorBody")
            throw RuntimeException("Anthropic API error: ${response.code}")
        }

        val reader = BufferedReader(response.body!!.charStream())
        var currentToolId: String? = null
        var currentToolName: String? = null
        var toolArgsBuffer = StringBuilder()

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (!l.startsWith("data: ")) continue
            val data = l.removePrefix("data: ").trim()
            if (data.isEmpty() || data == "[DONE]") continue

            try {
                val event = JSONObject(data)
                when (event.getString("type")) {
                    "content_block_start" -> {
                        val block = event.getJSONObject("content_block")
                        if (block.getString("type") == "tool_use") {
                            currentToolId = block.getString("id")
                            currentToolName = block.getString("name")
                            toolArgsBuffer.clear()
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.getJSONObject("delta")
                        when (delta.getString("type")) {
                            "text_delta" -> emit(StreamDelta.Text(delta.getString("text")))
                            "input_json_delta" -> toolArgsBuffer.append(delta.getString("partial_json"))
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolId != null) {
                            emit(StreamDelta.Tool(ToolCall(
                                id = currentToolId!!,
                                name = currentToolName ?: "",
                                arguments = toolArgsBuffer.toString().ifEmpty { "{}" },
                            )))
                            currentToolId = null
                            currentToolName = null
                        }
                    }
                    "message_stop" -> emit(StreamDelta.Done)
                }
            } catch (e: Exception) {
                Log.w("Anthropic", "Failed to parse SSE event: $data", e)
            }
        }

        response.close()
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(
        messages: List<Message>,
        system: String?,
        tools: List<ToolDef>?,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", 4096)
        body.put("stream", true)

        if (system != null) body.put("system", system)

        val msgArray = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            if (msg.role == "tool") {
                obj.put("role", "user")
                val content = JSONArray()
                content.put(JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId)
                    put("content", msg.content)
                })
                obj.put("content", content)
            } else if (msg.toolCalls != null) {
                obj.put("role", "assistant")
                val content = JSONArray()
                if (msg.content.isNotEmpty()) {
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                for (tc in msg.toolCalls) {
                    content.put(JSONObject().apply {
                        put("type", "tool_use")
                        put("id", tc.id)
                        put("name", tc.name)
                        put("input", JSONObject(tc.arguments))
                    })
                }
                obj.put("content", content)
            } else {
                obj.put("role", msg.role)
                obj.put("content", msg.content)
            }
            msgArray.put(obj)
        }
        body.put("messages", msgArray)

        if (tools != null && tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", JSONObject(tool.parameters))
                })
            }
            body.put("tools", toolsArray)
        }

        return body
    }
}
