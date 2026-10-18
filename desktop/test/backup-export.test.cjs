const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { setImmediate } = require('node:timers/promises');

const modulePath = path.join(__dirname, '../dist/backup-export.js');
const api = fs.existsSync(modulePath) ? require(modulePath) : {};
const contents = Buffer.from('{\n  "format":"otakulog-backup","version":6,"anime":[{"data":{"name":"虚构备份原文"}}]\n}\n');

async function setup(t, respond = (_request, response) => {
  response.setHeader('Content-Type', 'application/json'); response.end(contents);
}) {
  const requests = [], server = http.createServer((request, response) => { requests.push(request); respond(request, response); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-export-unit-'));
  t.after(async () => {
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const origin = 'http://127.0.0.1:' + server.address().port;
  const owner = { isDestroyed: () => false, webContents: { mainFrame: { url: origin + '/' } } };
  const event = { sender: owner.webContents, senderFrame: owner.webContents.mainFrame };
  const target = path.join(directory, '完整备份.json');
  let choices = 0;
  const options = { getWindow: () => owner, getOrigin: () => origin,
    sessionCookie: async () => 'fixture-session', choosePath: async () => { choices++; return target; } };
  assert.equal(typeof api.createBackupExporter, 'function', '应提供只接受固定备份操作的原生导出');
  return { directory, origin, owner, event, target, requests, options, choices: () => choices };
}

test('当用户确认保存时应该携带专用会话读取固定接口并逐字保存原备份', async t => {
  const run = await setup(t), exporter = api.createBackupExporter(run.options);
  fs.writeFileSync(run.target, '用户确认覆盖的旧备份');
  assert.deepEqual(await exporter.exportBackup(run.event), { status: 'saved' });
  assert.deepEqual(fs.readFileSync(run.target), contents);
  assert.equal(run.requests.length, 1);
  assert.equal(run.requests[0].method, 'GET');
  assert.equal(run.requests[0].url, '/api/anime/export');
  assert.equal(run.requests[0].headers.cookie, 'JSESSIONID=fixture-session');
  assert.equal(run.requests[0].headers['x-otakulog-desktop-token'], undefined);
  assert.equal(exporter.isBusy(), false);
});

test('当用户取消保存时应该不读取接口且不创建文件', async t => {
  const run = await setup(t);
  run.options.choosePath = async () => undefined;
  const exporter = api.createBackupExporter(run.options);
  assert.deepEqual(await exporter.exportBackup(run.event), { status: 'cancelled' });
  assert.deepEqual(run.requests, []); assert.equal(fs.existsSync(run.target), false);
  assert.equal(exporter.isBusy(), false);
});

test('当启动页子框架其他窗口或附加参数调用时应该拒绝且不弹出保存框', async t => {
  const run = await setup(t), exporter = api.createBackupExporter(run.options);
  for (const event of [
    { sender: {}, senderFrame: run.event.senderFrame },
    { sender: run.event.sender, senderFrame: { url: run.origin + '/' } },
    { sender: run.event.sender, senderFrame: null }
  ]) assert.equal((await exporter.exportBackup(event)).status, 'failed');
  for (const url of ['file:///startup.html', 'https://example.com/', run.origin + '/api/anime/export']) {
    run.owner.webContents.mainFrame.url = url;
    assert.equal((await exporter.exportBackup(run.event)).status, 'failed');
  }
  run.owner.webContents.mainFrame.url = run.origin + '/';
  assert.equal((await exporter.exportBackup(run.event, '/任意路径')).status, 'failed');
  assert.equal(run.choices(), 0); assert.deepEqual(run.requests, []);
});

test('当保存框等待时应该阻止重复导出且导航后不再读取或写入备份', async t => {
  const run = await setup(t);
  let finish;
  run.options.choosePath = () => new Promise(resolve => { finish = resolve; });
  const exporter = api.createBackupExporter(run.options), pending = exporter.exportBackup(run.event);
  await setImmediate();
  assert.equal(exporter.isBusy(), true);
  assert.equal((await exporter.exportBackup(run.event)).status, 'failed');
  run.owner.webContents.mainFrame.url = 'https://example.com/'; finish(run.target);
  assert.equal((await pending).status, 'failed');
  assert.deepEqual(run.requests, []); assert.equal(fs.existsSync(run.target), false);
  assert.equal(exporter.isBusy(), false);
});

for (const change of ['导航', '换窗口', '服务故障']) {
  test(`当读取会话期间${change}时应该不再发送备份请求`, async t => {
    const run = await setup(t);
    let finish;
    run.options.sessionCookie = () => new Promise(resolve => { finish = resolve; });
    const exporter = api.createBackupExporter(run.options), pending = exporter.exportBackup(run.event);
    await setImmediate();
    if (change === '导航') run.owner.webContents.mainFrame.url = 'https://example.com/';
    if (change === '换窗口') run.options.getWindow = () => undefined;
    if (change === '服务故障') run.options.getOrigin = () => undefined;
    finish('fixture-session');
    assert.equal((await pending).status, 'failed');
    assert.deepEqual(run.requests, []);
    assert.equal(fs.existsSync(run.target), false);
    assert.equal(exporter.isBusy(), false);
  });
}

for (const status of [302, 401, 503]) {
  test(`当备份接口返回${status}时应该拒绝且不覆盖已有文件或跟随重定向`, async t => {
    const run = await setup(t, (_request, response) => {
      response.writeHead(status, { Location: 'http://127.0.0.1:1/blocked' }); response.end('{}');
    });
    fs.writeFileSync(run.target, '旧备份保留');
    const exporter = api.createBackupExporter(run.options);
    assert.equal((await exporter.exportBackup(run.event)).status, 'failed');
    assert.equal(fs.readFileSync(run.target, 'utf8'), '旧备份保留');
    assert.equal(run.requests.length, 1); assert.equal(exporter.isBusy(), false);
  });
}

test('当会话缺失或服务器未返回备份时应该拒绝并保留原文件', async t => {
  let contentType = 'text/html', body = '虚构错误页';
  const run = await setup(t, (_request, response) => { response.setHeader('Content-Type', contentType); response.end(body); });
  fs.writeFileSync(run.target, '旧备份');
  run.options.sessionCookie = async () => undefined;
  const exporter = api.createBackupExporter(run.options);
  assert.equal((await exporter.exportBackup(run.event)).status, 'failed');
  assert.equal(run.requests.length, 0);
  run.options.sessionCookie = async () => 'fixture-session';
  for (const value of ['虚构错误页', '损坏JSON', '{"code":200}']) {
    body = value;
    assert.equal((await exporter.exportBackup(run.event)).status, 'failed');
    assert.equal(fs.readFileSync(run.target, 'utf8'), '旧备份');
    contentType = 'application/json';
  }
});

test('当目标无法替换时应该返回失败保留目标内容并清理本次临时文件', async t => {
  const run = await setup(t);
  fs.mkdirSync(run.target); fs.writeFileSync(path.join(run.target, '原文件.txt'), '保留');
  const exporter = api.createBackupExporter(run.options);
  assert.equal((await exporter.exportBackup(run.event)).status, 'failed');
  assert.deepEqual(fs.readdirSync(run.directory), ['完整备份.json']);
  assert.equal(fs.readFileSync(path.join(run.target, '原文件.txt'), 'utf8'), '保留');
  assert.equal(exporter.isBusy(), false);
});
