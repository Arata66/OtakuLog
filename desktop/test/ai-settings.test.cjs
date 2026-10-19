const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
let api = {};
try { api = require('../dist/ai-settings.js'); } catch (error) { if (error.code !== 'MODULE_NOT_FOUND') throw error; }

function encryption(available = true) {
  const key = crypto.randomBytes(32);
  return { isEncryptionAvailable: () => available,
    encryptString(value) {
      const iv = crypto.randomBytes(12), cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
      const data = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
      return Buffer.concat([iv, cipher.getAuthTag(), data]);
    },
    decryptString(value) {
      const cipher = crypto.createDecipheriv('aes-256-gcm', key, value.subarray(0, 12));
      cipher.setAuthTag(value.subarray(12, 28));
      return Buffer.concat([cipher.update(value.subarray(28)), cipher.final()]).toString('utf8');
    }
  };
}
async function fixture(t, crypt = encryption()) {
  assert.equal(typeof api.createAiSettingsStore, 'function', '应提供主进程的加密设置存储');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'otakulog-ai-settings-'));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'ai.json');
  return { directory, file, crypt, store: await api.createAiSettingsStore(file, crypt) };
}
function patch(apiKey, enabled = true, dailyLimit = 20) {
  return { keyAction: 'replace', apiKey, enabled, dailyLimit };
}
function context(store, control = async () => new globalThis.Response('{}')) {
  const origin = 'http://127.0.0.1:12345';
  const owner = { isDestroyed: () => false, webContents: { mainFrame: { url: origin + '/' } } };
  const event = { sender: owner.webContents, senderFrame: owner.webContents.mainFrame };
  let failed = false;
  const bridge = api.createAiSettingsBridge({ store, getWindow: () => owner, getOrigin: () => origin,
    control, onSyncFailure: async () => { failed = true; } });
  return { bridge, event, owner, failed: () => failed };
}

test('当首次打开时应该默认关闭AI并且不创建秘密文件', async t => {
  const { store, file } = await fixture(t);
  assert.deepEqual(store.read(), { enabled: false, hasKey: false, dailyLimit: 20, storage: 'ready' });
  await assert.rejects(fs.access(file), { code: 'ENOENT' });
});

test('当保存自己的Key并重开时应该只持久保存密文且状态不回传Key', async t => {
  const { store, file, crypt } = await fixture(t), secret = 'fictional-key-' + crypto.randomUUID();
  await store.commit(store.prepare(patch(secret, true, 3)));
  const bytes = await fs.readFile(file, 'utf8');
  assert.equal(bytes.includes(secret), false);
  assert.equal(JSON.stringify(store.read()).includes(secret), false);
  const reopened = await api.createAiSettingsStore(file, crypt);
  assert.deepEqual(reopened.read(), { enabled: true, hasKey: true, dailyLimit: 3, storage: 'ready' });
  assert.equal(reopened.configuration().apiKey, secret);
});

test('当Key无法解密时应该关闭AI并保留原文件直至明确替换或清除', async t => {
  const { store, file } = await fixture(t);
  await store.commit(store.prepare(patch('fictional-only')));
  const original = await fs.readFile(file);
  const reopened = await api.createAiSettingsStore(file, encryption());
  assert.equal(reopened.read().enabled, false);
  assert.equal(reopened.read().storage, 'unreadable');
  assert.throws(() => reopened.prepare({ enabled: false, dailyLimit: 7, keyAction: 'keep' }));
  assert.deepEqual(await fs.readFile(file), original);
  await reopened.commit(reopened.prepare({ enabled: false, dailyLimit: 7, keyAction: 'clear' }));
  assert.equal(JSON.parse(await fs.readFile(file, 'utf8')).encryptedKey, null);
  assert.equal(reopened.read().hasKey, false);
});

test('当系统加密不可用时应该拒绝明文保存但允许明确清除', async t => {
  const { store, file } = await fixture(t, encryption(false));
  assert.equal(store.read().storage, 'unavailable');
  assert.throws(() => store.prepare(patch('fictional-only')));
  await assert.rejects(fs.access(file), { code: 'ENOENT' });
  await store.commit(store.prepare({ enabled: false, dailyLimit: 0, keyAction: 'clear' }));
  assert.equal(store.read().hasKey, false);
});

test('当保存参数越权或Key包含非法字符时应该拒绝且不改变原配置', async t => {
  const { store, file } = await fixture(t);
  for (const value of [patch('x\r\nAuthorization: secret'), patch('x'.repeat(513)),
    patch(''), { ...patch('fake'), endpoint: 'http://elsewhere' }, { ...patch('fake'), dailyLimit: -1 },
    { ...patch('fake'), dailyLimit: 0.5 }, { enabled: true, dailyLimit: 2, keyAction: 'keep' }]) {
    assert.throws(() => store.prepare(value));
  }
  await assert.rejects(fs.access(file), { code: 'ENOENT' });
});

test('当页面保存配置时应该先暂停新调用再写密文并提交后端且不调用供应商', async t => {
  const { store, file } = await fixture(t), calls = [];
  const ctx = context(store, async (operation, body) => {
    calls.push(operation);
    if (operation === 'ai-settings-lock') await assert.rejects(fs.access(file), { code: 'ENOENT' });
    if (operation === 'ai-settings') {
      assert.equal(body.apiKey, 'fictional-only');
      assert.equal((await fs.readFile(file, 'utf8')).includes(body.apiKey), false);
    }
    return new globalThis.Response('{}');
  });
  const result = await ctx.bridge.update(ctx.event, patch('fictional-only'));
  assert.equal(result.ok, true);
  assert.deepEqual(calls, ['ai-settings-lock', 'ai-settings']);
  assert.equal(JSON.stringify(result).includes('fictional-only'), false);
});

test('当AI调用进行中拒绝配置锁时应该不写文件并保留旧Key', async t => {
  const { store, file } = await fixture(t);
  await store.commit(store.prepare(patch('old-fictional')));
  const original = await fs.readFile(file), ctx = context(store, async () => { throw new (require('../dist/backend.js').BackendControlError)(409); });
  const result = await ctx.bridge.update(ctx.event, patch('new-fictional'));
  assert.equal(result.ok, false);
  assert.match(result.message, /进行中|稍后/);
  assert.deepEqual(await fs.readFile(file), original);
  assert.equal(ctx.failed(), false);
});

test('当落盘后后端同步失败时应该恢复旧密文并停止不确定配置继续调用', async t => {
  const { store, file } = await fixture(t);
  await store.commit(store.prepare(patch('old-fictional')));
  const original = await fs.readFile(file);
  const ctx = context(store, async operation => {
    if (operation === 'ai-settings') throw new Error('secret-provider-body');
    return new globalThis.Response('{}');
  });
  const result = await ctx.bridge.update(ctx.event, patch('new-fictional'));
  assert.equal(result.ok, false);
  assert.equal(JSON.stringify(result).includes('secret-provider-body'), false);
  assert.deepEqual(await fs.readFile(file), original);
  assert.equal(ctx.failed(), true);
});

test('当磁盘不能替换配置时应该解除设置锁并保持原后端配置', async t => {
  const { store, file, directory } = await fixture(t), calls = [];
  await fs.mkdir(file);
  await fs.writeFile(path.join(file, '保留.txt'), '原资料');
  const ctx = context(store, async operation => { calls.push(operation); return new globalThis.Response('{}'); });
  const result = await ctx.bridge.update(ctx.event, patch('fictional-only'));
  assert.equal(result.ok, false);
  assert.deepEqual(calls, ['ai-settings-lock', 'ai-settings-cancel']);
  assert.equal(store.read().enabled, false);
  assert.equal(await fs.readFile(path.join(file, '保留.txt'), 'utf8'), '原资料');
  assert.deepEqual(await fs.readdir(directory), ['ai.json']);
  assert.equal(ctx.failed(), false);
});

test('当非主窗口或其他页面访问设置时应该拒绝读写与额外参数', async t => {
  const { store, file } = await fixture(t), ctx = context(store);
  for (const event of [{ ...ctx.event, sender: {} }, { ...ctx.event, senderFrame: { url: 'http://127.0.0.1:12345/' } }]) {
    assert.equal(ctx.bridge.read(event).ok, false);
    assert.equal((await ctx.bridge.update(event, patch('fake'))).ok, false);
  }
  assert.equal(ctx.bridge.read(ctx.event, 'extra').ok, false);
  assert.equal((await ctx.bridge.update(ctx.event, patch('fake'), 'extra')).ok, false);
  ctx.owner.webContents.mainFrame.url += 'another-page';
  assert.equal(ctx.bridge.read(ctx.event).ok, false);
  await assert.rejects(fs.access(file), { code: 'ENOENT' });
});
