"""Silero VAD wrapper for voice activity detection."""

import asyncio
import numpy as np
from dataclasses import dataclass
from typing import Callable, Awaitable


@dataclass
class VADEvent:
    kind: str  # "speech_start" or "speech_end"
    audio: bytes | None = None  # accumulated audio on speech_end


class VAD:
    """Silero VAD with turn detection.

    Feeds PCM frames, detects speech boundaries,
    and emits events with accumulated audio on speech_end.
    """

    def __init__(
        self,
        threshold: float = 0.5,
        sample_rate: int = 16000,
        silence_duration_ms: int = 400,
    ):
        self.threshold = threshold
        self.sample_rate = sample_rate
        self.silence_duration_ms = silence_duration_ms

        # Silero VAD expects 512 samples at 16kHz
        self.frame_size = 512

        self._model = None
        self._is_speaking = False
        self._audio_buffer = bytearray()
        self._silence_frames = 0
        self._silence_frames_threshold = int(
            (silence_duration_ms / 1000) * sample_rate / self.frame_size
        )
        self._on_event: Callable[[VADEvent], Awaitable[None]] | None = None
        self._pending_frames = bytearray()

    async def load(self):
        """Load the Silero VAD model."""
        loop = asyncio.get_event_loop()
        self._model = await loop.run_in_executor(None, self._load_model)

    def _load_model(self):
        from silero_vad import load_silero_vad
        model = load_silero_vad()
        return model

    def on_event(self, callback: Callable[[VADEvent], Awaitable[None]]):
        """Register event callback."""
        self._on_event = callback

    async def feed(self, pcm_bytes: bytes):
        """Feed raw 16-bit PCM bytes and detect speech boundaries."""
        import torch

        if self._model is None:
            raise RuntimeError("VAD model not loaded. Call load() first.")

        self._pending_frames.extend(pcm_bytes)
        frame_bytes = self.frame_size * 2  # 16-bit = 2 bytes per sample

        while len(self._pending_frames) >= frame_bytes:
            frame_data = bytes(self._pending_frames[:frame_bytes])
            del self._pending_frames[:frame_bytes]

            # Convert to float32 tensor
            samples = np.frombuffer(frame_data, dtype=np.int16).astype(np.float32) / 32768.0
            tensor = torch.from_numpy(samples)

            # Run VAD
            confidence = self._model(tensor, self.sample_rate).item()
            is_speech = confidence > self.threshold

            if is_speech:
                self._silence_frames = 0
                if not self._is_speaking:
                    self._is_speaking = True
                    self._audio_buffer = bytearray()
                    if self._on_event:
                        await self._on_event(VADEvent(kind="speech_start"))
                self._audio_buffer.extend(frame_data)
            else:
                if self._is_speaking:
                    self._audio_buffer.extend(frame_data)
                    self._silence_frames += 1
                    if self._silence_frames >= self._silence_frames_threshold:
                        self._is_speaking = False
                        audio = bytes(self._audio_buffer)
                        self._audio_buffer = bytearray()
                        if self._on_event:
                            await self._on_event(VADEvent(kind="speech_end", audio=audio))

    def reset(self):
        """Reset VAD state for a new session."""
        self._is_speaking = False
        self._audio_buffer = bytearray()
        self._silence_frames = 0
        self._pending_frames = bytearray()
        if self._model is not None:
            self._model.reset_states()
