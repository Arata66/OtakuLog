const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const file = path.join(__dirname, '../../main/resources/static/js/bangumi-import.js');
function setup(replies = []) {
    const elements = new Map(), calls = [], messages = [];
    const field = id => {
        if (!elements.has(id)) elements.set(id, { id, value: id === 'bgmImportUsername' ? 'test' : '', checked: false, disabled: false,
            textContent: '', innerHTML: '', focus() {}, querySelectorAll: () => [], remove() { elements.delete(id); } });
        return elements.get(id);
    };
    const context = { document: { getElementById: id => id === 'bangumiImportModal' ? elements.get(id) : field(id),
        createElement: () => ({ innerHTML: '', querySelectorAll: () => [], addEventListener() {}, remove() { elements.delete(this.id); } }),
        body: { appendChild: el => elements.set(el.id, el) } },
        fetchApi: async (url, options = {}) => { calls.push({ url, options }); const result = replies.shift(); if (result instanceof Error) throw result; return result; },
        esc: text => String(text).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'),
        crypto: { randomUUID: () => '11111111-1111-4111-8111-111111111111' }, AbortController,
        toggleSyncMenu() {}, trapFocus() {}, restoreFocus() {}, closeDetailModal() {},
        canLeaveAnimeMemory: () => true, beginAnimeMemoryOperation: () => ({}), endAnimeMemoryOperation() {},
        toast: text => messages.push(text), performSearch: async () => calls.push('search'), updateStats: async () => calls.push('stats'),
        loadDailyWatch: async () => calls.push('daily'), loadHeatmap: async () => calls.push('heatmap') };
    vm.createContext(context); vm.runInContext(fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '', context);
    return { context, field, calls, messages };
}
const preview = { code: 200, data: { previewKey: '22222222-2222-4222-8222-222222222222', username: 'test', total: 1, readCount: 1, newCount: 0,
    existingCount: 0, reviewCount: 1, complete: true, sourceChanged: false, rows: [{ result: 'REVIEW', reason: '集数未知',
        source: { subjectId: 12, nameCn: '<script>虚构</script>', type: 4, rate: 0, tags: ['自己的标签'], comment: '<img onerror=x>', eps: null, epStatus: 0 } }] } };
test('当打开导入入口时应该只打开面板且不直接写入', () => {
    const c = setup(); assert.equal(typeof c.context.openBangumiImport, 'function'); c.context.openBangumiImport(); assert.equal(c.calls.length, 0);
});
test('当读取预览时应该安全展示原文并等待明确确认', async () => {
    const c = setup([preview]); c.context.openBangumiImport(); await c.context.readBangumiImport();
    assert.equal(c.calls[0].url, '/api/bangumi/import/preview');
    assert.match(c.field('bgmImportRows').innerHTML, /&lt;script&gt;/); assert.doesNotMatch(c.field('bgmImportRows').innerHTML, /<script>/);
    assert.equal(c.calls.length, 1); assert.match(c.field('bgmImportStatus').textContent, /1/);
});
test('当确认响应丢失后重试时应该复用请求编号与选项', async () => {
    const c = setup([preview, null, { code: 200, data: { created: 0, skipped: 1, needsReview: 1, externalSaved: 1 } }]);
    c.context.openBangumiImport(); await c.context.readBangumiImport(); await c.context.confirmBangumiImport(); await c.context.confirmBangumiImport();
    const requests = c.calls.filter(x => x?.url?.endsWith('/confirm'));
    assert.equal(requests.length, 2); assert.equal(requests[0].options.body, requests[1].options.body);
    assert.ok(c.calls.includes('daily')); assert.ok(c.calls.includes('search'));
});
test('当读取失败时应该允许重试且不刷新作品数据', async () => {
    const c = setup([{ code: 502, message: '上游失败' }]); c.context.openBangumiImport(); await c.context.readBangumiImport();
    assert.match(c.field('bgmImportStatus').textContent, /上游失败/); assert.equal(c.field('bgmImportRead').disabled, false);
    assert.equal(c.calls.length, 1);
});
test('当未保存感想的离开确认被取消时应该不提交收藏或改动稿件', async () => {
    const c = setup([preview]); c.context.openBangumiImport(); await c.context.readBangumiImport();
    c.context.canLeaveAnimeMemory = () => false;
    await c.context.confirmBangumiImport(); assert.equal(c.calls.length, 1);
});
test('当关闭读取面板后旧响应到达时应该取消等待且不覆盖新面板', async () => {
    let resolve; const pending = new Promise(done => { resolve = done; });
    const c = setup([pending]); c.context.openBangumiImport(); const reading = c.context.readBangumiImport();
    c.context.closeBangumiImport(); assert.equal(c.calls[0].options.signal.aborted, true);
    c.context.openBangumiImport(); resolve(preview); await reading;
    assert.equal(c.field('bgmImportRows').innerHTML, ''); assert.equal(c.field('bgmImportConfirm').disabled, true);
});
test('当确认仍在等待时应该阻止关闭直到收到结果', async () => {
    let resolve; const pending = new Promise(done => { resolve = done; });
    const c = setup([preview, pending]); c.context.openBangumiImport(); await c.context.readBangumiImport();
    const confirming = c.context.confirmBangumiImport(); c.context.closeBangumiImport();
    assert.match(c.field('bgmImportStatus').textContent, /等待结果/);
    resolve({ code: 200, data: { created: 0, skipped: 1, needsReview: 1, externalSaved: 1 } }); await confirming;
    c.context.closeBangumiImport(); assert.equal(c.calls.filter(x => x?.url?.endsWith('/confirm')).length, 1);
});
