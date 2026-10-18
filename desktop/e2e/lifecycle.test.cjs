const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');
const { launchApplication } = require('./helpers/application.cjs');

async function until(read, message, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await delay(100);
  }
  throw new Error(message);
}

function running(pid) {
  try { process.kill(pid, 0); return true; }
  catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}

function javaPid(directory) {
  const logs = path.join(directory, 'logs');
  for (const name of fs.readdirSync(logs)) {
    const match = /with PID (\d+)/.exec(fs.readFileSync(path.join(logs, name), 'utf8'));
    if (match) return Number(match[1]);
  }
  return null;
}

test('当主进程在启动中或运行中异常退出时应该收回Java并允许原资料库重新打开', { timeout: 240000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-lifecycle-'));
  let current, ownedJava, succeeded = false;
  t.after(async () => {
    await current?.application.close().catch(() => {});
    if (ownedJava) await until(() => !running(ownedJava), '本次Java仍存活，保留验收目录：' + directory);
    if (!succeeded) { console.error('故障证据保留：' + directory); return; }
    fs.rmSync(directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  for (const waitForReady of [false, true]) {
    current = await launchApplication(directory, undefined, { waitForReady });
    ownedJava = await until(() => javaPid(directory), '本次启动日志中未找到Java进程');
    if (!waitForReady) {
      assert.match(current.page.url(), /^file:/, '故障必须发生在业务页面加载前');
      for (const name of fs.readdirSync(path.join(directory, 'logs')))
        assert.doesNotMatch(fs.readFileSync(path.join(directory, 'logs', name), 'utf8'), /Started OtakuLogApplication/,
          '故障必须发生在Spring完成启动前');
    }
    else {
      await current.page.locator('#animeName').fill('虚构崩溃恢复记录');
      await current.page.locator('#totalEpisodes').fill('12');
      await current.page.locator('#animeSeason').fill('2026秋');
      await current.page.locator('#animeScore').fill('8');
      await current.page.locator('#animeStatus').selectOption('planning');
      const saved = current.page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add'
        && response.request().method() === 'POST');
      await current.page.locator('#animeForm button[type="submit"]').click();
      const result = await (await saved).json();
      assert.equal(result.code, 200);
      assert.equal(result.data.name, '虚构崩溃恢复记录');
      const before = await current.page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).json());
      assert.deepEqual(before.anime.map(anime => anime.data.name), ['虚构崩溃恢复记录'], '终止前应已真实保存');
    }
    // Windows下Playwright持有的是cmd包装进程，故障必须精确作用于Electron本身。
    const mainPid = await current.application.evaluate(() => process.pid);
    const stopped = new Promise(resolve => current.application.process().once('exit', resolve));
    process.kill(mainPid, 'SIGKILL');
    await stopped;
    current = undefined;
    await until(() => !running(mainPid), '本次Electron主进程未退出');
    await until(() => !running(ownedJava), '父进程退出后本次Java未退出');
    ownedJava = null;
    // 旧日志移到独立子目录，下一次只从本次新日志识别进程。
    const archive = path.join(directory, 'old-logs-' + waitForReady);
    fs.renameSync(path.join(directory, 'logs'), archive);
  }
  current = await launchApplication(directory);
  assert.equal(await current.page.evaluate(async () => (await globalThis.fetch('/api/anime/search')).status), 200);
  const backup = await current.page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).json());
  assert.deepEqual(backup.anime.map(anime => anime.data.name), ['虚构崩溃恢复记录']);
  await current.page.evaluate(() => globalThis.performSearch());
  await current.page.getByText('虚构崩溃恢复记录', { exact: true }).first().waitFor();
  assert.ok(fs.existsSync(path.join(directory, 'data', 'otakulog.mv.db')));
  succeeded = true;
});
