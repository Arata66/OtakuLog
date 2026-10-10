const http = require('node:http');
const { test: base, expect, readEpisodes } = require('./fixtures.cjs');
const test = base.extend({
    bangumiMock: async ({}, use) => {
        const requests = [];
        const row = (id, name, eps, type, progress) => ({ subject: { id, name, name_cn: name, eps, date: '2024-04-01' }, type, ep_status: progress });
        const server = http.createServer((req, res) => {
            requests.push(req.url); res.setHeader('Content-Type', 'application/json');
            if (req.url.startsWith('/v0/users/failure/')) { res.writeHead(503); res.end('{}'); return; }
            res.end(JSON.stringify({ data: [row(90001, '虚构·收藏验收', 12, 3, 2), row(90002, '虚构·集数未知', 0, 3, 2),
                row(90003, '虚构·搁置作品', 12, 4, 2), row(90004, '演示·雨巷来信', 12, 3, 9)] }));
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        try { await use({ url: `http://127.0.0.1:${server.address().port}`, requests }); }
        finally { await new Promise(resolve => server.close(resolve)); }
    },
    bangumiBaseUrl: async ({ bangumiMock }, use) => use(bangumiMock.url)
});

test('当收藏包含有效重复和需核对条目时应该真实写入隔离库并支持继续观看', async ({ page, demo, bangumiMock }) => {
    const importUser = async username => {
        await page.locator('#syncBtn').click();
        const dialog = page.waitForEvent('dialog').then(async d => { expect(d.type()).toBe('prompt'); await d.accept(username); });
        const response = page.waitForResponse(r => r.url().endsWith('/api/bangumi/import/' + username));
        await page.locator('#btnImportBangumi').click(); await dialog;
        return (await response).json();
    };
    const readExport = async () => (await (await page.request.get(demo.url + '/api/anime/export')).json());
    expect((await importUser('fictional')).data).toEqual({ created: 1, skipped: 3, needsReview: 2, total: 4 });
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
    expect((await importUser('fictional')).data).toEqual({ created: 0, skipped: 4, needsReview: 2, total: 4 });
    expect((await readExport()).anime).toHaveLength(5);
    expect((await importUser('failure')).code).toBe(502);
    await expect(page.locator('#btnImportBangumi')).toBeEnabled();
    expect((await readExport()).anime).toHaveLength(5);
    expect(bangumiMock.requests).toHaveLength(3);
    expect(bangumiMock.requests.every(url => url.includes('subject_type=2&limit=50&offset=0'))).toBe(true);
    await page.locator('#dailyWatch [aria-label="继续观看"]').getByRole('button', { name: '虚构·收藏验收', exact: true }).click();
    await page.locator('#detailModal').getByRole('button', { name: '下一集', exact: true }).click();
    await expect(page.locator('#detailEpisodeCount')).toHaveText('3 / 12');
    expect((await readEpisodes(page, id)).entries[2].source).toBe('WATCHED');
});
