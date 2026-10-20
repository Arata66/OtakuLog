import { contextBridge, ipcRenderer } from 'electron';
import type { BackupExportResult } from './backup-export';
import type { Preferences, PreferencesResult } from './settings';
import type { AiSettingsPatch, AiSettingsResult } from './ai-settings';

if (process.isMainFrame) {
  contextBridge.exposeInMainWorld('otakulogDesktop', Object.freeze({
    openAppMenu: (): Promise<boolean> => ipcRenderer.invoke('otakulog:app-menu'),
    exportBackup: (): Promise<BackupExportResult> => ipcRenderer.invoke('otakulog:backup-export'),
    getPreferences: (): Promise<PreferencesResult> => ipcRenderer.invoke('otakulog:preferences-read'),
    updatePreferences: (patch: Partial<Preferences>): Promise<PreferencesResult> => ipcRenderer.invoke('otakulog:preferences-update', patch),
    getAiSettings: (): Promise<AiSettingsResult> => ipcRenderer.invoke('otakulog:ai-settings-read'),
    updateAiSettings: (patch: AiSettingsPatch): Promise<AiSettingsResult> => ipcRenderer.invoke('otakulog:ai-settings-update', patch)
  }));
  ipcRenderer.on('otakulog:open-ai-settings', () => window.dispatchEvent(new Event('otakulog:open-ai-settings')));
}
