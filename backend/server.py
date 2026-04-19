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
from wakeword import WakeWordDetector

logger = logging.getLogger(__name__)


class Session:
    """Per-connection session state."""

    def __init__(self, ws: ServerConnection, config: Config, stt: STT, llm: LLMRouter):
        self.ws = ws
        self.config = config
        self.listen_mode = config.listen_mode  # "wake_word", "push_to_talk", "always_on"

        # Listening state for wake_word mode
        self._actively_listening = False
        self._conversation_timeout_task: asyncio.Task | None = None
        self._conversation_timeout_sec = 30  # stay listening for 30s after response

        # Create per-session VAD (has internal state)
        self.vad = VAD(
            threshold=config.vad_threshold,
            sample_rate=config.sample_rate,
            silence_duration_ms=config.silence_duration_ms,
        )

        # Wake word detector
        self.wake_word = WakeWordDetector(
            wake_word=config.wake_word,
            threshold=config.wake_word_threshold,
        )

        # TTS client
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
            on_turn_complete=self._on_turn_complete,
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

    async def _on_wake_word_detected(self):
        """Called when wake word is detected."""
        if self._actively_listening:
            return  # already listening

        # Cancel any pending timeout
        if self._conversation_timeout_task:
            self._conversation_timeout_task.cancel()
            self._conversation_timeout_task = None

        self._actively_listening = True
        logger.info("Wake word detected — now listening")
        await self._send({"type": "wake_word_detected"})
        await self._send({"type": "status", "state": "listening"})

    async def _on_turn_complete(self):
        """Called when a conversation turn finishes (TTS done)."""
        if self.listen_mode == "wake_word":
            # Stay listening for follow-ups — start a timeout
            # If no new speech within timeout, go back to wake word mode
            if self._conversation_timeout_task:
                self._conversation_timeout_task.cancel()
            self._conversation_timeout_task = asyncio.create_task(
                self._conversation_timeout()
            )
            logger.info(f"Turn complete — listening for {self._conversation_timeout_sec}s more")
            await self._send({"type": "status", "state": "listening"})

    async def _conversation_timeout(self):
        """After N seconds of no new speech, go back to wake word mode."""
        try:
            await asyncio.sleep(self._conversation_timeout_sec)
            self._actively_listening = False
            self.vad.reset()
            logger.info("Conversation timeout — waiting for wake word")
            await self._send({"type": "status", "state": "waiting_for_wake_word"})
        except asyncio.CancelledError:
            pass  # new turn started, timeout cancelled

    async def run(self):
        """Handle the WebSocket connection lifecycle."""
        # Load models
        await self.vad.load()
        if self.listen_mode == "wake_word":
            await self.wake_word.load()
            self.wake_word.on_wake(self._on_wake_word_detected)
        await self.conversation.start()

        logger.info(f"Session started: {self.ws.remote_address} (mode={self.listen_mode})")
        await self._send({
            "type": "ready",
            "provider": self.conversation.llm.active_name,
            "listen_mode": self.listen_mode,
            "wake_word": self.config.wake_word,
        })

        try:
            async for message in self.ws:
                if isinstance(message, bytes):
                    await self._handle_audio(message)
                else:
                    data = json.loads(message)
                    await self._handle_command(data)
        except Exception as e:
            logger.error(f"Session error: {e}", exc_info=True)
        finally:
            logger.info(f"Session ended: {self.ws.remote_address}")

    async def _handle_audio(self, pcm_bytes: bytes):
        """Route audio to wake word detector and/or VAD based on mode."""
        if self.listen_mode == "wake_word":
            # Always feed wake word detector
            await self.wake_word.feed(pcm_bytes)

            # Only feed VAD when actively listening (after wake word)
            if self._actively_listening:
                await self.conversation.feed_audio(pcm_bytes)

        elif self.listen_mode == "always_on":
            await self.conversation.feed_audio(pcm_bytes)

        elif self.listen_mode == "push_to_talk":
            # PTT mode — audio only flows when client is recording
            await self.conversation.feed_audio(pcm_bytes)

    async def _handle_command(self, data: dict):
        """Handle JSON commands from the client."""
        msg_type = data.get("type", "")

        if msg_type == "hello":
            logger.info(f"Client hello: {data}")

        elif msg_type == "barge_in":
            self.conversation._cancel_event.set()
            await self._send({"type": "cancel"})

        elif msg_type == "ptt_release":
            await self.conversation.flush_audio()

        elif msg_type == "text_input":
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

        elif msg_type == "set_listen_mode":
            mode = data.get("mode", "")
            if mode in ("wake_word", "push_to_talk", "always_on"):
                self.listen_mode = mode
                self._actively_listening = (mode != "wake_word")
                logger.info(f"Listen mode changed to: {mode}")
                await self._send({
                    "type": "status",
                    "state": "mode_changed",
                    "listen_mode": mode,
                })

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
        logger.info(f"Loading Whisper model: {self.config.whisper_model}")
        self._stt = STT(
            model_size=self.config.whisper_model,
            compute_type=self.config.whisper_compute_type,
        )
        await self._stt.load()
        logger.info("Whisper model loaded")

        self._llm = LLMRouter.from_config(self.config)
        logger.info(f"LLM router ready, active provider: {self._llm.active_name}")

        logger.info(f"Starting WebSocket server on {self.config.ws_host}:{self.config.ws_port}")
        logger.info(f"Listen mode: {self.config.listen_mode}, wake word: {self.config.wake_word}")
        async with serve(
            self._handle_connection,
            self.config.ws_host,
            self.config.ws_port,
        ) as server:
            print(f"READY ws://{self.config.ws_host}:{self.config.ws_port}", flush=True)
            await asyncio.Future()

    async def _handle_connection(self, ws: ServerConnection):
        """Handle a new WebSocket connection."""
        session = Session(ws, self.config, self._stt, self._llm)
        await session.run()
