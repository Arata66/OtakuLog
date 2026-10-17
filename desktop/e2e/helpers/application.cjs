const path = require('node:path');
const { _electron } = require('@playwright/test');

const desktop = path.resolve(__dirname, '../..');
async function launchApplication(directory, executable = process.env.OTAKULOG_DESKTOP_EXECUTABLE, { configure } = {}) {
  if (executable && !path.isAbsolute(executable)) throw new Error('受测程序必须使用绝对路径');
  const env = {};
  for (const name of ['SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA'])
    if (process.env[name]) env[name] = process.env[name];
  env.PATH = path.join(process.env.SystemRoot, 'System32');
  const started = Date.now();
  const application = await _electron.launch({
    executablePath: executable || require('electron'),
    args: [...(executable ? [] : [desktop]), '--data-dir=' + directory], env, timeout: 30000
  });
  try {
    const page = await application.firstWindow();
    if (configure) await configure(application, page);
    await page.waitForURL(/^http:\/\/127\.0\.0\.1:\d+\/$/, { timeout: 90000 });
    await page.locator('#animeName').waitFor();
    return { application, page, startupMs: Date.now() - started };
  } catch (error) {
    await application.close();
    throw error;
  }
}

module.exports = { launchApplication };
