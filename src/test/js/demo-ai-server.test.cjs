const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const helperPath = path.resolve(__dirname, '../browser/ai-server.cjs');
const helper = fs.existsSync(helperPath) ? require(helperPath) : {};

test('当本地AI假服务接收请求时应该记录输入并返回有效空候选和用量', async () => {
    assert.equal(typeof helper.createAiServer, 'function', '缺少本地 AI 假服务');
    const server = await helper.createAiServer();
    try {
        assert.match(server.url, /^http:\/\/127\.0\.0\.1:\d+\/chat\/completions$/);
        const input = { model: 'deepseek-flash', messages: [{ role: 'user', content: '虚构演示文本' }] };
        const response = await fetch(server.url, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer demo-ai-only' }, body: JSON.stringify(input) });
        assert.equal(response.status, 200);
        const result = await response.json();
        assert.deepEqual(JSON.parse(result.choices[0].message.content), { insights: [] });
        assert.equal(result.choices[0].finish_reason, 'stop');
        assert.deepEqual(result.usage, { prompt_tokens: 12, completion_tokens: 3 });
        assert.equal(server.requests.length, 1);
        assert.deepEqual(server.requests[0].body, input);
        assert.equal(server.requests[0].method, 'POST');
        assert.equal(server.requests[0].url, '/chat/completions');
        assert.equal(server.requests[0].headers.authorization, 'Bearer demo-ai-only');
    } finally { await server.close(); }
    await assert.rejects(fetch(server.url));
});

test('当本地AI假服务指定异步响应时应该透传HTTP失败或无用量结果', async () => {
    assert.equal(typeof helper.createAiServer, 'function', '缺少本地 AI 假服务');
    let release, entered;
    const gate = new Promise(resolve => { release = resolve; });
    const received = new Promise(resolve => { entered = resolve; });
    const server = await helper.createAiServer(async request => {
        entered();
        await gate;
        return request.body.fail ? { status: 503, body: { error: '虚构上游暂时失败' }, headers: { 'Retry-After': '60' } }
            : { status: 200, body: { choices: [{ finish_reason: 'stop', message: { content: '{"insights":[]}' } }] } };
    });
    try {
        let settled = false;
        const pending = fetch(server.url, { method: 'POST', body: '{"fail":true}' });
        pending.then(() => { settled = true; });
        await received;
        assert.equal(settled, false);
        assert.equal(server.requests.length, 1);
        release();
        const response = await pending;
        assert.equal(response.status, 503);
        assert.equal(response.headers.get('retry-after'), '60');
        assert.deepEqual(await response.json(), { error: '虚构上游暂时失败' });
        const noUsage = await fetch(server.url, { method: 'POST', body: '{"fail":false}' });
        assert.equal(noUsage.status, 200);
        assert.equal((await noUsage.json()).usage, undefined);
        assert.equal(server.requests.length, 2);
    } finally { await server.close(); }
});

test('当本地AI假服务收到额外凭据时应该不在请求记录中保留其值', async () => {
    assert.equal(typeof helper.createAiServer, 'function', '缺少本地 AI 假服务');
    const server = await helper.createAiServer();
    try {
        await fetch(server.url, { method: 'POST', headers: { Authorization: 'Bearer sk-fictional-other-secret', Cookie: 'fictional-cookie-secret', 'X-Api-Key': 'sk-fictional-other-secret' }, body: '{}' });
        assert.doesNotMatch(JSON.stringify(server.requests[0].headers), /sk-fictional-other-secret|fictional-cookie-secret/);
    } finally { await server.close(); }
});
