const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { setImmediate } = require('node:timers/promises');

function startup() {
  const calls = [], app = new EventEmitter();
  let release;
  Object.assign(app, {
    setName() {}, setAppUserModelId() {}, setPath() {}, getAppPath: () => __dirname,
    requestSingleInstanceLock: () => true, whenReady: async () => {}, getVersion: () => '1.1.0-beta.1',
    quit: () => calls.push('quit')
  });
  const profile = {
    clearStorageData(options) { calls.push(['clear', options]); return new Promise(resolve => { release = resolve; }); },
    setPermissionCheckHandler() {}, setPermissionRequestHandler() {}, on() {}
  };
  class Window {
    constructor() { calls.push('window'); this.webContents = { on() {}, setWindowOpenHandler() {} }; }
    removeMenu() {}
    on() {}
    isDestroyed() { return false; }
    async loadFile() { calls.push('startup'); }
  }
  const electron = { app, BrowserWindow: Window, dialog: { showMessageBox: async () => ({ response: 1 }) },
    session: { fromPartition(name) { calls.push(['partition', name]); return profile; } } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../dist/main.js'), 'utf8'), {
    exports: {}, process: { argv: [], env: {} }, Buffer,
    require(name) {
      if (name === 'electron') return electron;
      if (name === 'node:fs') return { mkdirSync() {}, realpathSync: value => value, readFileSync: () => '{"version":"1.1.0-beta.1"}' };
      if (name === 'node:path') return path;
      if (name === './paths') return { resolveDataRoot: () => __dirname };
      if (name === './backend') return { createBackendLaunch() {}, startBackend() { calls.push('backend'); return new Promise(() => {}); } };
      throw new Error('意外模块：' + name);
    }
  });
  return { app, calls, release: () => release() };
}

test('当专用桌面资料启动时应该先仅清理工作线程和缓存再打开窗口与后端', async () => {
  const run = startup();
  await setImmediate();
  assert.ok(run.calls.some(call => Array.isArray(call) && call[0] === 'clear'), '启动应该等待桌面缓存隔离');
  assert.deepEqual(JSON.parse(JSON.stringify(run.calls)), [
    ['partition', 'persist:otakulog'], ['clear', { storages: ['serviceworkers', 'cachestorage'] }]
  ]);
  run.release();
  await setImmediate();
  assert.deepEqual(run.calls.slice(-3), ['window', 'startup', 'backend']);
});

test('当清理缓存期间退出时应该不再创建窗口或启动后端', async () => {
  const run = startup();
  await setImmediate();
  assert.ok(run.calls.some(call => Array.isArray(call) && call[0] === 'clear'), '启动应该等待桌面缓存隔离');
  run.app.emit('before-quit', { preventDefault() {} });
  await setImmediate();
  run.release();
  await setImmediate();
  assert.ok(run.calls.includes('quit'));
  assert.equal(run.calls.includes('window'), false);
  assert.equal(run.calls.includes('backend'), false);
});
