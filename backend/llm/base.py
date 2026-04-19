"""LLM provider protocol and shared types."""

from dataclasses import dataclass, field
from typing import AsyncIterator, Protocol, runtime_checkable


@dataclass
class Message:
    role: str  # "user", "assistant", "system", "tool"
    content: str
    tool_call_id: str | None = None
    tool_calls: list[dict] | None = None


@dataclass
class ToolDef:
    name: str
    description: str
    parameters: dict  # JSON Schema


@dataclass
class TokenDelta:
    text: str


@dataclass
class ToolCallDelta:
    id: str
    name: str
    arguments: str  # JSON string


@dataclass
class StopDelta:
    pass


Delta = TokenDelta | ToolCallDelta | StopDelta


@runtime_checkable
class LLMProvider(Protocol):
    name: str

    async def stream_chat(
        self,
        messages: list[Message],
        system: str | None = None,
        tools: list[ToolDef] | None = None,
    ) -> AsyncIterator[Delta]:
        """Stream chat completion, yielding deltas."""
        ...
