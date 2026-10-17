import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import path from 'node:path';
import { request as httpRequest } from 'node:http';

export interface BackendLaunch {
  file: string;
  args: string[];
  env: NodeJS.ProcessEnv;
}

export interface BackendConnection {
  child: ChildProcessWithoutNullStreams;
  origin: string;
  control: (operation: 'bootstrap' | 'shutdown', body?: unknown) => Promise<Response>;
  stop: (timeoutMs?: number) => Promise<void>;
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
      '--app.ai.enabled=false', '--app.ai.api-key='],
    env
  };
}

export function startBackend(options: {
  launch: BackendLaunch;
  version: string;
  timeoutMs?: number;
  onLog?: (text: string) => void;
}): Promise<BackendConnection> {
  return new Promise((resolve, reject) => {
    const controlToken = randomBytes(32).toString('hex');
    const child = spawn(options.launch.file, options.launch.args, {
      env: options.launch.env, windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe']
    });
    let ready = false;
    let failed = false;
    let pending = '';
    const timeout = setTimeout(() => fail(new Error('后端启动超时，请查看运行日志')),
      options.timeoutMs ?? 60000);

    function fail(error: Error) {
      if (ready || failed) return;
      failed = true;
      clearTimeout(timeout);
      // 启动尚未成功，只结束本次持有的子进程。
      child.kill();
      reject(error);
    }

    function log(text: string) {
      options.onLog?.(text.replaceAll(controlToken, '[已隐藏]'));
    }

    child.on('error', () => fail(new Error('无法启动内置 Java，请检查程序文件是否完整')));
    child.stdin.on('error', () => fail(new Error('后端控制通道已关闭')));
    child.on('exit', code => fail(new Error('后端在就绪前退出，退出码：' + code)));
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
        if (!line.startsWith('OTAKULOG_DESKTOP_READY ')) {
          log(line + '\n');
          continue;
        }
        if (ready || failed) continue;
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
          const control = (operation: 'bootstrap' | 'shutdown', body?: unknown) => new Promise<Response>((done, error) => {
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
                error(new Error('后端控制请求失败：' + status));
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
