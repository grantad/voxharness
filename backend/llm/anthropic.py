"""Anthropic Claude LLM provider."""

import json
from typing import AsyncIterator
from anthropic import AsyncAnthropic

from .base import Delta, LLMProvider, Message, StopDelta, TokenDelta, ToolCallDelta, ToolDef


class AnthropicProvider:
    name = "anthropic"

    def __init__(self, api_key: str, model: str = "claude-sonnet-4-6-20250514"):
        self.model = model
        self._client = AsyncAnthropic(api_key=api_key)

    async def stream_chat(
        self,
        messages: list[Message],
        system: str | None = None,
        tools: list[ToolDef] | None = None,
    ) -> AsyncIterator[Delta]:
        """Stream chat via Anthropic Messages API."""
        # Convert messages to Anthropic format
        api_messages = []
        for msg in messages:
            if msg.role == "tool":
                api_messages.append({
                    "role": "user",
                    "content": [{
                        "type": "tool_result",
                        "tool_use_id": msg.tool_call_id,
                        "content": msg.content,
                    }],
                })
            elif msg.tool_calls:
                # Assistant message with tool calls
                content = []
                if msg.content:
                    content.append({"type": "text", "text": msg.content})
                for tc in msg.tool_calls:
                    content.append({
                        "type": "tool_use",
                        "id": tc["id"],
                        "name": tc["name"],
                        "input": json.loads(tc["arguments"]) if isinstance(tc["arguments"], str) else tc["arguments"],
                    })
                api_messages.append({"role": "assistant", "content": content})
            else:
                api_messages.append({
                    "role": msg.role,
                    "content": msg.content,
                })

        # Build API kwargs
        kwargs = {
            "model": self.model,
            "max_tokens": 4096,
            "messages": api_messages,
        }
        if system:
            kwargs["system"] = system
        if tools:
            kwargs["tools"] = [
                {
                    "name": t.name,
                    "description": t.description,
                    "input_schema": t.parameters,
                }
                for t in tools
            ]

        async with self._client.messages.stream(**kwargs) as stream:
            current_tool_id = None
            current_tool_name = None
            tool_args_buffer = ""

            async for event in stream:
                if event.type == "content_block_start":
                    if event.content_block.type == "tool_use":
                        current_tool_id = event.content_block.id
                        current_tool_name = event.content_block.name
                        tool_args_buffer = ""
                elif event.type == "content_block_delta":
                    if event.delta.type == "text_delta":
                        yield TokenDelta(text=event.delta.text)
                    elif event.delta.type == "input_json_delta":
                        tool_args_buffer += event.delta.partial_json
                elif event.type == "content_block_stop":
                    if current_tool_id:
                        yield ToolCallDelta(
                            id=current_tool_id,
                            name=current_tool_name,
                            arguments=tool_args_buffer or "{}",
                        )
                        current_tool_id = None
                        current_tool_name = None
                elif event.type == "message_stop":
                    yield StopDelta()
