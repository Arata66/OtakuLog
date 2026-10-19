const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { expect } = require('@playwright/test');
const { launchApplication, openAddForm, selectDesktopPage, selectTableView } = require('./helpers/application.cjs');

async function readBackup(page) {
  const result = await page.evaluate(async () => {
    const response = await globalThis.fetch('/api/anime/export');
    return { status: response.status, body: await response.json() };
  });
  assert.equal(result.status, 200);
  return result.body;
}

function businessData(backup) {
  const data = { ...backup };
  delete data.exportedAt;
  return data;
}

async function saveByUi(page, pathname, action) {
  const [response] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === pathname && response.request().method() === 'POST'),
    action()
  ]);
  assert.equal(response.status(), 200);
  const result = await response.json();
  assert.equal(result.code, 200);
  return result.data;
}

async function fillAnime(page, name, status, score) {
  await openAddForm(page);
  await page.locator('#animeName').fill(name);
  await page.locator('#totalEpisodes').fill('12');
  await page.locator('#animeSeason').fill('2026秋');
  await page.locator('#animeScore').fill(score);
  await page.locator('#animeStatus').selectOption(status);
}

test('当渲染器模拟断网且没有Key时应该完成本地业务并跨资料目录恢复及重开保留', { timeout: 240000 }, async () => {
  const directories = [], remote = [], errors = [], onlineRequests = [], aiRequests = [], scripts = [];
  let current;
  const launch = async directory => {
    current = await launchApplication(directory, undefined, { configure: async (_application, page) => {
      page.on('pageerror', error => errors.push(error.message));
      // 仅模拟渲染器断网，不代表 Java 出站或整台 Windows 已断网。
      await page.context().route('**/*', async route => {
        const request = route.request(), url = new URL(request.url());
        if (['http:', 'https:'].includes(url.protocol) && !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
          remote.push({ url: url.href, type: request.resourceType() });
          return route.abort();
        }
        // 首页日历也会触发外部 HTTP，须在渲染器边界截断，避免实际访问上游。
        if (url.pathname.startsWith('/api/bangumi/') || url.pathname.startsWith('/api/tracemoe/')
          || ['/api/watch/airing', '/api/anime/airing-schedule'].includes(url.pathname)) {
          onlineRequests.push(url.pathname);
          const message = url.pathname === '/api/bangumi/search' ? '模拟离线：Bangumi搜索暂不可用' : '模拟离线：放送服务暂不可用';
          return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message }) });
        }
        if (url.pathname.includes('/insights/ai/')) {
          aiRequests.push(url.pathname);
          return route.abort();
        }
        if (request.resourceType() === 'script') scripts.push(url.pathname);
        return route.continue();
      });
    } });
    return current;
  };
  const close = async () => { await current.application.close(); current = undefined; };
  try {
    const sourceDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-offline-source-')); directories.push(sourceDirectory);
    let { page, application } = await launch(sourceDirectory);
    const settings = await page.evaluate(() => globalThis.otakulogDesktop.getAiSettings());
    assert.equal(settings.ok, true);
    assert.equal(settings.settings.enabled, false);
    assert.equal(settings.settings.hasKey, false);
    assert.equal((await readBackup(page)).anime.length, 0);
    assert.deepEqual(await page.evaluate(async () => ({ workers: (await globalThis.navigator.serviceWorker.getRegistrations()).length,
      controlled: Boolean(globalThis.navigator.serviceWorker.controller), caches: await globalThis.caches.keys() })), { workers: 0, controlled: false, caches: [] });

    await fillAnime(page, '虚构离线航行', 'watching', '8');
    await page.locator('#animeTags').fill('虚构奇幻,日常');
    const created = await saveByUi(page, '/api/anime/add', () => page.locator('#animeForm button[type="submit"]').click());
    await selectTableView(page);
    const row = page.locator('.table-card tbody tr').filter({ hasText: '虚构离线航行' });
    await expect(row.locator('.ep')).toHaveText('1 / 12');
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    await page.locator('#m-name').fill('虚构离线航行·修订');
    await page.locator('#m-score').fill('8.5');
    await page.locator('#m-remark').fill('离线修改的作品资料🌟');
    await saveByUi(page, `/api/anime/${created.id}/update`, () => page.locator('#editModal').getByRole('button', { name: '保存', exact: true }).click());
    await expect(row).toContainText('虚构离线航行·修订');
    await saveByUi(page, `/api/anime/${created.id}/next-episode`, () => row.getByRole('button', { name: '下一集', exact: true }).click());
    await expect(row.locator('.ep')).toHaveText('2 / 12');

    const memoryText = '虚构人物在结尾的选择让我投入，音乐的留白也很动人。';
    const statement = '喜欢结尾由人物主动选择推动的叙事';
    await row.getByRole('button', { name: '写感想', exact: true }).click();
    await page.locator('#memoryContent').fill(memoryText);
    await page.locator('#memoryOptional summary').click();
    await page.locator('#memoryLiked').fill('人物主动选择与音乐留白');
    await page.locator('#memoryDisliked').fill('虚构中段的节奏略慢');
    await page.locator('#memoryScope').fill('前两集');
    await page.locator('#memoryContext').selectOption('INITIAL');
    await saveByUi(page, `/api/anime/${created.id}/memories`, () => page.locator('#memorySave').click());
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
    await page.locator('.memory-entry').getByRole('button', { name: '整理观点', exact: true }).click();
    await page.locator('#tasteQuote').fill('虚构人物在结尾的选择让我投入');
    await page.locator('#tasteStatement').fill(statement);
    await page.locator('#tasteFactor').fill('人物选择');
    await page.locator('#tasteSave').click();
    await expect(page.locator('#tasteStatus')).toContainText('观点已保存');
    await expect(page.locator('.taste-entry')).toContainText('已确认');
    await expect(page.locator('.taste-entry')).toContainText('手工观点');
    await expect(page.locator('#tasteProfile')).toContainText('1 部作品 · 1 条已确认依据');
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();

    const beforeFailure = businessData(await readBackup(page));
    await fillAnime(page, '虚构断网后仍可保存的草稿', 'planning', '7.5');
    await page.locator('#animeRemark').fill('联网搜索失败也应保留的草稿');
    const [failedSearch] = await Promise.all([
      page.waitForResponse(response => new URL(response.url()).pathname === '/api/bangumi/search'),
      page.locator('#btnBangumi').click()
    ]);
    assert.equal(failedSearch.status(), 503);
    await expect(page.getByText('模拟离线：Bangumi搜索暂不可用', { exact: true })).toBeVisible();
    await expect(page.locator('#btnBangumi')).toBeEnabled();
    await expect(page.locator('#animeName')).toHaveValue('虚构断网后仍可保存的草稿');
    await expect(page.locator('#animeRemark')).toHaveValue('联网搜索失败也应保留的草稿');
    await expect(page.locator('#animeStatus')).toHaveValue('planning');
    await expect(page.locator('#animeScore')).toHaveValue('7.5');
    assert.deepEqual(businessData(await readBackup(page)), beforeFailure, '联网失败不得修改已有业务资料');
    await saveByUi(page, '/api/anime/add', () => page.locator('#animeForm button[type="submit"]').click());
    await expect(page.locator('.table-card tbody tr').filter({ hasText: '虚构断网后仍可保存的草稿' })).toBeVisible();
    await expect(page.locator('#stat-total')).toHaveText('2');
    await expect(page.locator('#stat-watching')).toHaveText('1');
    await expect(page.locator('#stat-planning')).toHaveText('1');
    await expect(page.locator('#stat-episodes')).toHaveText('2 / 24');
    await expect(page.locator('#stat-avg-score')).toHaveText('8.0');
    await selectDesktopPage(page, 'charts');
    await page.waitForFunction(() => Object.values(globalThis.Chart.instances).some(chart => chart.canvas.id === 'c1'));
    assert.deepEqual(await page.evaluate(() => Object.values(globalThis.Chart.instances).find(chart => chart.canvas.id === 'c1').data.datasets[0].data), [1, 0, 1, 0]);

    const savedFile = path.join(sourceDirectory, '离线完整备份.json');
    // 只替代系统保存框的用户选择，实际导出仍走桌面文件与真实本地 HTTP。
    await application.evaluate(({ dialog }, filePath) => { dialog.showSaveDialog = async () => ({ canceled: false, filePath }); }, savedFile);
    await selectDesktopPage(page, 'settings');
    await page.locator('[data-ui-click="exportData"]').click();
    await expect(page.getByText('备份已保存', { exact: true })).toBeVisible();
    const backup = JSON.parse(fs.readFileSync(savedFile, 'utf8'));
    assert.equal(backup.format, 'otakulog-backup');
    assert.equal(backup.version, 6);
    assert.equal(backup.anime.length, 2);
    assert.equal(backup.tags.length, 2);
    assert.deepEqual(backup.episodes.map(entry => entry.episodeNumber), [1, 2]);
    assert.equal(backup.memories.length, 1);
    assert.equal(backup.memories[0].content, memoryText);
    assert.equal(backup.memories[0].watchedDate, null);
    assert.equal(backup.insights.length, 1);
    assert.equal(backup.insights[0].origin, 'MANUAL');
    assert.equal(backup.insights[0].status, 'CONFIRMED');
    assert.deepEqual(backup.aiCalls, []);
    assert.deepEqual(businessData(backup), businessData(await readBackup(page)));
    await close();

    const restoredDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-offline-restored-')); directories.push(restoredDirectory);
    ({ page } = await launch(restoredDirectory));
    assert.equal((await readBackup(page)).anime.length, 0, '恢复目标必须为另一个全新资料目录');
    let confirmation;
    page.once('dialog', async dialog => { confirmation = { type: dialog.type(), message: dialog.message() }; await dialog.accept(); });
    const preview = page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/import/preview');
    const restored = page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/import');
    await page.locator('input[type="file"][accept=".json"]').setInputFiles(savedFile);
    assert.equal((await (await preview).json()).data.valid, true);
    assert.equal((await (await restored).json()).code, 200);
    assert.equal(confirmation.type, 'confirm');
    assert.match(confirmation.message, /本地备份预览/);
    assert.match(confirmation.message, /观影记忆 1 条/);
    assert.match(confirmation.message, /观点 1 条/);
    assert.deepEqual(businessData(await readBackup(page)), businessData(backup), '除导出时间外，所有业务集合须完整一致');
    await expect(page.locator('#stat-total')).toHaveText('2');
    await close();
    assert.ok(fs.existsSync(path.join(restoredDirectory, 'data', 'otakulog.mv.db')));
    ({ page } = await launch(restoredDirectory));
    assert.deepEqual(businessData(await readBackup(page)), businessData(backup), '重开程序后仍须保留全部恢复资料');
    await selectTableView(page);
    const restoredRow = page.locator('.table-card tbody tr').filter({ hasText: '虚构离线航行·修订' });
    await expect(restoredRow.locator('.ep')).toHaveText('2 / 12');
    await restoredRow.getByRole('button', { name: '写感想', exact: true }).click();
    await expect(page.locator('.memory-entry')).toContainText(memoryText);
    await page.locator('.memory-entry').getByRole('button', { name: '整理观点', exact: true }).click();
    await expect(page.locator('.taste-entry')).toContainText(statement);
    await expect(page.locator('.taste-entry')).toContainText('已确认');
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
    assert.equal(onlineRequests.filter(pathname => pathname === '/api/bangumi/search').length, 1);
    assert.deepEqual(aiRequests, []);
    assert.deepEqual(remote, [], '冷启动及本地业务不得依赖 CDN 或其他渲染器远程资源');
    assert.ok(scripts.includes('/js/vendor/chart.umd.js'));
    assert.ok(scripts.includes('/js/vendor/marked.min.js'));
    assert.deepEqual(errors, []);
  } finally {
    try {
      // 失败时也只关闭本次应用；原生选择仅用于放弃本次虚构草稿。
      await current?.application.evaluate(({ dialog }) => { dialog.showMessageBoxSync = () => 1; });
      await current?.application.close();
    } finally {
      for (const directory of directories) {
        assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
        assert.match(path.basename(directory), /^otakulog-offline-(source|restored)-/);
        fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
      }
    }
  }
});
