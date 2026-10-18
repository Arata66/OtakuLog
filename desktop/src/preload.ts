import { contextBridge, ipcRenderer } from 'electron';
import type { BackupExportResult } from './backup-export';

if (process.isMainFrame) {
  contextBridge.exposeInMainWorld('otakulogDesktop', Object.freeze({
    exportBackup: (): Promise<BackupExportResult> => ipcRenderer.invoke('otakulog:backup-export')
  }));
}
