"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const path_1 = require("path");
const promises_1 = require("fs/promises");
let mainWindow = null;
let tray = null;
const appWithCustom = electron_1.app;
const isDev = !electron_1.app.isPackaged;
function createWindow() {
    mainWindow = new electron_1.BrowserWindow({
        width: 1200,
        height: 800,
        minWidth: 800,
        minHeight: 600,
        title: 'OtakuLog',
        icon: (0, path_1.join)(__dirname, '../dist/favicon.ico'),
        webPreferences: {
            preload: (0, path_1.join)(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        },
        // 无地址栏、无菜单栏
        autoHideMenuBar: true,
    });
    // 加载应用
    if (isDev) {
        mainWindow.loadURL('http://localhost:5173');
        mainWindow.webContents.openDevTools();
    }
    else {
        mainWindow.loadFile((0, path_1.join)(__dirname, '../dist/index.html'));
    }
    // 关闭时最小化到托盘
    mainWindow.on('close', (e) => {
        if (!appWithCustom.isQuitting) {
            e.preventDefault();
            mainWindow?.hide();
        }
    });
    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}
function createTray() {
    const icon = electron_1.nativeImage.createFromPath((0, path_1.join)(__dirname, '../dist/favicon.ico'));
    tray = new electron_1.Tray(icon.resize({ width: 16, height: 16 }));
    const contextMenu = electron_1.Menu.buildFromTemplate([
        {
            label: '显示主窗口',
            click: () => {
                mainWindow?.show();
                mainWindow?.focus();
            },
        },
        { type: 'separator' },
        {
            label: '退出',
            click: () => {
                appWithCustom.isQuitting = true;
                electron_1.app.quit();
            },
        },
    ]);
    tray.setToolTip('OtakuLog - 番剧追踪');
    tray.setContextMenu(contextMenu);
    tray.on('double-click', () => {
        mainWindow?.show();
        mainWindow?.focus();
    });
}
// IPC: 文件对话框
electron_1.ipcMain.handle('dialog:openFile', async (_, options) => {
    if (!mainWindow)
        return null;
    const result = await electron_1.dialog.showOpenDialog(mainWindow, {
        properties: ['openFile'],
        filters: options?.filters || [],
    });
    return result.canceled ? null : result.filePaths[0];
});
// IPC: 读取目录
electron_1.ipcMain.handle('fs:readDir', async (_, dirPath) => {
    try {
        const entries = await (0, promises_1.readdir)(dirPath, { withFileTypes: true });
        return entries.map((e) => ({ name: e.name, isDirectory: e.isDirectory() }));
    }
    catch {
        return [];
    }
});
electron_1.app.whenReady().then(() => {
    createWindow();
    createTray();
    electron_1.app.on('activate', () => {
        if (electron_1.BrowserWindow.getAllWindows().length === 0) {
            createWindow();
        }
        else {
            mainWindow?.show();
        }
    });
});
electron_1.app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        electron_1.app.quit();
    }
});
