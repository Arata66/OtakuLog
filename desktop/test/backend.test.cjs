const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
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

function options(version) {
  return { version: '1.1.0-beta.1', timeoutMs: 5000, launch: {
    file: process.execPath, args: [path.join(__dirname, 'fixtures/backend-child.cjs'), version], env: process.env
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
