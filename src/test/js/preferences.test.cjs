const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = path.resolve(__dirname, '../../main/resources/static/js/preferences.js');

function setup(desktop, bridge) {
  assert.equal(fs.existsSync(source), true, '应提供统一的页面偏好入口');
  const stored = new Map([['otakulog-theme', 'light'], ['otakulog-lang', 'zh'], ['otakulog-accent', 'indigo']]);
  const messages = [], events = new Map(), attributes = {};
  const context = vm.createContext({ console, Promise,
    localStorage: { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) },
    document: { querySelector: () => ({ content: String(desktop) }), documentElement: { setAttribute: (key, value) => { attributes[key] = value; } } },
    window: { otakulogDesktop: bridge, matchMedia: () => ({ matches: false }),
      addEventListener: (name, callback) => events.set(name, callback) },
    toast: (...args) => messages.push(args) });
  vm.runInContext(fs.readFileSync(source, 'utf8'), context);
  return { preferences: context.window.otakulogPreferences, stored, messages, attributes, events };
}

test('当桌面读取偏好时应该等待主进程并忽略端口内的旧localStorage', async () => {
  let finish;
  const run = setup(true, { getPreferences: () => new Promise(resolve => { finish = resolve; }) });
  finish({ ok: true, preferences: { theme: 'dark', accent: 'jade', language: 'en' } });
  await run.preferences.ready;
  assert.equal(run.preferences.get('theme'), 'dark');
  assert.equal(run.preferences.get('language'), 'en');
  assert.equal(run.attributes['data-theme'], 'dark');
  assert.equal(run.attributes['data-accent'], 'jade');
  assert.equal(run.stored.get('otakulog-theme'), 'light');
});

test('当桌面保存失败时应该提示且在等待期间保护退出而不写localStorage', async () => {
  let finish;
  const run = setup(true, { getPreferences: async () => ({ ok: true, preferences: { theme: 'system', accent: 'indigo', language: 'zh' } }),
    updatePreferences: () => new Promise(resolve => { finish = resolve; }) });
  await run.preferences.ready;
  const saved = run.preferences.set('theme', 'dark');
  let prevented = false;
  run.events.get('beforeunload')({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  finish({ ok: false, message: '虚构保存失败' }); await saved;
  assert.equal(run.preferences.get('theme'), 'dark');
  assert.equal(run.stored.get('otakulog-theme'), 'light');
  assert.deepEqual(run.messages, [['虚构保存失败', 'error']]);
  run.events.get('beforeunload')({ preventDefault() { assert.fail('完成后应解除保护'); } });
});

test('当桌面桥不可用时应该保留默认值并提示而不使用网站旧偏好', async () => {
  const run = setup(true);
  await run.preferences.ready;
  assert.equal(run.preferences.get('theme'), 'system');
  run.preferences.showWarning();
  assert.equal(run.messages.length, 1);
});

test('当网站使用偏好时应该继续读取并保存localStorage且不调用桌面桥', async () => {
  const run = setup(false, { getPreferences() { assert.fail('网站不应调用桌面桥'); } });
  await run.preferences.ready;
  assert.equal(run.preferences.get('theme'), 'light');
  await run.preferences.set('language', 'en');
  assert.equal(run.stored.get('otakulog-lang'), 'en');
  assert.deepEqual(run.messages, []);
});
