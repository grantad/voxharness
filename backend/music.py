"""Music playback via yt-dlp (YouTube search + stream) and local files."""

import asyncio
import json
import logging
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

MUSIC_DIR = Path(__file__).parent / "audio_assets" / "music"


@dataclass
class TrackInfo:
    title: str
    url: str  # direct audio stream URL or local file path
    source: str  # "youtube" or "local"
    duration: float | None = None


class MusicService:
    """Search and resolve music tracks from YouTube or local files."""

    def __init__(self, music_dir: Path | None = None):
        self.music_dir = music_dir or MUSIC_DIR
        self.music_dir.mkdir(parents=True, exist_ok=True)

    async def search(self, query: str) -> TrackInfo | None:
        """Search for a track — checks local files first, then YouTube."""
        # Check local files
        local = self._find_local(query)
        if local:
            return local

        # Search YouTube
        return await self._search_youtube(query)

    def _find_local(self, query: str) -> TrackInfo | None:
        """Find a matching local audio file."""
        query_lower = query.lower()
        for ext in ("*.mp3", "*.wav", "*.ogg", "*.m4a", "*.flac"):
            for f in self.music_dir.glob(ext):
                if query_lower in f.stem.lower():
                    return TrackInfo(
                        title=f.stem,
                        url=str(f.absolute()),
                        source="local",
                    )
        return None

    async def _search_youtube(self, query: str) -> TrackInfo | None:
        """Search YouTube and return a streamable audio URL."""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._search_youtube_sync, query)

    def _search_youtube_sync(self, query: str) -> TrackInfo | None:
        """Synchronous YouTube search via yt-dlp."""
        try:
            # Use yt-dlp to search and extract audio URL
            cmd = [
                "yt-dlp",
                "--no-download",
                "--print", "%(title)s",
                "--print", "%(url)s",
                "--print", "%(duration)s",
                "-f", "bestaudio[ext=m4a]/bestaudio",
                "--no-playlist",
                f"ytsearch1:{query}",
            ]
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=15,
            )

            if result.returncode != 0:
                logger.error(f"yt-dlp error: {result.stderr}")
                return None

            lines = result.stdout.strip().split("\n")
            if len(lines) < 2:
                return None

            title = lines[0]
            url = lines[1]
            duration = float(lines[2]) if len(lines) > 2 and lines[2] != "NA" else None

            logger.info(f"YouTube found: {title} ({duration}s)")
            return TrackInfo(
                title=title,
                url=url,
                source="youtube",
                duration=duration,
            )

        except subprocess.TimeoutExpired:
            logger.error("yt-dlp search timed out")
            return None
        except Exception as e:
            logger.error(f"YouTube search error: {e}")
            return None

    async def get_stream_url(self, query: str) -> dict | None:
        """Get a playable stream URL for the client.

        Returns dict with {title, url, source} or None.
        """
        track = await self.search(query)
        if not track:
            return None

        return {
            "title": track.title,
            "url": track.url,
            "source": track.source,
            "duration": track.duration,
        }
