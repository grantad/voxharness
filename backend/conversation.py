"""Conversation orchestrator — turn loop with sentence-level TTS pipelining."""

import asyncio
import json
import logging
import re
from typing import AsyncIterator, Callable, Awaitable

from llm.base import Delta, Message, StopDelta, TokenDelta, ToolCallDelta, ToolDef
from llm.router import LLMRouter
from stt import STT, TranscriptResult
from tts import TTS
from vad import VAD, VADEvent

logger = logging.getLogger(__name__)

# Sentence boundary pattern: split on . ! ? followed by space or end
SENTENCE_RE = re.compile(r'(?<=[.!?])\s+')


class Conversation:
    """Manages the full voice conversation loop for one session."""

    def __init__(
        self,
        vad: VAD,
        stt: STT,
        tts: TTS,
        llm: LLMRouter,
        system_prompt: str,
        on_send: Callable[[dict], Awaitable[None]] | None = None,
        tools: list[ToolDef] | None = None,
        tool_handler: Callable[[str, dict], Awaitable[str]] | None = None,
        on_turn_complete: Callable[[], Awaitable[None]] | None = None,
    ):
        self.vad = vad
        self.stt = stt
        self.tts = tts
        self.llm = llm
        self.system_prompt = system_prompt
        self.on_send = on_send  # send JSON message to client
        self.tools = tools
        self.tool_handler = tool_handler
        self.on_turn_complete = on_turn_complete

        self.messages: list[Message] = []
        self._cancel_event = asyncio.Event()
        self._tts_active = False
        self._current_turn_task: asyncio.Task | None = None

    async def start(self):
        """Start listening for voice input."""
        self.vad.on_event(self._on_vad_event)
        logger.info("Conversation started")

    async def feed_audio(self, pcm_bytes: bytes):
        """Feed raw PCM audio from the mic."""
        await self.vad.feed(pcm_bytes)

    async def handle_text_input(self, text: str):
        """Handle typed text input (bypass STT)."""
        await self._process_turn(text)

    async def flush_audio(self):
        """Flush any buffered audio (called on PTT release)."""
        logger.info("PTT released, flushing audio buffer")
        await self.vad.flush()

    async def _on_vad_event(self, event: VADEvent):
        """Handle VAD speech start/end events."""
        if event.kind == "speech_start":
            # Barge-in: cancel current TTS if playing
            if self._tts_active:
                logger.info("Barge-in detected, cancelling TTS")
                self._cancel_event.set()
                if self._current_turn_task:
                    self._current_turn_task.cancel()
                await self._send({"type": "cancel"})

        elif event.kind == "speech_end" and event.audio:
            # Skip very short audio (likely noise, not speech)
            # 16-bit PCM at 16kHz = 32000 bytes/sec, so 16000 bytes = 0.5s
            if len(event.audio) < 8000:  # less than 0.25s
                logger.debug(f"Skipping short audio: {len(event.audio)} bytes")
                return

            # Transcribe the speech
            await self._send({"type": "status", "state": "transcribing"})
            result = await self.stt.transcribe(event.audio)

            text = result.text.strip()
            # Filter out noise/garbage transcripts
            if not text or len(text) < 2:
                return
            # Common Whisper hallucinations on noise
            noise_phrases = {"you", "yeah", "mm", "hmm", "uh", "um", "oh", "ah",
                             "thank you.", "thanks for watching.", "bye.",
                             "subscribe.", "like and subscribe."}
            if text.lower().rstrip(".!,") in noise_phrases:
                logger.debug(f"Filtered noise transcript: {text}")
                return

            logger.info(f"Transcript: {text}")
            await self._send({"type": "final_transcript", "text": text})

            # Start a new turn
            self._current_turn_task = asyncio.create_task(
                self._process_turn(result.text)
            )

    async def _process_turn(self, user_text: str):
        """Process a complete user turn: LLM -> sentence chunking -> TTS."""
        self._cancel_event.clear()

        # Add user message to history
        self.messages.append(Message(role="user", content=user_text))

        await self._run_llm_turn()

        # Signal turn is complete (for wake word mode to go back to listening)
        if self.on_turn_complete:
            await self.on_turn_complete()

    def _repair_history(self):
        """Ensure conversation history is valid for the API.

        Every assistant message with tool_calls must be followed by
        matching tool_result messages. If not, add placeholder results.
        """
        repaired = False
        i = 0
        while i < len(self.messages):
            msg = self.messages[i]
            if msg.role == "assistant" and msg.tool_calls:
                # Check that every tool_call has a matching tool_result after it
                needed_ids = {tc["id"] for tc in msg.tool_calls}
                found_ids = set()
                j = i + 1
                while j < len(self.messages) and self.messages[j].role == "tool":
                    if self.messages[j].tool_call_id in needed_ids:
                        found_ids.add(self.messages[j].tool_call_id)
                    j += 1

                missing = needed_ids - found_ids
                if missing:
                    # Insert placeholder tool results for missing ones
                    insert_at = i + 1
                    for tc in msg.tool_calls:
                        if tc["id"] in missing:
                            self.messages.insert(insert_at, Message(
                                role="tool",
                                content="[cancelled]",
                                tool_call_id=tc["id"],
                            ))
                            insert_at += 1
                    repaired = True
                    logger.warning(f"Repaired history: added {len(missing)} missing tool_result(s)")
            i += 1
        return repaired

    async def _run_llm_turn(self):
        """Run LLM and handle response (may recurse for tool calls)."""
        # Repair history before each LLM call
        self._repair_history()

        full_text = ""
        sentence_buffer = ""
        pending_tool_calls = []
        sentences_to_speak: list[str] = []

        try:
            async for delta in self.llm.stream_chat(
                messages=self.messages,
                system=self.system_prompt,
                tools=self.tools,
            ):
                if self._cancel_event.is_set():
                    break

                if isinstance(delta, TokenDelta):
                    full_text += delta.text
                    sentence_buffer += delta.text

                    # Send token to client for display
                    await self._send({"type": "assistant_token", "text": delta.text})

                    # Check for sentence boundaries
                    sentences = SENTENCE_RE.split(sentence_buffer)
                    if len(sentences) > 1:
                        for sentence in sentences[:-1]:
                            sentence = sentence.strip()
                            if sentence:
                                sentences_to_speak.append(sentence)
                        sentence_buffer = sentences[-1]

                elif isinstance(delta, ToolCallDelta):
                    pending_tool_calls.append(delta)

                elif isinstance(delta, StopDelta):
                    pass

            # Add any remaining text
            remaining = sentence_buffer.strip()
            if remaining and not self._cancel_event.is_set():
                sentences_to_speak.append(remaining)

            # Speak all sentences sequentially
            for sentence in sentences_to_speak:
                if self._cancel_event.is_set():
                    break
                await self._speak_sentence(sentence)

            # Add assistant message to history
            if full_text or pending_tool_calls:
                msg = Message(
                    role="assistant",
                    content=full_text,
                    tool_calls=[
                        {"id": tc.id, "name": tc.name, "arguments": tc.arguments}
                        for tc in pending_tool_calls
                    ] if pending_tool_calls else None,
                )
                self.messages.append(msg)

            # Handle tool calls — always add results for ALL tool calls
            # even if cancelled, to keep history valid
            if pending_tool_calls and self.tool_handler:
                for tc in pending_tool_calls:
                    tool_args = json.loads(tc.arguments)

                    if self._cancel_event.is_set():
                        # Cancelled — add placeholder result
                        self.messages.append(Message(
                            role="tool",
                            content="[cancelled by user]",
                            tool_call_id=tc.id,
                        ))
                        continue

                    # Execute tool
                    try:
                        result = await self.tool_handler(tc.name, tool_args)
                    except Exception as e:
                        result = f"Error: {e}"

                    # Forward tool call to client with any resolved data
                    event_data = {
                        "type": "tool_call",
                        "name": tc.name,
                        "args": tool_args,
                    }
                    if "_resolved" in tool_args:
                        event_data["resolved"] = tool_args.pop("_resolved")
                    await self._send(event_data)

                    self.messages.append(Message(
                        role="tool",
                        content=result,
                        tool_call_id=tc.id,
                    ))

                # Continue the conversation with tool results
                if not self._cancel_event.is_set():
                    await self._run_llm_turn()

        except asyncio.CancelledError:
            logger.info("Turn cancelled (barge-in)")
            # Repair history in case we were mid-tool-call
            self._repair_history()
        except Exception as e:
            logger.error(f"Error in turn: {e}", exc_info=True)
            # Try to repair and recover
            if self._repair_history():
                logger.info("History repaired after error")
            await self._send({"type": "error", "message": str(e)})

    async def _speak_sentence(self, text: str):
        """Send a sentence to TTS and stream audio to client."""
        if self._cancel_event.is_set():
            return

        logger.info(f"Speaking: {text[:80]}")
        self._tts_active = True
        await self._send({"type": "tts_start", "format": "mp3"})

        chunk_count = 0
        total_bytes = 0
        try:
            async for chunk in self.tts.synthesize(text, self._cancel_event):
                if self._cancel_event.is_set():
                    break
                chunk_count += 1
                total_bytes += len(chunk)
                await self._send_binary(chunk)
            logger.info(f"TTS sent {chunk_count} chunks, {total_bytes} bytes")
        except Exception as e:
            logger.error(f"TTS streaming error: {e}", exc_info=True)
        finally:
            self._tts_active = False
            await self._send({"type": "tts_end"})

    async def _send(self, msg: dict):
        """Send a JSON message to the client."""
        if self.on_send:
            await self.on_send(msg)

    async def _send_binary(self, data: bytes):
        """Send binary data to the client."""
        if self.on_send:
            await self.on_send(data)
