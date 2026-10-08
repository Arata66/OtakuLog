const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../main/resources/static');

function createApp(response = new Response('{"code":200}'), token = 'page-token') {
    const requests = [], messages = [], redirects = [];
    const context = vm.createContext({
        Headers, URL,
        Chart: { defaults: { font: {} } },
        console: { error() {} },
        localStorage: { getItem() { return null; } },
        document: {
            readyState: 'loading',
            documentElement: { setAttribute() {} },
            addEventListener() {},
            querySelector(selector) {
                if (selector === 'meta[name="_csrf"]') return token ? { content: token } : null;
                if (selector === 'meta[name="_csrf_header"]') return { content: 'X-CSRF-TOKEN' };
                return null;
            }
        },
        window: { location: { href: 'http://localhost:8080/', origin: 'http://localhost:8080', assign(url) { redirects.push(url); } } },
        fetch: async (url, options) => { requests.push({ url, options }); return response; }
    });
    vm.runInContext(fs.readFileSync(path.join(assets, 'js/anime-app.js'), 'utf8'), context);
    context.toast = (message, type) => messages.push({ message, type });
    return { context, requests, messages, redirects };
}

for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    test(`当使用${method}写入时应该携带页面令牌并保留原请求头`, async () => {
        const { context, requests } = createApp();
        const options = { method, headers: { 'Content-Type': 'application/json' }, body: '{}' };
        await context.fetchApi('/api/anime/1', options);
        const headers = new Headers(requests[0].options.headers);
        assert.equal(headers.get('X-CSRF-TOKEN'), 'page-token');
        assert.equal(headers.get('Content-Type'), 'application/json');
        assert.equal(options.headers['X-CSRF-TOKEN'], undefined);
    });
}

test('当读取数据时应该不发送写入令牌', async () => {
    const { context, requests } = createApp();
    const result = await context.fetchApi('/api/anime/stats');
    assert.equal(result.code, 200);
    assert.equal(new Headers(requests[0].options?.headers).has('X-CSRF-TOKEN'), false);
});

test('当请求其他源时应该不泄露页面令牌', async () => {
    const { context, requests } = createApp();
    await context.fetchApi('https://example.com/api/test', { method: 'POST' });
    assert.equal(new Headers(requests[0].options?.headers).has('X-CSRF-TOKEN'), false);
});

test('当写入使用Headers对象时应该保留其内容', async () => {
    const { context, requests } = createApp();
    await context.fetchApi('/api/anime/1', { method: 'delete', headers: new Headers({ 'X-Test': 'keep' }) });
    const headers = new Headers(requests[0].options.headers);
    assert.equal(headers.get('X-Test'), 'keep');
    assert.equal(headers.get('X-CSRF-TOKEN'), 'page-token');
});

test('当上传文件时应该保留表单并让浏览器生成内容类型', async () => {
    const { context, requests } = createApp();
    const body = new FormData();
    body.append('image', new Blob(['image']), 'test.png');
    await context.fetchApi('/api/trace-moe', { method: 'POST', body });
    assert.equal(requests[0].options.body, body);
    const headers = new Headers(requests[0].options.headers);
    assert.equal(headers.get('X-CSRF-TOKEN'), 'page-token');
    assert.equal(headers.has('Content-Type'), false);
});

test('当会话过期时应该引导登录并停止返回业务数据', async () => {
    const { context, messages, redirects } = createApp(new Response('{"code":401}', { status: 401 }));
    assert.equal(await context.fetchApi('/api/anime/stats'), null);
    assert.deepEqual(redirects, ['/login']);
    assert.ok(messages.some(item => item.message.includes('登录')));
});

test('当导出时会话过期也应该引导登录', async () => {
    const { context, redirects } = createApp(new Response('{"code":401}', { status: 401 }));
    assert.equal(await context.fetchApi('/api/anime/export', { responseType: 'blob' }), null);
    assert.deepEqual(redirects, ['/login']);
});

test('当正常导出时应该保留二进制响应', async () => {
    const response = new Response('[]');
    const { context } = createApp(response);
    assert.equal(await context.fetchApi('/api/anime/export', { responseType: 'blob' }), response);
});

test('当服务拒绝写入时应该显示服务返回的原因', async () => {
    const { context, messages } = createApp(new Response('{"message":"请求令牌无效"}', { status: 403 }));
    assert.equal(await context.fetchApi('/api/anime/1', { method: 'DELETE' }), null);
    assert.equal(messages[0].message, '请求令牌无效');
});

test('当保存编辑弹窗时应该用一次请求同时提交资料和状态', async () => {
    const { context, requests } = createApp();
    const fields = {
        'm-name': '编辑样本', 'm-season': '2026秋', 'm-score': '8',
        'm-total': '12', 'm-status': 'finished'
    };
    context.document.getElementById = id => ({ value: fields[id] || '', checked: false });
    context.closeEditModal = () => {};
    context.performSearch = () => {};
    context.updateStats = () => {};
    await context.saveEditModal(1);
    assert.equal(requests.length, 1);
    assert.equal(JSON.parse(requests[0].options.body).status, 'finished');
});

function createWorker(offline = false) {
    const listeners = {}, reads = [], writes = [], deleted = [], requests = [];
    const context = vm.createContext({
        URL, Response, Promise,
        self: {
            location: { origin: 'http://localhost:8080' },
            addEventListener(name, callback) { listeners[name] = callback; },
            skipWaiting() {}, clients: { claim() {} }
        },
        caches: {
            async keys() { return ['otakulog-v4', 'other-app']; },
            async delete(key) { deleted.push(key); },
            async open() { return { async put(request) { writes.push(request.url); }, async addAll() {} }; },
            async match(request) { reads.push(request.url); return new Response('private-old-data'); }
        },
        fetch: async (request, options) => {
            requests.push({ request, options });
            if (offline) throw new Error('离线');
            return new Response('{"code":200}');
        }
    });
    vm.runInContext(fs.readFileSync(path.join(assets, 'sw.js'), 'utf8'), context);
    return {
        reads, writes, deleted, requests,
        async request(method = 'GET') {
            let response;
            listeners.fetch({
                request: new Request('http://localhost:8080/api/anime/stats', { method }),
                respondWith(promise) { response = promise; }
            });
            return response;
        },
        async activate() {
            let completion;
            listeners.activate({ waitUntil(promise) { completion = promise; } });
            await completion;
        }
    };
}

test('当在线读取私人API时应该只走网络且不写入缓存', async () => {
    const worker = createWorker();
    assert.equal((await worker.request()).status, 200);
    assert.deepEqual(worker.reads, []);
    assert.deepEqual(worker.writes, []);
    assert.equal(worker.requests[0].options?.cache, 'no-store');
});

for (const method of ['GET', 'POST']) {
    test(`当离线执行${method}时应该返回503且不读取私人缓存`, async () => {
        const worker = createWorker(true);
        const response = await worker.request(method);
        assert.equal(response.status, 503);
        assert.equal((await response.json()).code, 503);
        assert.deepEqual(worker.reads, []);
    });
}

test('当激活新版本时应该清理旧私人缓存并保留其他应用缓存', async () => {
    const worker = createWorker();
    await worker.activate();
    assert.deepEqual(worker.deleted, ['otakulog-v4']);
});
