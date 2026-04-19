"""Tool definitions and handlers for the LLM to invoke."""

from llm.base import ToolDef

# Tool definitions exposed to the LLM
TOOLS = [
    ToolDef(
        name="play_music",
        description="Play a music track or genre. Use when the user asks to play music.",
        parameters={
            "type": "object",
            "properties": {
                "track": {
                    "type": "string",
                    "description": "Track name, genre, or description (e.g. 'lo-fi beats', 'jazz')",
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

    Most tools are forwarded to the client via WebSocket (handled in server.py).
    This handler returns confirmation messages for the LLM.
    """
    if name == "play_music":
        return f"Now playing: {args.get('track', 'music')}"
    elif name == "stop_music":
        return "Music stopped."
    elif name == "play_sfx":
        return f"Playing sound effect: {args.get('name', 'unknown')}"
    elif name == "show_text_card":
        return f"Showing card: {args.get('title', 'untitled')}"
    else:
        return f"Unknown tool: {name}"
