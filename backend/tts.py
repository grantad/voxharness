"""ElevenLabs streaming text-to-speech."""

import asyncio
import logging
from typing import AsyncIterator
from elevenlabs import AsyncElevenLabs

logger = logging.getLogger(__name__)


class TTS:
    """ElevenLabs streaming TTS client with cancellation support."""

    def __init__(self, api_key: str, voice_id: str = "21m00Tcm4TlvDq8ikWAM"):
        self.voice_id = voice_id
        self._client = AsyncElevenLabs(api_key=api_key)

    async def synthesize(
        self,
        text: str,
        cancel_event: asyncio.Event | None = None,
    ) -> AsyncIterator[bytes]:
        """Stream TTS audio chunks (MP3) for the given text.

        Args:
            text: Text to synthesize
            cancel_event: If set, stops streaming early (for barge-in)

        Yields:
            MP3 audio chunks
        """
        if not text.strip():
            return

        logger.info(f"TTS synthesizing: {text[:50]}...")

        try:
            audio_stream = self._client.text_to_speech.convert(
                voice_id=self.voice_id,
                text=text,
                model_id="eleven_turbo_v2_5",
                output_format="mp3_44100_128",
            )

            async for chunk in audio_stream:
                if cancel_event and cancel_event.is_set():
                    logger.info("TTS cancelled (barge-in)")
                    break
                if isinstance(chunk, bytes) and len(chunk) > 0:
                    yield chunk
        except Exception as e:
            logger.error(f"TTS error: {e}", exc_info=True)
