const { app, BrowserWindow, ipcMain, systemPreferences } = require('electron');
const { spawn } = require('child_process');
const path = require('path');

let mainWindow;
let pythonProcess;
const WS_PORT = 8765;

function startBackend() {
  return new Promise((resolve, reject) => {
    const backendDir = path.join(__dirname, '..', 'backend');
    // Use the venv Python to ensure Python 3.12+
    const venvPython = path.join(backendDir, '.venv', 'bin', 'python');
    pythonProcess = spawn(venvPython, ['main.py'], {
      cwd: backendDir,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    pythonProcess.stderr.on('data', (data) => {
      console.log(`[backend] ${data.toString().trim()}`);
    });

    pythonProcess.stdout.on('data', (data) => {
      const line = data.toString().trim();
      console.log(`[backend] ${line}`);
      if (line.startsWith('READY')) {
        resolve();
      }
    });

    pythonProcess.on('error', (err) => {
      console.error('Failed to start backend:', err);
      reject(err);
    });

    pythonProcess.on('exit', (code) => {
      console.log(`Backend exited with code ${code}`);
    });

    // Timeout after 60s (model loading can be slow first time)
    setTimeout(() => reject(new Error('Backend startup timeout')), 60000);
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 900,
    height: 700,
    title: 'VoxHarness',
    backgroundColor: '#0a0a0f',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

app.whenReady().then(async () => {
  // Request microphone permission on macOS
  if (process.platform === 'darwin') {
    const micStatus = systemPreferences.getMediaAccessStatus('microphone');
    console.log(`Mic permission status: ${micStatus}`);
    if (micStatus !== 'granted') {
      const granted = await systemPreferences.askForMediaAccess('microphone');
      console.log(`Mic permission granted: ${granted}`);
    }
  }

  try {
    console.log('Starting backend...');
    await startBackend();
    console.log('Backend ready, creating window...');
    createWindow();
  } catch (err) {
    console.error('Startup failed:', err);
    createWindow();
  }
});

app.on('window-all-closed', () => {
  if (pythonProcess) {
    pythonProcess.kill();
  }
  app.quit();
});

app.on('before-quit', () => {
  if (pythonProcess) {
    pythonProcess.kill();
  }
});
