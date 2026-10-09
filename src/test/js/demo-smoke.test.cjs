const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const net = require('node:net');
const { fork, spawnSync } = require('node:child_process');
const { once } = require('node:events');
const { createHttpSession } = require('../../../scripts/lib/http-session.cjs');

test('当隔离演示启动并操作时应该可写可核对且退出后只清理演示库', { skip: process.env.OTAKULOG_DEMO_TEST !== 'true', timeout: 120000 }, async () => {
    const server = net.createServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const port = server.address().port; await new Promise(resolve => server.close(resolve));
    const child = fork(path.resolve(__dirname, '../../../scripts/demo.cjs'), ['--port=' + port], { silent: true });
    let output = '', ready;
    child.stdout.on('data', value => { output += value; }); child.stderr.on('data', value => { output += value; });
    const exit = once(child, 'exit');
    try {
        ready = await Promise.race([once(child, 'message').then(([message]) => message), exit.then(() => { throw new Error('演示未就绪：' + output); }),
            new Promise((_, reject) => { const timeout = setTimeout(() => reject(new Error('演示就绪超时')), 95000); timeout.unref(); })]);
        assert.equal(ready.type, 'ready'); assert.match(ready.database, /^otakulog_demo_[a-f0-9]{32}$/);
        const client = createHttpSession(ready.url); await client.login(ready.username, ready.password);
        const read = async url => { const response = await client.request(url); assert.equal(response.status, 200); return (await response.json()).data; };
        const write = async (url, body, method = 'POST') => client.request(url, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
        let daily = await read('/api/watch/daily'); assert.equal(daily.ongoingCount, 2);
        const id = daily.continueWatching.find(item => item.anime.name === '演示·星港巡游').anime.id;
        assert.equal((await write(`/api/anime/${id}/next-episode`)).status, 200);
        assert.equal((await read('/api/watch/daily')).ongoingCount, 1);
        assert.equal((await write(`/api/anime/${id}/prev-episode`)).status, 200);
        assert.equal((await read('/api/watch/daily')).ongoingCount, 2);
        const rainId = daily.continueWatching.find(item => item.anime.name === '演示·雨巷来信').anime.id;
        const original = (await read(`/api/anime/${rainId}/episodes`)).entries[0];
        assert.equal(original.source, 'LEGACY');
        const body = { watchedDate: original.watchedDate, expected: original };
        assert.equal((await write(`/api/anime/${rainId}/episodes/1`, body, 'PUT')).status, 200);
        assert.equal((await read(`/api/anime/${rainId}/episodes`)).entries[0].source, 'MANUAL');
        assert.equal((await write(`/api/anime/${rainId}/episodes/1`, body, 'PUT')).status, 409);
        const exported = await client.request('/api/anime/export');
        assert.equal(exported.status, 200); assert.equal((await exported.json()).anime.length, 4);
    } finally {
        if (child.connected) child.send({ type: 'stop' });
        const [code] = await exit;
        assert.equal(code, 0, output);
    }
    const endpoint = /^(127\.0\.0\.1|localhost):(\d+)$/.exec(process.env.OTAKULOG_MYSQL_SERVER || '127.0.0.1:3306');
    const result = spawnSync('mysql', ['--protocol=TCP', '--host=127.0.0.1', '--port=' + endpoint[2], '--user=' + (process.env.DB_USER || 'root'),
        '--batch', '--skip-column-names', "--execute=SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + ready.database + "'"],
        { encoding: 'utf8', windowsHide: true, env: { ...process.env, MYSQL_PWD: process.env.DB_PASS || '123456' } });
    assert.equal(result.status, 0); assert.equal(result.stdout.trim(), '0');
});
