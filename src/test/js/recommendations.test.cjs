const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const file = path.join(__dirname, '../../main/resources/static/js/recommendations.js');
const settings = { key: null, version: 0, minYear: null, maxYear: null, excludedTags: [], recallTags: ['剧情'], includeTracked: false };
const ready = name => ({ code: 200, data: { settings, seedTags: ['剧情'], batchKey: '22222222-2222-4222-8222-222222222222', status: 'READY', excludedCounts: {}, sourceErrors: 0, unverified: 0,
    entries: [{ id: 101, name, nameCn: '', date: '2020-01-01', score: 8, tags: ['剧情'], reason: '实际标签：剧情', tracked: false, watched: false }] } });
function setup(replies = []) {
    const elements = new Map(), calls = [], messages = [];
    const field = id => { if (!elements.has(id)) elements.set(id, { id, value: '', checked: false, disabled: false, textContent: '', innerHTML: '', classList: { contains: () => true }, focus() {}, querySelectorAll: () => [], remove() { elements.delete(id); } }); return elements.get(id); };
    const esc = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
    const context = { document: { getElementById: id => id.endsWith('Modal') ? elements.get(id) : field(id), createElement: () => ({ innerHTML: '', querySelectorAll: () => [], remove() { elements.delete(this.id); } }), body: { appendChild: el => elements.set(el.id, el) }, activeElement: null },
        fetchApi: async (url, options = {}) => { calls.push({ url, options }); const reply = replies.shift(); if (reply instanceof Error) throw reply; return await reply; },
        esc, inlineStateHtml: (kind, title, detail) => esc(title) + esc(detail), toast: value => messages.push(value),
        crypto: { randomUUID: () => '11111111-1111-4111-8111-111111111111' }, AbortController, trapFocus() {}, restoreFocus() {}, confirm: () => false, searchBangumi() {} };
    vm.createContext(context); vm.runInContext(fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '', context);
    return { context, field, calls, messages };
}
test('当缺少偏好依据时应该提示选标签且不写入近期记录', async () => {
    const c = setup([{ code: 200, data: { settings, status: 'COLD_START', entries: [], excludedCounts: {} } }]);
    assert.equal(typeof c.context.loadRecommendationPanel, 'function'); await c.context.loadRecommendationPanel();
    assert.match(c.field('recGrid').innerHTML, /标签/); assert.equal(c.calls.length, 1);
});
test('当推荐所在图表页尚未打开时应该不召回或记录未看到的候选', async () => {
    const c = setup([ready('隐藏的候选')]); c.field('tab-charts').classList.contains = () => false;
    await c.context.loadRecommendationPanel(); assert.equal(c.calls.length, 0);
});
test('当候选还在视口外时应该等实际出现后才记录展示', async () => {
    const c = setup([ready('等待滚动的候选'), { code: 200 }]); let intersect;
    c.context.IntersectionObserver = class { constructor(callback) { intersect = callback; } observe() {} disconnect() {} };
    const card = { dataset: { subject: '101' } }; c.field('recGrid').querySelectorAll = () => [card];
    await c.context.loadRecommendationPanel(); assert.equal(c.calls.length, 1);
    await intersect([{ target: card, isIntersecting: true }]); assert.equal(c.calls[1].url, '/api/recommendations/shown');
});
test('当候选展示成功时应该安全呈现并只记录实际展示编号', async () => {
    const c = setup([ready('<script>虚构候选</script>'), { code: 200 }]); await c.context.loadRecommendationPanel();
    assert.match(c.field('recGrid').innerHTML, /&lt;script&gt;/); assert.doesNotMatch(c.field('recGrid').innerHTML, /<script>/);
    assert.equal(c.calls[1].url, '/api/recommendations/shown'); assert.deepEqual(JSON.parse(c.calls[1].options.body).subjectIds, [101]);
});
test('当旧推荐晚返回时应该保留较新的结果且不记录旧展示', async () => {
    let resolve; const old = new Promise(r => { resolve = r; }); const c = setup([old, ready('新的候选'), { code: 200 }]);
    const first = c.context.loadRecommendationPanel(); await c.context.loadRecommendationPanel(); resolve(ready('迟到候选')); await first;
    assert.match(c.field('recGrid').innerHTML, /新的候选/); assert.doesNotMatch(c.field('recGrid').innerHTML, /迟到候选/);
    assert.equal(c.calls.filter(x => x.url.endsWith('/shown')).length, 1); assert.equal(c.calls[0].options.signal.aborted, true);
});
test('当条件保存冲突时应该保留用户输入并恢复保存按钮', async () => {
    const c = setup([{ code: 200, data: settings }, { code: 409, msg: '条件已变化' }]); await c.context.openRecommendationSettings();
    c.field('recMinYear').value = '2020'; await c.context.saveRecommendationSettings();
    assert.equal(c.field('recMinYear').value, '2020'); assert.equal(c.field('recSaveSettings').disabled, false);
    assert.ok(c.context.document.getElementById('recommendationSettingsModal'));
});
test('当反馈响应丢失后重试时应该保留同一请求键和内容', async () => {
    const c = setup([ready('虚构候选'), { code: 200 }, new Error('虚构响应丢失'), { code: 200, data: { active: true } }]);
    await c.context.loadRecommendationPanel(); await c.context.feedbackRecommendation(0, 'NOT_INTERESTED'); await c.context.retryRecommendationFeedback(0);
    const writes = c.calls.filter(x => x.url.endsWith('/feedback')); assert.equal(writes.length, 2); assert.equal(writes[0].options.body, writes[1].options.body);
    assert.equal(JSON.parse(writes[0].options.body).requestKey, '11111111-1111-4111-8111-111111111111');
});
test('当上游失败时应该显示错误而非没有适合作品', async () => {
    const c = setup([{ code: 502, msg: '上游核对失败' }]); await c.context.loadRecommendationPanel();
    assert.match(c.field('recGrid').innerHTML, /失败/); assert.equal(c.calls.length, 1);
});

test('当反馈尚未返回时保存新条件应该移除旧候选并在反馈结束后刷新', async () => {
    let resolve;
    const pending = new Promise(r => { resolve = r; });
    const old = ready('正在反馈的候选'); old.data.entries.push({ ...old.data.entries[0], id: 102, name: '2010年旧候选', date: '2010-01-01' });
    const fresh = ready('2024年新候选'); fresh.data.entries[0].id = 103;
    const c = setup([old, pending, { code: 200, data: settings }, { code: 200 }, fresh]);
    c.context.IntersectionObserver = class { observe() {} disconnect() {} };
    await c.context.loadRecommendationPanel();
    const feedback = c.context.feedbackRecommendation(0, 'NOT_INTERESTED');
    await c.context.openRecommendationSettings(); c.field('recMinYear').value = '2020';
    await c.context.saveRecommendationSettings();
    assert.doesNotMatch(c.field('recGrid').innerHTML, /2010年旧候选/);
    resolve({ code: 200, data: { active: true } }); await feedback;
    assert.equal(c.calls.filter(call => call.url.startsWith('/api/recommendations?')).length, 2);
    assert.match(c.field('recGrid').innerHTML, /2024年新候选/);
    assert.doesNotMatch(c.field('recGrid').innerHTML, /2010年旧候选/);
});
