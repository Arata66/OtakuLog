import { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, dialog } from 'electron'
import { join } from 'path'
import { readdir } from 'fs/promises'

// 扩展 App 类型以支持自定义属性
interface AppWithCustom extends Electron.App {
  isQuitting?: boolean
}

let mainWindow: BrowserWindow | null = null
let tray: Tray | null = null
const appWithCustom = app as AppWithCustom

const isDev = !app.isPackaged

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'OtakuLog',
    icon: join(__dirname, '../dist/favicon.ico'),
    webPreferences: {
      preload: join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
    // 无地址栏、无菜单栏
    autoHideMenuBar: true,
  })

  // 加载应用
  if (isDev) {
    mainWindow.loadURL('http://localhost:5173')
    mainWindow.webContents.openDevTools()
  } else {
    mainWindow.loadFile(join(__dirname, '../dist/index.html'))
  }

  // 关闭时最小化到托盘
  mainWindow.on('close', (e) => {
    if (!appWithCustom.isQuitting) {
      e.preventDefault()
      mainWindow?.hide()
    }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function createTray() {
  const icon = nativeImage.createFromPath(join(__dirname, '../dist/favicon.ico'))
  tray = new Tray(icon.resize({ width: 16, height: 16 }))

  const contextMenu = Menu.buildFromTemplate([
    {
      label: '显示主窗口',
      click: () => {
        mainWindow?.show()
        mainWindow?.focus()
      },
    },
    { type: 'separator' },
    {
      label: '退出',
      click: () => {
        appWithCustom.isQuitting = true
        app.quit()
      },
    },
  ])

  tray.setToolTip('OtakuLog - 番剧追踪')
  tray.setContextMenu(contextMenu)

  tray.on('double-click', () => {
    mainWindow?.show()
    mainWindow?.focus()
  })
}

// IPC: 文件对话框
ipcMain.handle('dialog:openFile', async (_, options) => {
  if (!mainWindow) return null
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile'],
    filters: options?.filters || [],
  })
  return result.canceled ? null : result.filePaths[0]
})

// IPC: 读取目录
ipcMain.handle('fs:readDir', async (_, dirPath: string) => {
  try {
    const entries = await readdir(dirPath, { withFileTypes: true })
    return entries.map((e) => ({ name: e.name, isDirectory: e.isDirectory() }))
  } catch {
    return []
  }
})

app.whenReady().then(() => {
  createWindow()
  createTray()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    } else {
      mainWindow?.show()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})
