const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function app() {
    const requests = [], messages = [], opened = [], refreshed = [];
    const panel = { innerHTML: '', dataset: {} }, buttons = [{ disabled: false }, { disabled: false }];
    const fields = new Map([['dailyWatch', panel]]);
    const item = { anime: { id: 7, name: '追番入口', status: 'watching', currentEpisode: 2, totalEpisodes: 12, season: '2026秋', score: 8 }, lastWatchedDate: null };
    const daily = { date: '2026-10-08', weekday: 4, ongoingCount: 1, todayAiringCount: 1, continueWatching: [item], todayAiring: [item] };
    const context = vm.createContext({
        document: { getElementById: id => fields.get(id), querySelectorAll: () => buttons },
        esc: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;'),
        toast: (message, type) => messages.push({ message, type }),
        performSearch: async () => refreshed.push('list'), updateStats: async () => refreshed.push('stats'), loadHeatmap: async () => refreshed.push('heatmap'),
        openDetailModal: id => opened.push(id), refreshDetailProgress: async () => refreshed.push('detail'),
        fetchApi: async (url, options) => {
            requests.push({ url, options });
            if (options) item.anime = { ...item.anime, currentEpisode: 3 };
            return { code: 200, data: options ? item.anime : daily };
        }
    });
    vm.runInContext('const _cache = {};', context);
    for (const name of ['watch-actions.js', 'daily-watch.js']) {
        const file = path.resolve(__dirname, '../../main/resources/static/js', name);
        if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    }
    return { context, panel, fields, buttons, item, daily, requests, messages, opened, refreshed };
}

test('当首页加载日常入口时应该只读并说明放送参考和日期未知', async () => {
    const a = app(); await a.context.loadDailyWatch();
    assert.equal(a.requests.length, 1); assert.equal(a.requests[0].url, '/api/watch/daily');
    assert.equal(a.requests[0].options, undefined); assert.match(a.panel.innerHTML, /未核实本周更新/);
    assert.match(a.panel.innerHTML, /最近观看日期未知/); assert.match(a.panel.innerHTML, /记看第 3 集/);
});

test('当没有可继续作品时应该提供空状态而不渲染写入按钮', async () => {
    const a = app(); a.daily.continueWatching = []; a.daily.todayAiring = []; a.daily.ongoingCount = 0;
    await a.context.loadDailyWatch(); assert.match(a.panel.innerHTML, /暂无可继续/); assert.doesNotMatch(a.panel.innerHTML, /nextEpisode\(/);
});

test('当日常作品不在当前筛选列表时应该仍能打开完整详情', async () => {
    const a = app(); await a.context.loadDailyWatch(); a.context.openDailyAnime(7);
    assert.deepEqual(a.opened, [7]);
    assert.equal(vm.runInContext('_cache[7].totalEpisodes', a.context), 12);
});

test('当日常刷新失败时应该保留旧内容并标记需要重试', async () => {
    const a = app(); await a.context.loadDailyWatch();
    a.context.fetchApi = async () => null; await a.context.loadDailyWatch();
    assert.match(a.panel.innerHTML, /追番入口/); assert.match(a.panel.innerHTML, /刷新失败/); assert.match(a.panel.innerHTML, /重新加载/);
});

test('当早期请求较晚返回时应该保留较新日常入口', async () => {
    const a = app(); let finish;
    a.context.fetchApi = () => new Promise(resolve => { finish = resolve; }); const pending = a.context.loadDailyWatch();
    a.context.fetchApi = async () => ({ code: 200, data: { ...a.daily, ongoingCount: 5 } }); await a.context.loadDailyWatch();
    const latest = a.panel.innerHTML; finish({ code: 200, data: a.daily }); await pending;
    assert.equal(a.panel.innerHTML, latest);
});

test('当作品名称包含HTML时应该作为文本展示', async () => {
    const a = app(); a.item.anime.name = '<img onerror="alert(1)">'; await a.context.loadDailyWatch();
    assert.doesNotMatch(a.panel.innerHTML, /<img onerror/); assert.match(a.panel.innerHTML, /&lt;img/);
});

test('当重复点击同一作品进退集时应该只提交一次并禁用相关按钮', async () => {
    const a = app(); let finish;
    a.context.fetchApi = (url, options) => { a.requests.push({ url, options }); return new Promise(resolve => { finish = resolve; }); };
    const pending = a.context.nextEpisode(7); await a.context.nextEpisode(7); await a.context.prevEpisode(7);
    assert.equal(a.requests.length, 1); assert.ok(a.buttons.every(button => button.disabled));
    finish(null); await pending; assert.ok(a.buttons.every(button => !button.disabled));
});

test('当记看成功时应该用服务端进度反馈并刷新所有相关入口', async () => {
    const a = app(); await a.context.nextEpisode(7);
    assert.equal(a.requests[0].url, '/api/anime/7/next-episode');
    assert.equal(a.requests[0].options.returnError, true);
    assert.match(a.messages[0].message, /第 3 集/);
    assert.equal(vm.runInContext('_cache[7].currentEpisode', a.context), 3);
    assert.deepEqual(a.refreshed.sort(), ['detail', 'heatmap', 'list', 'stats']);
    assert.ok(a.requests.some(request => request.url === '/api/watch/daily'));
});

test('当最后一集完成时应该明确提示已完成', async () => {
    const a = app(); a.context.fetchApi = async (url, options) => ({ code: 200, data: options ? { ...a.item.anime, currentEpisode: 12, status: 'finished' } : a.daily });
    await a.context.nextEpisode(7); assert.match(a.messages[0].message, /12.*已完成/);
});

test('当退回第零集时应该说明已恢复计划', async () => {
    const a = app(); a.context.fetchApi = async (url, options) => ({ code: 200, data: options ? { ...a.item.anime, currentEpisode: 0, status: 'planning' } : a.daily });
    await a.context.prevEpisode(7); assert.match(a.messages[0].message, /0.*计划/);
});

test('当达到集数边界时应该展示可读提示而不泄露内部标识', async () => {
    const a = app(); a.context.fetchApi = async () => ({ code: 400, message: 'reached_max' }); await a.context.nextEpisode(7);
    assert.match(a.messages[0].message, /最后一集/); assert.doesNotMatch(a.messages[0].message, /reached_max/);
    assert.equal(a.refreshed.length, 0);
});

test('当网络结果不明时应该提示核对且不假推进或自动重试', async () => {
    const a = app(); vm.runInContext('_cache[7] = {currentEpisode: 2};', a.context);
    a.context.fetchApi = async (url, options) => { a.requests.push({ url, options }); return null; };
    await a.context.nextEpisode(7);
    assert.equal(a.requests.length, 1); assert.equal(vm.runInContext('_cache[7].currentEpisode', a.context), 2);
    assert.match(a.messages.at(-1).message, /刷新.*核对/); assert.ok(a.buttons.every(button => !button.disabled));
});

test('当操作前按钮本来禁用时应该在请求结束后保留禁用', async () => {
    const a = app(); a.buttons[1].disabled = true; a.context.fetchApi = async () => null;
    await a.context.nextEpisode(7); assert.equal(a.buttons[0].disabled, false); assert.equal(a.buttons[1].disabled, true);
});
