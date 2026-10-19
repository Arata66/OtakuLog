const path = require('node:path');
const { _electron } = require('@playwright/test');

const desktop = process.env.OTAKULOG_DESKTOP_APP_DIR || path.resolve(__dirname, '../..');
if (!path.isAbsolute(desktop)) throw new Error('受测桌面程序目录必须使用绝对路径');
function applicationLaunchOptions(directory, executable = process.env.OTAKULOG_DESKTOP_EXECUTABLE) {
  if (executable && !path.isAbsolute(executable)) throw new Error('受测程序必须使用绝对路径');
  const env = {};
  for (const name of ['SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA'])
    if (process.env[name]) env[name] = process.env[name];
  env.PATH = path.join(process.env.SystemRoot, 'System32');
  return {
    executablePath: executable || require('electron'),
    args: [...(executable ? [] : [desktop]), '--data-dir=' + directory], env, timeout: 30000
  };
}
async function launchApplication(directory, executable = process.env.OTAKULOG_DESKTOP_EXECUTABLE, { configure, waitForReady = true } = {}) {
  const started = Date.now();
  const application = await _electron.launch(applicationLaunchOptions(directory, executable));
  try {
    const page = await application.firstWindow();
    if (configure) await configure(application, page);
    if (waitForReady) {
      await page.waitForURL(/^http:\/\/127\.0\.0\.1:\d+\/$/, { timeout: 90000 });
      await page.locator('#animeName').waitFor({ state: 'attached' });
      if (await page.locator('body.desktop-collection').count())
        await page.waitForFunction(() => Boolean(globalThis.otakulogDesktopCollection));
    }
    return { application, page, startupMs: Date.now() - started };
  } catch (error) {
    await application.close();
    throw error;
  }
}

async function openAddForm(page) {
  if (await page.locator('#desktopAddButton').count()
    && !await page.locator('#desktopAddDialog').evaluate(dialog => dialog.open))
    await page.locator('#desktopAddButton').click();
  await page.locator('#animeName').waitFor();
}

async function selectDesktopPage(page, name) {
  const sidebar = page.locator('#desktopCollectionNav [data-desktop-tab="' + name + '"]');
  if (await sidebar.count()) await sidebar.click();
  else if (name !== 'settings') {
    const labels = { list: '番剧列表', charts: '数据图表', calendar: '放送日历', timeline: '观影时间线' };
    await page.getByRole('tab', { name: labels[name], exact: true }).click();
  }
}

async function selectTableView(page) {
  await selectDesktopPage(page, 'list');
  if (await page.locator('#viewTable').getAttribute('aria-pressed') !== 'true')
    await page.locator('#viewTable').click();
}

module.exports = { launchApplication, applicationLaunchOptions, openAddForm, selectDesktopPage, selectTableView };
