const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { expect } = require('@playwright/test');
const { launchApplication } = require('./helpers/application.cjs');

async function isolated(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-desktop-security-'));
  const remote = [], errors = [], scripts = [];
  let current;
  t.after(async () => {
    if (current && await current.page.locator('#memoryContent').count())
      await current.page.locator('#memoryContent').fill('').catch(() => {});
    await current?.application.close();
    assert.ok(path.resolve(directory).startsWith(path.resolve(os.tmpdir()) + path.sep));
    assert.match(path.basename(directory), /^otakulog-desktop-security-/);
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory, undefined, { configure: async (application, page) => {
    // 仅阻断渲染器远程资源；这不是整台 Windows 断网验收。
    await page.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (['http:', 'https:'].includes(url.protocol) && url.hostname !== '127.0.0.1') {
        remote.push({ url: url.href, type: request.resourceType() });
        return route.abort();
      }
      if (request.resourceType() === 'script') scripts.push(url.pathname);
      return route.continue();
    });
    page.on('pageerror', error => errors.push(error.message));
    await application.evaluate(({ app, shell }) => {
      // 仅记录系统浏览器调用，不打开真实外网。
      app.__otakulogExternalCalls = [];
      shell.openExternal = async url => { app.__otakulogExternalCalls.push(url); };
    });
  } });
  return { ...current, directory, remote, errors, scripts };
}

test('当远程脚本字体不可用时应该用真实本地资源完成新增进度记忆Markdown与图表', { timeout: 180000 }, async t => {
  const current = await isolated(t), { page } = current;
  const response = await page.request.get(page.url());
  assert.match(response.headers()['content-security-policy'] || '', /script-src 'self'/);
  assert.deepEqual(current.remote, []);
  assert.equal(await page.locator('link[href*="fonts.googleapis.com"]').count(), 0);
  assert.deepEqual(current.scripts.filter(value => value.startsWith('/js/vendor/')).sort(),
    ['/js/vendor/Sortable.min.js', '/js/vendor/chart.umd.js', '/js/vendor/marked.min.js', '/js/vendor/purify.min.js'].sort());
  assert.deepEqual(await page.evaluate(async () => ({ workers: (await globalThis.navigator.serviceWorker.getRegistrations()).length,
    controlled: Boolean(globalThis.navigator.serviceWorker.controller), caches: await globalThis.caches.keys() })), { workers: 0, controlled: false, caches: [] });
  await page.locator('#animeName').fill('虚构本地资源验收');
  await page.locator('#totalEpisodes').fill('12');
  await page.locator('#animeSeason').fill('2026秋');
  await page.locator('#animeScore').fill('8');
  await page.locator('#animeStatus').selectOption('watching');
  await page.locator('#animeRemark').fill('**虚构加粗** <img src="x" onerror="window.__remarkInjected=true">');
  const [save] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add' && response.request().method() === 'POST'),
    page.locator('#animeForm button[type="submit"]').click()
  ]);
  assert.equal((await save.json()).code, 200);
  const row = page.locator('.table-card tbody tr').filter({ hasText: '虚构本地资源验收' });
  await expect(row).toBeVisible();
  const [progress] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/next-episode') && response.request().method() === 'POST'),
    row.getByRole('button', { name: '下一集', exact: true }).click()
  ]);
  assert.equal((await progress.json()).code, 200);
  await expect(row).toContainText('1 / 12');
  await row.getByRole('button', { name: '写感想', exact: true }).click();
  await expect(page.locator('#detailModal .detail-remark strong')).toHaveText('虚构加粗');
  await page.locator('#memoryContent').fill('虚构的本地安全验收感想');
  await page.locator('#memorySave').click();
  await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
  await expect(page.locator('#animeMemorySection')).toContainText('虚构的本地安全验收感想');
  await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('tab', { name: '数据图表', exact: true }).click();
  await page.waitForFunction(() => Object.keys(globalThis.Chart.instances).length > 0);
  assert.equal(await page.evaluate(() => Boolean(globalThis.__remarkInjected)), false);
  assert.deepEqual(current.remote.filter(value => ['script', 'font', 'stylesheet'].includes(value.type)), []);
  assert.deepEqual(current.errors, []);
});

test('当浏览器原生执行注入探针时应该仅允许同源脚本并拒绝行内事件外部脚本和动态求值', { timeout: 180000 }, async t => {
  const { page } = await isolated(t);
  // 探针以同源脚本标签原生执行，避免 CDP 直接求值绕过 CSP 形成假证据。
  await page.route('**/js/test-probe.js', route => route.fulfill({ contentType: 'text/javascript', body: `
    window.__probe = { loaded: true, violations: [], evalBlocked: false, functionBlocked: false };
    document.addEventListener('securitypolicyviolation', event => __probe.violations.push({ directive: event.effectiveDirective, uri: event.blockedURI }));
    try { eval('window.__evalExecuted = true'); } catch (error) { __probe.evalBlocked = error.name === 'EvalError'; }
    try { new Function('window.__functionExecuted = true')(); } catch (error) { __probe.functionBlocked = error.name === 'EvalError'; }
    const inline = document.createElement('script'); inline.textContent = 'window.__inlineExecuted = true'; document.head.append(inline);
    const button = document.createElement('button'); button.setAttribute('onclick', 'window.__eventExecuted = true'); document.body.append(button); button.click(); button.remove();
    const external = document.createElement('script'); external.src = 'https://example.invalid/test-script.js'; document.head.append(external);
  ` }));
  await page.evaluate(() => {
    const script = globalThis.document.createElement('script'); script.src = '/js/test-probe.js'; globalThis.document.head.append(script);
  });
  await page.waitForFunction(() => globalThis.__probe?.violations.length >= 5);
  const probe = await page.evaluate(() => ({ ...globalThis.__probe, executed: [globalThis.__evalExecuted,
    globalThis.__functionExecuted, globalThis.__inlineExecuted, globalThis.__eventExecuted].some(Boolean) }));
  assert.equal(probe.loaded, true);
  assert.equal(probe.evalBlocked, true);
  assert.equal(probe.functionBlocked, true);
  assert.equal(probe.executed, false);
  assert.ok(probe.violations.some(value => value.directive === 'script-src-attr'));
  assert.ok(probe.violations.some(value => value.uri === 'https://example.invalid/test-script.js'));
});

test('当页面尝试外部导航弹窗禁止协议和同源重定向时应该维持窗口边界', { timeout: 180000 }, async t => {
  const current = await isolated(t), { application, page } = current, origin = new URL(page.url()).origin;
  const navigations = [];
  await application.evaluate(({ app, BrowserWindow }) => {
    app.__otakulogNavigationEvents = [];
    const contents = BrowserWindow.getAllWindows()[0].webContents;
    for (const type of ['will-navigate', 'will-redirect']) contents.on(type, (event, url) => {
      app.__otakulogNavigationEvents.push({ type, url, prevented: event.defaultPrevented });
    });
  });
  await page.route('https://example.invalid/**', route => {
    navigations.push(route.request().url());
    return route.fulfill({ contentType: 'text/html', body: '<p>虚构外部站点</p>' });
  });
  await page.evaluate(() => {
    for (const url of ['https://example.invalid/allowed', 'http://example.invalid/denied', 'file:///C:/otakulog-test',
      'javascript:window.__protocolExecuted=true', 'data:text/html,blocked', 'otakulog-test:blocked']) globalThis.open(url);
  });
  await expect.poll(() => application.evaluate(({ app }) => app.__otakulogExternalCalls)).toEqual(['https://example.invalid/allowed']);
  assert.equal(application.windows().length, 1);
  assert.equal(await page.evaluate(() => Boolean(globalThis.__protocolExecuted)), false);
  await page.evaluate(() => { globalThis.location.href = 'https://example.invalid/direct'; });
  await expect.poll(() => application.evaluate(({ app }) => app.__otakulogNavigationEvents))
    .toContainEqual({ type: 'will-navigate', url: 'https://example.invalid/direct', prevented: true });
  assert.equal(page.url(), origin + '/');
  assert.deepEqual(navigations, []);
  await page.route('**/desktop-test-redirect', route => route.fulfill({ status: 302, headers: { location: 'https://example.invalid/redirected' } }));
  await page.evaluate(() => { globalThis.location.href = '/desktop-test-redirect'; });
  await expect.poll(() => application.evaluate(({ app }) => app.__otakulogNavigationEvents))
    .toContainEqual({ type: 'will-redirect', url: 'https://example.invalid/redirected', prevented: true });
  assert.equal(page.url(), origin + '/');
  assert.deepEqual(navigations, []);
});
