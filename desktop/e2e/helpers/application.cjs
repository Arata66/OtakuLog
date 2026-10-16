const path = require('node:path');
const { _electron } = require('@playwright/test');

const desktop = path.resolve(__dirname, '../..');
const repository = path.resolve(desktop, '..');
const assets = {
  '/npm/chart.js@4.4.1/dist/chart.umd.min.js': 'chart.js/dist/chart.umd.js',
  '/npm/sortablejs@1.15.0/Sortable.min.js': 'sortablejs/Sortable.min.js',
  '/npm/marked@12/marked.min.js': 'marked/marked.min.js',
  '/npm/dompurify@3/dist/purify.min.js': 'dompurify/dist/purify.min.js'
};

async function launchApplication(directory, executable = process.env.OTAKULOG_DESKTOP_EXECUTABLE) {
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
    // 仅隔离测试映射现有 CDN 资源，不把它当作成品离线验收。
    await application.context().route('https://cdn.jsdelivr.net/**', async route => {
      const name = assets[new URL(route.request().url()).pathname];
      if (!name) return route.abort();
      await route.fulfill({ path: path.join(repository, 'node_modules', name), contentType: 'text/javascript' });
    });
    const page = await application.firstWindow();
    await page.waitForURL(/^http:\/\/127\.0\.0\.1:\d+\/$/, { timeout: 90000 });
    await page.locator('#animeName').waitFor();
    return { application, page, startupMs: Date.now() - started };
  } catch (error) {
    await application.close();
    throw error;
  }
}

module.exports = { launchApplication };
