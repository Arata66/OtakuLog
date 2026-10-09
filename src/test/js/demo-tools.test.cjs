const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const http = require('node:http');
const configFile = path.resolve(__dirname, '../../../scripts/lib/demo-config.cjs');
const sessionFile = path.resolve(__dirname, '../../../scripts/lib/http-session.cjs');
const config = fs.existsSync(configFile) ? require(configFile) : {};
const session = fs.existsSync(sessionFile) ? require(sessionFile) : {};

function settings(overrides = {}) {
    assert.equal(typeof config.createDemoConfig, 'function', '缺少隔离演示配置');
    return config.createDemoConfig({ env: {}, ...overrides });
}

test('当启动演示时应该每次生成独立库和临时凭据并绑定回环地址', () => {
    const first = settings(), second = settings();
    assert.match(first.database, /^otakulog_demo_[a-f0-9]{32}$/);
    assert.notEqual(first.database, second.database); assert.notEqual(first.loginPassword, second.loginPassword);
    assert.equal(first.url, 'http://127.0.0.1:18080');
    assert.ok(config.javaArguments(first).includes('--server.address=127.0.0.1'));
    assert.ok(config.javaArguments(first).includes('--spring.jpa.hibernate.ddl-auto=validate'));
    assert.ok(config.javaArguments(first).includes('--spring.flyway.enabled=true'));
});

test('当传入非法端口或日常预览端口时应该拒绝', () => {
    for (const port of [8080, 0, -1, 65536, 1.5, '18080x']) assert.throws(() => settings({ port }), /端口/);
});

test('当数据库地址不是本地或格式非法时应该拒绝', () => {
    for (const server of ['example.com:3306', '127.0.0.1:0', '127.0.0.1:3306/otaku_log', 'localhost:3306;DROP'])
        assert.throws(() => settings({ env: { OTAKULOG_MYSQL_SERVER: server } }), /数据库地址/);
});

test('当配置含数据库密码时应该只放入子进程环境且固定隔离数据源', () => {
    const item = settings({ env: { DB_PASS: 'private-test', SPRING_APPLICATION_JSON: '{"spring.datasource.url":"business"}' } });
    assert.doesNotMatch(config.javaArguments(item).join(' '), /private-test|business/);
    assert.equal(config.mysqlOptions(item).env.MYSQL_PWD, 'private-test');
    assert.equal(JSON.parse(config.javaEnvironment(item).SPRING_APPLICATION_JSON)['spring.datasource.password'], 'private-test');
    assert.ok(config.javaArguments(item).some(arg => arg.includes('/' + item.database + '?')));
});

test('当库名不是演示生成格式时应该在任何数据库命令前拒绝', async () => {
    const item = settings(); item.database = 'otaku_log'; const calls = [];
    await assert.rejects(config.withDemoDatabase(item, async () => {}, sql => calls.push(sql)), /演示库名/);
    assert.deepEqual(calls, []);
});

test('当数据库创建失败时应该不尝试删除可能已有的库', async () => {
    const item = settings(), calls = [];
    await assert.rejects(config.withDemoDatabase(item, async () => {}, sql => { calls.push(sql); throw new Error('无权限'); }), /无权限/);
    assert.equal(calls.length, 1); assert.match(calls[0], /^CREATE DATABASE /);
});

test('当演示启动失败时应该只删除本次成功创建的库', async () => {
    const item = settings(), calls = [];
    await assert.rejects(config.withDemoDatabase(item, async () => { throw new Error('启动失败'); }, sql => calls.push(sql)), /启动失败/);
    assert.equal(calls.length, 2); assert.match(calls[1], new RegExp('^DROP DATABASE `' + item.database + '`$'));
});

test('当演示正常结束时应该等待任务结束后清理独立库', async () => {
    const item = settings(), calls = [];
    await config.withDemoDatabase(item, async () => { calls.push('运行'); }, sql => calls.push(sql));
    assert.equal(calls[1], '运行'); assert.match(calls[2], /^DROP DATABASE /);
});

test('当子进程未能停止时应该保留演示库并报告错误', async () => {
    const item = settings(), calls = [];
    await assert.rejects(config.withDemoDatabase(item, async () => {
        const error = new Error('子进程未停止'); error.preserveDemoDatabase = true; throw error;
    }, sql => calls.push(sql)), /未停止/);
    assert.equal(calls.length, 1);
});

async function withServer(run) {
    const requests = [];
    const server = http.createServer((req, res) => {
        requests.push({ url: req.url, cookie: req.headers.cookie, csrf: req.headers['x-csrf-token'] });
        if (req.url === '/login' && req.method === 'GET') {
            res.setHeader('Set-Cookie', 'JSESSIONID=isolated; Path=/; HttpOnly'); res.end('<input name="_csrf" value="login-token">');
        } else if (req.url === '/login') { res.writeHead(302, { Location: '/' }); res.end(); }
        else if (req.url === '/') res.end('<meta name="_csrf" content="write-token">');
        else { res.setHeader('Content-Type', 'application/json'); res.end('{"code":200}'); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    try { await run(`http://127.0.0.1:${server.address().port}`, requests); }
    finally { await new Promise(resolve => server.close(resolve)); }
}

test('当演示登录并写入时应该使用独立Cookie和页面CSRF令牌', async () => {
    assert.equal(typeof session.createHttpSession, 'function', '缺少独立演示会话');
    await withServer(async (url, requests) => {
        const client = session.createHttpSession(url); await client.login('demo', 'temporary');
        await client.request('/api/anime/import', { method: 'POST', body: '{}' });
        assert.equal(requests.at(-1).cookie, 'JSESSIONID=isolated'); assert.equal(requests.at(-1).csrf, 'write-token');
    });
});

test('当演示请求指定其他源时应该阻止Cookie或令牌泄漏', async () => {
    assert.equal(typeof session.createHttpSession, 'function', '缺少独立演示会话');
    await withServer(async url => {
        const client = session.createHttpSession(url);
        await assert.rejects(client.request('https://example.com/api/test', { method: 'POST' }), /相对路径/);
        await assert.rejects(client.request('//example.com/api/test'), /相对路径/);
    });
});
