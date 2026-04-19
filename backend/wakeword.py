"""Wake word detection using openwakeword."""

import asyncio
import logging
import numpy as np
from typing import Callable, Awaitable

logger = logging.getLogger(__name__)

# Map user-friendly names to openwakeword model names
WAKE_WORD_MODELS = {
    "jarvis": "hey_jarvis_v0.1",
    "hey jarvis": "hey_jarvis_v0.1",
    "mycroft": "hey_mycroft_v0.1",
    "hey mycroft": "hey_mycroft_v0.1",
    "alexa": "alexa_v0.1",
    "computer": "hey_jarvis_v0.1",  # use hey_jarvis as fallback for "computer"
}


class WakeWordDetector:
    """Detects a wake word in streaming audio using openwakeword."""

    def __init__(
        self,
        wake_word: str = "hey_jarvis_v0.1",
        threshold: float = 0.5,
        cooldown_ms: int = 2000,
    ):
        # Resolve user-friendly name to model name
        self.model_name = WAKE_WORD_MODELS.get(wake_word.lower(), wake_word)
        self.threshold = threshold
        self.cooldown_ms = cooldown_ms
        self._model = None
        self._on_wake: Callable[[], Awaitable[None]] | None = None
        self._last_trigger_time = 0
        self._pending_frames = bytearray()
        # openwakeword expects 1280 samples (80ms at 16kHz)
        self.frame_size = 1280

    async def load(self):
        """Load the wake word model."""
        loop = asyncio.get_event_loop()
        self._model = await loop.run_in_executor(None, self._load_model)
        logger.info(f"Wake word model loaded: {self.model_name}")

    def _load_model(self):
        from openwakeword.model import Model
        return Model(
            wakeword_models=[self.model_name],
            inference_framework="onnx",
        )

    def on_wake(self, callback: Callable[[], Awaitable[None]]):
        """Register callback for when wake word is detected."""
        self._on_wake = callback

    async def feed(self, pcm_bytes: bytes):
        """Feed raw 16-bit PCM audio and check for wake word."""
        if self._model is None:
            return

        self._pending_frames.extend(pcm_bytes)
        frame_bytes = self.frame_size * 2  # 16-bit = 2 bytes per sample

        while len(self._pending_frames) >= frame_bytes:
            frame_data = bytes(self._pending_frames[:frame_bytes])
            del self._pending_frames[:frame_bytes]

            # Convert to int16 numpy array (openwakeword expects this)
            samples = np.frombuffer(frame_data, dtype=np.int16)

            # Run prediction
            prediction = self._model.predict(samples)

            score = prediction.get(self.model_name, 0.0)
            if score > self.threshold:
                import time
                now = time.monotonic() * 1000
                if now - self._last_trigger_time > self.cooldown_ms:
                    self._last_trigger_time = now
                    logger.info(f"Wake word detected! score={score:.3f}")
                    if self._on_wake:
                        await self._on_wake()

    def reset(self):
        """Reset detector state."""
        self._pending_frames = bytearray()
        if self._model:
            self._model.reset()
