const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { spawn } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { createDemoConfig, javaArguments, javaEnvironment, withDemoDatabase } = require('./lib/demo-config.cjs');
const { createHttpSession } = require('./lib/http-session.cjs');

async function checkPort(port) {
    const server = net.createServer();
    await new Promise((resolve, reject) => {
        server.once('error', () => reject(new Error('演示端口已占用，未创建数据库')));
        server.listen(port, '127.0.0.1', resolve);
    });
    await new Promise(resolve => server.close(resolve));
}

async function runDemo(config, signal, onReady = () => {}) {
    const jar = path.join(config.root, 'target/otakulog-1.0.0.jar');
    if (!fs.existsSync(jar)) throw new Error('缺少应用 JAR，请先执行 mvn clean verify');
    const fixture = fs.readFileSync(path.join(config.root, 'src/test/resources/fixtures/demo-backup.json'), 'utf8');
    await checkPort(config.port);
    signal.throwIfAborted();
    return withDemoDatabase(config, async () => {
        const logs = path.join(config.root, '.demo'); fs.mkdirSync(logs, { recursive: true });
        const logPath = path.join(logs, config.database + '.log');
        const log = fs.openSync(logPath, 'a');
        let child;
        try { child = spawn(config.java, javaArguments(config), { cwd: config.root, env: javaEnvironment(config), windowsHide: true, stdio: ['ignore', log, log] }); }
        finally { fs.closeSync(log); }
        let stopped = false, spawned = false, launchError = null, lastProbe = '尚未探测';
        child.once('spawn', () => { spawned = true; });
        const exited = new Promise(resolve => {
            child.once('error', error => { launchError = error.code || error.name; stopped = true; resolve({ code: 1 }); });
            child.once('exit', code => { stopped = true; resolve({ code }); });
        });
        const startupFailure = message => {
            let alive = false;
            try { if (child.pid) { process.kill(child.pid, 0); alive = true; } } catch {}
            const detail = { pid: child.pid || null, spawned, alive, exitCode: child.exitCode, signal: child.signalCode,
                launchError, lastProbe, logBytes: fs.statSync(logPath).size };
            if (process.platform === 'linux' && alive) {
                try {
                    detail.processState = fs.readFileSync(`/proc/${child.pid}/status`, 'utf8').match(/^State:\s*(.+)$/m)?.[1];
                    detail.stdout = fs.readlinkSync(`/proc/${child.pid}/fd/1`);
                    detail.stderr = fs.readlinkSync(`/proc/${child.pid}/fd/2`);
                } catch {}
            }
            return new Error(message + '，日志：' + logPath + '；启动诊断：' + JSON.stringify(detail));
        };
        const client = createHttpSession(config.url);
        try {
            const deadline = Date.now() + 90000;
            while (true) {
                signal.throwIfAborted();
                if (stopped) throw startupFailure('演示应用启动失败');
                try {
                    const response = await client.request('/login', { signal: AbortSignal.any([signal, AbortSignal.timeout(1000)]) });
                    lastProbe = 'HTTP ' + response.status;
                    if (response.status === 200) break;
                } catch (error) {
                    if (signal.aborted) throw error;
                    lastProbe = error.cause?.code || error.code || error.name;
                }
                if (Date.now() > deadline) throw startupFailure('演示启动超时');
                await delay(250, undefined, { signal });
            }
            // 只有本次随机凭据能登录的应用才可接收样例，避免端口竞争误导入。
            await client.login(config.loginUser, config.loginPassword);
            const options = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: fixture };
            const preview = await client.request('/api/anime/import/preview', options);
            if (preview.status !== 200 || !(await preview.json()).data?.valid) throw new Error('演示样例预览失败');
            signal.throwIfAborted();
            const restored = await client.request('/api/anime/import', options);
            if (restored.status !== 200 || (await restored.json()).code !== 200) throw new Error('演示样例恢复失败');
            onReady({ url: config.url, username: config.loginUser, password: config.loginPassword, database: config.database });
            const ended = await Promise.race([exited, delay(2147483647, undefined, { signal, ref: false }).catch(() => ({ cancelled: true }))]);
            if (!ended?.cancelled) throw new Error('演示应用意外退出，日志：' + logPath);
        } finally {
            if (!stopped) {
                child.kill();
                await Promise.race([exited, delay(10000, undefined, { ref: false }).then(() => {
                    const error = new Error('演示进程未停止，保留库供核对：' + config.database);
                    error.preserveDemoDatabase = true; throw error;
                })]);
            }
        }
    });
}

async function main() {
    if (Number(process.versions.node.split('.')[0]) < 22) throw new Error('演示工具需要 Node.js 22 或更高版本');
    if (process.argv.length > 3 || (process.argv[2] && !/^--port=\d+$/.test(process.argv[2]))) throw new Error('用法：node scripts/demo.cjs [--port=18080]');
    const config = createDemoConfig({ port: process.argv[2]?.slice(7) || 18080 });
    const controller = new AbortController();
    const stop = () => controller.abort();
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
    process.on('message', message => { if (message?.type === 'stop') stop(); });
    process.once('disconnect', stop);
    try {
        await runDemo(config, controller.signal, ready => {
            if (process.send) process.send({ type: 'ready', ...ready });
            else console.log(`隔离演示已启动：${ready.url}\n临时账号：${ready.username}\n临时密码：${ready.password}\n演示库：${ready.database}\n按 Ctrl+C 停止并清理本次演示库。`);
        });
    } catch (error) {
        if (!controller.signal.aborted || error.name !== 'AbortError') throw error;
    } finally { process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop); }
    if (process.connected) process.disconnect();
    console.log('隔离演示已结束，本次演示库已清理。');
}

if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; if (process.connected) process.disconnect(); });
module.exports = { runDemo };
