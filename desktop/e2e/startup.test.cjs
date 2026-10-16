const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { launchApplication } = require('./helpers/application.cjs');

const desktop = path.resolve(__dirname, '..');

test('当没有系统运行时入口时应该免登录打开独立窗口并在重启后保留隔离资料', { timeout: 240000 }, async t => {
  if (!process.env.OTAKULOG_DESKTOP_EXECUTABLE)
    assert.ok(fs.existsSync(path.join(desktop, 'staging', 'build-info.json')), '请先准备内置后端与运行时分发目录');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-desktop-e2e-'));
  let current;
  t.after(async () => {
    if (current && await current.page.locator('#memoryContent').count())
      await current.page.locator('#memoryContent').fill('').catch(() => {});
    await current?.application.close();
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory);
  assert.equal(await current.page.evaluate(() => typeof require), 'undefined');
  assert.equal(await current.page.evaluate(async () =>
    (await globalThis.navigator.permissions.query({ name: 'geolocation' })).state), 'denied');
  await current.page.locator('#animeName').fill('虚构桌面联动验收');
  await current.page.locator('#totalEpisodes').fill('12');
  await current.page.locator('#animeSeason').fill('2026秋');
  await current.page.locator('#animeScore').fill('8');
  await current.page.locator('#animeStatus').selectOption('planning');
  const [saved] = await Promise.all([
    current.page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add'
      && response.request().method() === 'POST'),
    current.page.locator('#animeForm button[type="submit"]').click()
  ]);
  assert.equal(saved.status(), 200);
  assert.equal((await saved.json()).code, 200);
  await current.page.getByText('虚构桌面联动验收', { exact: true }).first().waitFor();
  fs.mkdirSync(path.join(desktop, 'test-results'), { recursive: true });
  await current.page.screenshot({ path: path.join(desktop, 'test-results', 'first-window.png') });
  await current.application.close();
  current = undefined;
  assert.ok(fs.existsSync(path.join(directory, 'data', 'otakulog.mv.db')));
  current = await launchApplication(directory);
  await current.page.getByText('虚构桌面联动验收', { exact: true }).first().waitFor();
  await current.page.getByRole('button', { name: '写感想', exact: true }).first().click();
  await current.page.locator('#memoryContent').fill('虚构的退出保护草稿');
  await current.application.evaluate(({ app, BrowserWindow, dialog }) => new Promise(resolve => {
    // 仅替代原生对话框的用户选择，页面仍执行实际草稿保护。
    dialog.showMessageBoxSync = () => 0;
    BrowserWindow.getAllWindows()[0].webContents.once('will-prevent-unload', () => resolve());
    app.quit();
  }));
  assert.equal(await current.page.evaluate(async () => (await globalThis.fetch('/')).status), 200);
  assert.equal(await current.page.locator('#memoryContent').inputValue(), '虚构的退出保护草稿');
  const [memorySaved] = await Promise.all([
    current.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/memories')
      && response.request().method() === 'POST'),
    current.page.locator('#memorySave').click()
  ]);
  assert.equal(memorySaved.status(), 200);
  assert.equal((await memorySaved.json()).code, 200);
});
