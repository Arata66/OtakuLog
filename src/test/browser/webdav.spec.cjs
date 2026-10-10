const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { randomBytes, randomUUID } = require('node:crypto');
const { test: base, expect, handleDialog, openAnime } = require('./fixtures.cjs');

const test = base.extend({
    dav: async ({}, use) => {
        const password = randomBytes(24).toString('base64url');
        const state = { file: fs.readFileSync(path.join(__dirname, '../resources/fixtures/demo-backup.json'), 'utf8'),
            requests: [], failure: 0, release: null, received: null, hold: false };
        const server = http.createServer(async (req, res) => {
            const chunks = []; for await (const chunk of req) chunks.push(chunk);
            const body = Buffer.concat(chunks).toString('utf8');
            const authenticated = req.headers.authorization === 'Basic ' + Buffer.from('demo:' + password).toString('base64');
            state.requests.push({ method: req.method, path: req.url, authenticated });
            if (!authenticated || state.failure) { res.writeHead(authenticated ? state.failure : 401); res.end(); return; }
            if (req.method === 'HEAD') { res.writeHead(200); res.end(); return; }
            if (req.url !== '/dav/demo-backup.json') { res.writeHead(404); res.end(); return; }
            if (req.method === 'PUT') {
                if (state.hold) { const wait = new Promise(resolve => { state.release = resolve; }); state.received(); await wait; }
                state.file = body; res.writeHead(201); res.end(); return;
            }
            res.setHeader('Content-Type', 'application/json;charset=UTF-8'); res.end(state.file);
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        const config = { url: `http://127.0.0.1:${server.address().port}/dav/`, username: 'demo', password };
        try { await use({ state, config }); }
        finally { state.release?.(); await new Promise(resolve => server.close(resolve)); }
    },
    webdavConfig: async ({ dav }, use) => use(dav.config)
});

async function readExport(page, demo) {
    const response = await page.request.get(demo.url + '/api/anime/export'); expect(response.status()).toBe(200); return response.json();
}
function business(document) { return { ...document, exportedAt: null }; }
async function clickSync(page, id) { await page.locator('#syncBtn').click(); await page.locator('#' + id).click(); }
function addRemoteAnime(dav, date) {
    const backup = JSON.parse(dav.state.file);
    backup.anime.push({ key: 'remote-fixture', data: { ...backup.anime[0].data, name: '虚构·远程恢复', currentEpisode: 1,
        totalEpisodes: 3, status: 'WATCHING', endDate: null, sortOrder: 5 }, tagKeys: [] });
    backup.episodes.push({ animeKey: 'remote-fixture', episodeNumber: 1, watchedDate: date, source: 'MANUAL',
        createdAt: date + 'T12:00:00', updatedAt: date + 'T12:00:00' });
    dav.state.file = JSON.stringify(backup);
}

test('当推送后取消或确认WebDAV恢复时应该保留完整备份并刷新观看与热力图', async ({ page, demo, dav }) => {
    await openAnime(page, '演示·雨巷来信');
    await page.locator('#memoryContent').fill('虚构演示：希望把这段观影感受一起备份。');
    await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
    const before = await readExport(page, demo);
    expect(before.version).toBe(2); expect(before.memories).toHaveLength(1);
    const pushed = page.waitForResponse(r => r.url().endsWith('/api/sync/push'));
    await clickSync(page, 'btnSyncPush'); expect((await (await pushed).json()).code).toBe(200);
    await expect(page.locator('#btnSyncPush')).toBeEnabled();
    expect(business(JSON.parse(dav.state.file))).toEqual(business(before));
    const daily = (await (await page.request.get(demo.url + '/api/watch/daily')).json()).data;
    addRemoteAnime(dav, daily.date);
    const remote = JSON.parse(dav.state.file);
    const remoteMemory = { ...remote.memories[0], key: randomUUID(), animeKey: 'remote-fixture',
        content: '虚构远程回望：音乐仍然值得回味。', context: 'REFLECTION', watchedDate: null };
    remote.memories.push(remoteMemory); dav.state.file = JSON.stringify(remote);
    let dialog = handleDialog(page, /WebDAV 备份预览/, false);
    await clickSync(page, 'btnSyncPull'); await dialog; await expect(page.locator('#btnSyncPull')).toBeEnabled();
    expect(business(await readExport(page, demo))).toEqual(business(before));
    expect(dav.state.requests.filter(x => x.method === 'GET')).toHaveLength(1);
    dialog = handleDialog(page, /新增 1 部作品/, true);
    const restored = page.waitForResponse(r => r.url().endsWith('/api/sync/pull'));
    await clickSync(page, 'btnSyncPull'); await dialog;
    expect((await (await restored).json()).code).toBe(200);
    await expect(page.locator('#dailyWatch')).toContainText('可继续 3 部');
    await expect(page.locator('#dailyWatch')).toContainText('虚构·远程恢复');
    await expect(page.locator(`#heatmapContainer [data-tip="${daily.date}: 1 集"]`)).toHaveCount(1);
    await expect(page.locator('#btnSyncPull')).toBeEnabled();
    const after = await readExport(page, demo); expect(after.anime).toHaveLength(5);
    expect(after.tags).toEqual(before.tags); expect(after.groups).toEqual(before.groups); expect(after.memberships).toEqual(before.memberships);
    expect(after.episodes).toHaveLength(8);
    expect(after.memories).toHaveLength(2);
    expect(after.memories.find(memory => memory.key === remoteMemory.key)).toMatchObject({
        content: remoteMemory.content, watchedDate: null, version: remoteMemory.version,
        createdAt: remoteMemory.createdAt, updatedAt: remoteMemory.updatedAt
    });
    dialog = handleDialog(page, /新增 0 部作品/, true);
    const repeated = page.waitForResponse(r => r.url().endsWith('/api/sync/pull'));
    await clickSync(page, 'btnSyncPull'); await dialog; expect((await (await repeated).json()).code).toBe(200);
    await expect(page.locator('#btnSyncPull')).toBeEnabled(); expect((await readExport(page, demo)).anime).toHaveLength(5);
    expect(dav.state.requests.every(x => x.authenticated)).toBe(true);
});

test('当预览后远程变化或服务失败时应该拒绝写入并恢复同步按钮', async ({ page, demo, dav }) => {
    const before = await readExport(page, demo);
    const daily = (await (await page.request.get(demo.url + '/api/watch/daily')).json()).data;
    addRemoteAnime(dav, daily.date);
    const dialog = page.waitForEvent('dialog').then(async d => { expect(d.type()).toBe('confirm'); dav.state.file += ' '; await d.accept(); });
    const changed = page.waitForResponse(r => r.url().endsWith('/api/sync/pull'));
    await clickSync(page, 'btnSyncPull'); await dialog; expect((await changed).status()).toBe(400);
    await expect(page.locator('#tw')).toContainText('远程备份已变化'); await expect(page.locator('#btnSyncPull')).toBeEnabled();
    expect(business(await readExport(page, demo))).toEqual(business(before));
    for (const failure of [401, 503]) {
        dav.state.failure = failure;
        const failed = page.waitForResponse(r => r.url().endsWith('/api/sync/pull/preview'));
        await clickSync(page, 'btnSyncPull'); expect((await failed).status()).toBe(502);
        await expect(page.locator('#btnSyncPull')).toBeEnabled();
        expect(business(await readExport(page, demo))).toEqual(business(before));
    }
    dav.state.failure = 0;
    const conflict = JSON.parse(dav.state.file); conflict.episodes[0].watchedDate = '2024-03-03'; dav.state.file = JSON.stringify(conflict);
    const rejected = page.waitForEvent('dialog').then(async d => { expect(d.type()).toBe('alert'); expect(d.message()).toMatch(/无法恢复/); await d.accept(); });
    await clickSync(page, 'btnSyncPull'); await rejected; await expect(page.locator('#btnSyncPull')).toBeEnabled();
    expect(business(await readExport(page, demo))).toEqual(business(before));
});

test('当WebDAV推送等待时应该阻止重复推送和同时拉取而完成后允许重试', async ({ page, dav }) => {
    dav.state.hold = true; const received = new Promise(resolve => { dav.state.received = resolve; });
    const completed = page.waitForResponse(r => r.url().endsWith('/api/sync/push'));
    await clickSync(page, 'btnSyncPush'); await received;
    await page.locator('#syncBtn').click();
    await expect(page.locator('#btnSyncPush')).toBeDisabled(); await expect(page.locator('#btnSyncPull')).toBeDisabled();
    await page.evaluate(() => { syncPush(); syncPull(); });
    expect(dav.state.requests.filter(x => x.method === 'PUT')).toHaveLength(1);
    expect(dav.state.requests.filter(x => x.method === 'GET')).toHaveLength(0);
    dav.state.hold = false; dav.state.release(); expect((await completed).status()).toBe(200);
    await expect(page.locator('#btnSyncPush')).toBeEnabled(); await expect(page.locator('#btnSyncPull')).toBeEnabled();
    const retried = page.waitForResponse(r => r.url().endsWith('/api/sync/push'));
    await page.locator('#btnSyncPush').click(); expect((await retried).status()).toBe(200);
    expect(dav.state.requests.filter(x => x.method === 'PUT')).toHaveLength(2);
});
