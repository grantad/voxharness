"""Speech-to-text using faster-whisper."""

import asyncio
import io
import numpy as np
import soundfile as sf
from dataclasses import dataclass


@dataclass
class TranscriptResult:
    text: str
    language: str
    confidence: float


class STT:
    """Faster-whisper speech-to-text wrapper."""

    def __init__(self, model_size: str = "base.en", compute_type: str = "int8"):
        self.model_size = model_size
        self.compute_type = compute_type
        self._model = None

    async def load(self):
        """Load the Whisper model (runs in executor to avoid blocking)."""
        loop = asyncio.get_event_loop()
        self._model = await loop.run_in_executor(None, self._load_model)

    def _load_model(self):
        from faster_whisper import WhisperModel

        return WhisperModel(
            self.model_size,
            device="cpu",
            compute_type=self.compute_type,
        )

    async def transcribe(self, pcm_audio: bytes, sample_rate: int = 16000) -> TranscriptResult:
        """Transcribe raw 16-bit PCM audio bytes.

        Args:
            pcm_audio: Raw 16-bit signed PCM bytes
            sample_rate: Sample rate of the audio (default 16kHz)

        Returns:
            TranscriptResult with transcribed text
        """
        if self._model is None:
            raise RuntimeError("STT model not loaded. Call load() first.")

        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            None, self._transcribe_sync, pcm_audio, sample_rate
        )

    def _transcribe_sync(self, pcm_audio: bytes, sample_rate: int) -> TranscriptResult:
        """Synchronous transcription (runs in thread pool)."""
        # Convert PCM bytes to float32 numpy array
        audio = np.frombuffer(pcm_audio, dtype=np.int16).astype(np.float32) / 32768.0

        segments, info = self._model.transcribe(
            audio,
            beam_size=1,  # greedy for speed
            language="en",
            vad_filter=False,  # we already did VAD
        )

        text = " ".join(seg.text.strip() for seg in segments).strip()

        return TranscriptResult(
            text=text,
            language=info.language,
            confidence=info.language_probability,
        )
