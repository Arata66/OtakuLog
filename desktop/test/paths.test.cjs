const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const { resolveDataRoot } = require('../dist/paths.js');

test('当用户未指定目录时应该把资料放在Windows用户目录而非程序目录', () => {
  const base = path.join(os.tmpdir(), 'Windows 用户');
  assert.equal(resolveDataRoot([], { LOCALAPPDATA: base }), path.join(base, 'OtakuLog'));
});

test('当指定独立资料目录时应该保留中文空格并拒绝相对路径', () => {
  const base = path.join(os.tmpdir(), '隔离 资料');
  assert.equal(resolveDataRoot(['--data-dir=' + base], {}), base);
  assert.throws(() => resolveDataRoot(['--data-dir=relative'], {}), /绝对路径/);
});

test('当配置不完整或包含数据库选项分隔符时应该明确拒绝而不猜测其他目录', () => {
  assert.throws(() => resolveDataRoot([], {}), /LOCALAPPDATA/);
  assert.throws(() => resolveDataRoot(['--data-dir='], { LOCALAPPDATA: os.tmpdir() }), /不能为空/);
  assert.throws(() => resolveDataRoot(['--data-dir=' + path.join(os.tmpdir(), '错误;资料')], {}), /分号/);
});
