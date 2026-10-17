const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const key = '22222222-2222-4222-8222-222222222222';
const source = { insightKey: key, sourceVersion: 0, insightVersion: 0, sourceFingerprint: 'a'.repeat(64), direction: 'LIKE', animeName: '<script>虚构来源</script>', factor: '人物选择', statement: '虚构观点', quote: '虚构原文', animeId: 1, memoryId: 1 };
function setup(replies = []) {
    const fields = new Map(), calls = [];
    const field = id => { if (!fields.has(id)) fields.set(id, { value: '', checked: false, disabled: false, hidden: false, textContent: '', innerHTML: '', classList: { contains: () => true }, querySelectorAll: () => [], focus() {} }); return fields.get(id); };
    const context = { document: { getElementById: id => id.endsWith('Modal') ? fields.get(id) : field(id), createElement: () => ({ querySelectorAll: () => [], remove() { fields.delete(this.id); } }), body: { appendChild: el => fields.set(el.id, el) }, activeElement: null },
        fetchApi: async (url, options = {}) => { calls.push({ url, options }); const r = replies.shift(); if (r instanceof Error) throw r; return await r; },
        crypto: { randomUUID: () => '11111111-1111-4111-8111-111111111111' }, AbortController, URLSearchParams, esc: x => String(x ?? '').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;'),
        inlineStateHtml: (_, title, detail) => title + detail, toast() {}, confirm: () => true, trapFocus() {}, restoreFocus() {} };
    vm.createContext(context); require('./helpers/ui-events.cjs')(context);
    for (const name of ['recommendations.js', 'recommendation-bases.js']) { const file = path.join(__dirname, '../../main/resources/static/js', name); if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context); }
    return { context, field, fields, calls };
}
const page = { code: 200, data: { sources: [source], entries: [] } };
test('当打开关联管理时应该只读取来源且安全显示原文', async () => {
    const c = setup([page]); assert.equal(typeof c.context.openRecommendationBases, 'function'); await c.context.openRecommendationBases();
    assert.equal(c.calls.length, 1); assert.equal(c.calls[0].options.method, undefined);
    assert.doesNotMatch(c.field('recBasisEvidence').innerHTML, /<script>/); assert.match(c.field('recBasisEvidence').innerHTML, /&lt;script&gt;/);
});
test('当预览后改动标签时应该要求重新预览而不提交旧目标', async () => {
    const c = setup([page, { code: 200, data: { effect: '按剧情召回', tags: ['剧情'] } }]); await c.context.openRecommendationBases();
    c.field('recBasisTag').value = '剧情'; await c.context.previewRecommendationBasis(); c.field('recBasisTag').value = '机战';
    await c.context.saveRecommendationBasis(); assert.equal(c.calls.length, 2); assert.match(c.field('recModalStatus').textContent, /重新预览/);
});
test('当保存响应丢失后重试时应该保留同一编号及完整请求', async () => {
    const c = setup([page, { code: 200, data: { effect: '按剧情召回', tags: ['剧情'] } }, new Error('虚构响应丢失'), { code: 409, msg: '虚构版本冲突' }]);
    await c.context.openRecommendationBases(); c.field('recBasisTag').value = '剧情'; await c.context.previewRecommendationBasis(); await c.context.saveRecommendationBasis(); await c.context.saveRecommendationBasis();
    const writes = c.calls.filter(call => call.url.endsWith('/bases')); assert.equal(writes.length, 3);
    assert.equal(writes[1].options.body, writes[2].options.body); assert.equal(c.field('recBasisTag').value, '剧情');
});
test('当旧预览迟到而选择已经变化时应该不启用旧预览保存', async () => {
    let resolve; const pending = new Promise(r => { resolve = r; }); const c = setup([page, pending]); await c.context.openRecommendationBases();
    c.field('recBasisTag').value = '剧情'; const request = c.context.previewRecommendationBasis(); c.field('recBasisTag').value = '机战'; c.context.recommendationBasisFormChanged();
    resolve({ code: 200, data: { effect: '旧剧情', tags: ['剧情'] } }); await request;
    assert.equal(c.field('recBasisSave').disabled, true); assert.doesNotMatch(c.field('recModalStatus').textContent, /旧剧情/);
});
test('当明确按作品或标签推荐时应该只传对应模式的输入', () => {
    const c = setup(); assert.equal(typeof c.context.recommendationQuery, 'function');
    c.field('recMode').value = 'SEED'; c.field('recSeedId').value = '201'; c.field('recModeTags').value = '剧情';
    const seed = c.context.recommendationQuery(); assert.match(seed, /mode=SEED/); assert.match(seed, /subjectId=201/);
    c.field('recMode').value = 'TAGS'; const tags = c.context.recommendationQuery(); assert.match(tags, /mode=TAGS/); assert.doesNotMatch(tags, /subjectId/);
});
test('当首次读取已保存关联时应该等待用户勾选而不自动挑选口味依据', async () => {
    const sources = [1,2,3,4].map(i => ({ ...source, insightKey: `00000000-0000-4000-8000-${String(i).padStart(12,'0')}`, direction: i === 4 ? 'DISLIKE' : 'LIKE' }));
    const entries = sources.map(s => ({ state: 'ACTIVE', source: s, data: { key: s.insightKey, insightKey: s.insightKey, kind: 'TAG', tag: '剧情', direction: s.direction, enabled: true, version: 0 } }));
    const c = setup([{ code: 200, data: { sources, entries } }]); await c.context.openRecommendationBases(); c.field('recMode').value = 'TASTE';
    assert.doesNotMatch(c.context.recommendationQuery(), /basisKeys/);
    c.context.toggleRecommendationBasis(entries[0].data.key, true); assert.match(c.context.recommendationQuery(), /basisKeys/);
});
test('当重新读取发现所选来源失效时应该移除旧口味候选', async () => {
    const entry = { data: { key, insightKey: key, tag: '剧情', kind: 'TAG', direction: 'LIKE', enabled: true, version: 0 }, source, state: 'ACTIVE' };
    const active = { code: 200, data: { sources: [source], entries: [entry] } };
    const ready = { code: 200, data: { entries: [{ id: 201, name: '旧依据候选', nameCn: '', reason: '剧情' }], seedTags: ['剧情'], status: 'READY' } };
    const stale = { code: 200, data: { sources: [], entries: [{ ...entry, state: 'STALE', source: null }] } };
    const c = setup([active, ready, stale]); c.context.IntersectionObserver = class { observe() {} disconnect() {} };
    await c.context.openRecommendationBases(); c.context.closeRecommendationModal(); c.field('recMode').value = 'TASTE';
    c.context.toggleRecommendationBasis(key, true); await c.context.loadRecommendationPanel(); assert.match(c.field('recGrid').innerHTML, /旧依据候选/);
    await c.context.openRecommendationBases(); assert.doesNotMatch(c.field('recGrid').innerHTML, /旧依据候选/); assert.doesNotMatch(c.context.recommendationQuery(), /basisKeys/);
});
