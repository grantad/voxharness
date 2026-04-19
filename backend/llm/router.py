"""LLM provider router — selects provider by config."""

from typing import AsyncIterator

from .base import Delta, Message, ToolDef
from .anthropic import AnthropicProvider
from .openai import OpenAIProvider


class LLMRouter:
    """Routes LLM requests to the configured provider."""

    def __init__(self):
        self._providers: dict[str, object] = {}
        self._active: str = ""

    def register(self, name: str, provider):
        """Register a provider."""
        self._providers[name] = provider
        if not self._active:
            self._active = name

    def set_active(self, name: str):
        """Switch active provider at runtime."""
        if name not in self._providers:
            raise ValueError(f"Unknown provider: {name}. Available: {list(self._providers)}")
        self._active = name

    @property
    def active_name(self) -> str:
        return self._active

    def _get_provider(self):
        if not self._active:
            raise RuntimeError("No LLM provider registered")
        return self._providers[self._active]

    async def stream_chat(
        self,
        messages: list[Message],
        system: str | None = None,
        tools: list[ToolDef] | None = None,
    ) -> AsyncIterator[Delta]:
        """Stream chat through the active provider."""
        provider = self._get_provider()
        async for delta in provider.stream_chat(messages, system=system, tools=tools):
            yield delta

    @classmethod
    def from_config(cls, config) -> "LLMRouter":
        """Build router from Config object."""
        router = cls()

        if config.anthropic_api_key:
            router.register(
                "anthropic",
                AnthropicProvider(
                    api_key=config.anthropic_api_key,
                    model=config.anthropic_model,
                ),
            )

        if config.openai_api_key:
            router.register(
                "openai",
                OpenAIProvider(
                    api_key=config.openai_api_key,
                    model=config.openai_model,
                ),
            )

        # Set active provider
        if config.llm_provider in router._providers:
            router.set_active(config.llm_provider)

        return router
