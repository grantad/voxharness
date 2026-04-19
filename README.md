# VoxHarness

Voice-first AI harness. Speak to an LLM like a person — it speaks back, displays responses on screen, and plays music and sounds.

## Quick Start

### 1. Backend Setup

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -e .
```

### 2. Configure

```bash
cp .env.example .env
# Edit .env with your API keys (Anthropic/OpenAI + ElevenLabs)
```

### 3. Run Backend (standalone)

```bash
cd backend
python main.py
```

### 4. Run Electron App

```bash
cd electron
npm install
npm start
```

The Electron app spawns the Python backend automatically.

## Architecture

```
Microphone → WebSocket → Silero VAD → faster-whisper STT
                                              ↓
                                     LLM (Claude / GPT-4o)
                                              ↓
                                     ElevenLabs TTS → Speakers
                                              ↓
                                     Electron UI (conversation + visuals)
```

- **Push-to-talk**: Hold the mic button and speak
- **Text input**: Type in the text box as a fallback
- **Barge-in**: Speak while the AI is talking to interrupt

## Tech Stack

- **Backend**: Python, websockets, faster-whisper, silero-vad
- **LLM**: Anthropic Claude / OpenAI GPT (switchable)
- **TTS**: ElevenLabs streaming
- **Frontend**: Electron with vanilla JS
- **Protocol**: WebSocket (JSON + binary PCM/MP3)
