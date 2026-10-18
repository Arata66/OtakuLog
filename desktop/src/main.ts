import { app, BrowserWindow, dialog, ipcMain, Menu, screen, session, shell } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { createBackendLaunch, startBackend, type BackendConnection } from './backend';
import { resolveDataRoot } from './paths';
import { BACKUP_EXPORT_CHANNEL, createBackupExporter } from './backup-export';
import { createPreferencesBridge, createSettingsStore, restoreWindowBounds } from './settings';

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
let failureShown = false;
let restartRequested = false;
let faultQuitRequested = false;
let settings: Awaited<ReturnType<typeof createSettingsStore>> | undefined;

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
    await settings?.flush();
    if (!connection && starting) connection = await starting.catch(() => undefined);
    await connection?.stop();
    if (restartRequested) app.relaunch();
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
      restartRequested = false;
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
  // 随机端口仍可能复用旧站点缓存，只清除桌面专用会话的工作线程与缓存。
  await profile.clearStorageData({ storages: ['serviceworkers', 'cachestorage'] });
  if (stopping || allowQuit) return;
  profile.setPermissionCheckHandler(() => false);
  profile.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  profile.on('will-download', (_event, item) => {
    item.setSaveDialogOptions({ title: '保存文件', defaultPath: path.join(app.getPath('documents'), item.getFilename()) });
  });
  settings = await createSettingsStore(path.join(dataRoot, 'config', 'settings.json'));
  if (stopping || allowQuit) return;
  const primary = screen.getPrimaryDisplay();
  const bounds = restoreWindowBounds(settings.window(), [primary.workArea,
    ...screen.getAllDisplays().filter(display => display.id !== primary.id).map(display => display.workArea)]);
  window = new BrowserWindow({
    title: 'OtakuLog', ...bounds, minWidth: Math.min(760, bounds.width), minHeight: Math.min(560, bounds.height),
    backgroundColor: '#f4f5f7',
    webPreferences: { session: profile, preload: path.join(app.getAppPath(), 'dist', 'preload.js'),
      nodeIntegration: false, contextIsolation: true, sandbox: true }
  });
  const preferences = createPreferencesBridge({ getWindow: () => window,
    getOrigin: () => !stopping && !allowQuit && !failureShown ? connection?.origin : undefined, store: settings });
  ipcMain.handle('otakulog:preferences-read', (event, ...args) => preferences.read(event, ...args));
  ipcMain.handle('otakulog:preferences-update', (event, patch, ...args) => preferences.update(event, patch, ...args));
  const exporter = createBackupExporter({
    getWindow: () => window,
    getOrigin: () => !stopping && !allowQuit && !failureShown ? connection?.origin : undefined,
    onIdle: () => {
      // 只延续用户已确认的故障退出，普通关闭被阻止后仍保留窗口。
      if (faultQuitRequested && !stopping && !allowQuit) app.quit();
    },
    sessionCookie: async origin => (await profile.cookies.get({ url: origin + '/api/anime/export' }))
      .find(cookie => cookie.name === 'JSESSIONID')?.value,
    choosePath: async owner => {
      const result = await dialog.showSaveDialog(owner, { title: '保存完整备份',
        defaultPath: path.join(app.getPath('documents'), 'otakulog_backup.json'),
        filters: [{ name: 'JSON 备份', extensions: ['json'] }],
        properties: ['showOverwriteConfirmation', 'createDirectory'] });
      return result.canceled ? undefined : result.filePath;
    }
  });
  ipcMain.handle(BACKUP_EXPORT_CHANNEL, (event, ...args) => exporter.exportBackup(event, ...args));
  let captureTimer: ReturnType<typeof setTimeout> | undefined, windowWarningShown = false;
  let persistedBounds = bounds, observedBounds = window.getNormalBounds();
  const captureWindow = () => {
    if (!window || window.isDestroyed() || window.isMinimized()) return;
    const actual = window.getNormalBounds();
    // Windows分数缩放下读写边界不对称，未调整的窗口沿用原尺寸，避免每次重开累积放大。
    if (actual.x !== observedBounds.x || actual.y !== observedBounds.y
      || actual.width !== observedBounds.width || actual.height !== observedBounds.height) persistedBounds = actual;
    observedBounds = actual;
    void settings!.updateWindow({ ...persistedBounds, maximized: window.isMaximized() }).catch(() => {
      if (windowWarningShown || settings!.warning()) return;
      windowWarningShown = true;
      void dialog.showMessageBox({ type: 'warning', title: '窗口设置未保存',
        message: '窗口位置未能保存，请检查资料目录的写入权限。', buttons: ['知道了'] });
    });
  };
  const queueCapture = () => {
    clearTimeout(captureTimer); captureTimer = setTimeout(captureWindow, 300);
  };
  window.on('resize', queueCapture); window.on('move', queueCapture);
  window.on('maximize', queueCapture); window.on('unmaximize', queueCapture);
  window.on('close', event => {
    if (exporter.isBusy()) { event.preventDefault(); return; }
    clearTimeout(captureTimer); captureWindow();
  });
  const openDirectory = async (directory: string) => {
    const error = await shell.openPath(directory).catch(() => '无法打开目录');
    if (error) await dialog.showMessageBox({ type: 'warning', title: '无法打开目录', message: '无法打开资料目录，请检查目录是否存在及访问权限。', buttons: ['知道了'] });
  };
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { label: '文件', submenu: [
      { label: '打开资料目录', click: () => openDirectory(dataRoot) },
      { label: '打开数据目录', click: () => openDirectory(path.join(dataRoot, 'data')) },
      { label: '打开日志目录', click: () => openDirectory(path.join(dataRoot, 'logs')) },
      { type: 'separator' }, { label: '退出', role: 'quit' }
    ] },
    { label: '编辑', submenu: [
      { label: '撤销', role: 'undo' }, { label: '重做', role: 'redo' }, { type: 'separator' },
      { label: '剪切', role: 'cut' }, { label: '复制', role: 'copy' }, { label: '粘贴', role: 'paste' },
      { label: '全选', role: 'selectAll' }
    ] },
    { label: '视图', submenu: [
      { label: '实际大小', role: 'resetZoom' }, { label: '放大', role: 'zoomIn' }, { label: '缩小', role: 'zoomOut' }
    ] },
    { label: '帮助', submenu: [{ label: '关于 OtakuLog', click: () => {
      void dialog.showMessageBox({ type: 'info', title: '关于 OtakuLog', message: 'OtakuLog ' + app.getVersion(),
        detail: '资料保存在本机。跨电脑迁移请使用完整 JSON 备份。', buttons: ['知道了'] });
    } }] }
  ]));
  observedBounds = window.getNormalBounds();
  if (settings.window()?.maximized) window.maximize();
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
    else { restartRequested = false; faultQuitRequested = false; }
  });
  window.webContents.on('render-process-gone', () => {
    if (!stopping && !allowQuit) void showFailure(new Error('应用页面意外退出，请重新打开程序'));
  });
  window.webContents.on('will-navigate', (event, target) => {
    if (!connection || new URL(target).origin !== connection.origin) event.preventDefault();
  });
  window.webContents.on('will-redirect', (event, target) => {
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
  if (allowQuit || stopping || failureShown) return;
  failureShown = true;
  const result = await dialog.showMessageBox({
    type: 'error', title: '无法打开 OtakuLog', message: error instanceof Error ? error.message : '启动失败',
    detail: '资料未被自动删除。日志位于：' + path.join(dataRoot, 'logs'),
    buttons: ['重新启动', '退出'], defaultId: 1, cancelId: 1
  });
  // 旧后端停止后才安排重新启动，避免同一个资料库同时被两个进程打开。
  restartRequested = result.response === 0;
  faultQuitRequested = true;
  app.quit();
}
