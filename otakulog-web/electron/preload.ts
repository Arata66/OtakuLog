import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('electronAPI', {
  // 打开文件对话框
  openFile: (options?: { filters?: { name: string; extensions: string[] }[] }) =>
    ipcRenderer.invoke('dialog:openFile', options),

  // 读取目录
  readDir: (path: string) => ipcRenderer.invoke('fs:readDir', path),

  // 平台信息
  platform: process.platform,
})
