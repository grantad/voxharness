"""WebSocket server — bridges client audio to the conversation pipeline."""

import asyncio
import json
import logging
from websockets.asyncio.server import serve, ServerConnection

from config import Config
from conversation import Conversation
from llm.router import LLMRouter
from stt import STT
from tools import TOOLS, handle_tool
from tts import TTS
from vad import VAD

logger = logging.getLogger(__name__)


class Session:
    """Per-connection session state."""

    def __init__(self, ws: ServerConnection, config: Config, stt: STT, llm: LLMRouter):
        self.ws = ws
        self.config = config

        # Create per-session VAD (has internal state)
        self.vad = VAD(
            threshold=config.vad_threshold,
            sample_rate=config.sample_rate,
            silence_duration_ms=config.silence_duration_ms,
        )

        # TTS client (stateless, safe to share but cheap to create)
        self.tts = TTS(
            api_key=config.elevenlabs_api_key,
            voice_id=config.elevenlabs_voice_id,
        )

        # Conversation orchestrator
        self.conversation = Conversation(
            vad=self.vad,
            stt=stt,
            tts=self.tts,
            llm=llm,
            system_prompt=config.system_prompt,
            on_send=self._send,
            tools=TOOLS,
            tool_handler=handle_tool,
        )

    async def _send(self, msg):
        """Send a message (JSON dict or binary bytes) to the WebSocket client."""
        try:
            if isinstance(msg, bytes):
                await self.ws.send(msg)
            else:
                await self.ws.send(json.dumps(msg))
        except Exception as e:
            logger.warning(f"Failed to send to client: {e}")

    async def run(self):
        """Handle the WebSocket connection lifecycle."""
        # Load VAD model for this session
        await self.vad.load()
        await self.conversation.start()

        logger.info(f"Session started: {self.ws.remote_address}")
        await self._send({"type": "ready", "provider": self.conversation.llm.active_name})

        audio_bytes_received = 0
        try:
            async for message in self.ws:
                if isinstance(message, bytes):
                    # Binary = PCM audio from mic
                    audio_bytes_received += len(message)
                    if audio_bytes_received % 32000 < len(message):  # log every ~1s of audio
                        logger.debug(f"Audio received: {audio_bytes_received} bytes total")
                    await self.conversation.feed_audio(message)
                else:
                    # JSON = control messages
                    data = json.loads(message)
                    await self._handle_command(data)
        except Exception as e:
            logger.error(f"Session error: {e}", exc_info=True)
        finally:
            logger.info(f"Session ended: {self.ws.remote_address}")

    async def _handle_command(self, data: dict):
        """Handle JSON commands from the client."""
        msg_type = data.get("type", "")

        if msg_type == "hello":
            logger.info(f"Client hello: {data}")

        elif msg_type == "barge_in":
            # Client-side barge-in detection
            self.conversation._cancel_event.set()
            await self._send({"type": "cancel"})

        elif msg_type == "text_input":
            # Typed text (bypass STT)
            text = data.get("text", "").strip()
            if text:
                await self.conversation.handle_text_input(text)

        elif msg_type == "switch_provider":
            provider = data.get("provider", "")
            try:
                self.conversation.llm.set_active(provider)
                await self._send({
                    "type": "status",
                    "state": "provider_switched",
                    "provider": provider,
                })
                logger.info(f"Switched to provider: {provider}")
            except ValueError as e:
                await self._send({"type": "error", "message": str(e)})

        else:
            logger.warning(f"Unknown command type: {msg_type}")


class Server:
    """WebSocket server that manages sessions."""

    def __init__(self, config: Config):
        self.config = config
        self._stt: STT | None = None
        self._llm: LLMRouter | None = None

    async def start(self):
        """Initialize shared resources and start the WebSocket server."""
        # Load STT model (shared across sessions)
        logger.info(f"Loading Whisper model: {self.config.whisper_model}")
        self._stt = STT(
            model_size=self.config.whisper_model,
            compute_type=self.config.whisper_compute_type,
        )
        await self._stt.load()
        logger.info("Whisper model loaded")

        # Initialize LLM router (shared across sessions)
        self._llm = LLMRouter.from_config(self.config)
        logger.info(f"LLM router ready, active provider: {self._llm.active_name}")

        # Start WebSocket server
        logger.info(f"Starting WebSocket server on {self.config.ws_host}:{self.config.ws_port}")
        async with serve(
            self._handle_connection,
            self.config.ws_host,
            self.config.ws_port,
        ) as server:
            print(f"READY ws://{self.config.ws_host}:{self.config.ws_port}", flush=True)
            await asyncio.Future()  # run forever

    async def _handle_connection(self, ws: ServerConnection):
        """Handle a new WebSocket connection."""
        session = Session(ws, self.config, self._stt, self._llm)
        await session.run()
