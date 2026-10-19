const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { expect } = require('@playwright/test');
const { launchApplication, openAddForm, selectTableView } = require('./helpers/application.cjs');

test('当本地保存触发每日备份或备份失败时应该保留首份文件并明确提示而不丢记录', { timeout: 180000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-daily-backup-'));
  let current;
  t.after(async () => {
    await current?.application.evaluate(({ dialog }) => { dialog.showMessageBoxSync = () => 1; });
    await current?.application.close();
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.match(path.basename(directory), /^otakulog-daily-backup-/);
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  current = await launchApplication(directory, undefined, { configure: async (_app, page) => {
    await page.route('**/api/watch/airing', route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":503,"message":"虚构离线放送服务"}' }));
  } });
  const { page, application } = current;
  const daily = path.join(directory, 'backups', 'daily');
  assert.equal(fs.existsSync(daily), false);
  await openAddForm(page);
  await page.locator('#animeName').fill('虚构每日备份作品');
  await page.locator('#totalEpisodes').fill('12');
  await page.locator('#animeSeason').fill('2026秋');
  await page.locator('#animeScore').fill('8');
  await page.locator('#animeStatus').selectOption('planning');
  const [saved] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add' && response.request().method() === 'POST'),
    page.locator('#animeForm button[type="submit"]').click()
  ]);
  assert.equal((await saved.json()).code, 200);
  await expect.poll(() => fs.existsSync(daily) ? fs.readdirSync(daily).filter(name => /^\d{4}-\d{2}-\d{2}\.json$/.test(name)).length : 0).toBe(1);
  const file = path.join(daily, fs.readdirSync(daily).find(name => name.endsWith('.json'))), first = fs.readFileSync(file);
  assert.equal(JSON.parse(first).version, 6);
  assert.equal(JSON.parse(first).anime[0].data.name, '虚构每日备份作品');
  await selectTableView(page);
  const edit = async remark => {
    await page.locator('.table-card tbody tr').filter({ hasText: '虚构每日备份作品' }).getByRole('button', { name: '编辑', exact: true }).click();
    await page.locator('#m-remark').fill(remark);
    const [response] = await Promise.all([
      page.waitForResponse(response => /\/api\/anime\/\d+\/update$/.test(new URL(response.url()).pathname)),
      page.locator('#editModal').getByRole('button', { name: '保存', exact: true }).click()
    ]);
    assert.equal((await response.json()).code, 200);
  };
  await edit('第二次正常保存');
  assert.deepEqual(fs.readFileSync(file), first);
  const kept = file + '.保留'; fs.renameSync(file, kept); fs.mkdirSync(file);
  fs.writeFileSync(path.join(file, '原内容.txt'), '不可删除');
  await edit('备份无法写入时仍成功保存的记录');
  const status = async () => page.evaluate(async () => (await (await globalThis.fetch('/api/desktop/backups/status')).json()).data);
  await expect.poll(async () => (await status()).warning).toBeTruthy();
  await expect(page.locator('#desktopBackupStatus')).toContainText('自动备份未完成', { timeout: 15000 });
  assert.deepEqual(fs.readFileSync(kept), first);
  assert.equal(fs.readFileSync(path.join(file, '原内容.txt'), 'utf8'), '不可删除');
  const backup = await page.evaluate(async () => (await (await globalThis.fetch('/api/anime/export')).json()));
  assert.equal(backup.anime[0].data.remark, '备份无法写入时仍成功保存的记录');
  fs.unlinkSync(path.join(file, '原内容.txt')); fs.rmdirSync(file); fs.renameSync(kept, file);
  await edit('目录恢复后的记录');
  await expect.poll(async () => (await status()).warning).toBeNull();
  assert.deepEqual(fs.readFileSync(file), first);
  const opened = await application.evaluate(async ({ Menu, shell }) => {
    let directory; shell.openPath = async value => { directory = value; return ''; };
    await Menu.getApplicationMenu().items.find(item => item.label === '文件').submenu.items.find(item => item.label === '打开备份目录').click();
    return directory;
  });
  assert.equal(opened, path.join(directory, 'backups'));
});
