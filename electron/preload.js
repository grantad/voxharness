const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('voxharness', {
  wsUrl: 'ws://127.0.0.1:8765',
});
