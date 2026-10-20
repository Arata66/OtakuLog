const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function app() {
    const fields = new Map(['memoryReviewAnime', 'memoryReviewContext', 'memoryReviewMonth', 'memoryReviewOrder',
        'memoryReviewResults', 'memoryReviewPages', 'memoryReviewStatus', 'tab-memories'].map(id => [id,
        { value: '', innerHTML: '', textContent: '', setAttribute() {}, classList: { contains: () => true } }]));
    fields.get('memoryReviewOrder').value = 'newest';
    const requests = [], opened = [];
    const escape = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
    const context = vm.createContext({ URLSearchParams, document: { getElementById: id => fields.get(id), addEventListener() {} },
        window: { addEventListener() {} }, _cache: {}, memoryContexts: { NOTE: '随手记', INITIAL: '初看', REFLECTION: '回望', REWATCH: '重看' },
        esc: escape, memoryTextHtml: value => '<p>' + escape(value) + '</p>', uiAction: name => 'data-ui-click="' + name + '"',
        openDetailModal: (...args) => opened.push(args), toast() {},
        fetchApi: (url, options) => new Promise(resolve => requests.push({ url, options, resolve })) });
    const file = path.resolve(__dirname, '../../main/resources/static/js/memory-review.js');
    if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    return { context, fields, requests, opened };
}
function response(text = '虚构原文', total = 1) {
    return { code: 200, data: { page: 0, size: 20, totalEntries: total, works: [{ id: 7, name: '虚构作品' }],
        entries: total ? [{ animeName: '虚构作品', memory: { id: 11, animeId: 7, content: text, context: 'INITIAL',
            createdAt: '2026-10-01T10:00:00', watchedDate: null } }] : [] } };
}
test('当切换筛选时应该传递书写月份与明确条件且只发送读取', async () => {
    const a = app(); a.fields.get('memoryReviewMonth').value = '2026-10'; a.fields.get('memoryReviewAnime').value = '7';
    a.fields.get('memoryReviewContext').value = 'INITIAL';
    const pending = a.context.loadMemoryReview(2);
    const url = new URL(a.requests[0].url, 'http://localhost');
    assert.equal(url.searchParams.get('month'), '2026-10'); assert.equal(url.searchParams.get('page'), '2');
    assert.equal(url.searchParams.get('animeId'), '7'); assert.equal(url.searchParams.get('context'), 'INITIAL');
    assert.equal(a.requests[0].options?.method, undefined); a.requests[0].resolve(response()); await pending;
});
test('当旧查询迟到时应该保留较新的筛选结果', async () => {
    const a = app(), first = a.context.loadMemoryReview(0), second = a.context.loadMemoryReview(0);
    a.requests[1].resolve(response('新筛选')); await second; a.requests[0].resolve(response('旧筛选')); await first;
    assert.match(a.fields.get('memoryReviewResults').innerHTML, /新筛选/);
    assert.doesNotMatch(a.fields.get('memoryReviewResults').innerHTML, /旧筛选/);
});
test('当感想含HTML时应该转义原文并明确未知观看日期', async () => {
    const a = app(), pending = a.context.loadMemoryReview();
    a.requests[0].resolve(response('<script>原文</script>')); await pending;
    const html = a.fields.get('memoryReviewResults').innerHTML;
    assert.match(html, /&lt;script&gt;/); assert.doesNotMatch(html, /<script>/); assert.match(html, /观看日期：未填写/);
});
test('当查询失败时应该保留筛选并提供重试而不显示空库', async () => {
    const a = app(); a.fields.get('memoryReviewMonth').value = '2026-10';
    const pending = a.context.loadMemoryReview(); a.requests[0].resolve(null); await pending;
    assert.equal(a.fields.get('memoryReviewMonth').value, '2026-10');
    assert.match(a.fields.get('memoryReviewResults').innerHTML, /重试/);
    assert.doesNotMatch(a.fields.get('memoryReviewResults').innerHTML, /还没有观影记忆/);
});
test('当筛选为空时应该区分无结果并允许清除筛选', async () => {
    const a = app(); a.fields.get('memoryReviewMonth').value = '2026-10';
    const pending = a.context.loadMemoryReview(); a.requests[0].resolve(response('', 0)); await pending;
    assert.match(a.fields.get('memoryReviewResults').innerHTML, /没有符合条件/);
    const reset = a.context.resetMemoryReview();
    assert.equal(a.fields.get('memoryReviewMonth').value, ''); a.requests[1].resolve(response('', 0)); await reset;
    assert.match(a.fields.get('memoryReviewResults').innerHTML, /还没有观影记忆/);
});
test('当打开旧感想时应该重新读取并跳到该条所在分页', async () => {
    const a = app(), pending = a.context.openMemoryReviewEntry(11);
    assert.equal(a.requests[0].url, '/api/memories/11');
    a.requests[0].resolve({ code: 200, data: { anime: { id: 7, name: '新作品名' }, memoryId: 11, page: 2 } }); await pending;
    assert.equal(a.context._cache[7].name, '新作品名'); assert.deepEqual(a.opened[0], [7, 2, 11]);
});
