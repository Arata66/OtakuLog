import { app, BrowserWindow, dialog, session, shell } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { createBackendLaunch, startBackend, type BackendConnection } from './backend';
import { resolveDataRoot } from './paths';

app.setName('OtakuLog');
app.setAppUserModelId('com.arata66.otakulog');
let dataRoot: string;
try {
  dataRoot = resolveDataRoot(process.argv, process.env);
  for (const directory of ['data', 'config', 'secrets', 'backups', 'logs', 'web-profile'])
    fs.mkdirSync(path.join(dataRoot, directory), { recursive: true });
  dataRoot = fs.realpathSync(dataRoot);
  app.setPath('userData', path.join(dataRoot, 'web-profile'));
} catch (error) {
  dialog.showErrorBox('无法打开 OtakuLog', error instanceof Error ? error.message : '无法创建资料目录');
  app.exit(1);
  throw error;
}

let window: BrowserWindow | undefined;
let connection: BackendConnection | undefined;
let starting: Promise<BackendConnection> | undefined;
let allowQuit = false;
let stopping = false;

if (!app.requestSingleInstanceLock()) {
  allowQuit = true;
  app.quit();
} else {
  app.on('second-instance', () => {
    if (window?.isMinimized()) window.restore();
    window?.show();
    window?.focus();
  });
  app.on('before-quit', event => {
    if (allowQuit) return;
    event.preventDefault();
    if (stopping) return;
    // 先让页面完成草稿确认，取消退出后仍需保留可保存的后端。
    if (window && !window.isDestroyed()) {
      window.close();
      return;
    }
    stopping = true;
    void closeBackend();
  });
  void app.whenReady().then(openWindow).catch(showFailure);
}

async function closeBackend() {
  try {
    if (!connection && starting) connection = await starting.catch(() => undefined);
    await connection?.stop();
    allowQuit = true;
    app.quit();
  } catch {
    const result = await dialog.showMessageBox({
      type: 'warning', title: 'OtakuLog 正在退出',
      message: '后端仍在退出，可能有写入尚未完成。',
      detail: '继续等待可以让记录正常保存。强制退出可能丢失正在编辑或写入的内容。',
      buttons: ['继续等待', '强制退出'], defaultId: 0, cancelId: 0
    });
    if (result.response === 1) {
      connection?.child.kill();
      allowQuit = true;
      app.quit();
    } else {
      void closeBackend();
    }
  }
}

async function openWindow() {
  const resources = app.isPackaged ? process.resourcesPath : path.join(app.getAppPath(), 'staging');
  const info = JSON.parse(fs.readFileSync(path.join(resources, 'build-info.json'), 'utf8')) as {
    version: string;
  };
  if (info.version !== app.getVersion()) throw new Error('应用文件与桌面程序版本不同，请重新下载完整程序');
  const profile = session.fromPartition('persist:otakulog');
  profile.setPermissionCheckHandler(() => false);
  profile.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  profile.on('will-download', (_event, item) => {
    item.setSaveDialogOptions({ title: '保存文件', defaultPath: path.join(app.getPath('documents'), item.getFilename()) });
  });
  window = new BrowserWindow({
    title: 'OtakuLog', width: 1280, height: 880, minWidth: 760, minHeight: 560,
    backgroundColor: '#f4f5f7',
    webPreferences: { session: profile, nodeIntegration: false, contextIsolation: true, sandbox: true }
  });
  window.removeMenu();
  window.on('closed', () => {
    window = undefined;
    app.quit();
  });
  window.webContents.on('will-prevent-unload', event => {
    const selected = dialog.showMessageBoxSync({
      type: 'question', title: '退出 OtakuLog',
      message: '还有未保存的内容或正在进行的操作。',
      detail: '继续编辑可以保留当前草稿。退出后，未保存的文字会丢失；已提交的写入仍会等待完成。',
      buttons: ['继续编辑', '放弃草稿并退出'], defaultId: 0, cancelId: 0
    });
    if (selected === 1) event.preventDefault();
  });
  window.webContents.on('will-navigate', (event, target) => {
    if (!connection || new URL(target).origin !== connection.origin) event.preventDefault();
  });
  window.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const target = new URL(url);
      if (target.protocol === 'https:') void shell.openExternal(target.toString());
    } catch {
      // 无效外链保持在应用之外。
    }
    return { action: 'deny' };
  });
  await window.loadFile(path.join(app.getAppPath(), 'src', 'startup.html'));
  if (stopping || allowQuit || !window || window.isDestroyed()) return;
  const logPath = path.join(dataRoot, 'logs', 'desktop-' + Date.now() + '.log');
  let logSize = 0;
  starting = startBackend({
    launch: createBackendLaunch(resources, path.join(dataRoot, 'data')), version: app.getVersion(),
    onLog: text => {
      if (logSize > 2 * 1024 * 1024) return;
      logSize += Buffer.byteLength(text);
      try { fs.appendFileSync(logPath, text, { encoding: 'utf8', mode: 0o600 }); } catch {
        // 日志不可写不能把正常保存的记录报告成失败。
      }
    }
  });
  connection = await starting;
  if (stopping || allowQuit) return;
  const response = await connection.control('bootstrap');
  const cookies = response.headers.getSetCookie();
  const cookie = cookies.map(value => /^JSESSIONID=([^;]+)/.exec(value)).find(Boolean);
  if (!cookie) throw new Error('后端未建立本地会话');
  await profile.cookies.set({
    url: connection.origin, name: 'JSESSIONID', value: cookie[1], path: '/', httpOnly: true, sameSite: 'strict'
  });
  connection.child.once('exit', () => {
    if (!stopping && !allowQuit) void showFailure(new Error('本地服务已退出，请重新打开程序'));
  });
  await window.loadURL(connection.origin);
}

async function showFailure(error: unknown) {
  if (allowQuit || stopping) return;
  const result = await dialog.showMessageBox({
    type: 'error', title: '无法打开 OtakuLog', message: error instanceof Error ? error.message : '启动失败',
    detail: '资料未被自动删除。日志位于：' + path.join(dataRoot, 'logs'),
    buttons: ['重新启动', '退出'], defaultId: 1, cancelId: 1
  });
  if (result.response === 0) app.relaunch();
  app.quit();
}
