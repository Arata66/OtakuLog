const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { expect } = require('@playwright/test');
const { launchApplication, applicationLaunchOptions, openAddForm } = require('./helpers/application.cjs');

async function until(read, message, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await delay(100);
  }
  throw new Error(message);
}
function alive(pid) {
  try { process.kill(pid, 0); return true; }
  catch (error) { if (error.code === 'ESRCH') return false; throw error; }
}
function latestLog(directory) {
  const folder = path.join(directory, 'logs');
  const latest = fs.readdirSync(folder).filter(name => /^desktop-\d+\.log$/.test(name)).sort().at(-1);
  return latest ? fs.readFileSync(path.join(folder, latest), 'utf8') : '';
}
function javaPid(directory) {
  const value = /with PID (\d+)/.exec(latestLog(directory));
  return value && Number(value[1]);
}
function isolated(t) {
  const state = { directory: fs.mkdtempSync(path.join(os.tmpdir(), 'otakulog-launch-cases-')), pids: new Set(), succeeded: false };
  t.after(async () => {
    await state.current?.application.evaluate(({ app, dialog }) => { app.__finishFault?.({ response: 1 }); dialog.showMessageBoxSync = () => 1; }).catch(() => {});
    await state.current?.application.close().catch(() => {});
    for (const pid of state.pids) await until(() => !alive(pid), '本次Java仍在运行，保留目录：' + state.directory);
    if (!state.succeeded) { console.error('故障样例保留：' + state.directory); return; }
    fs.rmSync(state.directory, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
  });
  return state;
}
async function secondLaunch(directory) {
  const options = applicationLaunchOptions(directory), child = spawn(options.executablePath, options.args,
    { env: options.env, windowsHide: true, stdio: 'ignore' });
  try {
    const exit = new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', code => resolve(code)); });
    return await Promise.race([exit, delay(10000).then(() => { throw new Error('第二个实例未正常退出'); })]);
  } finally { if (child.exitCode === null && child.signalCode === null) child.kill(); }
}
async function listening8080() {
  return new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port: 8080 });
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('error', () => { socket.destroy(); resolve(false); });
    socket.setTimeout(1000, () => { socket.destroy(); resolve(false); });
  });
}

test('当8080已占用且启动中或运行中重复打开时应该唤醒原窗口且仅保留一个后端', { timeout: 120000 }, async t => {
  const state = isolated(t), occupied = net.createServer(socket => socket.end());
  let owned = false;
  try {
    await new Promise((resolve, reject) => { occupied.once('error', reject); occupied.listen(8080, '127.0.0.1', resolve); });
    owned = true;
  } catch (error) { if (error.code !== 'EADDRINUSE') throw error; }
  t.after(async () => { if (owned) await new Promise(resolve => occupied.close(resolve)); });
  assert.equal(await listening8080(), true);
  state.current = await launchApplication(state.directory, undefined, { waitForReady: false });
  const { application, page } = state.current;
  const original = await application.evaluate(({ app }) => { app.__duplicates = 0; app.on('second-instance', () => { app.__duplicates++; }); return process.pid; });
  assert.match(page.url(), /^file:/);
  assert.doesNotMatch(latestLog(state.directory), /Started OtakuLogApplication/);
  assert.equal(await secondLaunch(state.directory), 0);
  await expect.poll(() => application.evaluate(({ app }) => app.__duplicates)).toBe(1);
  await page.waitForURL(/^http:\/\/127\.0\.0\.1:\d+\/$/, { timeout: 90000 });
  await page.locator('#animeName').waitFor({ state: 'attached' });
  const backend = await until(() => javaPid(state.directory), '未找到本次Java'); state.pids.add(backend);
  assert.notEqual(new URL(page.url()).port, '8080');
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].minimize());
  await expect.poll(() => application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isMinimized())).toBe(true);
  assert.equal(await secondLaunch(state.directory), 0);
  await expect.poll(() => application.evaluate(({ app }) => app.__duplicates)).toBe(2);
  await expect.poll(() => application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].isMinimized())).toBe(false);
  assert.equal(await application.evaluate(() => process.pid), original);
  assert.equal(await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length), 1);
  assert.equal(fs.readdirSync(path.join(state.directory, 'logs')).filter(name => /^desktop-\d+\.log$/.test(name)).length, 1);
  assert.equal(javaPid(state.directory), backend);
  assert.equal(await page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).status), 200);
  await application.close(); state.current = undefined;
  await until(() => !alive(backend), '关闭后本次Java未退出');
  assert.equal(await listening8080(), true, '关闭应用不能停止8080上的其他服务');
  state.succeeded = true;
});

async function heldFailure(application) {
  await application.evaluate(({ app, dialog }) => {
    app.__faults = [];
    dialog.showMessageBox = options => { app.__faults.push(options); return new Promise(resolve => { app.__finishFault = resolve; }); };
    dialog.showMessageBoxSync = () => 1;
  });
}
async function leaveFailure(state) {
  const { application } = state.current;
  const exited = new Promise(resolve => application.once('close', resolve));
  await application.evaluate(({ app }) => { setTimeout(() => app.__finishFault({ response: 1 }), 50); });
  await exited; state.current = undefined;
}

test('当后端在就绪前或业务运行中退出时应该提示一次并拒绝假保存且允许原资料重开', { timeout: 180000 }, async t => {
  const state = isolated(t);
  state.current = await launchApplication(state.directory, undefined, {
    waitForReady: false, configure: async application => heldFailure(application)
  });
  let backend = await until(() => javaPid(state.directory), '启动期未找到本次Java'); state.pids.add(backend);
  assert.doesNotMatch(latestLog(state.directory), /Started OtakuLogApplication/);
  process.kill(backend, 'SIGKILL');
  await expect.poll(() => state.current.application.evaluate(({ app }) => app.__faults.length)).toBe(1);
  assert.match(state.current.page.url(), /^file:/);
  assert.match(await state.current.application.evaluate(({ app }) => app.__faults[0].message), /后端/);
  await leaveFailure(state);
  await until(() => !alive(backend), '启动失败后本次Java未退出');
  assert.equal(fs.existsSync(state.directory), true);

  state.current = await launchApplication(state.directory); await heldFailure(state.current.application);
  const { application, page } = state.current;
  backend = await until(() => javaPid(state.directory), '运行期未找到本次Java'); state.pids.add(backend);
  await openAddForm(page);
  await page.locator('#animeName').fill('虚构后端故障保留记录'); await page.locator('#totalEpisodes').fill('12');
  await page.locator('#animeSeason').fill('2026秋'); await page.locator('#animeScore').fill('8');
  await page.locator('#animeStatus').selectOption('planning');
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/api/anime/add' && response.request().method() === 'POST');
  await page.locator('#animeForm button[type="submit"]').click();
  assert.equal((await (await saved).json()).code, 200);
  const before = await page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).json());
  assert.deepEqual(before.anime.map(entry => entry.data.name), ['虚构后端故障保留记录']);
  await page.evaluate(() => {
    globalThis.__faultMessages = [];
    const originalToast = globalThis.toast;
    globalThis.toast = (message, type) => { globalThis.__faultMessages.push({ message, type }); originalToast(message, type); };
  });
  process.kill(backend, 'SIGKILL');
  await expect.poll(() => application.evaluate(({ app }) => app.__faults.length)).toBe(1);
  assert.equal((await page.evaluate(() => globalThis.otakulogDesktop.exportBackup())).status, 'failed');
  await openAddForm(page);
  await page.locator('#animeName').fill('虚构不能保存的记录');
  await page.locator('#totalEpisodes').fill('12'); await page.locator('#animeSeason').fill('2026秋');
  await page.locator('#animeScore').fill('8'); await page.locator('#animeStatus').selectOption('planning');
  const refused = page.waitForEvent('requestfailed', request => new URL(request.url()).pathname === '/api/anime/add' && request.method() === 'POST');
  await page.locator('#animeForm button[type="submit"]').click();
  await refused;
  await expect.poll(() => page.evaluate(() => globalThis.__faultMessages.length)).toBeGreaterThan(0);
  assert.equal(await page.evaluate(() => globalThis.__faultMessages.some(message => message.type === 'success')), false);
  assert.equal(await page.locator('#animeName').inputValue(), '虚构不能保存的记录');
  assert.equal(await application.evaluate(({ app }) => app.__faults.length), 1);
  await leaveFailure(state); await until(() => !alive(backend), '故障退出后本次Java未结束');
  state.current = await launchApplication(state.directory);
  backend = await until(() => javaPid(state.directory), '重新打开未找到本次Java'); state.pids.add(backend);
  const restored = await state.current.page.evaluate(async () => (await globalThis.fetch('/api/anime/export')).json());
  assert.deepEqual(restored.anime.map(entry => entry.data.name), ['虚构后端故障保留记录']);
  await state.current.page.getByText('虚构后端故障保留记录', { exact: true }).first().waitFor();
  state.succeeded = true;
});
