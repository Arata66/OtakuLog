const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { expect } = require('@playwright/test');
const { launchApplication } = require('./helpers/application.cjs');

test('当桌面使用原生备份时应该正确处理取消保存失败及真实文件恢复', { timeout: 180000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-native-backup-'));
  const errors = [];
  let current;
  t.after(async () => {
    if (current) {
      await current.application.evaluate(({ app, dialog }) => {
        app.__releaseBackupChoice?.({ canceled: true }); dialog.showMessageBoxSync = () => 1;
      }).catch(() => {});
      await expect(current.page.locator('[data-ui-click="exportData"]')).toBeEnabled({ timeout: 35000 });
      await current.application.close();
    }
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory, undefined, { configure: async (application, page) => {
    page.on('pageerror', error => errors.push(error.message));
    await application.evaluate(({ app, dialog }) => {
      app.__backupDialogs = 0;
      dialog.showSaveDialog = async () => { app.__backupDialogs++; return { canceled: true }; };
    });
    await page.waitForFunction(() => typeof globalThis.otakulogDesktop === 'object');
    assert.equal((await page.evaluate(() => globalThis.otakulogDesktop.exportBackup())).status, 'failed');
    assert.equal(await application.evaluate(({ app }) => app.__backupDialogs), 0, '启动页不得弹出保存框');
  } });
  const { application, page } = current;
  assert.deepEqual(await page.evaluate(() => Object.keys(globalThis.otakulogDesktop)), ['exportBackup']);
  assert.equal(await page.evaluate(() => typeof require), 'undefined');
  const child = await page.locator('body').evaluateHandle(body => {
    const frame = body.ownerDocument.createElement('iframe'); frame.src = 'about:blank'; body.appendChild(frame); return frame;
  });
  assert.equal(await (await child.asElement().contentFrame()).evaluate(() => typeof globalThis.otakulogDesktop), 'undefined');
  await child.evaluate(frame => frame.remove());

  await page.locator('#animeName').fill('虚构原生备份验收');
  await page.locator('#totalEpisodes').fill('12');
  await page.locator('#animeSeason').fill('2026秋');
  await page.locator('#animeScore').fill('8');
  await page.locator('#animeRemark').fill('虚构原文：中文与星星🌟');
  await page.locator('#animeStatus').selectOption('planning');
  const added = page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add' && response.request().method() === 'POST');
  await page.locator('#animeForm button[type="submit"]').click();
  const result = await (await added).json();
  assert.equal(result.code, 200);

  await application.evaluate(({ app, dialog }) => {
    dialog.showSaveDialog = () => new Promise(resolve => { app.__backupDialogs++; app.__releaseBackupChoice = resolve; });
  });
  const button = page.locator('[data-ui-click="exportData"]');
  await button.click();
  await expect.poll(() => application.evaluate(({ app }) => app.__backupDialogs)).toBe(1);
  assert.equal(await button.isDisabled(), true);
  assert.equal((await page.evaluate(() => globalThis.otakulogDesktop.exportBackup())).status, 'failed');
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].close());
  assert.equal(await page.evaluate(async () => (await globalThis.fetch('/')).status), 200, '保存期间关闭窗口应被阻止');
  await application.evaluate(({ app }) => app.__releaseBackupChoice({ canceled: true }));
  await expect(button).toBeEnabled();
  assert.equal(await page.getByText('备份已保存', { exact: true }).count(), 0);

  const savedFile = path.join(directory, '中文 完整备份.json');
  fs.writeFileSync(savedFile, '用户确认覆盖的旧备份');
  await application.evaluate(({ dialog }, filePath) => { dialog.showSaveDialog = async () => ({ canceled: false, filePath }); }, savedFile);
  await button.click();
  await expect(page.getByText('备份已保存', { exact: true })).toBeVisible();
  const bytes = fs.readFileSync(savedFile), backup = JSON.parse(bytes.toString('utf8'));
  assert.equal(backup.format, 'otakulog-backup'); assert.equal(backup.version, 6);
  assert.equal(backup.anime[0].data.name, '虚构原生备份验收');
  assert.equal(backup.anime[0].data.remark, '虚构原文：中文与星星🌟');

  const blockedTarget = path.join(directory, '不能覆盖的目录.json');
  fs.mkdirSync(blockedTarget); fs.writeFileSync(path.join(blockedTarget, '原内容.txt'), '保留');
  await application.evaluate(({ dialog }, filePath) => { dialog.showSaveDialog = async () => ({ canceled: false, filePath }); }, blockedTarget);
  await button.click();
  await expect(page.getByText('备份保存失败，请检查本地服务、目标位置和写入权限', { exact: true })).toBeVisible();
  assert.equal(fs.readFileSync(path.join(blockedTarget, '原内容.txt'), 'utf8'), '保留');
  assert.deepEqual(fs.readFileSync(savedFile), bytes);

  const importRequests = [];
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/anime/import') importRequests.push(request); });
  const input = page.locator('input[type="file"][accept=".json"]');
  await input.setInputFiles([]);
  assert.equal(importRequests.length, 0);
  const invalidFile = path.join(directory, '损坏备份.json'); fs.writeFileSync(invalidFile, '{损坏');
  const invalid = page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/import/preview');
  await input.setInputFiles(invalidFile);
  const invalidPreview = await (await invalid).json();
  assert.equal(invalidPreview.data.valid, false);
  assert.ok(invalidPreview.data.conflicts.length > 0);
  assert.equal(importRequests.length, 0);
  assert.equal((await page.evaluate(async () => (await (await globalThis.fetch('/api/anime/export')).json()).anime.length)), 1);

  assert.equal(await page.evaluate(async id => (await globalThis.fetchApi('/api/anime/' + id, { method: 'DELETE' })).code, result.data.id), 200);
  for (let attempt = 0; attempt < 2; attempt++) {
    page.once('dialog', async dialog => { assert.equal(dialog.type(), 'confirm'); await dialog.accept(); });
    const restored = page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/import');
    await input.setInputFiles(savedFile);
    assert.equal((await (await restored).json()).code, 200);
  }
  const final = await page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).json());
  assert.equal(final.anime.length, 1); assert.equal(final.anime[0].data.name, '虚构原生备份验收');
  assert.equal(final.anime[0].data.remark, '虚构原文：中文与星星🌟');
  await page.evaluate(() => globalThis.performSearch());
  await page.getByText('虚构原生备份验收', { exact: true }).first().waitFor();
  assert.deepEqual(errors, []);

  await application.evaluate(({ app, dialog }) => {
    dialog.showSaveDialog = () => new Promise(resolve => { app.__backupDialogs++; app.__releaseBackupChoice = resolve; });
    dialog.showMessageBox = async () => { app.__faultAnswered = true; return { response: 1 }; };
  });
  await button.click();
  await expect.poll(() => application.evaluate(({ app }) => app.__backupDialogs)).toBe(2);
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].webContents.forcefullyCrashRenderer());
  await expect.poll(() => application.evaluate(({ app }) => app.__faultAnswered)).toBe(true);
  assert.equal(application.process().exitCode, null, '故障退出应等本次导出结束');
  const exited = new Promise(resolve => application.once('close', resolve));
  await application.evaluate(({ app }) => { setTimeout(() => app.__releaseBackupChoice({ canceled: true }), 50); });
  await exited;
  current = undefined;
});
