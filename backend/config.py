"""Configuration loaded from environment variables and .env file."""

import os
from dataclasses import dataclass, field
from dotenv import load_dotenv

load_dotenv()


@dataclass
class Config:
    # Server
    ws_host: str = "127.0.0.1"
    ws_port: int = 8765

    # LLM
    llm_provider: str = "anthropic"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-4-20250514"
    openai_api_key: str = ""
    openai_model: str = "gpt-4o"

    # STT
    whisper_model: str = "base.en"
    whisper_compute_type: str = "int8"

    # TTS
    elevenlabs_api_key: str = ""
    elevenlabs_voice_id: str = "21m00Tcm4TlvDq8ikWAM"  # Rachel default

    # Audio
    sample_rate: int = 16000  # mic input sample rate
    vad_threshold: float = 0.5
    silence_duration_ms: int = 400  # ms of silence to end a turn

    # System prompt
    system_prompt: str = (
        "You are a voice assistant. Reply conversationally in short, clear sentences. "
        "Use tools when asked to play music, show images, or perform actions."
    )

    @classmethod
    def from_env(cls) -> "Config":
        """Load config from environment variables."""
        return cls(
            ws_host=os.getenv("WS_HOST", cls.ws_host),
            ws_port=int(os.getenv("WS_PORT", str(cls.ws_port))),
            llm_provider=os.getenv("LLM_PROVIDER", cls.llm_provider),
            anthropic_api_key=os.getenv("ANTHROPIC_API_KEY", ""),
            anthropic_model=os.getenv("ANTHROPIC_MODEL", cls.anthropic_model),
            openai_api_key=os.getenv("OPENAI_API_KEY", ""),
            openai_model=os.getenv("OPENAI_MODEL", cls.openai_model),
            whisper_model=os.getenv("WHISPER_MODEL", cls.whisper_model),
            whisper_compute_type=os.getenv("WHISPER_COMPUTE_TYPE", cls.whisper_compute_type),
            elevenlabs_api_key=os.getenv("ELEVENLABS_API_KEY", ""),
            elevenlabs_voice_id=os.getenv("ELEVENLABS_VOICE_ID", cls.elevenlabs_voice_id),
            sample_rate=int(os.getenv("SAMPLE_RATE", str(cls.sample_rate))),
            vad_threshold=float(os.getenv("VAD_THRESHOLD", str(cls.vad_threshold))),
            silence_duration_ms=int(os.getenv("SILENCE_DURATION_MS", str(cls.silence_duration_ms))),
            system_prompt=os.getenv("SYSTEM_PROMPT", cls.system_prompt),
        )
