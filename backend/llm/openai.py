"""OpenAI GPT LLM provider."""

import json
from typing import AsyncIterator
from openai import AsyncOpenAI

from .base import Delta, LLMProvider, Message, StopDelta, TokenDelta, ToolCallDelta, ToolDef


class OpenAIProvider:
    name = "openai"

    def __init__(self, api_key: str, model: str = "gpt-4o"):
        self.model = model
        self._client = AsyncOpenAI(api_key=api_key)

    async def stream_chat(
        self,
        messages: list[Message],
        system: str | None = None,
        tools: list[ToolDef] | None = None,
    ) -> AsyncIterator[Delta]:
        """Stream chat via OpenAI Chat Completions API."""
        # Convert messages to OpenAI format
        api_messages = []
        if system:
            api_messages.append({"role": "system", "content": system})

        for msg in messages:
            if msg.role == "tool":
                api_messages.append({
                    "role": "tool",
                    "tool_call_id": msg.tool_call_id,
                    "content": msg.content,
                })
            elif msg.tool_calls:
                api_msg = {
                    "role": "assistant",
                    "content": msg.content or None,
                    "tool_calls": [
                        {
                            "id": tc["id"],
                            "type": "function",
                            "function": {
                                "name": tc["name"],
                                "arguments": tc["arguments"],
                            },
                        }
                        for tc in msg.tool_calls
                    ],
                }
                api_messages.append(api_msg)
            else:
                api_messages.append({
                    "role": msg.role,
                    "content": msg.content,
                })

        # Build kwargs
        kwargs = {
            "model": self.model,
            "max_tokens": 4096,
            "messages": api_messages,
            "stream": True,
        }
        if tools:
            kwargs["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": t.name,
                        "description": t.description,
                        "parameters": t.parameters,
                    },
                }
                for t in tools
            ]

        stream = await self._client.chat.completions.create(**kwargs)

        tool_calls_buffer: dict[int, dict] = {}  # index -> {id, name, args}

        async for chunk in stream:
            delta = chunk.choices[0].delta if chunk.choices else None
            finish_reason = chunk.choices[0].finish_reason if chunk.choices else None

            if delta:
                # Text content
                if delta.content:
                    yield TokenDelta(text=delta.content)

                # Tool calls
                if delta.tool_calls:
                    for tc_delta in delta.tool_calls:
                        idx = tc_delta.index
                        if idx not in tool_calls_buffer:
                            tool_calls_buffer[idx] = {
                                "id": tc_delta.id or "",
                                "name": "",
                                "arguments": "",
                            }
                        buf = tool_calls_buffer[idx]
                        if tc_delta.id:
                            buf["id"] = tc_delta.id
                        if tc_delta.function:
                            if tc_delta.function.name:
                                buf["name"] = tc_delta.function.name
                            if tc_delta.function.arguments:
                                buf["arguments"] += tc_delta.function.arguments

            if finish_reason == "tool_calls":
                for idx in sorted(tool_calls_buffer):
                    buf = tool_calls_buffer[idx]
                    yield ToolCallDelta(
                        id=buf["id"],
                        name=buf["name"],
                        arguments=buf["arguments"] or "{}",
                    )
                tool_calls_buffer.clear()
            elif finish_reason == "stop":
                yield StopDelta()
