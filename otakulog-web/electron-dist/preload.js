"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
electron_1.contextBridge.exposeInMainWorld('electronAPI', {
    // 打开文件对话框
    openFile: (options) => electron_1.ipcRenderer.invoke('dialog:openFile', options),
    // 读取目录
    readDir: (path) => electron_1.ipcRenderer.invoke('fs:readDir', path),
    // 平台信息
    platform: process.platform,
});
