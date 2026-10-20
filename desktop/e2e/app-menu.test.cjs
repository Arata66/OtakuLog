const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { expect } = require('@playwright/test');
const { launchApplication, openAddForm, selectDesktopPage } = require('./helpers/application.cjs');

test('当桌面收起原生菜单栏时应该通过省略号打开原菜单并保留编辑快捷键', { timeout: 90000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-app-menu-'));
  let current, succeeded = false;
  t.after(async () => {
    await current?.application.close().catch(() => {});
    if (!succeeded) { console.error('应用菜单证据保留：' + directory); return; }
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.match(path.basename(directory), /^otakulog-app-menu-/);
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory, undefined, { configure: async (application, page) => {
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(1280, 900));
    await page.route('**/api/watch/airing', route => route.fulfill({ status: 503, json: { code: 503 } }));
  } });
  const { page, application } = current;
  const menuState = await application.evaluate(({ BrowserWindow, Menu }) => {
    const owner = BrowserWindow.getAllWindows()[0], menu = Menu.getApplicationMenu();
    globalThis.__menuOwners = [];
    menu.popup = options => globalThis.__menuOwners.push(options.window.id);
    return { visible: owner.isMenuBarVisible(), id: owner.id, labels: menu.items.map(item => item.label),
      files: menu.items[0].submenu.items.map(item => item.label), roles: menu.items[1].submenu.items.map(item => item.role) };
  });
  assert.equal(menuState.visible, false);
  assert.deepEqual(menuState.labels, ['文件', '编辑', '视图', '设置', '帮助']);
  assert.ok(menuState.files.includes('打开备份目录')); assert.ok(menuState.roles.includes('undo'));
  await expect(page.getByRole('button', { name: '应用菜单', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '应用菜单', exact: true }).click();
  await expect.poll(() => application.evaluate(() => globalThis.__menuOwners)).toEqual([menuState.id]);
  await openAddForm(page);
  await page.locator('#animeName').pressSequentially('menudemo');
  await page.locator('#animeName').press('Control+Z');
  await expect(page.locator('#animeName')).toHaveValue('');
  await page.locator('#animeName').press('Control+Y');
  await expect(page.locator('#animeName')).toHaveValue('menudemo');
  await page.locator('#animeName').press('Control+A'); await page.locator('#animeName').press('Backspace');
  await page.locator('#desktopAddClose').click();
  const screenshots = path.resolve(__dirname, '../test-results'); fs.mkdirSync(screenshots, { recursive: true });
  await page.screenshot({ path: path.join(screenshots, 'app-menu-light.png'), animations: 'disabled' });
  await selectDesktopPage(page, 'settings'); await page.locator('#themeToggle').click();
  await selectDesktopPage(page, 'list');
  await page.screenshot({ path: path.join(screenshots, 'app-menu-dark.png'), animations: 'disabled' });
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(760, 700));
  await expect(page.getByRole('button', { name: '应用菜单', exact: true })).toBeInViewport();
  await expect.poll(() => page.evaluate(() => globalThis.document.documentElement.scrollWidth <= globalThis.innerWidth + 1)).toBe(true);
  assert.equal(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isMenuBarVisible()), false);
  succeeded = true;
});
