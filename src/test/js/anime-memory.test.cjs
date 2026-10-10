const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function app(accepted = true) {
    const nodes = new Map(), requests = [], confirmations = [];
    const decode = value => value.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&amp;', '&');
    const node = (id, value = '') => ({ id, value, textContent: '', innerHTML: '', disabled: false, focus() {}, reportValidity: () => true });
    const section = { dataset: { animeId: '7' }, querySelectorAll: () => [...nodes.values()].filter(n => n.id !== 'memoryList'), addEventListener() {} };
    let html = '';
    Object.defineProperty(section, 'innerHTML', { get: () => html, set(value) {
        html = value;
        for (const match of value.matchAll(/<(textarea|input|select|form|div|p|button|label|details)\b[^>]*\bid="([^"]+)"[^>]*>/g)) {
            const n = node(match[2]);
            if (match[1] === 'textarea') n.value = decode(value.slice(match.index + match[0].length).split('</textarea>')[0]);
            else if (match[1] === 'input') n.value = decode(match[0].match(/value="([^"]*)"/)?.[1] || '');
            else if (match[1] === 'select') n.value = 'NOTE';
            nodes.set(n.id, n);
        }
    } });
    let current = section;
    const entry = { id: 12, animeId: 7, key: '11111111-1111-4111-8111-111111111111', content: '旧感想', liked: null, disliked: null, scope: null, context: 'INITIAL', watchedDate: null, version: 3, createdAt: '2026-10-09T19:00:00', updatedAt: '2026-10-09T19:00:00' };
    const context = vm.createContext({
        document: { getElementById: id => id === 'animeMemorySection' ? current : nodes.get(id), addEventListener() {} },
        window: { addEventListener() {} }, Date, crypto: { randomUUID: () => '22222222-2222-4222-8222-222222222222' },
        confirm: message => { confirmations.push(message); return accepted; },
        esc: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'),
        fetchApi: async (url, options) => { requests.push({ url, options }); return { code: 200, data: options?.method ? entry : { animeId: 7, page: 0, size: 20, totalEntries: 1, entries: [entry] } }; }
    });
    const file = path.resolve(__dirname, '../../main/resources/static/js/anime-memory.js');
    if (fs.existsSync(file)) vm.runInContext(fs.readFileSync(file, 'utf8'), context);
    return { context, section, entry, nodes, requests, confirmations, field: id => nodes.get(id), replaceSection: value => { current = value; } };
}

async function ready(accepted = true) { const a = app(accepted); await a.context.loadAnimeMemories(7); return a; }

test('当打开记忆区时应该提供自由原文并保持观看日期为空', async () => {
    const a = await ready();
    assert.match(a.section.innerHTML, /此刻有什么想留下的/);
    assert.equal(a.field('memoryWatchedDate').value, '');
    assert.equal(a.field('memoryContext').value, 'NOTE');
    assert.match(a.requests[0].url, /page=0&size=20/);
});

test('当保存感想时应该保留原文并允许喜欢与不满意同时存在', async () => {
    const a = await ready(); a.field('memoryContent').value = '  第一行\n第二行  ';
    a.field('memoryLiked').value = '演出'; a.field('memoryDisliked').value = '收束'; a.field('memoryScope').value = '第一季';
    await a.context.saveAnimeMemory(7);
    const body = JSON.parse(a.requests[1].options.body);
    assert.equal(body.content, '  第一行\n第二行  '); assert.equal(body.liked, '演出'); assert.equal(body.disliked, '收束');
    assert.equal(body.scope, '第一季'); assert.equal(body.watchedDate, null); assert.equal(body.context, 'NOTE');
    assert.equal(a.field('memoryContent').value, '');
});

test('当响应丢失后重试时应该保留草稿并复用同一编号', async () => {
    const a = await ready(); a.field('memoryContent').value = '感想';
    a.context.fetchApi = async (url, options) => { a.requests.push({ url, options }); return null; };
    await a.context.saveAnimeMemory(7); await a.context.saveAnimeMemory(7);
    assert.equal(a.field('memoryContent').value, '感想');
    assert.equal(JSON.parse(a.requests[1].options.body).key, JSON.parse(a.requests[2].options.body).key);
    assert.match(a.field('memoryStatus').textContent, /保留/);
});

test('当版本冲突时应该保留输入并阻止自动或重复提交', async () => {
    const a = await ready(); a.context.editAnimeMemory(7, 12); a.field('memoryContent').value = '改写';
    a.context.fetchApi = async (url, options) => { a.requests.push({ url, options }); return { code: 409, message: '记忆已变化' }; };
    await a.context.saveAnimeMemory(7); await a.context.saveAnimeMemory(7);
    assert.equal(a.requests.length, 2); assert.equal(a.field('memoryContent').value, '改写');
    assert.match(a.field('memoryStatus').textContent, /重新加载/);
    assert.equal(JSON.parse(a.requests[1].options.body).expectedVersion, 3);
});

test('当取消放弃未保存感想时应该阻止关闭与翻页并保留原文', async () => {
    const a = await ready(false); a.field('memoryContent').value = '未写完';
    assert.equal(a.context.canLeaveAnimeMemory(), false);
    await a.context.loadAnimeMemories(7, 1);
    assert.equal(a.requests.length, 1); assert.equal(a.field('memoryContent').value, '未写完');
    assert.match(a.confirmations[0], /未保存/);
});

test('当保存期间重复点击或退出时应该只提交一次并明确阻止退出', async () => {
    const a = await ready(); a.field('memoryContent').value = '感想'; let resolve;
    a.context.fetchApi = (url, options) => { a.requests.push({ url, options }); return new Promise(done => { resolve = done; }); };
    const pending = a.context.saveAnimeMemory(7); await a.context.saveAnimeMemory(7);
    assert.equal(a.context.canLeaveAnimeMemory(), false); assert.match(a.field('memoryStatus').textContent, /正在保存/);
    assert.equal(a.requests.length, 2); resolve(null); await pending;
});

test('当详情切换后旧列表返回时应该忽略过期响应', async () => {
    const a = app(); let resolve; a.context.fetchApi = () => new Promise(done => { resolve = done; });
    const pending = a.context.loadAnimeMemories(7); const before = a.field('memoryList').innerHTML;
    a.replaceSection({ dataset: { animeId: '8' }, innerHTML: '其他作品' });
    resolve({ code: 200, data: { entries: [a.entry], page: 0, size: 20, totalEntries: 1 } }); await pending;
    assert.equal(a.field('memoryList').innerHTML, before);
});

test('当同一详情内旧页较晚返回时应该只显示最新一页', async () => {
    const a = await ready(); const pendingResponses = [];
    a.context.fetchApi = () => new Promise(resolve => pendingResponses.push(resolve));
    const first = a.context.loadAnimeMemories(7, 0), second = a.context.loadAnimeMemories(7, 1);
    pendingResponses[1]({ code: 200, data: { page: 1, size: 20, totalEntries: 21, entries: [{ ...a.entry, content: '最新页' }] } }); await second;
    pendingResponses[0]({ code: 200, data: { page: 0, size: 20, totalEntries: 21, entries: [{ ...a.entry, content: '旧页' }] } }); await first;
    assert.match(a.field('memoryList').innerHTML, /最新页/); assert.doesNotMatch(a.field('memoryList').innerHTML, /旧页/);
});

test('当内容含HTML时应该只展示转义后的纯文本', async () => {
    const a = app(); a.entry.content = '<img src=x onerror="alert(1)">\n第二行'; a.entry.liked = '<script>恶意</script>';
    await a.context.loadAnimeMemories(7);
    assert.match(a.field('memoryList').innerHTML, /&lt;img/); assert.match(a.field('memoryList').innerHTML, /&lt;script/);
    assert.doesNotMatch(a.field('memoryList').innerHTML, /<img|<script/);
    assert.match(a.field('memoryList').innerHTML, /\n第二行/);
});

test('当选择回望时应该新增感想而非编辑旧记录', async () => {
    const a = await ready(); a.context.writeAnimeReflection(7); a.field('memoryContent').value = '现在的感想';
    await a.context.saveAnimeMemory(7);
    assert.equal(a.requests[1].options.method, 'POST'); assert.equal(JSON.parse(a.requests[1].options.body).context, 'REFLECTION');
    assert.equal(a.requests[1].url, '/api/anime/7/memories');
});

test('当编辑记忆时应该发送已有编号和版本', async () => {
    const a = await ready(); a.context.editAnimeMemory(7, 12); a.field('memoryContent').value = '修正错字'; await a.context.saveAnimeMemory(7);
    assert.equal(a.requests[1].options.method, 'PUT'); assert.equal(a.requests[1].url, '/api/anime/7/memories/12');
    assert.equal(JSON.parse(a.requests[1].options.body).key, a.entry.key);
    assert.equal(JSON.parse(a.requests[1].options.body).expectedVersion, 3);
});

test('当删除记忆时应该确认并发送版本', async () => {
    const a = await ready(); await a.context.deleteAnimeMemory(7, 12);
    assert.match(a.confirmations[0], /删除/); assert.equal(a.requests[1].options.method, 'DELETE');
    assert.equal(a.requests[1].url, '/api/anime/7/memories/12?expectedVersion=3');
});

test('当取消编辑或重载时应该允许保留未保存修改', async () => {
    const a = await ready(false); a.context.editAnimeMemory(7, 12); a.field('memoryContent').value = '未完成';
    a.context.cancelAnimeMemoryEdit(7); await a.context.loadAnimeMemories(7);
    assert.equal(a.field('memoryContent').value, '未完成'); assert.equal(a.requests.length, 1);
});

test('当加载失败时应该提供重试且仍保留填写入口', async () => {
    const a = app(); a.context.fetchApi = async () => null; await a.context.loadAnimeMemories(7);
    assert.match(a.field('memoryList').innerHTML, /重试/); assert.ok(a.field('memoryContent'));
});

test('当原文为空或超过长度时应该不写入', async () => {
    const a = await ready(); a.field('memoryContent').value = '   '; await a.context.saveAnimeMemory(7);
    a.field('memoryContent').value = '字'.repeat(16001); await a.context.saveAnimeMemory(7); assert.equal(a.requests.length, 1);
});

test('当日期超出范围或无效时应该保留输入且不写入', async () => {
    const a = await ready(); a.field('memoryContent').value = '感想';
    for (const value of ['0999-12-31', '9999-01-01', '2024-02-30']) { a.field('memoryWatchedDate').value = value; await a.context.saveAnimeMemory(7); }
    assert.equal(a.requests.length, 1); assert.equal(a.field('memoryContent').value, '感想');
});

test('当保存成功而列表刷新失败时应该明确已保存且不重复提交旧草稿', async () => {
    const a = await ready(); a.field('memoryContent').value = '感想';
    a.context.fetchApi = async (url, options) => { a.requests.push({ url, options }); return options?.method ? { code: 200, data: a.entry } : null; };
    await a.context.saveAnimeMemory(7);
    assert.equal(a.field('memoryContent').value, ''); assert.match(a.field('memoryStatus').textContent, /已保存.*刷新失败/);
    await a.context.saveAnimeMemory(7); assert.equal(a.requests.length, 3);
});

const appSource = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/anime-app.js'), 'utf8');
test('当关闭详情的保护被取消时应该保留弹窗并不恢复焦点', () => {
    let removed = false, focused = false;
    const context = vm.createContext({ document: { getElementById: () => ({ remove() { removed = true; } }) }, canLeaveAnimeMemory: () => false, restoreFocus: () => { focused = true; } });
    vm.runInContext(appSource.slice(appSource.indexOf('function closeDetailModal('), appSource.indexOf('function closeTraceMoeModal(')), context);
    assert.equal(context.closeDetailModal(), false); assert.equal(removed, false); assert.equal(focused, false);
});

function backupApp(allowLeave, writeCode = 200) {
    const requests = [], closed = [];
    const node = { classList: { add() {} }, setAttribute() {} };
    const context = vm.createContext({
        confirm: () => true, alert() {}, toast() {}, performSearch() {}, updateStats() {}, loadDailyWatch() {}, loadHeatmap() {},
        canLeaveAnimeMemory: () => allowLeave, closeDetailModal: force => { closed.push(force); return true; },
        fetchApi: async (url, options) => { requests.push({ url, options }); return url.endsWith('/preview') ? { code: 200, data: { valid: true, memories: 2, newMemories: 1, warnings: [] } } : { code: writeCode }; },
        document: { getElementById: () => node }
    });
    vm.runInContext(appSource.slice(appSource.indexOf('function confirmBackupImport('), appSource.indexOf('async function importData('))
        + appSource.slice(appSource.indexOf('let webdavSyncBusy'), appSource.indexOf('async function syncStatus(')), context);
    return { context, requests, closed };
}

for (const action of ['importBackupJson', 'syncPull']) {
    test(`当${action}遇到未保存感想且取消离开时应该不提交恢复`, async () => {
        const a = backupApp(false); await a.context[action]('{}'); assert.equal(a.requests.length, 1); assert.equal(a.closed.length, 0);
    });
    test(`当${action}成功时应该关闭过期详情而失败保留草稿`, async () => {
        const a = backupApp(true); await a.context[action]('{}'); assert.deepEqual(a.closed, [true]);
        const failed = backupApp(true, 409); await failed.context[action]('{}'); assert.equal(failed.closed.length, 0);
    });
}

async function detailOperationApp(action) {
    const a = await ready();
    const originalGet = a.context.document.getElementById;
    const syncNode = { classList: { add() {} }, setAttribute() {} };
    let modal = { dataset: { animeId: '7' }, remove() { modal = null; } };
    a.context.document.getElementById = id => id === 'detailModal' ? modal : id.startsWith('sync') || id.startsWith('btnSync') ? syncNode : originalGet(id);
    Object.assign(a.context, { restoreFocus() {}, alert() {}, toast() {}, performSearch() {}, updateStats() {}, loadDailyWatch() {}, loadHeatmap() {} });
    vm.runInContext(appSource.slice(appSource.indexOf('function closeDetailModal('), appSource.indexOf('function closeTraceMoeModal('))
        + appSource.slice(appSource.indexOf('async function deleteAnime('), appSource.indexOf('async function changeStatus('))
        + appSource.slice(appSource.indexOf('function confirmBackupImport('), appSource.indexOf('async function importData('))
        + appSource.slice(appSource.indexOf('let webdavSyncBusy'), appSource.indexOf('async function syncStatus(')), a.context);
    a.field('memoryContent').value = '操作前的原稿';
    let release;
    a.context.fetchApi = async (url, options) => {
        a.requests.push({ url, options });
        if (url.endsWith('/preview')) return { code: 200, data: { valid: true, warnings: [], fingerprint: '摘要' } };
        return new Promise(resolve => { release = resolve; });
    };
    const pending = a.context[action](action === 'deleteAnime' ? 7 : '{}');
    await Promise.resolve(); await Promise.resolve();
    return { ...a, pending, release: result => release(result), getModal: () => modal, setModal: value => { modal = value; } };
}

for (const [action, label] of [['importBackupJson', '本地恢复'], ['syncPull', 'WebDAV恢复'], ['deleteAnime', '删除作品']]) {
    test(`当${label}请求等待时应该锁住原稿与保存并阻止退出`, async () => {
        const a = await detailOperationApp(action);
        assert.equal(a.field('memoryContent').disabled, true);
        assert.equal(a.field('memorySave').disabled, true);
        assert.equal(a.context.closeDetailModal(), false);
        const count = a.requests.length; await a.context.saveAnimeMemory(7); assert.equal(a.requests.length, count);
        a.release({ code: 409, message: '操作失败' }); await a.pending;
        assert.equal(a.field('memoryContent').disabled, false); assert.equal(a.field('memoryContent').value, '操作前的原稿'); assert.ok(a.getModal());
    });
    test(`当${label}完成前出现另一详情时应该保留后来详情`, async () => {
        const a = await detailOperationApp(action); let removed = false;
        const later = { dataset: { animeId: '8' }, remove() { removed = true; } }; a.setModal(later);
        a.release({ code: 200 }); await a.pending;
        assert.equal(removed, false); assert.equal(a.getModal(), later);
    });
    test(`当${label}异常终止时应该释放原稿并保留内容`, async () => {
        const a = await detailOperationApp(action);
        a.release(null); await a.pending;
        assert.equal(a.field('memoryContent').disabled, false); assert.equal(a.field('memoryContent').value, '操作前的原稿'); assert.ok(a.getModal());
    });
}
