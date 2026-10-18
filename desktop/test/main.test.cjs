const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { setImmediate } = require('node:timers/promises');

function startup(options = {}) {
  const calls = [], app = new EventEmitter();
  let release, ready, loadFailed, finishDialog, finishExport;
  const child = new EventEmitter();
  const connection = { child, origin: 'http://127.0.0.1:12345',
    control: async () => new globalThis.Response(null, { headers: { 'Set-Cookie': 'JSESSIONID=fixture; Path=/' } }),
    stop: async () => { calls.push('stop'); await options.stopped; },
  };
  Object.assign(app, {
    setName() {}, setAppUserModelId() {}, setPath() {}, getAppPath: () => __dirname,
    requestSingleInstanceLock: () => true, whenReady: async () => {}, getVersion: () => '1.1.0-beta.1',
    quit: () => calls.push('quit'), relaunch: () => calls.push('relaunch')
  });
  const profile = {
    clearStorageData(options) { calls.push(['clear', options]); return new Promise(resolve => { release = resolve; }); },
    setPermissionCheckHandler() {}, setPermissionRequestHandler() {}, on() {}, cookies: { async set() {} }
  };
  const windows = [];
  class Window extends EventEmitter {
    constructor() { super(); calls.push('window'); windows.push(this); this.webContents = new EventEmitter(); this.webContents.setWindowOpenHandler = () => {}; }
    removeMenu() {}
    isDestroyed() { return !!this.closed; }
    close() {
      let prevented = false;
      this.emit('close', { preventDefault() { prevented = true; } });
      if (!prevented) { this.closed = true; this.emit('closed'); }
    }
    async loadFile() { calls.push('startup'); }
    async loadURL() { calls.push('loaded'); if (options.failLoad) await new Promise((_resolve, reject) => { loadFailed = reject; }); }
  }
  const electron = { app, BrowserWindow: Window, ipcMain: { handle() {} }, dialog: { showMessageBoxSync: () => options.leave ?? 0,
    showMessageBox: async () => { calls.push('failure'); return new Promise(resolve => { finishDialog = resolve; }); } },
    session: { fromPartition(name) { calls.push(['partition', name]); return profile; } } };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../dist/main.js'), 'utf8'), {
    exports: {}, process: { argv: [], env: {} }, Buffer,
    require(name) {
      if (name === 'electron') return electron;
      if (name === 'node:fs') return { mkdirSync() {}, realpathSync: value => value, readFileSync: () => '{"version":"1.1.0-beta.1"}' };
      if (name === 'node:path') return path;
      if (name === './paths') return { resolveDataRoot: () => __dirname };
      if (name === './backup-export') {
        if (!options.exporting) return require('../dist/backup-export.js');
        return { BACKUP_EXPORT_CHANNEL: 'otakulog:backup-export', createBackupExporter(config) {
          let busy = true;
          finishExport = () => { busy = false; config.onIdle?.(); };
          return { isBusy: () => busy };
        } };
      }
      if (name === './backend') return { createBackendLaunch() {}, startBackend() { calls.push('backend'); return new Promise(resolve => { ready = resolve; }); } };
      throw new Error('意外模块：' + name);
    }
  });
  return { app, calls, child, windows, release: () => release(), ready: () => ready(connection),
    failLoad: () => loadFailed(new Error('虚构页面加载失败')), finishDialog: response => finishDialog({ response }),
    finishExport: () => finishExport() };
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

async function running(options) {
  const run = startup(options);
  await setImmediate(); run.release();
  await setImmediate(); run.ready();
  await setImmediate();
  return run;
}

test('当后端退出又触发页面加载失败时应该只显示一次错误', async () => {
  const run = await running({ failLoad: true });
  run.child.emit('exit', 1);
  run.failLoad();
  await setImmediate();
  assert.equal(run.calls.filter(call => call === 'failure').length, 1);
  run.finishDialog(1);
});

test('当页面渲染进程崩溃时应该给出错误和重新打开入口', async () => {
  const run = await running();
  run.windows[0].webContents.emit('render-process-gone', {}, { reason: 'crashed' });
  await setImmediate();
  assert.equal(run.calls.filter(call => call === 'failure').length, 1);
  run.finishDialog(1);
});

test('当故障后选择重启时应该等待旧后端完全退出再安排重启', async () => {
  let finishStop;
  const run = await running({ stopped: new Promise(resolve => { finishStop = resolve; }) });
  run.child.emit('exit', 1); run.finishDialog(0);
  await setImmediate();
  assert.equal(run.calls.includes('relaunch'), false, '旧后端未停止时不应安排重启');
  run.app.emit('before-quit', { preventDefault() {} });
  run.app.emit('before-quit', { preventDefault() {} });
  await setImmediate();
  assert.equal(run.calls.includes('stop'), true);
  assert.equal(run.calls.includes('relaunch'), false);
  finishStop();
  await setImmediate();
  assert.ok(run.calls.indexOf('relaunch') > run.calls.indexOf('stop'));
  assert.equal(run.calls.filter(call => call === 'relaunch').length, 1);
});

test('当重启遇到草稿且选择继续编辑时应该取消这次重启安排', async () => {
  const run = await running();
  run.child.emit('exit', 1); run.finishDialog(0);
  await setImmediate();
  run.windows[0].webContents.emit('will-prevent-unload', { preventDefault() { assert.fail('应该保留页面'); } });
  run.app.emit('before-quit', { preventDefault() {} });
  run.app.emit('before-quit', { preventDefault() {} });
  await setImmediate();
  assert.equal(run.calls.includes('stop'), true);
  assert.equal(run.calls.includes('relaunch'), false, '后续正常退出不能执行已取消的重启');
});

test('当后端尚未就绪就关闭窗口时应该收回本次启动而不加载业务页面', async () => {
  const run = startup();
  await setImmediate(); run.release();
  await setImmediate(); run.windows[0].close();
  run.app.emit('before-quit', { preventDefault() {} });
  run.ready();
  await setImmediate();
  assert.equal(run.calls.includes('stop'), true);
  assert.equal(run.calls.includes('loaded'), false);
  assert.equal(run.calls.includes('failure'), false);
});

for (const response of [0, 1]) {
  test(`当导出期间故障并确认${response === 0 ? '重启' : '退出'}时应该等导出结束后继续退出`, async () => {
    const run = await running({ exporting: true });
    run.windows[0].webContents.emit('render-process-gone'); run.finishDialog(response);
    await setImmediate();
    run.app.emit('before-quit', { preventDefault() {} });
    assert.equal(run.windows[0].isDestroyed(), false);
    const attempts = run.calls.filter(call => call === 'quit').length;
    run.finishExport();
    assert.equal(run.calls.filter(call => call === 'quit').length, attempts + 1);
    run.app.emit('before-quit', { preventDefault() {} });
    run.app.emit('before-quit', { preventDefault() {} });
    await setImmediate();
    assert.equal(run.calls.includes('stop'), true);
    assert.equal(run.calls.includes('relaunch'), response === 0);
  });
}

test('当普通关闭被导出阻止时应该在导出结束后保留正常窗口', async () => {
  const run = await running({ exporting: true });
  run.windows[0].close();
  assert.equal(run.windows[0].isDestroyed(), false);
  run.finishExport();
  assert.equal(run.calls.includes('quit'), false);
});
