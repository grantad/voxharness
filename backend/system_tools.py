"""System-level tools — shell commands, file ops, cron jobs, code generation.

Gives the LLM direct access to the system, similar to Claude Code but voice-controlled.
"""

import asyncio
import logging
import os
import subprocess
from pathlib import Path

from llm.base import ToolDef

logger = logging.getLogger(__name__)

# Working directory for commands (defaults to home)
WORK_DIR = os.path.expanduser("~")

SYSTEM_TOOLS = [
    ToolDef(
        name="run_command",
        description=(
            "Execute a shell command on the user's system and return the output. "
            "Use this for any system operation: installing packages, running scripts, "
            "git commands, checking system status, listing files, etc. "
            "Commands run in a bash shell. Be careful with destructive commands."
        ),
        parameters={
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to execute (e.g. 'ls -la', 'git status', 'python script.py')",
                },
                "working_directory": {
                    "type": "string",
                    "description": "Directory to run the command in. Defaults to home directory.",
                },
                "timeout": {
                    "type": "integer",
                    "description": "Timeout in seconds (default 30, max 300)",
                    "default": 30,
                },
            },
            "required": ["command"],
        },
    ),
    ToolDef(
        name="read_file",
        description=(
            "Read the contents of a file. Use this to examine code, config files, logs, etc."
        ),
        parameters={
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Absolute or relative path to the file",
                },
                "max_lines": {
                    "type": "integer",
                    "description": "Maximum number of lines to read (default 200)",
                    "default": 200,
                },
            },
            "required": ["path"],
        },
    ),
    ToolDef(
        name="write_file",
        description=(
            "Write content to a file. Creates the file if it doesn't exist, "
            "creates parent directories as needed. Use this to create scripts, "
            "config files, code files, etc."
        ),
        parameters={
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Absolute or relative path for the file",
                },
                "content": {
                    "type": "string",
                    "description": "The full content to write to the file",
                },
            },
            "required": ["path", "content"],
        },
    ),
    ToolDef(
        name="edit_file",
        description=(
            "Edit a file by replacing specific text. Use this for targeted code changes "
            "rather than rewriting the entire file."
        ),
        parameters={
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path to the file to edit",
                },
                "old_text": {
                    "type": "string",
                    "description": "The exact text to find and replace",
                },
                "new_text": {
                    "type": "string",
                    "description": "The replacement text",
                },
            },
            "required": ["path", "old_text", "new_text"],
        },
    ),
    ToolDef(
        name="create_cron_job",
        description=(
            "Create a cron job that runs on a schedule. Use this when the user wants "
            "to schedule recurring tasks."
        ),
        parameters={
            "type": "object",
            "properties": {
                "schedule": {
                    "type": "string",
                    "description": "Cron schedule expression (e.g. '0 9 * * *' for daily at 9am, '*/5 * * * *' for every 5 min)",
                },
                "command": {
                    "type": "string",
                    "description": "The command to run on schedule",
                },
                "comment": {
                    "type": "string",
                    "description": "A descriptive comment for the cron job",
                },
            },
            "required": ["schedule", "command"],
        },
    ),
    ToolDef(
        name="list_cron_jobs",
        description="List all current cron jobs for the user.",
        parameters={"type": "object", "properties": {}},
    ),
    ToolDef(
        name="delete_cron_job",
        description="Delete a cron job by its comment or line number.",
        parameters={
            "type": "object",
            "properties": {
                "comment": {
                    "type": "string",
                    "description": "The comment identifying the cron job to delete",
                },
            },
            "required": ["comment"],
        },
    ),
    ToolDef(
        name="search_files",
        description=(
            "Search for files by name pattern or search file contents with grep. "
            "Use this to find code, config files, or any files on the system."
        ),
        parameters={
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Search pattern — file glob (e.g. '*.py') or text to grep for",
                },
                "directory": {
                    "type": "string",
                    "description": "Directory to search in (default: current working directory)",
                },
                "search_content": {
                    "type": "boolean",
                    "description": "If true, search file contents (grep). If false, search file names (find).",
                    "default": False,
                },
            },
            "required": ["pattern"],
        },
    ),
]


async def handle_system_tool(name: str, args: dict) -> str:
    """Execute a system-level tool and return the result."""
    loop = asyncio.get_event_loop()

    if name == "run_command":
        return await loop.run_in_executor(None, _run_command, args)
    elif name == "read_file":
        return await loop.run_in_executor(None, _read_file, args)
    elif name == "write_file":
        return await loop.run_in_executor(None, _write_file, args)
    elif name == "edit_file":
        return await loop.run_in_executor(None, _edit_file, args)
    elif name == "create_cron_job":
        return await loop.run_in_executor(None, _create_cron_job, args)
    elif name == "list_cron_jobs":
        return await loop.run_in_executor(None, _list_cron_jobs, args)
    elif name == "delete_cron_job":
        return await loop.run_in_executor(None, _delete_cron_job, args)
    elif name == "search_files":
        return await loop.run_in_executor(None, _search_files, args)
    else:
        return f"Unknown system tool: {name}"


def _resolve_path(path: str) -> str:
    """Resolve a path, expanding ~ and making relative paths absolute."""
    path = os.path.expanduser(path)
    if not os.path.isabs(path):
        path = os.path.join(WORK_DIR, path)
    return path


def _run_command(args: dict) -> str:
    """Execute a shell command."""
    command = args["command"]
    cwd = _resolve_path(args.get("working_directory", WORK_DIR))
    timeout = min(args.get("timeout", 30), 300)

    logger.info(f"Running command: {command} (in {cwd})")

    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd,
            env={**os.environ, "PATH": os.environ.get("PATH", "")},
        )

        output = ""
        if result.stdout:
            output += result.stdout
        if result.stderr:
            output += f"\n[stderr]\n{result.stderr}" if output else result.stderr

        # Truncate very long output
        if len(output) > 5000:
            output = output[:5000] + f"\n... (truncated, {len(output)} total chars)"

        status = "success" if result.returncode == 0 else f"exit code {result.returncode}"
        return f"[{status}]\n{output}" if output else f"[{status}] (no output)"

    except subprocess.TimeoutExpired:
        return f"[error] Command timed out after {timeout}s"
    except Exception as e:
        return f"[error] {e}"


def _read_file(args: dict) -> str:
    """Read a file's contents."""
    path = _resolve_path(args["path"])
    max_lines = args.get("max_lines", 200)

    try:
        with open(path, "r") as f:
            lines = f.readlines()

        total = len(lines)
        if total > max_lines:
            content = "".join(lines[:max_lines])
            return f"{content}\n... ({total} total lines, showing first {max_lines})"
        return "".join(lines)

    except FileNotFoundError:
        return f"[error] File not found: {path}"
    except Exception as e:
        return f"[error] {e}"


def _write_file(args: dict) -> str:
    """Write content to a file."""
    path = _resolve_path(args["path"])
    content = args["content"]

    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(content)

        lines = content.count("\n") + 1
        logger.info(f"Wrote {lines} lines to {path}")
        return f"File written: {path} ({lines} lines)"

    except Exception as e:
        return f"[error] {e}"


def _edit_file(args: dict) -> str:
    """Edit a file by replacing text."""
    path = _resolve_path(args["path"])
    old_text = args["old_text"]
    new_text = args["new_text"]

    try:
        with open(path, "r") as f:
            content = f.read()

        if old_text not in content:
            return f"[error] Text not found in {path}"

        count = content.count(old_text)
        content = content.replace(old_text, new_text)

        with open(path, "w") as f:
            f.write(content)

        return f"Replaced {count} occurrence(s) in {path}"

    except FileNotFoundError:
        return f"[error] File not found: {path}"
    except Exception as e:
        return f"[error] {e}"


def _create_cron_job(args: dict) -> str:
    """Create a cron job."""
    schedule = args["schedule"]
    command = args["command"]
    comment = args.get("comment", "voxharness")

    try:
        # Get existing crontab
        result = subprocess.run(
            ["crontab", "-l"], capture_output=True, text=True
        )
        existing = result.stdout if result.returncode == 0 else ""

        # Add new job
        new_line = f"# {comment}\n{schedule} {command}\n"
        new_crontab = existing.rstrip("\n") + "\n" + new_line

        # Install new crontab
        proc = subprocess.run(
            ["crontab", "-"],
            input=new_crontab,
            capture_output=True,
            text=True,
        )

        if proc.returncode == 0:
            logger.info(f"Created cron job: {schedule} {command}")
            return f"Cron job created: {schedule} {command} (comment: {comment})"
        else:
            return f"[error] {proc.stderr}"

    except Exception as e:
        return f"[error] {e}"


def _list_cron_jobs(args: dict) -> str:
    """List all cron jobs."""
    try:
        result = subprocess.run(
            ["crontab", "-l"], capture_output=True, text=True
        )
        if result.returncode != 0:
            return "No cron jobs found."
        return result.stdout or "No cron jobs found."
    except Exception as e:
        return f"[error] {e}"


def _delete_cron_job(args: dict) -> str:
    """Delete a cron job by comment."""
    comment = args["comment"]

    try:
        result = subprocess.run(
            ["crontab", "-l"], capture_output=True, text=True
        )
        if result.returncode != 0:
            return "No cron jobs found."

        lines = result.stdout.split("\n")
        new_lines = []
        skip_next = False
        removed = False

        for line in lines:
            if skip_next:
                skip_next = False
                removed = True
                continue
            if comment in line and line.strip().startswith("#"):
                skip_next = True
                removed = True
                continue
            new_lines.append(line)

        if not removed:
            return f"No cron job found with comment: {comment}"

        new_crontab = "\n".join(new_lines)
        proc = subprocess.run(
            ["crontab", "-"],
            input=new_crontab,
            capture_output=True,
            text=True,
        )

        if proc.returncode == 0:
            return f"Cron job '{comment}' deleted."
        else:
            return f"[error] {proc.stderr}"

    except Exception as e:
        return f"[error] {e}"


def _search_files(args: dict) -> str:
    """Search for files or file contents."""
    pattern = args["pattern"]
    directory = _resolve_path(args.get("directory", WORK_DIR))
    search_content = args.get("search_content", False)

    try:
        if search_content:
            # Grep for content
            result = subprocess.run(
                ["grep", "-r", "-l", "--include=*", "-m", "20", pattern, directory],
                capture_output=True,
                text=True,
                timeout=15,
            )
        else:
            # Find by name
            result = subprocess.run(
                ["find", directory, "-maxdepth", "5", "-name", pattern, "-type", "f"],
                capture_output=True,
                text=True,
                timeout=15,
            )

        output = result.stdout.strip()
        if not output:
            return f"No files found matching '{pattern}' in {directory}"

        # Limit results
        lines = output.split("\n")
        if len(lines) > 30:
            output = "\n".join(lines[:30]) + f"\n... ({len(lines)} total matches)"

        return output

    except subprocess.TimeoutExpired:
        return "[error] Search timed out"
    except Exception as e:
        return f"[error] {e}"
