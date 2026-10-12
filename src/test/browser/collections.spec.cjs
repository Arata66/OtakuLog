const http = require('node:http');
const { test: base, expect, readEpisodes } = require('./fixtures.cjs');
const test = base.extend({
    bangumiMock: async ({}, use) => {
        const requests = [];
        const row = (id, name, eps, type, progress) => ({ subject: { id, name, name_cn: name, eps, date: '2024-04-01' },
            type, ep_status: progress, rate: 8, tags: ['虚构收藏标签'], comment: '来源评论\n<script>虚构原文</script>' });
        const server = http.createServer((req, res) => {
            requests.push(req.url); res.setHeader('Content-Type', 'application/json');
            if (req.url.startsWith('/v0/users/failure/')) { res.writeHead(503); res.end('{}'); return; }
            if (req.url.startsWith('/v0/users/big/')) {
                const offset = Number(new URL(req.url, 'http://example.test').searchParams.get('offset'));
                res.end(JSON.stringify({ total: 251, data: Array.from({ length: Math.min(50, 251 - offset) }, (_, n) =>
                    row(94000 + offset + n, '虚构批量收藏' + (offset + n), 1, 2, 0)) })); return;
            }
            res.end(JSON.stringify({ total: 4, data: [row(90001, '虚构·收藏验收', 12, 3, 2), row(90002, '虚构·集数未知', 0, 3, 2),
                row(90003, '虚构·搁置作品', 12, 4, 2), row(90004, '演示·雨巷来信', 12, 3, 9)] }));
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        try { await use({ url: `http://127.0.0.1:${server.address().port}`, requests }); }
        finally { await new Promise(resolve => server.close(resolve)); }
    },
    bangumiBaseUrl: async ({ bangumiMock }, use) => use(bangumiMock.url)
});

async function readPreview(page, username) {
    await page.locator('#syncBtn').click(); await page.locator('#btnImportBangumi').click();
    await page.locator('#bgmImportUsername').fill(username);
    const response = page.waitForResponse(r => r.url().endsWith('/api/bangumi/import/preview'));
    await page.locator('#bgmImportRead').click();
    const result = await (await response).json();
    await expect(page.locator('#bgmImportRead')).toBeEnabled(); return result;
}

test('当公开收藏超过二百条时应该完整预览且确认前不写入确认后保留全部来源', async ({ page, demo, bangumiMock }) => {
    const exported = async () => (await (await page.request.get(demo.url + '/api/anime/export')).json());
    const before = await exported(); delete before.exportedAt;
    const preview = await readPreview(page, 'big'); expect(preview.data.readCount).toBe(251);
    await expect(page.locator('#bgmImportStatus')).toContainText('251 / 251');
    const readOnly = await exported(); delete readOnly.exportedAt; expect(readOnly).toEqual(before);
    expect(bangumiMock.requests).toHaveLength(6);
    await page.locator('#bgmImportPages').getByRole('button', { name: '下一页', exact: true }).click();
    await expect(page.locator('#bgmImportRows')).toContainText('虚构批量收藏20');
    await page.locator('#bgmImportScores').check(); await page.locator('#bgmImportConfirm').click();
    await expect(page.locator('#bgmImportStatus')).toContainText('新增 251');
    const after = await exported(); expect(after.anime).toHaveLength(255); expect(after.externalCollections).toHaveLength(251);
    expect(after.importBatches).toHaveLength(1); expect(after.memories).toHaveLength(0);
    expect(after.anime.filter(a => a.data.bangumiId >= 94000).every(a => a.data.score === 8)).toBe(true);
    await page.locator('#bgmImportExternal').click(); await expect(page.locator('#bgmImportStatus')).toContainText('251 条');
    await page.locator('#bgmImportRows summary').first().click();
    await expect(page.locator('#bgmImportRows')).toContainText('<script>虚构原文</script>');
    await expect(page.locator('#bgmImportRows script')).toHaveCount(0);
});

test('当导入已写入而响应丢失时应该用同一请求编号恢复结果且不重复保存', async ({ page, demo }) => {
    await readPreview(page, 'fictional'); const bodies = []; let lost = false;
    await page.route('**/api/bangumi/import/confirm', async route => {
        bodies.push(route.request().postData());
        if (!lost) { lost = true; const response = await route.fetch(); expect(response.status()).toBe(200); await route.abort('failed'); }
        else await route.continue();
    });
    await page.locator('#bgmImportConfirm').click(); await expect(page.locator('#bgmImportStatus')).toContainText('重试');
    await expect(page.locator('#bgmImportScores')).toBeDisabled();
    await page.locator('#bgmImportConfirm').click(); await expect(page.locator('#bgmImportStatus')).toContainText('导入完成');
    expect(bodies).toHaveLength(2); expect(bodies[0]).toBe(bodies[1]);
    const backup = await (await page.request.get(demo.url + '/api/anime/export')).json();
    expect(backup.anime).toHaveLength(5); expect(backup.externalCollections).toHaveLength(4); expect(backup.importBatches).toHaveLength(1);
});

test('当手机上仅预览并关闭导入时应该保留原有数据且无横向溢出', async ({ page, demo }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 800 });
    await readPreview(page, 'fictional');
    await page.screenshot({ path: testInfo.outputPath('Bangumi预览手机.png') });
    const backup = await (await page.request.get(demo.url + '/api/anime/export')).json();
    expect(backup.anime).toHaveLength(4); expect(backup.externalCollections).toHaveLength(0);
    const width = await page.locator('#bangumiImportModal .modal-card').evaluate(el => ({ inner: el.scrollWidth, outer: el.clientWidth }));
    expect(width.inner).toBeLessThanOrEqual(width.outer);
    await page.locator('#bangumiImportModal').getByRole('button', { name: '关闭', exact: true }).click();
    await expect(page.locator('#dailyWatch')).toContainText('演示·雨巷来信');
});

test('当收藏包含有效重复和需核对条目时应该真实写入隔离库并支持继续观看', async ({ page, demo, bangumiMock }) => {
    const importUser = async username => {
        await page.locator('#syncBtn').click();
        await page.locator('#btnImportBangumi').click();
        await page.locator('#bgmImportUsername').fill(username);
        const response = page.waitForResponse(r => r.url().endsWith('/api/bangumi/import/preview'));
        await page.locator('#bgmImportRead').click();
        const result = await (await response).json();
        if (result.code !== 200) return result;
        const confirmed = page.waitForResponse(r => r.url().endsWith('/api/bangumi/import/confirm'));
        await page.locator('#bgmImportConfirm').click();
        const outcome = await (await confirmed).json();
        await expect(page.locator('#bgmImportStatus')).toContainText('导入完成');
        await page.locator('#bangumiImportModal').getByRole('button', { name: '关闭', exact: true }).click();
        return outcome;
    };
    const readExport = async () => (await (await page.request.get(demo.url + '/api/anime/export')).json());
    expect((await importUser('fictional')).data).toMatchObject({ created: 1, skipped: 3, needsReview: 2, total: 4, externalSaved: 4 });
    await expect(page.locator('#dailyWatch')).toContainText('可继续 3 部');
    await expect(page.locator('#dailyWatch')).toContainText('虚构·收藏验收');
    await expect(page.locator('#btnImportBangumi')).toBeEnabled();
    const first = await readExport(); expect(first.anime).toHaveLength(5);
    const daily = (await (await page.request.get(demo.url + '/api/watch/daily')).json()).data;
    const id = daily.continueWatching.find(x => x.anime.name === '虚构·收藏验收').anime.id;
    const imported = await readEpisodes(page, id);
    expect(imported.entries).toHaveLength(2);
    expect(imported.entries.every(x => x.watchedDate === null && x.source === 'IMPORT')).toBe(true);
    const rain = daily.continueWatching.find(x => x.anime.name === '演示·雨巷来信').anime;
    expect(rain.currentEpisode).toBe(2);
    expect((await readEpisodes(page, rain.id)).entries[0].source).toBe('LEGACY');
    expect((await importUser('fictional')).data).toMatchObject({ created: 0, skipped: 4, needsReview: 2, total: 4, externalSaved: 4 });
    expect((await readExport()).anime).toHaveLength(5);
    expect((await importUser('failure')).code).toBe(502);
    await page.locator('#bangumiImportModal').getByRole('button', { name: '关闭', exact: true }).click();
    await expect(page.locator('#btnImportBangumi')).toBeEnabled();
    expect((await readExport()).anime).toHaveLength(5);
    expect(bangumiMock.requests).toHaveLength(3);
    expect(bangumiMock.requests.every(url => url.includes('subject_type=2&limit=50&offset=0'))).toBe(true);
    await page.locator('#dailyWatch [aria-label="继续观看"]').getByRole('button', { name: '虚构·收藏验收', exact: true }).click();
    await page.locator('#detailModal').getByRole('button', { name: '下一集', exact: true }).click();
    await expect(page.locator('#detailEpisodeCount')).toHaveText('3 / 12');
    expect((await readEpisodes(page, id)).entries[2].source).toBe('WATCHED');
});
