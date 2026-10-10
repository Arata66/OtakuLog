const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/anime-app.js'), 'utf8');
const body = source.slice(source.indexOf('async function importFromBangumi()'), source.indexOf('function closeDetailModal()'));
function setup(reply, username = ' 用户/名 ') {
    const button = { disabled: false }, calls = [], messages = [];
    const context = { document: { getElementById: () => button }, prompt: text => { calls.push(text); return username; },
        fetchApi: async (url, options) => { calls.push(url); assert.equal(options.method, 'POST'); if (reply instanceof Error) throw reply; return reply; },
        toggleSyncMenu() {}, toast: (text, type) => messages.push({ text, type }),
        performSearch: async () => calls.push('search'), updateStats: async refreshDaily => { assert.equal(refreshDaily, false); calls.push('stats'); },
        loadDailyWatch: async () => calls.push('daily'), loadHeatmap: async () => calls.push('heatmap') };
    vm.createContext(context); vm.runInContext(body, context);
    return { run: context.importFromBangumi, button, calls, messages };
}
test('当导入成功且部分收藏需核对时应该解释结果并刷新观看入口', async () => {
    const c = setup({ code: 200, data: { created: 1, skipped: 2, total: 3, needsReview: 2 } });
    await c.run();
    assert.match(c.calls[0], /200/);
    assert.ok(c.calls.includes('/api/bangumi/import/' + encodeURIComponent('用户/名')));
    for (const name of ['search', 'stats', 'daily', 'heatmap']) assert.ok(c.calls.includes(name));
    assert.match(c.messages[0].text, /需核对 2/); assert.equal(c.button.disabled, false);
});
test('当取消账号输入时应该不发送导入请求', async () => {
    const c = setup(null, null); await c.run(); assert.equal(c.calls.length, 1); assert.equal(c.button.disabled, false);
});
test('当上游导入失败时应该显示错误且不刷新数据', async () => {
    const c = setup({ code: 502, message: '上游失败' }); await c.run();
    assert.equal(c.messages[0].type, 'error'); assert.equal(c.messages[0].text, '上游失败');
    assert.equal(c.calls.length, 2); assert.equal(c.button.disabled, false);
});
test('当请求意外抛出异常时应该恢复导入按钮并提示失败', async () => {
    const c = setup(new Error('连接中断')); await c.run();
    assert.equal(c.button.disabled, false); assert.equal(c.messages[0].type, 'error');
});
