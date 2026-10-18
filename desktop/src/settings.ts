import type { BrowserWindow, IpcMainInvokeEvent, Rectangle } from 'electron';
import { readFile, open, rename, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { trustedSender } from './backup-export';

export type Preferences = { theme: 'system' | 'light' | 'dark'; accent: string; language: 'zh' | 'en' };
type WindowState = Rectangle & { maximized: boolean };
type Settings = { version: 1; preferences: Preferences; window?: WindowState };
export type PreferencesResult = { ok: true; preferences: Preferences; warning?: string } | { ok: false; message: string };
const defaults = (): Settings => ({ version: 1, preferences: { theme: 'system', accent: 'indigo', language: 'zh' } });
const choices = { theme: ['system', 'light', 'dark'], accent: ['indigo', 'jade', 'violet', 'coral', 'amber', 'rose'], language: ['zh', 'en'] };

function validPreferences(value: unknown, complete = false): value is Partial<Preferences> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const entries = Object.entries(value);
  return entries.length > 0 && (!complete || entries.length === 3) && entries.every(([key, item]) =>
    Object.hasOwn(choices, key) && choices[key as keyof Preferences].includes(item));
}

function validWindow(value: unknown): value is WindowState {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const item = value as WindowState;
  return Object.keys(item).length === 5 && typeof item.maximized === 'boolean'
    && [item.x, item.y, item.width, item.height].every(Number.isInteger)
    && Math.abs(item.x) <= 100000 && Math.abs(item.y) <= 100000
    && item.width >= 320 && item.width <= 10000 && item.height >= 240 && item.height <= 10000;
}

async function writeSettings(filePath: string, settings: Settings) {
  const temporary = path.join(path.dirname(filePath), '.otakulog-settings-' + randomUUID() + '.tmp');
  let created = false;
  try {
    const file = await open(temporary, 'wx', 0o600); created = true;
    try { await file.writeFile(JSON.stringify(settings, null, 2) + '\n'); await file.sync(); }
    finally { await file.close(); }
    await rename(temporary, filePath); created = false;
  } finally { if (created) await unlink(temporary).catch(() => {}); }
}

export async function createSettingsStore(filePath: string) {
  let current = defaults(), warning: string | undefined, tail = Promise.resolve();
  try {
    const parsed = JSON.parse(await readFile(filePath, 'utf8'));
    if (parsed?.version !== 1 || !validPreferences(parsed.preferences, true)
      || Object.keys(parsed).some(key => !['version', 'preferences', 'window'].includes(key))
      || (parsed.window !== undefined && !validWindow(parsed.window))) throw new Error();
    current = parsed;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
      warning = '桌面设置无法读取，已使用默认设置并保留原文件。请从菜单打开资料目录检查设置文件。';
  }
  const update = (change: (state: Settings) => Settings) => {
    const saved = tail.then(async () => {
      if (warning) throw new Error(warning);
      const next = change(current);
      await writeSettings(filePath, next); current = next;
    });
    // 单次写入失败不能阻塞后续操作；仅成功落盘后更新已保存快照。
    tail = saved.catch(() => {});
    return saved;
  };
  return {
    preferences: () => ({ ...current.preferences }),
    window: () => current.window && { ...current.window },
    warning: () => warning,
    flush: () => tail,
    updatePreferences(patch: unknown) {
      if (!validPreferences(patch)) return Promise.reject(new Error('只允许保存有效的主题、主题色和语言'));
      const accepted = { ...patch };
      return update(state => ({ ...state, preferences: { ...state.preferences, ...accepted } }));
    },
    updateWindow(bounds: WindowState) {
      if (!validWindow(bounds)) return Promise.reject(new Error('窗口位置无效'));
      const accepted = { ...bounds };
      return update(state => ({ ...state, window: accepted }));
    }
  };
}

export function restoreWindowBounds(saved: Rectangle | undefined, workAreas: Rectangle[]): Rectangle {
  const primary = workAreas[0] || { x: 0, y: 0, width: 1280, height: 880 };
  let area = primary, overlap = 0;
  if (saved) for (const candidate of workAreas) {
    const visible = Math.max(0, Math.min(saved.x + saved.width, candidate.x + candidate.width) - Math.max(saved.x, candidate.x))
      * Math.max(0, Math.min(saved.y + saved.height, candidate.y + candidate.height) - Math.max(saved.y, candidate.y));
    if (visible > overlap) { area = candidate; overlap = visible; }
  }
  const width = Math.min(area.width, Math.max(760, saved?.width || 1280));
  const height = Math.min(area.height, Math.max(560, saved?.height || 880));
  const x = saved && overlap ? saved.x : area.x + Math.round((area.width - width) / 2);
  const y = saved && overlap ? saved.y : area.y + Math.round((area.height - height) / 2);
  return { x: Math.max(area.x, Math.min(x, area.x + area.width - width)),
    y: Math.max(area.y, Math.min(y, area.y + area.height - height)), width, height };
}

export function createPreferencesBridge(options: {
  getWindow: () => BrowserWindow | undefined; getOrigin: () => string | undefined;
  store: Awaited<ReturnType<typeof createSettingsStore>>;
}) {
  const denied = (): PreferencesResult => ({ ok: false, message: '当前页面无法访问桌面设置，请重新打开程序' });
  const allowed = (event: IpcMainInvokeEvent) => trustedSender(event, options.getWindow(), options.getOrigin());
  return {
    read(event: IpcMainInvokeEvent, ...args: unknown[]): PreferencesResult {
      if (args.length || !allowed(event)) return denied();
      return { ok: true, preferences: options.store.preferences(), warning: options.store.warning() };
    },
    async update(event: IpcMainInvokeEvent, patch: unknown, ...args: unknown[]): Promise<PreferencesResult> {
      if (args.length || !allowed(event)) return denied();
      try { await options.store.updatePreferences(patch); return { ok: true, preferences: options.store.preferences() }; }
      catch { return { ok: false, message: '设置未能保存，本次选择仅在当前窗口生效。请检查设置文件和资料目录的写入权限。' }; }
    }
  };
}
