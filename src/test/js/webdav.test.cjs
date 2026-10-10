const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/anime-app.js'), 'utf8');
const confirmation = source.slice(source.indexOf('function confirmBackupImport('), source.indexOf('async function importBackupJson('));
const syncStart = source.indexOf('let webdavSyncBusy');
const sync = source.slice(syncStart >= 0 ? syncStart : source.indexOf('async function syncPush()'), source.indexOf('async function syncStatus()'));
function setup({ valid = true, accepted = true, fetch } = {}) {
    const calls = [], messages = [], refreshes = [], statsArgs = [], buttons = [{ disabled: false }, { disabled: false }];
    const elements = { syncMenu: { classList: { add() {} } }, syncBtn: { setAttribute() {} }, btnSyncPush: buttons[0], btnSyncPull: buttons[1] };
    const context = { document: { getElementById: id => elements[id] },
        confirm: () => accepted, alert: text => messages.push(text), toast: (text, type) => messages.push({ text, type }),
        fetchApi: async (url, options) => { calls.push({ url, options }); return fetch ? fetch(url) : { code: 200, data: url.endsWith('/preview')
            ? { valid, conflicts: ['日期冲突'], warnings: [], fingerprint: '预览摘要' } : {} }; },
        performSearch: async () => refreshes.push('search'), updateStats: async refreshDaily => { statsArgs.push(refreshDaily); refreshes.push('stats'); },
        loadDailyWatch: async () => refreshes.push('daily'), loadHeatmap: async () => refreshes.push('heatmap') };
    vm.createContext(context); vm.runInContext(confirmation + sync, context);
    return { context, calls, buttons, messages, refreshes, statsArgs };
}
test('当取消WebDAV预览确认时应该不提交恢复且恢复按钮', async () => {
    const c = setup({ accepted: false }); await c.context.syncPull();
    assert.equal(c.calls.length, 1); assert.ok(c.buttons.every(b => !b.disabled)); assert.equal(c.refreshes.length, 0);
});
test('当WebDAV预览存在冲突时应该解释原因且不提交', async () => {
    const c = setup({ valid: false }); await c.context.syncPull();
    assert.equal(c.calls.length, 1); assert.ok(c.messages.some(x => typeof x === 'string' && x.includes('日期冲突')));
});
test('当WebDAV恢复成功时应该提交预览摘要并刷新观看和热力图', async () => {
    const c = setup(); await c.context.syncPull();
    assert.deepEqual(JSON.parse(c.calls[1].options.body), { fingerprint: '预览摘要' });
    assert.deepEqual(c.refreshes.sort(), ['daily', 'heatmap', 'search', 'stats']); assert.ok(c.buttons.every(b => !b.disabled));
    assert.equal(c.statsArgs[0], false);
});
test('当WebDAV请求正在执行时应该防止重复及相反方向提交', async () => {
    let release; const deferred = new Promise(resolve => { release = resolve; });
    const c = setup({ fetch: () => deferred }); const pending = c.context.syncPush();
    assert.ok(c.buttons.every(b => b.disabled));
    await c.context.syncPush(); await c.context.syncPull(); assert.equal(c.calls.length, 1);
    release({ code: 200, data: {} }); await pending; assert.ok(c.buttons.every(b => !b.disabled));
});
test('当WebDAV恢复请求失败时应该保留界面数据并恢复按钮', async () => {
    const c = setup({ fetch: () => null }); await c.context.syncPull();
    assert.equal(c.refreshes.length, 0); assert.ok(c.buttons.every(b => !b.disabled));
});
test('当WebDAV推送意外抛出异常时应该提示失败且释放操作状态', async () => {
    const c = setup({ fetch: () => { throw new Error('连接中断'); } }); await c.context.syncPush();
    assert.ok(c.messages.some(m => m.type === 'error')); assert.ok(c.buttons.every(b => !b.disabled));
});
