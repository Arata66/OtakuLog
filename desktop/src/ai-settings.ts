import type { BrowserWindow, IpcMainInvokeEvent } from 'electron';
import { open, readFile, rename, stat, unlink } from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { trustedSender } from './backup-export';
import { BackendControlError, type BackendConnection } from './backend';

type Configuration = { enabled: boolean; apiKey: string; dailyLimit: number };
type Stored = { version: 1; enabled: boolean; dailyLimit: number; encryptedKey: string | null };
export type AiSettingsPatch = { enabled: boolean; dailyLimit: number; keyAction: 'keep' | 'replace' | 'clear'; apiKey?: string };
type State = { enabled: boolean; hasKey: boolean; dailyLimit: number; storage: 'ready' | 'unavailable' | 'unreadable'; warning?: string };
export type AiSettingsResult = { ok: true; settings: State } | { ok: false; message: string };
type Encryption = { isEncryptionAvailable: () => boolean; encryptString: (value: string) => Buffer; decryptString: (value: Buffer) => string };
const defaults = (): Configuration => ({ enabled: false, apiKey: '', dailyLimit: 20 });
const validKey = (key: unknown): key is string => typeof key === 'string' && key.length <= 512 && !!key.trim()
  && [...key].every(character => { const code = character.charCodeAt(0); return code >= 32 && (code < 127 || code > 159); });

class InputError extends Error {}

async function writeAtomic(filePath: string, bytes: Buffer | undefined) {
  if (bytes === undefined) { await unlink(filePath).catch(error => { if (error.code !== 'ENOENT') throw error; }); return; }
  const temporary = path.join(path.dirname(filePath), '.otakulog-ai-' + randomUUID() + '.tmp');
  let created = false;
  try {
    const handle = await open(temporary, 'wx', 0o600); created = true;
    try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
    await rename(temporary, filePath); created = false;
  } finally { if (created) await unlink(temporary).catch(() => {}); }
}

export async function createAiSettingsStore(filePath: string, encryption: Encryption) {
  let current = defaults(), encryptedKey: string | null = null, bytes: Buffer | undefined, unreadable = false;
  try {
    if ((await stat(filePath)).size > 16384) throw new Error();
    bytes = await readFile(filePath);
    const value = JSON.parse(bytes.toString('utf8')) as Stored;
    if (!value || value.version !== 1 || Object.keys(value).length !== 4 || typeof value.enabled !== 'boolean'
      || !Number.isInteger(value.dailyLimit) || value.dailyLimit < 0 || value.dailyLimit > 1000
      || !(value.encryptedKey === null || (typeof value.encryptedKey === 'string'
        && value.encryptedKey.length <= 8192 && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value.encryptedKey)))) throw new Error();
    encryptedKey = value.encryptedKey;
    current.dailyLimit = value.dailyLimit;
    if (encryptedKey !== null) {
      if (!encryption.isEncryptionAvailable()) throw new Error();
      const key = encryption.decryptString(Buffer.from(encryptedKey, 'base64'));
      if (!validKey(key)) throw new Error();
      current = { enabled: value.enabled, apiKey: key, dailyLimit: value.dailyLimit };
    } else if (value.enabled) throw new Error();
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') { unreadable = true; current.enabled = false; current.apiKey = ''; }
  }
  const read = (): State => {
    const available = encryption.isEncryptionAvailable();
    return { enabled: available && !unreadable && current.enabled, hasKey: available && !unreadable && !!current.apiKey,
      dailyLimit: current.dailyLimit, storage: available ? unreadable ? 'unreadable' : 'ready' : 'unavailable',
      ...(!available ? { warning: '系统加密暂不可用，AI 已关闭。可以继续记番；恢复系统加密后重新打开，或清除原密钥。' }
        : unreadable ? { warning: 'AI 配置无法读取或密钥无法解密，已关闭 AI 并保留原文件。请重新输入密钥或明确清除。' } : {}) };
  };
  return {
    read,
    configuration: (): Configuration => read().storage === 'ready' ? { ...current } : { ...defaults(), dailyLimit: current.dailyLimit },
    checkpoint: () => ({ current: { ...current }, encryptedKey, bytes, unreadable }),
    prepare(input: unknown) {
      if (unreadable && bytes === undefined) throw new InputError('原 AI 配置无法完整读取，已保留原文件。请先检查资料目录中的 secrets/ai.json。');
      if (!input || typeof input !== 'object' || Array.isArray(input)) throw new InputError('AI 设置格式无效');
      const value = input as AiSettingsPatch;
      if (typeof value.enabled !== 'boolean' || !Number.isInteger(value.dailyLimit) || value.dailyLimit < 0 || value.dailyLimit > 1000
        || !['keep', 'replace', 'clear'].includes(value.keyAction)
        || Object.keys(value).length !== (value.keyAction === 'replace' ? 4 : 3)
        || Object.keys(value).some(key => !['enabled', 'dailyLimit', 'keyAction', 'apiKey'].includes(key))
        || (value.keyAction !== 'replace' && Object.hasOwn(value, 'apiKey'))) throw new InputError('只允许修改 AI 开关、密钥和每日次数（0 至 1000）');
      let key = current.apiKey, cipher = encryptedKey;
      if (value.keyAction === 'replace') {
        if (!validKey(value.apiKey)) throw new InputError('请输入有效密钥，最长 512 字符且不能包含控制字符');
        if (!encryption.isEncryptionAvailable()) throw new InputError('系统加密不可用，未保存密钥');
        key = value.apiKey.trim();
        cipher = encryption.encryptString(key).toString('base64');
      } else if (value.keyAction === 'clear') { key = ''; cipher = null; }
      else if (unreadable || (!encryption.isEncryptionAvailable() && cipher !== null)) throw new InputError('原密钥无法读取，请重新输入或明确清除');
      if (value.enabled && !key) throw new InputError('请先输入自己的 API Key，或关闭 AI');
      const configuration = { enabled: value.enabled, apiKey: key, dailyLimit: value.dailyLimit };
      const stored: Stored = { version: 1, enabled: value.enabled, dailyLimit: value.dailyLimit, encryptedKey: cipher };
      return { configuration, stored, bytes: Buffer.from(JSON.stringify(stored, null, 2) + '\n') };
    },
    async commit(next: { configuration: Configuration; stored: Stored; bytes: Buffer }) {
      await writeAtomic(filePath, next.bytes);
      current = { ...next.configuration }; encryptedKey = next.stored.encryptedKey; bytes = next.bytes; unreadable = false;
    },
    async restore(previous: { current: Configuration; encryptedKey: string | null; bytes?: Buffer; unreadable: boolean }) {
      await writeAtomic(filePath, previous.bytes);
      current = { ...previous.current }; encryptedKey = previous.encryptedKey; bytes = previous.bytes; unreadable = previous.unreadable;
    }
  };
}

export function createAiSettingsBridge(options: {
  store: Awaited<ReturnType<typeof createAiSettingsStore>>;
  getWindow: () => BrowserWindow | undefined; getOrigin: () => string | undefined;
  control: BackendConnection['control']; onSyncFailure: () => Promise<void>; onIdle?: () => void;
}) {
  let busy = false;
  const denied = (): AiSettingsResult => ({ ok: false, message: '当前页面无法访问 AI 设置，请重新打开程序' });
  const allowed = (event: IpcMainInvokeEvent) => trustedSender(event, options.getWindow(), options.getOrigin());
  return {
    isBusy: () => busy,
    read(event: IpcMainInvokeEvent, ...args: unknown[]): AiSettingsResult {
      return args.length || !allowed(event) ? denied() : { ok: true, settings: options.store.read() };
    },
    async update(event: IpcMainInvokeEvent, patch: unknown, ...args: unknown[]): Promise<AiSettingsResult> {
      if (args.length || !allowed(event)) return denied();
      if (busy) return { ok: false, message: 'AI 设置正在保存，请稍后再试' };
      busy = true;
      let locked = false, persisted = false, submitted = false;
      const previous = options.store.checkpoint();
      try {
        const next = options.store.prepare(patch);
        await options.control('ai-settings-lock', {}); locked = true;
        await options.store.commit(next); persisted = true;
        submitted = true;
        await options.control('ai-settings', next.configuration);
        return { ok: true, settings: options.store.read() };
      } catch (error) {
        let uncertain = submitted;
        if (persisted) try { await options.store.restore(previous); } catch { uncertain = true; }
        const refused = !locked && error instanceof BackendControlError && error.status === 409;
        if (!submitted && !(error instanceof InputError) && !refused) {
          try { await options.control('ai-settings-cancel', {}); } catch { uncertain = true; }
        }
        // 同步结果未知时停止后端，避免继续使用可能与磁盘不同的密钥。
        if (uncertain) await options.onSyncFailure();
        return { ok: false, message: uncertain ? 'AI 设置同步未完成，已停止后端。请重启后核对配置，原资料保留。'
          : error instanceof InputError ? error.message
          : !locked ? 'AI 调用或设置保存进行中，请稍后重试；若后端已退出，请重新打开程序。'
          : 'AI 设置未能保存，请检查资料目录的写入权限，原配置保留。' };
      } finally { busy = false; options.onIdle?.(); }
    }
  };
}
