/**
 * Waveform visualizer and visual effects for VoxHarness.
 */

class WaveformVisualizer {
  constructor(canvasId) {
    this.canvas = document.getElementById(canvasId);
    this.ctx = this.canvas.getContext('2d');
    this.analyser = null;
    this.dataArray = null;
    this.animationId = null;
    this.mode = 'idle'; // 'idle', 'listening', 'speaking'
    this.idlePhase = 0;
  }

  /**
   * Connect to a Web Audio API AnalyserNode for real-time visualization.
   */
  connectAnalyser(analyser) {
    this.analyser = analyser;
    this.dataArray = new Uint8Array(analyser.frequencyBinCount);
  }

  start() {
    if (this.animationId) return;
    this._draw();
  }

  stop() {
    if (this.animationId) {
      cancelAnimationFrame(this.animationId);
      this.animationId = null;
    }
  }

  setMode(mode) {
    this.mode = mode;
  }

  _draw() {
    this.animationId = requestAnimationFrame(() => this._draw());

    const { canvas, ctx } = this;
    const w = canvas.width;
    const h = canvas.height;

    ctx.clearRect(0, 0, w, h);

    if (this.mode === 'idle') {
      this._drawIdle(w, h);
    } else if (this.analyser && this.dataArray) {
      this.analyser.getByteTimeDomainData(this.dataArray);
      this._drawWaveform(w, h);
    } else {
      this._drawIdle(w, h);
    }
  }

  _drawIdle(w, h) {
    // Gentle sine wave animation when idle
    this.idlePhase += 0.02;
    const ctx = this.ctx;
    const midY = h / 2;

    ctx.beginPath();
    ctx.strokeStyle = 'rgba(108, 92, 231, 0.3)';
    ctx.lineWidth = 2;

    for (let x = 0; x < w; x++) {
      const t = (x / w) * Math.PI * 4 + this.idlePhase;
      const y = midY + Math.sin(t) * 5 + Math.sin(t * 0.5 + this.idlePhase * 0.7) * 3;
      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
  }

  _drawWaveform(w, h) {
    const ctx = this.ctx;
    const data = this.dataArray;
    const len = data.length;
    const midY = h / 2;
    const sliceWidth = w / len;

    // Glow effect
    ctx.shadowColor = this.mode === 'listening' ? '#6c5ce7' : '#00e676';
    ctx.shadowBlur = 8;

    ctx.beginPath();
    ctx.strokeStyle = this.mode === 'listening' ? '#6c5ce7' : '#00e676';
    ctx.lineWidth = 2;

    let x = 0;
    for (let i = 0; i < len; i++) {
      const v = data[i] / 128.0;
      const y = (v * midY);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
      x += sliceWidth;
    }
    ctx.stroke();
    ctx.shadowBlur = 0;
  }
}

// Card display helper
function showCard(title, body) {
  const panel = document.getElementById('visuals-panel');
  const display = document.getElementById('card-display');
  display.innerHTML = `<h3>${escapeHtml(title)}</h3><p>${escapeHtml(body)}</p>`;
  panel.classList.remove('hidden');

  // Auto-hide after 10s
  setTimeout(() => {
    panel.classList.add('hidden');
  }, 10000);
}

function hideCard() {
  document.getElementById('visuals-panel').classList.add('hidden');
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
