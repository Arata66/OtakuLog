const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { clearTimeout } = require('node:timers');
const { setImmediate } = require('node:timers/promises');

test('当后端握手失败时应该等本次子进程关闭后才报告可重新启动', async () => {
  const child = new EventEmitter(), exports = {};
  let killed = 0;
  child.exitCode = null; child.signalCode = null;
  child.kill = () => { killed++; return true; };
  child.stdin = new EventEmitter(); child.stdin.write = () => {};
  for (const name of ['stdout', 'stderr']) {
    child[name] = new EventEmitter(); child[name].setEncoding = () => {};
  }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../dist/backend.js'), 'utf8'), {
    exports, Buffer, setTimeout, clearTimeout, process,
    require(name) { return name === 'node:child_process' ? { spawn: () => child } : require(name); }
  });
  let settled = false;
  const pending = exports.startBackend({ version: '当前版本', launch: { file: 'fixture', args: [], env: {} } })
    .catch(error => { settled = true; return error; });
  child.stdout.emit('data', 'OTAKULOG_DESKTOP_READY {"version":"旧版本","port":12345}\n');
  await setImmediate();
  assert.equal(killed, 1);
  assert.equal(settled, false, '进程尚未关闭时不能允许下一次启动');
  child.signalCode = 'SIGTERM'; child.emit('exit', null, 'SIGTERM'); child.emit('close', null, 'SIGTERM');
  assert.match((await pending).message, /后端版本与桌面程序不一致/);
});
