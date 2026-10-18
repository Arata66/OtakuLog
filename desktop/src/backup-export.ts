import type { BrowserWindow, IpcMainInvokeEvent } from 'electron';
import { request } from 'node:http';
import { open, rename, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import path from 'node:path';

export const BACKUP_EXPORT_CHANNEL = 'otakulog:backup-export';
export type BackupExportResult = { status: 'saved' | 'cancelled' } | { status: 'failed'; message: string };

export function trustedSender(event: IpcMainInvokeEvent, owner: BrowserWindow | undefined, origin: string | undefined) {
  try {
    if (!owner || owner.isDestroyed() || !origin || event.sender !== owner.webContents
      || !event.senderFrame || event.senderFrame !== owner.webContents.mainFrame) return false;
    const url = new URL(event.senderFrame.url);
    return url.origin === origin && url.pathname === '/';
  } catch { return false; }
}

export function readLocalBackup(origin: string, sessionCookie: string): Promise<Buffer> {
  const url = new URL(origin);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.origin !== origin)
    return Promise.reject(new Error('本地服务地址无效'));
  return new Promise((resolve, reject) => {
    // 业务只读取已握手的固定接口，Cookie来自专用会话，不使用控制凭据或通用fetch。
    const call = request({ hostname: '127.0.0.1', port: url.port, path: '/api/anime/export',
      method: 'GET', signal: AbortSignal.timeout(30000),
      headers: { Cookie: 'JSESSIONID=' + sessionCookie, Accept: 'application/json' }
    }, response => {
      response.on('error', reject);
      if (response.statusCode !== 200 || !/^application\/json(?:;|$)/i.test(response.headers['content-type'] || '')) {
        response.destroy(); reject(new Error('读取完整备份失败')); return;
      }
      const chunks: Buffer[] = [];
      response.on('data', (chunk: Buffer) => chunks.push(chunk));
      response.on('end', () => {
        try {
          const bytes = Buffer.concat(chunks), document = JSON.parse(bytes.toString('utf8'));
          if (document?.format !== 'otakulog-backup' || !Number.isInteger(document.version)
            || document.version < 1 || !Array.isArray(document.anime)) throw new Error();
          // 只识别备份响应，保存原始字节，不重组或裁剪业务字段。
          resolve(bytes);
        } catch { reject(new Error('本地服务未返回完整备份')); }
      });
    });
    call.on('error', reject); call.end();
  });
}

async function saveBackupFile(destination: string, bytes: Buffer) {
  const temporary = path.join(path.dirname(destination), '.otakulog-' + randomUUID() + '.tmp');
  let created = false;
  try {
    const file = await open(temporary, 'wx', 0o600);
    created = true;
    try { await file.writeFile(bytes); await file.sync(); }
    finally { await file.close(); }
    // 文件完整写好才替换，写入失败时保留用户已有备份。
    await rename(temporary, destination);
    created = false;
  } finally {
    if (created) await unlink(temporary).catch(() => {});
  }
}

export function createBackupExporter(options: {
  getWindow: () => BrowserWindow | undefined;
  getOrigin: () => string | undefined;
  sessionCookie: (origin: string) => Promise<string | undefined>;
  choosePath: (owner: BrowserWindow) => Promise<string | undefined>;
  onIdle?: () => void;
}) {
  let busy = false;
  const denied = (): BackupExportResult => ({ status: 'failed', message: '当前页面无法导出备份，请重新打开程序' });
  return {
    isBusy: () => busy,
    async exportBackup(event: IpcMainInvokeEvent, ...args: unknown[]): Promise<BackupExportResult> {
      const owner = options.getWindow(), origin = options.getOrigin();
      if (args.length || !trustedSender(event, owner, origin)) return denied();
      const stillTrusted = () => origin === options.getOrigin() && owner === options.getWindow()
        && trustedSender(event, owner, origin);
      if (busy) return { status: 'failed', message: '备份正在导出，请等待完成' };
      busy = true;
      try {
        const destination = await options.choosePath(owner!);
        if (!destination) return { status: 'cancelled' };
        if (!stillTrusted()) return denied();
        const cookie = await options.sessionCookie(origin!);
        if (!stillTrusted()) return denied();
        if (!cookie) return { status: 'failed', message: '本地会话已失效，请重新启动 OtakuLog' };
        const bytes = await readLocalBackup(origin!, cookie);
        if (!stillTrusted()) return denied();
        await saveBackupFile(destination, bytes);
        return { status: 'saved' };
      } catch {
        return { status: 'failed', message: '备份保存失败，请检查本地服务、目标位置和写入权限' };
      } finally { busy = false; options.onIdle?.(); }
    }
  };
}
