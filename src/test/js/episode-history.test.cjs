const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function app(accepted = true) {
    const requests = [], messages = [], confirmations = [];
    const section = { dataset: { animeId: '7' }, innerHTML: '', querySelectorAll: () => [] };
    const status = { textContent: '' }, input = { value: '2024-01-02' };
    let current = section;
    const entry = { episodeNumber: 1, recorded: true, recordId: 12, watchedDate: '2024-01-02', source: 'LEGACY', updatedAt: null };
    const context = vm.createContext({
        document: { getElementById(id) { return id === 'episodeHistorySection' ? current : id === 'episodeHistoryStatus' ? status : input; } },
        esc: value => String(value ?? '').replaceAll('<', '&lt;').replaceAll('"', '&quot;'),
        confirm: message => { confirmations.push(message); return accepted; },
        toast: message => messages.push(message), loadHeatmap: () => {}, Date,
        fetchApi: async (url, options) => {
            requests.push({ url, options });
            return { code: 200, data: { animeId: 7, currentEpisode: 1, page: 0, size: 20, totalRows: 1, entries: [entry] } };
        }
    });
    const file = path.resolve(__dirname, '../../main/resources/static/js/episode-history.js');
    if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    return { context, section, status, input, entry, requests, messages, confirmations, replaceSection(value) { current = value; } };
}

test('当打开历史列表时应该只读取并解释旧来源且不填今天', async () => {
    const a = app(); a.entry.watchedDate = null;
    await a.context.loadEpisodeHistory(7);
    assert.equal(a.requests.length, 1); assert.equal(a.requests[0].options, undefined);
    assert.match(a.section.innerHTML, /历史来源不明/); assert.match(a.section.innerHTML, /value=""/);
    assert.match(a.section.innerHTML, /核对并保存/);
});

test('当确认旧日期时应该携带原始快照并只保存该集', async () => {
    const a = app(); await a.context.loadEpisodeHistory(7);
    await a.context.saveEpisodeDate(7, 1);
    assert.equal(a.confirmations.length, 1);
    const write = a.requests.find(r => r.options?.method === 'PUT');
    assert.equal(write.url, '/api/anime/7/episodes/1');
    assert.deepEqual(JSON.parse(write.options.body), { watchedDate: '2024-01-02', expected: a.entry });
    assert.equal(write.options.returnConflict, true);
});

test('当取消核对旧记录时应该不写入', async () => {
    const a = app(false); await a.context.loadEpisodeHistory(7);
    await a.context.saveEpisodeDate(7, 1);
    assert.equal(a.requests.length, 1);
});

test('当清空已知日期时应该确认并发送空值而非今天', async () => {
    const a = app(); a.entry.source = 'WATCHED'; await a.context.loadEpisodeHistory(7);
    a.input.value = ''; await a.context.saveEpisodeDate(7, 1);
    assert.match(a.confirmations[0], /清空/);
    assert.equal(JSON.parse(a.requests[1].options.body).watchedDate, null);
});

test('当快照冲突时应该保留输入提示重载且不自动重试', async () => {
    const a = app(); await a.context.loadEpisodeHistory(7); a.input.value = '2024-02-03';
    a.context.fetchApi = async (url, options) => { a.requests.push({ url, options }); return { code: 409, message: '记录已变化，请重新加载' }; };
    await a.context.saveEpisodeDate(7, 1); await a.context.saveEpisodeDate(7, 1);
    assert.equal(a.requests.length, 2); assert.equal(a.input.value, '2024-02-03');
    assert.match(a.status.textContent, /重新加载/);
});

test('当详情已切换时应该忽略旧的列表响应', async () => {
    const a = app(); let resolve;
    a.context.fetchApi = () => new Promise(done => { resolve = done; });
    const pending = a.context.loadEpisodeHistory(7); a.replaceSection({ innerHTML: '新详情', dataset: { animeId: '8' } });
    const before = a.section.innerHTML;
    resolve({ code: 200, data: { entries: [a.entry], totalRows: 1, page: 0, size: 20 } }); await pending;
    assert.equal(a.section.innerHTML, before);
});

test('当保存期间连续点击时应该只提交一次', async () => {
    const a = app(); await a.context.loadEpisodeHistory(7); let resolve;
    a.context.fetchApi = (url, options) => { a.requests.push({ url, options }); return new Promise(done => { resolve = done; }); };
    const pending = a.context.saveEpisodeDate(7, 1); await a.context.saveEpisodeDate(7, 1);
    assert.equal(a.requests.length, 2); resolve(null); await pending;
});

test('当翻页前有未保存日期时应该允许取消离开', async () => {
    const a = app(false); await a.context.loadEpisodeHistory(7); a.input.value = '2024-02-03';
    await a.context.changeEpisodePage(7, 1);
    assert.equal(a.requests.length, 1); assert.match(a.confirmations[0], /未保存/);
});

test('当清空已知日期但取消确认时应该保留原记录', async () => {
    const a = app(false); a.entry.source = 'WATCHED'; await a.context.loadEpisodeHistory(7);
    a.input.value = ''; await a.context.saveEpisodeDate(7, 1);
    assert.equal(a.requests.length, 1); assert.match(a.confirmations[0], /清空/);
});

test('当日期输入未通过浏览器校验时应该阻止提交', async () => {
    const a = app(); await a.context.loadEpisodeHistory(7); a.input.reportValidity = () => false;
    await a.context.saveEpisodeDate(7, 1); assert.equal(a.requests.length, 1);
});

test('当保存完成前已关闭详情时应该不修改新页面', async () => {
    const a = app(); await a.context.loadEpisodeHistory(7); let resolve;
    a.context.fetchApi = () => new Promise(done => { resolve = done; });
    const pending = a.context.saveEpisodeDate(7, 1); const before = a.section.innerHTML;
    a.replaceSection(null); resolve({ code: 200 }); await pending;
    assert.equal(a.section.innerHTML, before); assert.equal(a.messages.length, 0);
});

test('当保存成功但刷新失败时应该保留输入并要求重载后再保存', async () => {
    const a = app(); await a.context.loadEpisodeHistory(7);
    a.context.fetchApi = async (url, options) => { a.requests.push({ url, options }); return options ? { code: 200 } : null; };
    a.input.value = '2024-02-03'; await a.context.saveEpisodeDate(7, 1);
    assert.equal(a.input.value, '2024-02-03'); assert.match(a.status.textContent, /日期已保存.*刷新失败/);
    await a.context.saveEpisodeDate(7, 1); assert.equal(a.requests.length, 3);
});

test('当保存另一集后刷新时应该保留草稿且不把未修改旧值覆盖新记录', async () => {
    const a = app();
    const second = { ...a.entry, episodeNumber: 2, recordId: 13, source: 'MANUAL' };
    const third = { ...a.entry, episodeNumber: 3, recordId: 14, source: 'MANUAL' };
    const inputs = new Map([1, 2, 3].map(number => [number, { value: '2024-01-02' }]));
    const originalGet = a.context.document.getElementById;
    a.context.document.getElementById = id => id.startsWith('episode-date-') ? inputs.get(Number(id.split('-').at(-1))) : originalGet(id);
    let refreshed = false;
    a.context.fetchApi = async (url, options) => {
        if (options) { refreshed = true; return { code: 200 }; }
        inputs.get(2).value = '2024-01-02'; inputs.get(3).value = refreshed ? '2024-03-04' : '2024-01-02';
        return { code: 200, data: { page: 0, size: 20, totalRows: 3, currentEpisode: 3,
            entries: [a.entry, second, { ...third, watchedDate: refreshed ? '2024-03-04' : '2024-01-02' }] } };
    };
    await a.context.loadEpisodeHistory(7); inputs.get(2).value = '2024-02-03';
    await a.context.saveEpisodeDate(7, 1);
    assert.equal(inputs.get(2).value, '2024-02-03');
    assert.equal(inputs.get(3).value, '2024-03-04');
});
