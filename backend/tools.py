"""Tool definitions and handlers for the LLM to invoke."""

import logging
from llm.base import ToolDef
from music import MusicService

logger = logging.getLogger(__name__)

# Shared music service instance
_music_service = MusicService()

# Tool definitions exposed to the LLM
TOOLS = [
    ToolDef(
        name="play_music",
        description="Play a music track, song, or genre. Searches YouTube for the track and streams it. Use when the user asks to play music, a song, or any audio content.",
        parameters={
            "type": "object",
            "properties": {
                "track": {
                    "type": "string",
                    "description": "Track name, artist, song title, or search query (e.g. 'He Has Risen hymn', 'lo-fi beats', 'Mozart Symphony 40')",
                },
                "volume": {
                    "type": "number",
                    "description": "Volume level from 0.0 to 1.0",
                    "default": 0.7,
                },
            },
            "required": ["track"],
        },
    ),
    ToolDef(
        name="stop_music",
        description="Stop the currently playing music.",
        parameters={"type": "object", "properties": {}},
    ),
    ToolDef(
        name="play_sfx",
        description="Play a sound effect.",
        parameters={
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Sound effect name (e.g. 'chime', 'alert', 'notification')",
                },
            },
            "required": ["name"],
        },
    ),
    ToolDef(
        name="show_text_card",
        description="Display a text card on screen with a title and body content.",
        parameters={
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Card title"},
                "body": {"type": "string", "description": "Card body (supports markdown)"},
            },
            "required": ["title", "body"],
        },
    ),
]


async def handle_tool(name: str, args: dict) -> str:
    """Execute a tool call and return result string.

    For play_music, searches YouTube and returns the stream URL
    which gets forwarded to the client for playback.
    """
    if name == "play_music":
        query = args.get("track", "music")
        logger.info(f"Searching for music: {query}")
        result = await _music_service.get_stream_url(query)
        if result:
            # Store the result so it can be forwarded with the tool_call event
            args["_resolved"] = result
            return f"Now playing: {result['title']}"
        else:
            return f"Could not find music for: {query}"
    elif name == "stop_music":
        return "Music stopped."
    elif name == "play_sfx":
        return f"Playing sound effect: {args.get('name', 'unknown')}"
    elif name == "show_text_card":
        return f"Showing card: {args.get('title', 'untitled')}"
    else:
        return f"Unknown tool: {name}"
