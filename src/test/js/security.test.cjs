const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../../main/resources/static');

test('当受管桌面加载页面时应该跳过工作线程且网站仍然注册', async () => {
    for (const mode of ['true', 'false', null]) {
        const app = createApp(undefined, 'page-token', mode), registrations = [];
        app.context.navigator = { serviceWorker: { register: async url => registrations.push(url) } };
        assert.equal(typeof app.context.registerServiceWorker, 'function', '应该统一核对桌面标志后注册');
        app.context.registerServiceWorker();
        await Promise.resolve();
        assert.deepEqual(registrations, mode === 'true' ? [] : ['/sw.js']);
    }
});

function createApp(response = new Response('{"code":200}'), token = 'page-token', desktopManaged = 'false') {
    const requests = [], messages = [], redirects = [], events = new Map();
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
                if (selector === 'meta[name="otakulog-desktop-managed"]') return desktopManaged == null ? null : { content: desktopManaged };
                return null;
            }
        },
        window: { addEventListener(name, listener) { events.set(name, listener); }, location: { href: 'http://localhost:8080/', origin: 'http://localhost:8080', assign(url) { redirects.push(url); } } },
        fetch: async (url, options) => { requests.push({ url, options }); return response; }
    });
    vm.runInContext(fs.readFileSync(path.join(assets, 'js/anime-app.js'), 'utf8'), context);
    context.toast = (message, type) => messages.push({ message, type });
    return { context, requests, messages, redirects, leaving() {
        let prevented = false;
        events.get('beforeunload')?.({ preventDefault() { prevented = true; }, returnValue: undefined });
        return prevented;
    } };
}

test('当桌面列表有多个写入时应该持续保护退出直到最后一个响应读取完成', async () => {
    const app = createApp(undefined, 'page-token', 'true'), responses = [];
    app.context.fetch = () => new Promise(resolve => responses.push(resolve));
    const first = app.context.fetchApi('/api/bangumi/import/confirm', { method: 'POST' });
    const second = app.context.fetchApi('/api/anime/restore', { method: 'POST' });
    assert.equal(app.leaving(), true, '没有详情面板时也应保护写入');
    responses[0](new Response('{"code":200}'));
    await first;
    assert.equal(app.leaving(), true, '先完成的请求不能清掉另一个请求的保护');
    let finishBody, bodyStarted;
    const readingBody = new Promise(resolve => { bodyStarted = resolve; });
    responses[1]({ status: 200, ok: true, json: () => new Promise(resolve => { finishBody = resolve; bodyStarted(); }) });
    await readingBody;
    assert.equal(app.leaving(), true, '响应正文尚未完成时应继续等待');
    finishBody({ code: 200 });
    await second;
    assert.equal(app.leaving(), false);
});

for (const failure of ['network', 401, 409, 503]) {
    test(`当桌面写入遇到${failure}时应该释放退出保护并保留原错误处理`, async () => {
        const app = createApp(undefined, 'page-token', 'true');
        let finish;
        app.context.fetch = () => new Promise((resolve, reject) => { finish = () => failure === 'network'
            ? reject(new Error('虚构断连')) : resolve(new Response('{"message":"虚构失败"}', { status: failure })); });
        const pending = app.context.fetchApi('/api/anime/1/memories/1/insights/ai/extract', { method: 'POST', returnError: true, returnConflict: true });
        assert.equal(app.leaving(), true);
        finish();
        const result = await pending;
        assert.equal(app.leaving(), false);
        assert.equal(result?.code ?? null, typeof failure === 'number' && failure !== 401 ? failure : null);
    });
}

test('当读取数据或请求外站以及网站模式写入时应该保留既有退出行为', async () => {
    for (const [mode, url, method] of [['true', '/api/anime/search', 'GET'], ['true', 'https://example.com/test', 'POST'], ['false', '/api/anime/add', 'POST']]) {
        const app = createApp(undefined, 'page-token', mode);
        let finish;
        app.context.fetch = () => new Promise(resolve => { finish = resolve; });
        const request = app.context.fetchApi(url, { method });
        assert.equal(app.leaving(), false);
        finish(new Response('{"code":200}'));
        await request;
        assert.equal(app.leaving(), false);
    }
});

function createBackupApp(preview, accepted = true) {
    const app = createApp();
    app.context.confirm = () => accepted;
    app.context.alert = message => app.messages.push({ message });
    app.context.document.getElementById = () => ({ classList: { add() {} } });
    app.context.performSearch = () => {};
    app.context.updateStats = () => {};
    app.context.loadDailyWatch = () => {};
    app.context.loadHeatmap = () => {};
    app.context.fetchApi = async (url, options) => {
        app.requests.push({ url, options });
        return { code: 200, data: url.endsWith('/preview') ? preview : {}, message: '恢复完成' };
    };
    return app;
}

for (const status of ['saved', 'cancelled', 'failed']) {
    test(`当桌面原生备份返回${status}时应该仅按真实保存结果提示`, async () => {
        const app = createApp(undefined, 'page-token', 'true'), downloads = [];
        app.context.window.otakulogDesktop = { exportBackup: async () => ({ status, message: '虚构保存失败' }) };
        app.context.URL = class extends URL {
            static createObjectURL() { return 'blob:fixture'; }
            static revokeObjectURL() {}
        };
        app.context.document.createElement = () => ({ click() { downloads.push('download'); } });
        await app.context.exportData();
        assert.deepEqual(app.requests, [], '桌面应由主进程读取和保存备份');
        assert.deepEqual(downloads, []);
        assert.deepEqual(app.messages, status === 'cancelled' ? [] : [status === 'saved'
            ? { message: '备份已保存', type: 'success' } : { message: '虚构保存失败', type: 'error' }]);
    });
}

test('当桌面原生导出桥不可用或调用失败时应该提示失败且不退回网页下载', async () => {
    for (const bridge of [undefined, { exportBackup: async () => { throw new Error('虚构桥错误'); } }]) {
        const app = createApp(undefined, 'page-token', 'true');
        app.context.window.otakulogDesktop = bridge;
        await app.context.exportData();
        assert.deepEqual(app.requests, []);
        assert.deepEqual(app.messages, [{ message: '导出失败，请重新打开程序后重试', type: 'error' }]);
    }
});

test('当原生保存框等待时应该禁用导出按钮并阻止重复操作且取消后恢复', async () => {
    const app = createApp(undefined, 'page-token', 'true'), button = { disabled: false };
    let finish, calls = 0;
    app.context.document.querySelectorAll = () => [button];
    app.context.window.otakulogDesktop = { exportBackup: () => { calls++; return new Promise(resolve => { finish = resolve; }); } };
    const pending = app.context.exportData();
    assert.equal(button.disabled, true);
    await app.context.exportData();
    assert.equal(calls, 1);
    finish({ status: 'cancelled' }); await pending;
    assert.equal(button.disabled, false); assert.deepEqual(app.messages, []);
});

test('当网站导出时应该保留浏览器下载并提示用户确认保存', async () => {
    const app = createApp(), anchor = { click() { this.clicked = true; } };
    app.context.document.createElement = () => anchor;
    app.context.URL = class extends URL {
        static createObjectURL() { return 'blob:fixture'; }
        static revokeObjectURL() {}
    };
    await app.context.exportData();
    assert.equal(anchor.clicked, true); assert.equal(anchor.download, 'otakulog_export.json');
    assert.equal(app.requests[0].url, '/api/anime/export');
    assert.deepEqual(app.messages, [{ message: '已发起备份下载，请在浏览器中确认保存', type: 'success' }]);
});

test('当调用方需要处理记录冲突时应该返回冲突并保留默认错误提示行为', async () => {
    const app = createApp(new Response('{"message":"记录已变化"}', { status: 409 }));
    const result = await app.context.fetchApi('/api/anime/1/episodes/1', { method: 'PUT', returnConflict: true });
    assert.equal(result.code, 409); assert.equal(result.message, '记录已变化');
    assert.equal(app.messages.length, 0); assert.equal(app.requests[0].options.returnConflict, undefined);
    assert.equal(app.requests[0].options.headers.get('X-CSRF-TOKEN'), 'page-token');
    const ordinary = createApp(new Response('{"message":"冲突"}', { status: 409 }));
    assert.equal(await ordinary.context.fetchApi('/api/anime/1', { method: 'PUT' }), null);
    assert.equal(ordinary.messages[0].message, '冲突');
});

test('当调用方处理进度边界错误时应该返回原因且不泄漏内部选项或绕过登录', async () => {
    const app = createApp(new Response('{"message":"reached_max"}', { status: 400 }));
    const result = await app.context.fetchApi('/api/anime/1/next-episode', { method: 'POST', returnError: true });
    assert.equal(result.code, 400); assert.equal(result.message, 'reached_max');
    assert.equal(app.messages.length, 0); assert.equal(app.requests[0].options.returnError, undefined);
    assert.equal(app.requests[0].options.headers.get('X-CSRF-TOKEN'), 'page-token');
    const expired = createApp(new Response('{}', { status: 401 }));
    assert.equal(await expired.context.fetchApi('/api/anime/1/next-episode', { method: 'POST', returnError: true }), null);
    assert.equal(expired.redirects.length, 1);
});

function createReportApp(overrides = {}) {
    const app = createApp();
    const elements = new Map(), charts = [];
    app.context.document.getElementById = id => {
        if (!elements.has(id)) elements.set(id, { textContent: '', innerHTML: '', getContext() { return {}; }, classList: { remove() {} } });
        return elements.get(id);
    };
    app.context.Chart = function(canvas, config) { charts.push(config); this.destroy = () => {}; };
    const report = {
        year: 2026, totalWatched: 3, totalEpisodes: 4, averageRating: 0, ratedAnimeCount: 0, watchingHours: 1.6,
        minutesPerEpisode: 24, watchingHoursEstimated: true, watchedAnimeCount: 2, legacyDatedEpisodes: 5,
        undatedEpisodeRecords: 6, missingEpisodeRecords: 7, undatedFinishedAnimeCount: 1,
        monthlyStats: [{ month: '1', count: 3, episodes: 4, legacyEpisodes: 5, avgScore: 0, ratedCount: 0 }],
        ratingDistribution: [], tagDistribution: [], topAnimes: [], ...overrides
    };
    app.context.fetchApi = async () => ({ code: 200, data: report });
    return { ...app, elements, charts };
}

test('当年报没有已评分作品时应该显示未评分而非零分', async () => {
    const app = createReportApp();
    await app.context.showAnnualReport(2026);
    assert.equal(app.elements.get('reportAvgRating').textContent, '未评分');
    assert.ok(app.elements.get('reportTopAnimes').innerHTML.includes('暂无已评分'));
});

test('当年报存在历史与缺失记录时应该说明来源及全库覆盖范围', async () => {
    const app = createReportApp();
    await app.context.showAnnualReport(2026);
    const basis = app.elements.get('reportBasis')?.textContent || '';
    assert.ok(basis.includes('历史来源不明 5 集'));
    assert.ok(basis.includes('全库 6 条记录日期未知'));
    assert.ok(basis.includes('7 集进度缺少逐集记录'));
    assert.ok(basis.includes('1 部已完成作品缺少完成日期'));
    assert.ok(app.elements.get('reportWatchingHoursBasis')?.textContent.includes('24 分钟估算'));
});

test('当绘制年报月度趋势时应该使用观看集数而非完成作品数', async () => {
    const app = createReportApp({ averageRating: 8.5, ratedAnimeCount: 1 });
    await app.context.showAnnualReport(2026);
    assert.equal(app.charts[0].data.datasets[0].data[0], 4);
    assert.equal(app.elements.get('reportAvgRating').textContent, 8.5);
    assert.ok(app.elements.get('reportRatingBasis')?.textContent.includes('1 部'));
});

test('当恢复预览存在冲突时应该展示原因且不提交', async () => {
    const app = createBackupApp({ valid: false, conflicts: ['日期冲突'] });
    await app.context.importBackupJson('备份内容');
    assert.deepEqual(app.requests.map(r => r.url), ['/api/anime/import/preview']);
    assert.ok(app.messages.some(m => m.message.includes('日期冲突')));
});

test('当用户取消恢复确认时应该不提交', async () => {
    const app = createBackupApp({ valid: true, created: 1, updated: 0, warnings: [] }, false);
    await app.context.importBackupJson('备份内容');
    assert.equal(app.requests.length, 1);
});

test('当用户确认本地恢复时应该提交原始文件且只提交一次', async () => {
    const app = createBackupApp({ valid: true, created: 1, updated: 0, warnings: [] });
    await app.context.importBackupJson('备份内容');
    assert.deepEqual(app.requests.map(r => r.url), ['/api/anime/import/preview', '/api/anime/import']);
    assert.equal(app.requests[1].options.body, '备份内容');
});

test('当确认WebDAV恢复时应该带上预览摘要', async () => {
    const app = createBackupApp({ valid: true, created: 1, updated: 0, warnings: [], fingerprint: '摘要' });
    await app.context.syncPull();
    assert.deepEqual(app.requests.map(r => r.url), ['/api/sync/pull/preview', '/api/sync/pull']);
    assert.deepEqual(JSON.parse(app.requests[1].options.body), { fingerprint: '摘要' });
});

test('当预览请求失败时应该不继续恢复', async () => {
    const app = createBackupApp({});
    app.context.fetchApi = async (url, options) => { app.requests.push({ url, options }); return null; };
    await app.context.importBackupJson('备份内容');
    assert.equal(app.requests.length, 1);
});

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

for (const options of [{}, { responseType: 'blob' }, { method: 'POST', returnError: true }]) {
    test(`当受管桌面会话失效且使用${JSON.stringify(options)}时应该提示重启并停止业务响应`, async () => {
        const app = createApp(new Response('{"code":401}', { status: 401 }), 'page-token', 'true');
        assert.equal(await app.context.fetchApi('/api/anime/export', options), null);
        assert.deepEqual(app.redirects, []);
        assert.deepEqual(app.messages, [{ message: '本地会话已失效，请重新启动 OtakuLog', type: 'error' }]);
    });
}

for (const flag of ['false', '', null, 'TRUE']) {
    test(`当页面没有严格受管桌面标志${String(flag)}时应该保持网站重新登录`, async () => {
        const app = createApp(new Response('{}', { status: 401 }), 'page-token', flag);
        assert.equal(await app.context.fetchApi('/api/groups'), null);
        assert.deepEqual(app.redirects, ['/login']);
        assert.equal(app.messages[0].message, '登录已失效，请重新登录');
    });
}

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
