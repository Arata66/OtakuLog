import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { request as httpRequest } from 'node:http';

export class BackendControlError extends Error {
  constructor(public readonly status: number) { super('后端控制请求失败：' + status); }
}

export interface BackendLaunch {
  file: string;
  args: string[];
  env: NodeJS.ProcessEnv;
}

export interface BackendConnection {
  child: ChildProcessWithoutNullStreams;
  origin: string;
  control: (operation: 'bootstrap' | 'shutdown' | 'ai-settings-lock' | 'ai-settings-cancel' | 'ai-settings', body?: unknown) => Promise<Response>;
  stop: (timeoutMs?: number) => Promise<void>;
}

function dataProtectionFailure(code: unknown, launch: BackendLaunch): Error | undefined {
  const reasons: Record<string, string> = {
    ENGINE_MISMATCH: '资料库的引擎版本与当前程序不同。请用原程序导出完整备份，再在新程序的独立资料目录恢复。',
    BACKUP_FAILED: '升级前备份未能完成，尚未开始迁移。请检查备份目录空间和写入权限。',
    DATABASE_BUSY: '资料库正在被其他程序使用。请关闭对应的 OtakuLog 后重试。',
    SCHEMA_NEWER: '资料结构比当前程序更新。请使用匹配的新版本打开。',
    UPGRADE_BLOCKED: '资料升级未完成，已停止自动重试。请保留原资料和升级备份，并恢复到独立目录。'
  };
  if (typeof code !== 'string' || !Object.hasOwn(reasons, code)) return undefined;
  const location = (name: string) => launch.args.find(arg => arg.startsWith(name + '='))?.slice(name.length + 1);
  return new Error(reasons[code] + '\n资料目录：' + (location('--app.desktop.data-dir') ?? '未提供')
    + '\n备份目录：' + (location('--app.desktop.backup-dir') ?? '未提供'));
}

export function createBackendLaunch(resources: string, directory: string,
  source: NodeJS.ProcessEnv = process.env): BackendLaunch {
  if (!path.isAbsolute(resources) || !path.isAbsolute(directory))
    throw new Error('程序和资料目录必须是绝对路径');
  const env: NodeJS.ProcessEnv = {};
  for (const name of ['SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA']) {
    if (source[name]) env[name] = source[name];
  }
  return {
    file: path.join(resources, 'runtime', 'bin', 'java.exe'),
    args: ['-Dfile.encoding=UTF-8', '-jar', path.join(resources, 'backend', 'otakulog.jar'),
      '--spring.profiles.active=desktop', '--server.address=127.0.0.1', '--server.port=0',
      '--app.desktop.managed=true', '--app.desktop.data-dir=' + directory,
      '--app.desktop.backup-dir=' + path.join(directory, '..', 'backups'),
      '--app.ai.enabled=false', '--app.ai.api-key='],
    env
  };
}

export function startBackend(options: {
  launch: BackendLaunch;
  version: string;
  timeoutMs?: number;
  upgradeTimeoutMs?: number;
  onLog?: (text: string) => void;
}): Promise<BackendConnection> {
  return new Promise((resolve, reject) => {
    const controlToken = randomBytes(32).toString('hex');
    const child = spawn(options.launch.file, options.launch.args, {
      env: options.launch.env, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe']
    });
    let ready = false;
    let failed = false;
    let protectionReported = false;
    let protectionFailure: Error | undefined;
    let upgrading = false;
    let pending = '';
    let timeout = setTimeout(() => fail(protectionFailure ?? new Error('后端启动超时，请查看运行日志')),
      options.timeoutMs ?? 60000);

    function fail(error: Error) {
      if (ready || failed) return;
      failed = true;
      clearTimeout(timeout);
      // 启动尚未成功，只结束本次持有的子进程。
      child.once('close', () => reject(error));
      child.kill();
    }

    function log(text: string) {
      options.onLog?.(text.replaceAll(controlToken, '[已隐藏]'));
    }

    child.on('error', () => fail(new Error('无法启动内置 Java，请检查程序文件是否完整')));
    child.stdin.on('error', () => fail(new Error('后端控制通道已关闭')));
    child.on('exit', code => fail(protectionFailure ?? new Error('后端在就绪前退出，退出码：' + code)));
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (text: string) => log(text));
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (text: string) => {
      pending += text;
      if (pending.length > 262144) {
        if (ready) {
          log(pending.slice(0, 262144));
          pending = '';
        } else {
          fail(new Error('后端启动信息超过允许长度'));
        }
        return;
      }
      let newline: number;
      while ((newline = pending.indexOf('\n')) !== -1) {
        const line = pending.slice(0, newline).trimEnd();
        pending = pending.slice(newline + 1);
        if (line === 'OTAKULOG_DESKTOP_UPGRADING') {
          if (ready || failed || protectionReported || upgrading) continue;
          upgrading = true;
          clearTimeout(timeout);
          timeout = setTimeout(() => fail(protectionFailure ?? new Error(
            '资料升级超时。' + dataProtectionFailure('UPGRADE_BLOCKED', options.launch)!.message)), options.upgradeTimeoutMs ?? 600000);
          continue;
        }
        if (line.startsWith('OTAKULOG_DESKTOP_FAILURE ')) {
          if (ready || failed) continue;
          protectionReported = true;
          try {
            const packet = JSON.parse(line.slice('OTAKULOG_DESKTOP_FAILURE '.length)) as { code?: unknown } | null;
            protectionFailure = dataProtectionFailure(packet?.code, options.launch);
          } catch {
            // 故障包只接受固定原因，异常正文不能直接进入桌面提示。
          }
          // 等待 Spring 完成清理和释放资料锁，再允许用户重启。
          continue;
        }
        if (!line.startsWith('OTAKULOG_DESKTOP_READY ')) {
          log(line + '\n');
          continue;
        }
        if (ready || failed || protectionReported) continue;
        try {
          const info = JSON.parse(line.slice('OTAKULOG_DESKTOP_READY '.length)) as {
            port: number; version: string;
          };
          if (info.version !== options.version) throw new Error('后端版本与桌面程序不一致');
          if (!Number.isInteger(info.port) || info.port < 1 || info.port > 65535)
            throw new Error('后端报告的端口无效');
          ready = true;
          clearTimeout(timeout);
          const origin = 'http://127.0.0.1:' + info.port;
          const control: BackendConnection['control'] = (operation, body) => new Promise<Response>((done, error) => {
            // 仅连接已握手子进程的回环端口，避免 fetch 的网页端口限制误伤本机控制通道。
            const signal = AbortSignal.timeout(5000);
            const fail = (failure: Error) => error(signal.aborted ? new Error('后端控制请求超时') : failure);
            const request = httpRequest({
              hostname: '127.0.0.1', port: info.port, path: '/internal/desktop/' + operation,
              method: 'POST', signal,
              headers: { 'X-OtakuLog-Desktop-Token': controlToken, 'Content-Type': 'application/json' }
            }, response => {
              response.on('error', fail);
              const status = response.statusCode ?? 500;
              if (status < 200 || status >= 300) {
                response.destroy();
                error(new BackendControlError(status));
                return;
              }
              const chunks: Buffer[] = [];
              response.on('data', (chunk: Buffer) => chunks.push(chunk));
              response.on('end', () => {
                const headers = new Headers();
                for (let index = 0; index < response.rawHeaders.length; index += 2)
                  headers.append(response.rawHeaders[index], response.rawHeaders[index + 1]);
                done(new Response(status === 204 || status === 205 ? null : Buffer.concat(chunks), { status, headers }));
              });
            });
            request.on('error', fail);
            request.end(body === undefined ? undefined : JSON.stringify(body));
          });
          resolve({ child, origin, control, stop: async (timeoutMs = 15000) => {
            if (child.exitCode !== null || child.signalCode !== null) return;
            try { await control('shutdown'); } catch {
              // 标准输入断开同样要求后端正常关闭，不改成批量杀进程。
            }
            if (!child.stdin.destroyed) child.stdin.end();
            if (child.exitCode !== null || child.signalCode !== null) return;
            await new Promise<void>((done, error) => {
              const timer = setTimeout(() => {
                child.off('exit', finished);
                error(new Error('后端仍在退出，请等待写入完成'));
              }, timeoutMs);
              function finished() { clearTimeout(timer); done(); }
              child.once('exit', finished);
            });
          } });
        } catch (error) {
          fail(error instanceof Error ? error : new Error('后端启动信息无效'));
        }
      }
    });
    child.stdin.write(JSON.stringify({ controlToken, version: options.version }) + '\n');
  });
}
