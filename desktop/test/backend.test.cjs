const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const { setInterval, clearInterval } = require('node:timers');
const { createBackendLaunch, startBackend } = require('../dist/backend.js');

test('当启动内置后端时应该固定运行时和隔离资料目录并清除开发凭据', () => {
  const resources = path.join(os.tmpdir(), '桌面 程序');
  const directory = path.join(os.tmpdir(), '桌面 资料');
  const launch = createBackendLaunch(resources, directory, {
    SystemRoot: 'C:\\Windows', PATH: '不应继承', JAVA_TOOL_OPTIONS: '不应继承',
    DEEPSEEK_API_KEY: '虚构密钥', APP_AI_ENABLED: 'true', SPRING_DATASOURCE_URL: '开发数据库'
  });
  assert.equal(launch.file, path.join(resources, 'runtime', 'bin', 'java.exe'));
  assert.ok(launch.args.includes('--app.desktop.data-dir=' + directory));
  assert.ok(launch.args.includes('--app.desktop.managed=true'));
  assert.equal(launch.env.DEEPSEEK_API_KEY, undefined);
  assert.equal(launch.env.APP_AI_ENABLED, undefined);
  assert.equal(launch.env.SPRING_DATASOURCE_URL, undefined);
  assert.equal(launch.env.JAVA_TOOL_OPTIONS, undefined);
  assert.equal(launch.env.SystemRoot, 'C:\\Windows');
});

function options(version, port = 12345) {
  return { version: '1.1.0-beta.1', timeoutMs: 5000, launch: {
    file: process.execPath, args: [path.join(__dirname, 'fixtures/backend-child.cjs'), version, String(port)], env: process.env
  } };
}

test('当子进程完成握手时应该只连接它报告的本机端口', async t => {
  const connection = await startBackend(options('1.1.0-beta.1'));
  t.after(() => connection.child.kill());
  assert.equal(connection.origin, 'http://127.0.0.1:12345');
  assert.ok(connection.child.pid > 0);
  await connection.stop(5000);
  assert.equal(connection.child.exitCode, 0);
});

test('当子进程报告错误版本时应该拒绝加载页面并结束本次子进程', async () => {
  await assert.rejects(startBackend(options('旧版本')), /后端版本与桌面程序不一致/);
});

test('当子进程一直未就绪时应该超时而不是无限等待', async () => {
  await assert.rejects(startBackend({ ...options('silent'), timeoutMs: 1000 }), /后端启动超时/);
});

async function localServer(t, handle, port = 0) {
  const server = http.createServer(handle);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  return server;
}

async function localConnection(t, server) {
  const connection = await startBackend(options('1.1.0-beta.1', server.address().port));
  t.after(() => connection.stop());
  return connection;
}

test('当本次后端使用6679端口时应该以POST凭据正常控制并保留多条Cookie', async t => {
  const requests = [];
  // 先成功绑定才会发请求，端口被占用时不接触其他服务。
  const server = await localServer(t, (request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      requests.push({ method: request.method, path: request.url, token: request.headers['x-otakulog-desktop-token'], cookie: request.headers.cookie,
        contentType: request.headers['content-type'], body: Buffer.concat(chunks).toString() });
      response.setHeader('Set-Cookie', ['JSESSIONID=fixture-session; Path=/; HttpOnly', 'another=fixture; Path=/']);
      response.setHeader('Content-Type', 'application/json');
      response.end(request.url === '/api/anime/export' ? '{"format":"otakulog-backup","version":6,"anime":[]}' : '{"code":200}');
    });
  }, 6679);
  const connection = await localConnection(t, server);
  const response = await connection.control('bootstrap', { fixture: '虚构控制正文' });
  assert.equal(response.status, 200);
  assert.deepEqual(response.headers.getSetCookie(), ['JSESSIONID=fixture-session; Path=/; HttpOnly', 'another=fixture; Path=/']);
  assert.deepEqual(await response.json(), { code: 200 });
  assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].path, '/internal/desktop/bootstrap');
  assert.match(requests[0].token, /^[a-f0-9]{64}$/);
  assert.equal(requests[0].contentType, 'application/json');
  assert.deepEqual(JSON.parse(requests[0].body), { fixture: '虚构控制正文' });
  const { readLocalBackup } = require('../dist/backup-export.js');
  const backup = await readLocalBackup(connection.origin, 'fixture-session');
  assert.equal(JSON.parse(backup.toString()).format, 'otakulog-backup');
  assert.equal(requests[1].method, 'GET');
  assert.equal(requests[1].path, '/api/anime/export');
  assert.equal(requests[1].cookie, 'JSESSIONID=fixture-session');
  assert.equal(requests[1].token, undefined);
});

test('当控制响应重定向时应该拒绝且不向重定向目标发送凭据', async t => {
  const received = [];
  const target = await localServer(t, (request, response) => { received.push(request.url); response.end('{}'); });
  const server = await localServer(t, (_request, response) => {
    response.writeHead(302, { Location: 'http://127.0.0.1:' + target.address().port + '/blocked' });
    response.end();
  });
  const connection = await localConnection(t, server);
  await assert.rejects(connection.control('bootstrap'), /后端控制请求失败：302/);
  assert.deepEqual(received, []);
});

test('当控制响应失败或连接中断时应该拒绝并仍能关闭所属后端', async t => {
  let broken = false;
  const server = await localServer(t, (_request, response) => {
    if (broken) { response.destroy(); return; }
    response.writeHead(503); response.end('虚构故障');
  });
  const connection = await localConnection(t, server);
  await assert.rejects(connection.control('bootstrap'), /后端控制请求失败：503/);
  broken = true;
  await assert.rejects(connection.control('bootstrap'));
  await connection.stop();
  assert.equal(connection.child.exitCode, 0);
});

test('当控制响应持续传输但五秒未完成时应该按总时限中断并关闭连接', { timeout: 12000 }, async t => {
  let socketClosed;
  const closed = new Promise(resolve => { socketClosed = resolve; });
  const server = await localServer(t, (request, response) => {
    if (request.url.endsWith('/shutdown')) { response.end('{}'); return; }
    response.writeHead(200); response.flushHeaders();
    const interval = setInterval(() => response.write(' '), 100);
    response.on('close', () => { clearInterval(interval); socketClosed(); });
  });
  const connection = await localConnection(t, server), started = Date.now();
  await assert.rejects(connection.control('bootstrap'), /后端控制请求超时/);
  await closed;
  const elapsed = Date.now() - started;
  assert.ok(elapsed >= 4500 && elapsed < 8000, '持续来数据也必须在总时限内结束，实际毫秒：' + elapsed);
  await connection.stop();
  assert.equal(connection.child.exitCode, 0);
});
