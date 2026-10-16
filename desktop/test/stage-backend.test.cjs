const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { stageBackend } = require('../scripts/stage-backend.cjs');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-stage-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const runtime = path.join(root, 'runtime');
  fs.mkdirSync(path.join(runtime, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(runtime, 'legal', 'java.base'), { recursive: true });
  fs.writeFileSync(path.join(runtime, 'bin', 'java.exe'), '虚构运行时');
  fs.writeFileSync(path.join(runtime, 'release'), 'JAVA_VERSION="17.0.20.1"\nIMPLEMENTOR="Eclipse Adoptium"\n');
  fs.writeFileSync(path.join(runtime, 'legal', 'java.base', 'LICENSE'), '虚构许可');
  const jar = path.join(root, 'otakulog.jar');
  fs.writeFileSync(jar, '虚构应用');
  fs.writeFileSync(path.join(root, 'personal.properties'), '不能分发的个人配置');
  return { root, runtime, jar, destination: path.join(root, 'output'), version: '1.1.0-beta.1', commit: 'a'.repeat(40) };
}

test('当准备分发文件时应该只包含应用运行时与版本清单', t => {
  const options = fixture(t);
  stageBackend(options);
  assert.deepEqual(fs.readdirSync(options.destination).sort(), ['backend', 'build-info.json', 'runtime']);
  assert.equal(fs.readFileSync(path.join(options.destination, 'backend', 'otakulog.jar'), 'utf8'), '虚构应用');
  assert.ok(fs.existsSync(path.join(options.destination, 'runtime', 'legal', 'java.base', 'LICENSE')));
  const info = JSON.parse(fs.readFileSync(path.join(options.destination, 'build-info.json'), 'utf8'));
  assert.equal(info.version, options.version);
  assert.equal(info.commit, options.commit);
  assert.match(info.jarSha256, /^[0-9a-f]{64}$/);
});

test('当目标目录已有资料时应该拒绝覆盖并保留原文件', t => {
  const options = fixture(t);
  fs.mkdirSync(options.destination);
  fs.writeFileSync(path.join(options.destination, 'records.json'), '保留');
  assert.throws(() => stageBackend(options), /目标目录必须为空/);
  assert.equal(fs.readFileSync(path.join(options.destination, 'records.json'), 'utf8'), '保留');
});

test('当运行时版本不匹配时应该拒绝生成候选文件', t => {
  const options = fixture(t);
  fs.writeFileSync(path.join(options.runtime, 'release'), 'JAVA_VERSION="23.0.2"\n');
  assert.throws(() => stageBackend(options), /Java 运行时版本/);
  assert.equal(fs.existsSync(options.destination), false);
});

test('当输入目录包含符号链接时应该拒绝意外收集其他资料', t => {
  const options = fixture(t);
  fs.symlinkSync(options.root, path.join(options.runtime, 'outside'), 'junction');
  assert.throws(() => stageBackend(options), /符号链接/);
  assert.equal(fs.existsSync(options.destination), false);
});
