import { contextBridge, ipcRenderer } from 'electron';
import type { BackupExportResult } from './backup-export';
import type { Preferences, PreferencesResult } from './settings';

if (process.isMainFrame) {
  contextBridge.exposeInMainWorld('otakulogDesktop', Object.freeze({
    exportBackup: (): Promise<BackupExportResult> => ipcRenderer.invoke('otakulog:backup-export'),
    getPreferences: (): Promise<PreferencesResult> => ipcRenderer.invoke('otakulog:preferences-read'),
    updatePreferences: (patch: Partial<Preferences>): Promise<PreferencesResult> => ipcRenderer.invoke('otakulog:preferences-update', patch)
  }));
}
