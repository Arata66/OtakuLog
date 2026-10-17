const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function app() {
    const nodes = new Map(), calls = [], dialogs = [];
    const decode = text => text.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&amp;', '&');
    function node(id) {
        const n = { id, value: '', textContent: '', disabled: false, hidden: false, focus() {}, remove() { nodes.delete(id); }, querySelectorAll: () => [...nodes.values()], addEventListener() {}, appendChild(child) { nodes.set(child.id, child); } };
        let html = '';
        Object.defineProperty(n, 'innerHTML', { get: () => html, set(value) {
            html = value;
            for (const m of value.matchAll(/<(textarea|input|select|form|div|p|button|details|section|h5)\b[^>]*\bid="([^"]+)"[^>]*>/g)) {
                const child = node(m[2]);
                if (m[1] === 'textarea') child.value = decode(value.slice(m.index + m[0].length).split('</textarea>')[0]);
                if (m[1] === 'input') child.value = decode(m[0].match(/value="([^"]*)"/)?.[1] || '');
                if (m[1] === 'select') child.value = value.slice(m.index).match(/<option value="([^"]+)"/)?.[1] || '';
            }
        } });
        nodes.set(id, n); return n;
    }
    node('tasteMount'); node('tasteProfile'); node('tasteUsage');
    const memory = { id: 12, animeId: 7, key: 'source', content: '虚构音乐很动人', liked: '', disliked: '中段音乐太满', scope: '第一季', context: 'INITIAL', version: 3 };
    const insight = { id: 1, key: 'insight', sourceVersion: 3, quoteField: 'CONTENT', quote: memory.content, statement: '音乐让我投入', direction: 'LIKE', aspect: 'AUDIOVISUAL', factor: '音乐', status: 'PENDING', origin: 'AI', version: 0, stale: false };
    const context = vm.createContext({ document: { getElementById: id => nodes.get(id), createElement: () => node('new'), readyState: 'loading', addEventListener() {} }, window: { addEventListener() {} }, crypto: { randomUUID: () => '11111111-1111-4111-8111-111111111111' }, confirm: message => { dialogs.push(message); return false; }, esc: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'), memoryContexts: { INITIAL: '初看' },
        fetchApi: async (url, options) => {
            calls.push({ url, options });
            if (url.endsWith('/profile')) return { code: 200, data: { animeCount: 0, evidenceCount: 0, staleCount: 0, groups: [] } };
            if (url.endsWith('/usage')) return { code: 200, data: { callsToday: 1, dailyLimit: 20, remainingCalls: 19, knownInputTokens: 12, knownOutputTokens: 3, unknownCallCount: 0 } };
            if (url.endsWith('/preview')) return { code: 200, data: { configured: true, sourceVersion: 3, selections: { content: memory.content, liked: '', disliked: memory.disliked }, selectedCharacters: 15, remainingCalls: 19, maxOutputTokens: 1200, estimatedInputTokens: 80, estimatedCostYuan: 0.01, model: 'deepseek-flash', provider: 'DeepSeek' } };
            if (url.endsWith('/extract') || url.includes('/calls/')) return { code: 200, data: { requestKey: '11111111-1111-4111-8111-111111111111', status: 'SUCCEEDED', cached: false, entries: [insight], usage: { promptTokens: 12, completionTokens: 3 } } };
            if (url.endsWith('/insights') && !options?.method) return { code: 200, data: { entries: [insight] } };
            if (options?.method) return { code: 200, data: { ...insight, ...JSON.parse(options.body || '{}') } };
            return { code: 200, data: memory };
        } });
    require('./helpers/ui-events.cjs')(context);
    const file = path.resolve(__dirname, '../../main/resources/static/js/taste-profile.js');
    if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    return { context, nodes, calls, memory, insight, dialogs, field: id => nodes.get(id) };
}
async function ready() { const a = app(); assert.equal(typeof a.context.openTasteMemory, 'function', '应该有整理观点入口'); await a.context.openTasteMemory(7, 12); return a; }

test('当候选尚未确认时应该不显示在本地总结', async () => {
    const a = await ready(); await a.context.refreshTasteProfile();
    assert.match(a.field('tasteProfile').innerHTML, /没有已确认/);
    assert.doesNotMatch(a.field('tasteProfile').innerHTML, /音乐让我投入/);
    assert.match(a.field('tasteEntries').innerHTML, /待核对/);
});
test('当手工确认改写或拒绝观点时应该只调用本地写入', async () => {
    const a = await ready(); await a.context.setTasteInsightStatus(1, 'CONFIRMED');
    a.context.editTasteInsight(1); a.field('tasteStatement').value = '我更喜欢结尾音乐'; await a.context.saveTasteInsight();
    await a.context.setTasteInsightStatus(1, 'REJECTED');
    assert.equal(a.calls.filter(c => c.url.includes('/ai/') && c.options?.method).length, 0);
    const writes = a.calls.filter(c => c.options?.method === 'PUT'); assert.equal(writes.length, 3);
    assert.equal(JSON.parse(writes[1].options.body).statement, '我更喜欢结尾音乐');
});
test('当预览后取消时应该不向AI发送且改变选文使预览失效', async () => {
    const a = await ready(); await a.context.previewTasteAi();
    assert.match(a.field('tastePreview').innerHTML, /明确发送并整理/);
    assert.equal(a.calls.filter(c => c.url.endsWith('/extract')).length, 0);
    a.field('tasteSelectContent').value = '音乐'; a.context.invalidateTastePreview();
    await a.context.sendTasteAi(); assert.equal(a.calls.filter(c => c.url.endsWith('/extract')).length, 0);
});
test('当完整原文超过预算时应该保持完整输入并要求用户自己选连续片段', async () => {
    const a = app(); a.memory.content = '字'.repeat(6001); await a.context.openTasteMemory(7, 12);
    assert.equal(a.field('tasteSelectContent').value.length, 6001);
    await a.context.previewTasteAi();
    assert.equal(a.calls.filter(c => c.url.endsWith('/preview')).length, 0);
    assert.match(a.field('tasteStatus').textContent, /6000/);
});
test('当发送响应丢失时应该先GET核对同一编号且不自动再次发送', async () => {
    const a = await ready(); const original = a.context.fetchApi;
    a.context.fetchApi = async (url, options) => url.endsWith('/extract') ? (a.calls.push({ url, options }), null) : original(url, options);
    await a.context.previewTasteAi(); await a.context.sendTasteAi();
    assert.equal(a.calls.filter(c => c.url.endsWith('/extract')).length, 1);
    assert.equal(a.calls.filter(c => c.url.includes('/calls/')).length, 1);
    assert.match(a.field('tasteResult').innerHTML, /12/);
});
test('当调用编号尚未找到时应该保留草稿并禁止自动新编号重发', async () => {
    const a = await ready(); const original = a.context.fetchApi;
    a.context.fetchApi = async (url, options) => url.endsWith('/extract') || url.includes('/calls/') ? (a.calls.push({ url, options }), { code: 404, message: '未找到' }) : original(url, options);
    await a.context.previewTasteAi(); await a.context.sendTasteAi(); await a.context.sendTasteAi();
    assert.equal(a.calls.filter(c => c.url.endsWith('/extract')).length, 1);
    assert.match(a.field('tasteStatus').textContent, /编号.*保留|保留.*编号/);
    assert.equal(a.context.canLeaveTasteMemory(), false);
});
test('当观点写入409时应该保留输入并要求重新核对', async () => {
    const a = await ready(); a.context.editTasteInsight(1); a.field('tasteStatement').value = '仍未写完';
    a.context.fetchApi = async (url, options) => (a.calls.push({ url, options }), { code: 409, message: '已修改' });
    await a.context.saveTasteInsight(); await a.context.saveTasteInsight();
    assert.equal(a.field('tasteStatement').value, '仍未写完'); assert.match(a.field('tasteStatus').textContent, /保留.*重新/);
    assert.equal(a.calls.filter(c => c.options?.method === 'PUT').length, 1);
});
test('当旧作品迟到响应返回时应该不覆盖新打开的作品', async () => {
    const a = app(); let releases = []; a.context.fetchApi = (url, options) => new Promise(resolve => releases.push({ url, resolve }));
    const old = a.context.openTasteMemory(7, 12); a.context.closeTasteMemory(true);
    const next = a.context.openTasteMemory(8, 13);
    for (const r of releases.slice(2)) r.resolve({ code: 200, data: r.url.endsWith('/insights') ? { entries: [] } : { ...a.memory, id: 13, content: '新的原文' } }); await next;
    for (const r of releases.slice(0, 2)) r.resolve({ code: 200, data: r.url.endsWith('/insights') ? { entries: [a.insight] } : a.memory }); await old;
    assert.match(a.field('tastePanel').innerHTML, /新的原文/); assert.doesNotMatch(a.field('tastePanel').innerHTML, /虚构音乐/);
});
test('当原文含HTML时应该只展示纯文本', async () => {
    const a = app(); a.memory.content = '<img src=x onerror=alert(1)>'; a.insight.quote = '<script>恶意</script>'; await a.context.openTasteMemory(7, 12);
    assert.match(a.field('tastePanel').innerHTML, /&lt;img/); assert.match(a.field('tasteEntries').innerHTML, /&lt;script/);
    assert.doesNotMatch(a.field('tasteEntries').innerHTML, /<script/);
});
test('当AI成功时应该仍保护尚未保存的手工观点草稿', async () => {
    const a = await ready(); a.field('tasteStatement').value = '我的观点还没写完';
    await a.context.previewTasteAi(); await a.context.sendTasteAi();
    assert.equal(a.field('tasteStatement').value, '我的观点还没写完'); assert.equal(a.context.canLeaveTasteMemory(), false);
});
test('当重置手工表单时应该仍保护修改后的AI选文', async () => {
    const a = await ready(); a.field('tasteSelectContent').value = '音乐'; a.context.resetTasteForm(false);
    assert.equal(a.context.canLeaveTasteMemory(), false);
});
test('当AI成功后希望整理其他选文时应该提供显式新预览入口', async () => {
    const a = await ready(); await a.context.previewTasteAi(); await a.context.sendTasteAi();
    assert.match(a.field('tasteResult').innerHTML, /核对后开始新的预览/);
});
test('当历史RUNNING结果未知时应该核对后允许显式新预览且不自动发送', async () => {
    const a = await ready(); const original = a.context.fetchApi;
    a.context.fetchApi = async (url, options) => url.endsWith('/extract') || url.includes('/calls/') ? { code: 200, data: { requestKey: '11111111-1111-4111-8111-111111111111', status: 'RUNNING', message: '整理结果未知，可能已计费', entries: [] } } : original(url, options);
    a.context.confirm = () => true; await a.context.previewTasteAi(); await a.context.sendTasteAi(); await a.context.newTasteAiRequest();
    assert.match(a.field('tastePreview').innerHTML, /明确发送并整理/);
    assert.match(a.field('tasteStatus').textContent, /预览完成/);
});
test('当核对旧成功调用时应该仍保护随后修改的AI选文', async () => {
    const a = await ready(); await a.context.previewTasteAi(); await a.context.sendTasteAi();
    a.field('tasteSelectContent').value = '虚构'; await a.context.reconcileTasteAi();
    assert.equal(a.context.canLeaveTasteMemory(), false);
});
test('当已有手工观点时读取AI结果应该保留独立手工观点', async () => {
    const a = await ready(); a.insight.aiCallKey = '11111111-1111-4111-8111-111111111111';
    a.field('tasteQuote').value = a.memory.content; a.field('tasteStatement').value = '手工观点'; a.field('tasteFactor').value = '留白';
    const original = a.context.fetchApi;
    a.context.fetchApi = async (url, options) => options?.method === 'POST' && url.endsWith('/insights') ? { code: 200, data: { ...a.insight, ...JSON.parse(options.body), id: 2, origin: 'MANUAL', aiCallKey: null } } : original(url, options);
    await a.context.saveTasteInsight(); await a.context.previewTasteAi(); await a.context.sendTasteAi();
    assert.match(a.field('tasteEntries').innerHTML, /手工观点/);
});
test('当原文加载期间开始恢复操作时应该在加载后保持等待保护并正确恢复控件', async () => {
    const a = app(), responses = []; a.context.fetchApi = url => new Promise(resolve => responses.push({ url, resolve }));
    const loading = a.context.openTasteMemory(7, 12); const operation = a.context.beginTasteOperation('正在恢复备份');
    for (const r of responses) r.resolve({ code: 200, data: r.url.endsWith('/insights') ? { entries: [] } : a.memory }); await loading;
    assert.equal(a.context.canLeaveTasteMemory(), false); assert.equal(a.field('tasteSave').disabled, true);
    a.context.endTasteOperation(operation); assert.equal(a.field('tasteSave').disabled, false); assert.equal(a.context.canLeaveTasteMemory(), true);
});
