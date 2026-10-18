const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { setImmediate } = require('node:timers/promises');
const modulePath = path.join(__dirname, '../dist/settings.js');
const api = fs.existsSync(modulePath) ? require(modulePath) : {};

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-settings-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  assert.equal(typeof api.createSettingsStore, 'function', '应提供独立于端口的设置存储');
  return path.join(directory, 'settings.json');
}

test('当首次启动并并发修改偏好与窗口时应该完整保存且重开后不丢字段', async t => {
  const file = fixture(t), store = await api.createSettingsStore(file);
  assert.deepEqual(store.preferences(), { theme: 'system', accent: 'indigo', language: 'zh' });
  const bounds = { x: -1200, y: 20, width: 1000, height: 700, maximized: true };
  await Promise.all([store.updatePreferences({ theme: 'dark' }), store.updateWindow(bounds),
    store.updatePreferences({ language: 'en', accent: 'jade' })]);
  await store.flush();
  const reopened = await api.createSettingsStore(file);
  assert.deepEqual(reopened.preferences(), { theme: 'dark', accent: 'jade', language: 'en' });
  assert.deepEqual(reopened.window(), bounds);
  assert.deepEqual(Object.keys(JSON.parse(fs.readFileSync(file))).sort(), ['preferences', 'version', 'window']);
  assert.deepEqual(fs.readdirSync(path.dirname(file)), ['settings.json']);
});

test('当页面提交未知键秘密或非法偏好时应该拒绝且保留已保存设置', async t => {
  const file = fixture(t), store = await api.createSettingsStore(file);
  await store.updatePreferences({ theme: 'dark' });
  const previous = fs.readFileSync(file);
  for (const patch of [{ apiKey: '虚构密钥' }, { theme: 'purple' }, { language: 'xx' }, { accent: 'red' },
    { window: { width: 999 } }, {}, null, [], { theme: 'dark', secret: '虚构秘密' }]) {
    await assert.rejects(async () => store.updatePreferences(patch));
    assert.deepEqual(fs.readFileSync(file), previous);
  }
});

test('当设置损坏或结构不兼容时应该提示并禁止覆盖原文件', async t => {
  const file = fixture(t);
  for (const value of ['{损坏', '{"version":99}', '{"version":1,"preferences":{"theme":"dark","apiKey":"虚构"}}']) {
    fs.writeFileSync(file, value);
    const store = await api.createSettingsStore(file);
    assert.ok(store.warning());
    assert.equal(store.preferences().theme, 'system');
    await assert.rejects(() => store.updatePreferences({ theme: 'light' }));
    await store.flush();
    assert.equal(fs.readFileSync(file, 'utf8'), value);
  }
});

test('当保存目标不可替换时应该保留原内容并允许后续保存恢复', async t => {
  const file = fixture(t), store = await api.createSettingsStore(file);
  fs.mkdirSync(file); fs.writeFileSync(path.join(file, '原内容.txt'), '保留');
  await assert.rejects(() => store.updatePreferences({ theme: 'dark' }));
  assert.equal(fs.readFileSync(path.join(file, '原内容.txt'), 'utf8'), '保留');
  assert.deepEqual(fs.readdirSync(path.dirname(file)), ['settings.json']);
  fs.unlinkSync(path.join(file, '原内容.txt')); fs.rmdirSync(file);
  await store.updatePreferences({ language: 'en' });
  assert.equal(JSON.parse(fs.readFileSync(file)).preferences.language, 'en');
});

test('当显示器移除或工作区缩小时应该把窗口完整放回可见区域', () => {
  assert.equal(typeof api.restoreWindowBounds, 'function');
  const small = { x: 0, y: 0, width: 800, height: 600 };
  assert.deepEqual(api.restoreWindowBounds({ x: 4000, y: -900, width: 1400, height: 900, maximized: true }, [small]),
    { x: 0, y: 0, width: 800, height: 600 });
  const second = { x: -1600, y: 0, width: 1600, height: 900 };
  const restored = api.restoreWindowBounds({ x: -1500, y: 30, width: 1000, height: 700 }, [small, second]);
  assert.deepEqual(restored, { x: -1500, y: 30, width: 1000, height: 700 });
});

test('当错误来源请求偏好时应该拒绝而且不写设置文件', async t => {
  const file = fixture(t), store = await api.createSettingsStore(file);
  const origin = 'http://127.0.0.1:12345', owner = { isDestroyed: () => false, webContents: { mainFrame: { url: origin + '/' } } };
  const event = { sender: owner.webContents, senderFrame: owner.webContents.mainFrame };
  assert.equal(typeof api.createPreferencesBridge, 'function');
  const bridge = api.createPreferencesBridge({ getWindow: () => owner, getOrigin: () => origin, store });
  for (const bad of [{ ...event, sender: {} }, { ...event, senderFrame: { url: origin + '/' } }]) {
    assert.equal(bridge.read(bad).ok, false);
    assert.equal((await bridge.update(bad, { theme: 'dark' })).ok, false);
  }
  owner.webContents.mainFrame.url = 'file:///startup.html';
  assert.equal(bridge.read(event).ok, false);
  assert.equal((await bridge.update(event, { theme: 'dark' })).ok, false);
  assert.equal(fs.existsSync(file), false);
  owner.webContents.mainFrame.url = origin + '/';
  assert.equal(bridge.read(event, '额外参数').ok, false);
  assert.equal((await bridge.update(event, { theme: 'dark' }, '额外参数')).ok, false);
  assert.equal((await bridge.update(event, { theme: 'dark' })).ok, true);
  assert.deepEqual(Object.keys(bridge.read(event)).sort(), ['ok', 'preferences', 'warning']);
  assert.equal(bridge.read(event).preferences.theme, 'dark');
  await setImmediate();
});
