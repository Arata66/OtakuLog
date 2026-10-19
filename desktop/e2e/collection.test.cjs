const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { expect } = require('@playwright/test');
const { launchApplication, openAddForm, selectDesktopPage, selectTableView } = require('./helpers/application.cjs');

test('当桌面使用收藏馆时应该保留真实封面地址并通过原入口保存进度与感想', { timeout: 180000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-collection-'));
  const errors = [], aiRequests = [];
  const cover = 'https://covers.example.invalid/approved-fixture.png';
  let current, succeeded = false;
  t.after(async () => {
    await current?.application.evaluate(({ dialog }) => { dialog.showMessageBoxSync = () => 1; }).catch(() => {});
    await current?.application.close().catch(() => {});
    if (!succeeded) { console.error('收藏馆验收证据保留：' + directory); return; }
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.match(path.basename(directory), /^otakulog-collection-/);
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory, undefined, { configure: async (_application, page) => {
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/watch/airing', route => route.fulfill({ status: 503,
      contentType: 'application/json', body: '{"code":503,"message":"虚构离线放送服务"}' }));
    await page.route('https://covers.example.invalid/**', route => route.request().url() === cover
      ? route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1cAAAAASUVORK5CYII=', 'base64') })
      : route.abort());
    page.on('request', request => { if (request.url().includes('/insights/ai/')) aiRequests.push(request.url()); });
  } });
  const { page, application } = current;
  await expect(page.locator('#desktopCollectionNav')).toBeVisible({ timeout: 4000 });
  await expect(page.locator('#viewGallery')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('#animeForm')).toHaveCount(1);
  await expect(page.locator('#animeName')).not.toBeVisible();

  await selectDesktopPage(page, 'insights');
  await expect(page.locator('#tab-insights #tasteProfile')).toBeVisible();
  await selectDesktopPage(page, 'charts');
  await expect(page.locator('#tab-charts .overview')).toBeVisible();
  await selectDesktopPage(page, 'settings');
  await expect(page.locator('#tab-settings #themeToggle')).toBeVisible();
  await expect(page.locator('#tab-settings #desktopSettingsBtn')).toBeVisible();

  await openAddForm(page);
  await page.locator('#animeName').fill('虚构未保存草稿');
  page.once('dialog', dialog => dialog.dismiss());
  await page.locator('#animeName').press('Escape');
  await expect(page.locator('#desktopAddDialog')).toBeVisible();
  await expect(page.locator('#animeName')).toHaveValue('虚构未保存草稿');
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#desktopAddClose').click();
  await expect(page.locator('#desktopAddDialog')).not.toBeVisible();
  await expect(page.locator('#desktopAddButton')).toBeFocused();

  async function add(name, image) {
    await openAddForm(page);
    await page.locator('#animeName').fill(name);
    await page.locator('#totalEpisodes').fill('12');
    await page.locator('#animeSeason').fill('2026秋');
    await page.locator('#animeScore').fill('8');
    await page.locator('#animeStatus').selectOption('watching');
    await page.locator('#animeCover').fill(image);
    const [response] = await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add' && response.request().method() === 'POST'),
      page.locator('#animeForm button[type="submit"]').click()
    ]);
    const result = await response.json();
    assert.equal(result.code, 200);
    await expect(page.locator('#desktopAddDialog')).not.toBeVisible();
    await expect(page.locator('#gc-' + result.data.id)).toBeVisible();
    return result.data;
  }
  const withCover = await add('虚构指定封面作品', cover);
  const withoutCover = await add('虚构暂无封面作品', '');
  const brokenCover = await add('虚构封面加载失败作品', 'https://covers.example.invalid/missing.png');
  const card = page.locator('#gc-' + withCover.id);
  await expect(card.locator('.g-cover')).toHaveAttribute('src', cover);
  await expect.poll(() => card.locator('.g-cover').evaluate(image => image.complete && image.naturalWidth > 0)).toBe(true);
  await expect(page.locator('#gc-' + withoutCover.id + ' .g-cover-empty')).toBeVisible();
  await expect(page.locator('#gc-' + withoutCover.id + ' img')).toHaveCount(0);
  await expect(page.locator('#gc-' + brokenCover.id + ' .g-cover-empty')).toBeVisible();

  const [advanced] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/' + withCover.id + '/next-episode'),
    card.getByRole('button', { name: '下一集', exact: true }).click()
  ]);
  assert.equal((await advanced.json()).data.currentEpisode, 2);
  await card.locator('.g-name').click();
  await expect(page.locator('#detailModal')).toBeVisible();
  await page.locator('#memoryContent').fill('虚构收藏馆验收感想：愿意继续看角色的成长。');
  await page.locator('#memorySave').click();
  await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
  await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();

  await selectTableView(page);
  await expect(page.locator('.table-card tbody tr').filter({ hasText: withCover.name }).locator('.ep')).toHaveText('2 / 12');
  await page.locator('#viewGallery').click();
  await expect(card).toBeVisible();
  for (const width of [1280, 760]) {
    await application.evaluate(({ BrowserWindow }, value) => BrowserWindow.getAllWindows()[0].setContentSize(value, 900), width);
    await expect.poll(() => page.evaluate(() => globalThis.document.documentElement.scrollWidth <= globalThis.innerWidth + 1)).toBe(true);
  }
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(1280, 900));
  const screenshots = path.resolve(__dirname, '../test-results');
  fs.mkdirSync(screenshots, { recursive: true });
  await selectDesktopPage(page, 'settings');
  if (await page.locator('html').getAttribute('data-theme') === 'dark') await page.locator('#themeToggle').click();
  await selectDesktopPage(page, 'list');
  await page.evaluate(() => globalThis.scrollTo(0, 0));
  await page.screenshot({ path: path.join(screenshots, 'collection-light.png'), animations: 'disabled' });
  await selectDesktopPage(page, 'settings');
  if (await page.locator('html').getAttribute('data-theme') !== 'dark') await page.locator('#themeToggle').click();
  await selectDesktopPage(page, 'list');
  await page.evaluate(() => globalThis.scrollTo(0, 0));
  await page.screenshot({ path: path.join(screenshots, 'collection-dark.png'), animations: 'disabled' });
  const backup = await page.evaluate(async () => (await (await globalThis.fetch('/api/anime/export')).json()));
  assert.equal(backup.anime.find(entry => entry.data.name === withCover.name).data.coverUrl, cover);
  assert.equal(backup.anime.find(entry => entry.data.name === withoutCover.name).data.coverUrl, null);
  assert.equal(backup.anime.find(entry => entry.data.name === brokenCover.name).data.coverUrl, 'https://covers.example.invalid/missing.png');
  assert.equal(backup.memories.length, 1);
  assert.deepEqual(aiRequests, []);
  assert.deepEqual(errors, []);
  succeeded = true;
});

test('当添加窗口内以图搜番成功时应该能操作结果并保留或明确替换原草稿', { timeout: 90000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-collection-trace-'));
  let current;
  t.after(async () => {
    await current?.application.evaluate(({ dialog }) => { dialog.showMessageBoxSync = () => 1; }).catch(() => {});
    await current?.application.close().catch(() => {});
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.match(path.basename(directory), /^otakulog-collection-trace-/);
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory, undefined, { configure: async (_application, page) => {
    await page.route('**/api/watch/airing', route => route.fulfill({ status: 503, json: { code: 503 } }));
    await page.route('**/api/tracemoe/search', route => route.fulfill({ json: { code: 200,
      data: { allResults: [{ animeName: '虚构图片识别作品', episode: 1, confidence: 99 }] } } }));
    await page.route('**/api/bangumi/search?**', route => route.fulfill({ status: 503,
      json: { code: 503, message: '虚构搜索失败：手工资料仍可编辑' } }));
  } });
  const { page } = current;
  await openAddForm(page);
  await page.locator('#animeName').fill('虚构原有草稿');
  const image = { name: '虚构截图.png', mimeType: 'image/png',
    buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1cAAAAASUVORK5CYII=', 'base64') };
  await page.locator('#traceMoeInput').setInputFiles(image);
  const trace = page.locator('#traceMoeModal');
  await expect(trace).toBeVisible();
  page.once('dialog', dialog => dialog.dismiss());
  await trace.getByRole('button', { name: '添加追踪', exact: true }).click({ timeout: 4000 });
  await expect(page.locator('#animeName')).toHaveValue('虚构原有草稿');
  await expect(page.locator('#desktopAddDialog')).toBeVisible();
  await page.locator('#traceMoeInput').setInputFiles(image);
  await expect(trace).toBeVisible();
  await trace.getByRole('button', { name: '添加追踪', exact: true }).press('Escape');
  await expect(trace).toHaveCount(0);
  await expect(page.locator('#desktopAddDialog')).toBeVisible();
  await expect(page.locator('#animeName')).toHaveValue('虚构原有草稿');
  await page.locator('#traceMoeInput').setInputFiles(image);
  page.once('dialog', dialog => dialog.accept());
  await trace.getByRole('button', { name: '添加追踪', exact: true }).click();
  await expect(page.locator('#animeName')).toHaveValue('虚构图片识别作品');
  const error = page.getByRole('alert').filter({ hasText: '虚构搜索失败：手工资料仍可编辑' });
  await expect(error).toBeVisible();
  assert.equal(await error.evaluate(element => element.closest('dialog')?.id), 'desktopAddDialog');
  assert.equal(await page.evaluate(async () => (await (await globalThis.fetch('/api/anime/export')).json()).anime.length), 0);
  page.once('dialog', dialog => dialog.accept());
  await page.locator('#desktopAddClose').click();
});
