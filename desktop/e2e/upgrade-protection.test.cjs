const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { expect } = require('@playwright/test');
const { launchApplication, openAddForm } = require('./helpers/application.cjs');

test('当资料引擎版本不匹配时应该在开库前拒绝并显示恢复位置且原记录仍能重开', { timeout: 120000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-upgrade-protection-'));
  let current, succeeded = false;
  t.after(async () => {
    await current?.application.evaluate(({ app, dialog }) => {
      app.__finishUpgradeFault?.({ response: 1 });
      dialog.showMessageBoxSync = () => 1;
    }).catch(() => {});
    await current?.application.close().catch(() => {});
    if (!succeeded) { console.error('升级拒启证据保留：' + directory); return; }
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.match(path.basename(directory), /^otakulog-upgrade-protection-/);
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  const offlineCalendar = async (_application, page) => {
    await page.route('**/api/watch/airing', route => route.fulfill({ status: 503,
      contentType: 'application/json', body: '{"code":503,"message":"虚构离线放送服务"}' }));
  };
  current = await launchApplication(directory, undefined, { configure: offlineCalendar });
  await openAddForm(current.page);
  await current.page.locator('#animeName').fill('虚构引擎保护记录');
  await current.page.locator('#totalEpisodes').fill('12');
  await current.page.locator('#animeSeason').fill('2026秋');
  await current.page.locator('#animeScore').fill('8');
  await current.page.locator('#animeStatus').selectOption('planning');
  const [saved] = await Promise.all([
    current.page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add'
      && response.request().method() === 'POST'),
    current.page.locator('#animeForm button[type="submit"]').click()
  ]);
  assert.equal((await saved.json()).code, 200);
  await current.application.close(); current = undefined;
  const marker = path.join(directory, 'data', 'desktop-engine.json');
  assert.equal(fs.existsSync(marker), true, '正常初始化应保存资料引擎标记');
  const originalMarker = fs.readFileSync(marker);
  fs.writeFileSync(marker, JSON.stringify({ ...JSON.parse(originalMarker), version: 'future-fixture' }));
  const database = path.join(directory, 'data', 'otakulog.mv.db');
  const digest = () => createHash('sha256').update(fs.readFileSync(database)).digest('hex');
  const before = digest();
  current = await launchApplication(directory, undefined, { waitForReady: false,
    configure: async application => application.evaluate(({ app, dialog }) => {
      app.__upgradeFaults = [];
      dialog.showMessageBox = options => {
        app.__upgradeFaults.push(options);
        return new Promise(resolve => { app.__finishUpgradeFault = resolve; });
      };
    })
  });
  await expect.poll(() => current.application.evaluate(({ app }) => app.__upgradeFaults.length), { timeout: 30000 }).toBe(1);
  const fault = await current.application.evaluate(({ app }) => app.__upgradeFaults[0]);
  assert.match(fault.message, /引擎版本/);
  assert.ok(fault.message.includes(path.join(directory, 'data')));
  assert.ok(fault.message.includes(path.join(directory, 'backups')));
  assert.match(current.page.url(), /^file:/, '拒绝打开时不能进入可编辑业务页面');
  assert.equal(digest(), before, '引擎不匹配时不能打开或重写数据库');
  const closed = new Promise(resolve => current.application.once('close', resolve));
  await current.application.evaluate(({ app }) => { setTimeout(() => app.__finishUpgradeFault({ response: 1 }), 30); });
  await closed; current = undefined;
  fs.writeFileSync(marker, originalMarker);
  current = await launchApplication(directory, undefined, { configure: offlineCalendar });
  const backup = await current.page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).json());
  assert.deepEqual(backup.anime.map(anime => anime.data.name), ['虚构引擎保护记录']);
  succeeded = true;
});
