const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const { expect } = require('@playwright/test');
const { launchApplication, openAddForm, selectDesktopPage } = require('./helpers/application.cjs');

test('当端口变化重开时应该保留偏好和窗口并支持菜单及输入法组合键', { timeout: 180000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-preferences-e2e-'));
  const file = path.join(directory, 'config', 'settings.json'), errors = [];
  let current, reserved;
  t.after(async () => {
    await current?.application.close();
    if (reserved) await new Promise(resolve => reserved.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory);
  let { application, page } = current;
  page.on('pageerror', error => errors.push(error.message));
  assert.equal(await page.evaluate(() => typeof globalThis.otakulogPreferences), 'object');
  await page.evaluate(() => globalThis.otakulogPreferences.ready);
  const firstPort = Number(new URL(page.url()).port);
  await selectDesktopPage(page, 'settings');
  if (await page.locator('html').getAttribute('data-theme') === 'dark') await page.locator('#themeToggle').click();
  await page.locator('#themeToggle').click();
  await page.locator('#accentBtn').click(); await page.locator('[data-accent="jade"]').click();
  await page.locator('#langToggle').click();
  await expect.poll(() => fs.existsSync(file) ? JSON.parse(fs.readFileSync(file)).preferences : null)
    .toEqual({ theme: 'dark', accent: 'jade', language: 'en' });
  const normal = await application.evaluate(({ BrowserWindow, screen }) => {
    const owner = BrowserWindow.getAllWindows()[0], area = screen.getPrimaryDisplay().workArea;
    const width = Math.min(1000, area.width), height = Math.min(700, area.height);
    // 初始位置留在当前工作区内，避免小屏幕上的正常回位被误判为恢复失败。
    owner.setBounds({ x: area.x + Math.min(30, Math.floor((area.width - width) / 2)),
      y: area.y + Math.min(30, Math.floor((area.height - height) / 2)), width, height });
    return owner.getNormalBounds();
  });
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].maximize());
  await expect.poll(() => JSON.parse(fs.readFileSync(file)).window?.maximized).toBe(true);
  const savedWindow = JSON.parse(fs.readFileSync(file)).window;
  assert.deepEqual(savedWindow, { ...normal, maximized: true });
  await page.evaluate(() => {
    globalThis.localStorage.setItem('otakulog-theme', 'light'); globalThis.localStorage.setItem('otakulog-lang', 'zh'); globalThis.localStorage.setItem('otakulog-accent', 'rose');
  });
  await application.close(); current = undefined;
  reserved = net.createServer();
  await new Promise((resolve, reject) => { reserved.once('error', reject); reserved.listen(firstPort, '127.0.0.1', resolve); });
  current = await launchApplication(directory); ({ application, page } = current);
  page.on('pageerror', error => errors.push(error.message));
  assert.notEqual(Number(new URL(page.url()).port), firstPort);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expect(page.locator('html')).toHaveAttribute('data-accent', 'jade');
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  assert.equal(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isMaximized()), true);
  assert.deepEqual(JSON.parse(fs.readFileSync(file)).window, savedWindow);
  const restoredNormal = await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].getNormalBounds());
  await application.close(); current = undefined;
  assert.deepEqual(JSON.parse(fs.readFileSync(file)).window, savedWindow, '未调整的窗口不能把系统取整差异重新写回');
  current = await launchApplication(directory); ({ application, page } = current);
  page.on('pageerror', error => errors.push(error.message));
  assert.deepEqual(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].getNormalBounds()), restoredNormal,
    '连续重开不能继续放大窗口');
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].unmaximize());

  const opened = await application.evaluate(async ({ Menu, shell }) => {
    const items = Menu.getApplicationMenu().items.flatMap(menu => menu.submenu?.items || []), paths = [];
    shell.openPath = async value => { paths.push(value); return ''; };
    for (const label of ['打开数据目录', '打开日志目录']) await items.find(item => item.label === label).click();
    return paths;
  });
  assert.deepEqual(opened, [path.join(directory, 'data'), path.join(directory, 'logs')]);
  await openAddForm(page);
  for (const zoom of [1, 1.25, 1.5]) {
    await application.evaluate(({ BrowserWindow }, factor) => BrowserWindow.getAllWindows()[0].webContents.setZoomFactor(factor), zoom);
    assert.equal(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].webContents.getZoomFactor()), zoom);
    await expect(page.locator('#animeName')).toBeVisible();
  }
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].webContents.setZoomFactor(1));
  await page.evaluate(() => { globalThis.__searches = 0; globalThis.searchBangumi = () => { globalThis.__searches++; }; });
  for (const event of [{ key: 'Enter', isComposing: true }, { key: 'Enter', keyCode: 229 }])
    await page.locator('#animeName').dispatchEvent('keydown', event);
  assert.equal(await page.evaluate(() => globalThis.__searches), 0);
  await page.locator('#animeName').dispatchEvent('keydown', { key: 'Enter' });
  assert.equal(await page.evaluate(() => globalThis.__searches), 1);
  await page.evaluate(() => {
    const overlay = globalThis.document.createElement('div'); overlay.id = 'traceMoeModal'; globalThis.document.body.appendChild(overlay); globalThis.trapFocus(overlay);
  });
  await page.locator('#traceMoeModal').dispatchEvent('keydown', { key: 'Escape', isComposing: true });
  assert.equal(await page.locator('#traceMoeModal').count(), 1);
  await page.locator('#traceMoeModal').dispatchEvent('keydown', { key: 'Escape' });
  assert.equal(await page.locator('#traceMoeModal').count(), 0);
  assert.deepEqual(errors, []);
});

test('当设置损坏或写入失败时应该可见提示并保留原文件', { timeout: 180000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-settings-failure-'));
  const config = path.join(directory, 'config'), file = path.join(config, 'settings.json');
  fs.mkdirSync(config); fs.writeFileSync(file, '{虚构损坏设置');
  let current;
  t.after(async () => {
    await current?.application.evaluate(({ dialog }) => { dialog.showMessageBox = async () => ({ response: 0 }); dialog.showMessageBoxSync = () => 1; });
    await current?.application.close();
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory);
  await expect(current.page.getByText('桌面设置无法读取，已使用默认设置并保留原文件。请从菜单打开资料目录检查设置文件。', { exact: true })).toBeVisible();
  await current.application.close(); current = undefined;
  assert.equal(fs.readFileSync(file, 'utf8'), '{虚构损坏设置');
  fs.unlinkSync(file);
  current = await launchApplication(directory);
  const before = fs.existsSync(file) ? fs.readFileSync(file) : undefined;
  if (before) fs.unlinkSync(file);
  fs.mkdirSync(file); fs.writeFileSync(path.join(file, '保留.txt'), '原内容');
  await selectDesktopPage(current.page, 'settings');
  await current.page.locator('#themeToggle').click();
  await expect(current.page.getByText('设置未能保存，本次选择仅在当前窗口生效。请检查设置文件和资料目录的写入权限。', { exact: true })).toBeVisible();
  assert.equal(fs.readFileSync(path.join(file, '保留.txt'), 'utf8'), '原内容');
  await current.application.evaluate(({ dialog }) => { dialog.showMessageBox = async () => ({ response: 0 }); });
});
