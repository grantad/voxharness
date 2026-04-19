/**
 * VoxHarness renderer — WebSocket client, mic capture, audio playback.
 */

// --- State ---
let ws = null;
let playbackContext = null;  // separate context for TTS playback
let micStream = null;
let micWorklet = null;
let ttsChunks = [];          // accumulate all MP3 chunks per utterance
let ttsPlaying = false;
let currentTTSSource = null;
let ttsPlaybackQueue = [];   // queue of complete MP3 buffers to play sequentially
let currentAssistantEl = null;
const waveform = new WaveformVisualizer('waveform');

// --- DOM refs ---
const messagesEl = document.getElementById('messages');
const micBtn = document.getElementById('mic-btn');
const micIndicator = document.getElementById('mic-indicator');
const micLabel = document.getElementById('mic-label');
const connIndicator = document.getElementById('conn-indicator');
const connLabel = document.getElementById('conn-label');
const providerLabel = document.getElementById('provider-label');
const textInput = document.getElementById('text-input');
const sendBtn = document.getElementById('send-btn');

// --- Audio Context for playback ---
function getPlaybackContext() {
  if (!playbackContext) {
    playbackContext = new AudioContext();
  }
  return playbackContext;
}

// --- WebSocket ---
function connect() {
  const url = window.voxharness?.wsUrl || 'ws://127.0.0.1:8765';
  ws = new WebSocket(url);
  ws.binaryType = 'arraybuffer';

  ws.onopen = () => {
    connIndicator.className = 'indicator on';
    connLabel.textContent = 'Connected';
    ws.send(JSON.stringify({ type: 'hello' }));
  };

  ws.onclose = () => {
    connIndicator.className = 'indicator off';
    connLabel.textContent = 'Disconnected';
    setTimeout(connect, 3000);
  };

  ws.onerror = (err) => {
    console.error('WebSocket error:', err);
    connIndicator.className = 'indicator error';
  };

  ws.onmessage = (event) => {
    if (event.data instanceof ArrayBuffer) {
      handleTTSAudio(event.data);
    } else {
      handleMessage(JSON.parse(event.data));
    }
  };
}

function handleMessage(msg) {
  switch (msg.type) {
    case 'ready':
      providerLabel.textContent = msg.provider || '--';
      addSystemMessage('Connected. Hold the mic button and speak, or type a message.');
      break;

    case 'final_transcript':
      addMessage('user', msg.text);
      break;

    case 'assistant_token':
      appendAssistantToken(msg.text);
      break;

    case 'tts_start':
      waveform.setMode('speaking');
      ttsChunks = [];
      break;

    case 'tts_end':
      // All chunks received — now decode and play the complete MP3
      playAccumulatedTTS();
      break;

    case 'cancel':
      cancelTTS();
      break;

    case 'tool_call':
      handleToolCall(msg.name, msg.args);
      break;

    case 'status':
      if (msg.state === 'transcribing') {
        micLabel.textContent = 'Transcribing...';
      } else if (msg.state === 'provider_switched') {
        providerLabel.textContent = msg.provider;
        addSystemMessage(`Switched to ${msg.provider}`);
      }
      break;

    case 'error':
      addSystemMessage(`Error: ${msg.message}`);
      break;
  }
}

// --- Messages UI ---
function addMessage(role, text) {
  if (role === 'assistant') {
    currentAssistantEl = null;
  }

  const el = document.createElement('div');
  el.className = `message ${role}`;
  el.innerHTML = `
    <div class="role">${role}</div>
    <div class="content">${escapeHtml(text)}</div>
  `;
  messagesEl.appendChild(el);
  scrollToBottom();

  if (role === 'assistant') {
    currentAssistantEl = el.querySelector('.content');
  }
}

function appendAssistantToken(text) {
  if (!currentAssistantEl) {
    addMessage('assistant', '');
  }
  currentAssistantEl.textContent += text;
  scrollToBottom();
}

function addSystemMessage(text) {
  const el = document.createElement('div');
  el.className = 'message assistant';
  el.style.opacity = '0.6';
  el.style.fontStyle = 'italic';
  el.innerHTML = `<div class="content">${escapeHtml(text)}</div>`;
  messagesEl.appendChild(el);
  scrollToBottom();
}

function scrollToBottom() {
  const conv = document.getElementById('conversation');
  conv.scrollTop = conv.scrollHeight;
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// --- Mic Capture ---
let isRecording = false;
let micAudioContext = null;
let micInitialized = false;
let micAnalyser = null;
let framesSent = 0;

async function initMic() {
  if (micInitialized) return true;

  try {
    // List all audio input devices so we can pick the right one
    const devices = await navigator.mediaDevices.enumerateDevices();
    const audioInputs = devices.filter(d => d.kind === 'audioinput');
    console.log('Available audio inputs:');
    audioInputs.forEach((d, i) => console.log(`  [${i}] ${d.label} (${d.deviceId.slice(0,8)}...)`));

    // Find a real microphone — skip BlackHole and other virtual devices
    const virtualKeywords = ['blackhole', 'virtual', 'loopback', 'soundflower'];
    let preferredDevice = audioInputs.find(d =>
      !virtualKeywords.some(kw => d.label.toLowerCase().includes(kw))
    );

    const constraints = {
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    };

    // If we found a real mic, specify it explicitly
    if (preferredDevice) {
      constraints.audio.deviceId = { exact: preferredDevice.deviceId };
      console.log(`Selected mic: ${preferredDevice.label}`);
    } else {
      console.warn('No non-virtual mic found, using default');
    }

    console.log('Requesting mic access...');
    micStream = await navigator.mediaDevices.getUserMedia(constraints);
    console.log('Mic stream obtained:', micStream.getAudioTracks()[0].label);
    console.log('Mic track settings:', JSON.stringify(micStream.getAudioTracks()[0].getSettings()));

    micAudioContext = new AudioContext();
    console.log(`AudioContext created: sampleRate=${micAudioContext.sampleRate}, state=${micAudioContext.state}`);

    if (micAudioContext.state === 'suspended') {
      await micAudioContext.resume();
      console.log('AudioContext resumed');
    }

    const source = micAudioContext.createMediaStreamSource(micStream);

    // Analyser for waveform
    micAnalyser = micAudioContext.createAnalyser();
    micAnalyser.fftSize = 2048;
    source.connect(micAnalyser);

    const processor = micAudioContext.createScriptProcessor(4096, 1, 1);
    processor.onaudioprocess = (e) => {
      if (!isRecording || !ws || ws.readyState !== WebSocket.OPEN) return;

      const float32 = e.inputBuffer.getChannelData(0);
      const actualRate = micAudioContext.sampleRate;
      const targetRate = 16000;

      // Check if we're getting actual audio (not silence)
      let maxVal = 0;
      for (let i = 0; i < float32.length; i++) {
        const abs = Math.abs(float32[i]);
        if (abs > maxVal) maxVal = abs;
      }

      let samples;
      if (Math.abs(actualRate - targetRate) < 100) {
        samples = float32;
      } else {
        const ratio = actualRate / targetRate;
        const targetLength = Math.floor(float32.length / ratio);
        samples = new Float32Array(targetLength);
        for (let i = 0; i < targetLength; i++) {
          const srcIdx = i * ratio;
          const idx = Math.floor(srcIdx);
          const frac = srcIdx - idx;
          const s0 = float32[idx] || 0;
          const s1 = float32[Math.min(idx + 1, float32.length - 1)] || 0;
          samples[i] = s0 + frac * (s1 - s0);
        }
      }

      // Convert to 16-bit PCM
      const pcm = new Int16Array(samples.length);
      for (let i = 0; i < samples.length; i++) {
        const s = Math.max(-1, Math.min(1, samples[i]));
        pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
      }

      ws.send(pcm.buffer);
      framesSent++;

      // Log periodically
      if (framesSent % 10 === 1) {
        console.log(`Mic frame #${framesSent}: ${pcm.length} samples, peak=${maxVal.toFixed(4)}, bytes=${pcm.buffer.byteLength}`);
      }
    };

    source.connect(processor);
    processor.connect(micAudioContext.destination);

    micWorklet = { source, processor };
    micInitialized = true;
    console.log('Mic fully initialized');
    return true;
  } catch (err) {
    console.error('Failed to init mic:', err);
    addSystemMessage(`Mic error: ${err.message}`);
    return false;
  }
}

async function startMic() {
  if (isRecording) return;

  const ok = await initMic();
  if (!ok) return;

  isRecording = true;
  framesSent = 0;
  micIndicator.className = 'indicator active';
  micLabel.textContent = 'Listening';
  micBtn.classList.add('listening');
  waveform.setMode('listening');
  waveform.connectAnalyser(micAnalyser);
  console.log('Recording started');
}

function stopMic() {
  if (!isRecording) return;
  isRecording = false;
  console.log(`Recording stopped after ${framesSent} frames`);

  micIndicator.className = 'indicator off';
  micLabel.textContent = 'Mic Off';
  micBtn.classList.remove('listening');
  waveform.setMode('idle');
}

// --- TTS Playback ---
function handleTTSAudio(arrayBuffer) {
  // Accumulate MP3 chunks — we'll decode once we have the complete audio
  ttsChunks.push(new Uint8Array(arrayBuffer));
}

async function playAccumulatedTTS() {
  if (ttsChunks.length === 0) {
    return;
  }

  // Concatenate all chunks into one complete MP3 buffer
  const totalLength = ttsChunks.reduce((sum, c) => sum + c.length, 0);
  const combined = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of ttsChunks) {
    combined.set(chunk, offset);
    offset += chunk.length;
  }
  ttsChunks = [];

  // Queue for sequential playback
  ttsPlaybackQueue.push(combined);
  if (!ttsPlaying) {
    playNextFromQueue();
  }
}

async function playNextFromQueue() {
  if (ttsPlaybackQueue.length === 0) {
    ttsPlaying = false;
    waveform.setMode('idle');
    return;
  }

  ttsPlaying = true;
  waveform.setMode('speaking');
  const combined = ttsPlaybackQueue.shift();

  const ctx = getPlaybackContext();
  if (ctx.state === 'suspended') await ctx.resume();

  try {
    const audioBuffer = await ctx.decodeAudioData(combined.buffer.slice(0));
    const source = ctx.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(ctx.destination);
    currentTTSSource = source;
    source.onended = () => {
      currentTTSSource = null;
      playNextFromQueue(); // play next sentence when this one finishes
    };
    source.start();
    console.log(`Playing TTS: ${audioBuffer.duration.toFixed(1)}s`);
  } catch (err) {
    console.error('Failed to decode TTS audio:', err);
    playNextFromQueue(); // skip to next on error
  }
}

function cancelTTS() {
  ttsChunks = [];
  ttsPlaybackQueue = [];
  if (currentTTSSource) {
    try { currentTTSSource.stop(); } catch (e) {}
    currentTTSSource = null;
  }
  ttsPlaying = false;
  waveform.setMode('idle');
}

// --- Tool Calls ---
function handleToolCall(name, args) {
  switch (name) {
    case 'play_music':
      addSystemMessage(`Playing: ${args.track || 'music'}`);
      break;
    case 'stop_music':
      addSystemMessage('Music stopped');
      break;
    case 'play_sfx':
      addSystemMessage(`SFX: ${args.name || 'unknown'}`);
      break;
    case 'show_text_card':
      showCard(args.title || '', args.body || '');
      break;
  }
}

// --- Event Listeners ---

// Push-to-talk
micBtn.addEventListener('mousedown', () => startMic());
micBtn.addEventListener('mouseup', () => stopMic());
micBtn.addEventListener('mouseleave', () => stopMic());

// Touch support
micBtn.addEventListener('touchstart', (e) => { e.preventDefault(); startMic(); });
micBtn.addEventListener('touchend', (e) => { e.preventDefault(); stopMic(); });

// Text input
sendBtn.addEventListener('click', sendText);
textInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') sendText();
});

function sendText() {
  const text = textInput.value.trim();
  if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;
  ws.send(JSON.stringify({ type: 'text_input', text }));
  addMessage('user', text);
  textInput.value = '';
  currentAssistantEl = null;
}

// --- Init ---
waveform.start();
connect();
