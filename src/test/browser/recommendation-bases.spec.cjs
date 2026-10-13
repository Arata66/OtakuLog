const http = require('node:http');
const { test: base, expect, handleDialog } = require('./fixtures.cjs');
const test = base.extend({
    recommendationSource: async ({}, use) => {
        const requests = [];
        const server = http.createServer(async (req, res) => {
            res.setHeader('Content-Type', 'application/json; charset=utf-8');
            if (req.url.startsWith('/v0/search/subjects')) {
                let body = ''; for await (const chunk of req) body += chunk; requests.push(JSON.parse(body));
                res.end(JSON.stringify({ data: [981000,981001,981002,981003].map(id => ({ id, name: '虚构依据候选' + id })) })); return;
            }
            if (req.url.startsWith('/v0/episodes')) { res.end(JSON.stringify({ data: [], total: 0 })); return; }
            if (req.url.startsWith('/v0/subjects/')) {
                const id = Number(req.url.split('/').pop()), tags = ['剧情','奇幻','虚构演示']; if (id === 981002) tags.push('机战');
                res.end(JSON.stringify({ id, type: 2, name: '虚构依据候选' + id, name_cn: '', date: id === 981003 ? '2010-01-01' : '2020-01-01',
                    tags: tags.map(name => ({ name, count: 1 })), rating: { score: 8 } })); return;
            }
            res.writeHead(404); res.end('{}');
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        try { await use({ url: `http://127.0.0.1:${server.address().port}`, requests }); }
        finally { await new Promise(resolve => server.close(resolve)); }
    },
    bangumiBaseUrl: async ({ recommendationSource }, use) => use(recommendationSource.url)
});
test.use({ recommendationsLive: true });
const exportData = page => page.evaluate(() => fetchApi('/api/anime/export'));
const basisPage = page => page.evaluate(async () => (await fetchApi('/api/recommendations/bases')).data);
async function charts(page, mobile = false) {
    await page.getByRole(mobile ? 'button' : 'tab', { name: mobile ? '图表' : '数据图表', exact: true }).click();
    await page.locator('#recIncludeRecent').check();
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(4);
}
async function prepareSources(page, negative = false) {
    return page.evaluate(async negative => {
        const daily = await fetchApi('/api/watch/daily'), id = daily.data.continueWatching[0].anime.id;
        const memory = await fetchApi(`/api/anime/${id}/memories`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: crypto.randomUUID(), content: '<script>只是虚构原文</script>，人物选择使我投入。', context: 'NOTE' }) });
        if (memory.code !== 200) throw new Error(memory.msg || memory.message);
        const entries = [];
        for (const direction of negative ? ['LIKE','DISLIKE'] : ['LIKE']) {
            const result = await fetchApi(`/api/anime/${id}/memories/${memory.data.id}/insights`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ key: crypto.randomUUID(), sourceVersion: memory.data.version, quoteField: 'CONTENT', quote: '<script>只是虚构原文</script>，人物选择',
                    statement: direction === 'LIKE' ? '虚构的喜欢人物选择' : '虚构的不喜欢人物选择', direction, aspect: 'CHARACTER', factor: '人物选择', status: 'CONFIRMED' }) });
            if (result.code !== 200) throw new Error(result.msg || result.message); entries.push(result.data);
        }
        return { id, memory: memory.data, entries };
    }, negative);
}
async function saveTagBasis(page, insightKey, tag = '剧情') {
    await expect(page.locator('#recBasisSource')).toBeEnabled(); await page.locator('#recBasisSource').selectOption(insightKey);
    await page.locator('#recBasisTag').fill(tag); await expect(page.locator('#recBasisSave')).toBeDisabled();
    await page.getByRole('button', { name: '预览关联影响', exact: true }).click(); await expect(page.locator('#recBasisSave')).toBeEnabled();
    await page.locator('#recBasisSave').click(); await expect(page.locator('#recModalStatus')).toContainText('关联已保存');
}
async function start(page) { await page.getByRole('button', { name: '开始推荐', exact: true }).click(); }
async function openBases(page) { await page.getByRole('button', { name: '推荐依据', exact: true }).click(); await expect(page.locator('#recBasisSource')).toBeEnabled(); }
async function closeBases(page) { await page.getByRole('button', { name: '关闭推荐面板', exact: true }).click(); }

test('当按标签或从作品详情找类似时应该保留永久条件且排除种子自身', async ({ page, recommendationSource }) => {
    const before = await exportData(page); await charts(page);
    await page.locator('#recMode').selectOption('TAGS'); await page.locator('#recModeTags').fill('剧情');
    await page.getByRole('button', { name: '推荐条件', exact: true }).click(); await expect(page.locator('#recSaveSettings')).toBeEnabled();
    await page.locator('#recMinYear').fill('2020'); await page.locator('#recExcludedTags').fill('机战'); await page.locator('#recSaveSettings').click();
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(2); await expect(page.locator('#recEvidence')).toContainText('剧情');
    await page.evaluate(() => openBangumiDetailModal(981000));
    await page.locator('#detailModal').getByRole('button', { name: '找类似这部', exact: true }).click();
    await expect(page.locator('#recMode')).toHaveValue('SEED'); await expect(page.locator('#recSeedId')).toHaveValue('981000');
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(1); await expect(page.locator('#recGrid')).toContainText('虚构依据候选981001');
    await expect(page.locator('#recEvidence')).toContainText('种子：虚构依据候选981000'); await expect(page.locator('#recSummary')).toContainText('种子自身 1');
    expect(recommendationSource.requests.length).toBeGreaterThan(0);
    const after = await exportData(page); for (const field of ['anime','episodes','memories','insights','aiCalls']) expect(after[field]).toEqual(before[field]);
});

test('当明确关联正负同标签并选择口味模式时应该等待勾选且展示冲突而不扩大偏好', async ({ page, recommendationSource }) => {
    const before = await exportData(page), sources = await prepareSources(page, true); await charts(page); await openBases(page);
    expect((await basisPage(page)).entries).toHaveLength(0); await page.locator('#recBasisTag').fill('剧情');
    await page.getByRole('button', { name: '预览关联影响', exact: true }).click(); await expect(page.locator('#recBasisSave')).toBeEnabled();
    expect((await basisPage(page)).entries).toHaveLength(0);
    for (const entry of sources.entries) await saveTagBasis(page, entry.key);
    await closeBases(page); await page.locator('#recMode').selectOption('TASTE');
    await expect(page.locator('#recBasisSelection input:checked')).toHaveCount(0); const requestCount = recommendationSource.requests.length;
    await start(page); await expect(page.locator('#recGrid')).toContainText('先关联并勾选喜欢观点'); expect(recommendationSource.requests).toHaveLength(requestCount);
    await page.locator('#recBasisSelection input').nth(0).check(); await page.locator('#recBasisSelection input').nth(1).check(); await start(page);
    await expect(page.locator('#recGrid')).toContainText('喜欢与避开同一标签'); expect(recommendationSource.requests).toHaveLength(requestCount);
    const negative = page.locator('#recBasisSelection label').filter({ hasText: '避开' }).locator('input'); await negative.uncheck(); await start(page);
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(4); await expect(page.locator('#recEvidence')).toContainText('人物选择');
    await openBases(page); await page.locator('#recBasisRows .rec-feedback-row').filter({ hasText: '喜欢' }).getByRole('button', { name: '停用', exact: true }).click();
    await expect(page.locator('#recBasisRows')).toContainText('已停用'); await expect(page.locator('#recGrid')).toContainText('先关联并勾选喜欢观点');
    const after = await exportData(page); expect(after.recommendationBases).toHaveLength(2); expect(after.recommendationBases.filter(e => e.enabled)).toHaveLength(1);
    for (const field of ['anime','episodes','aiCalls']) expect(after[field]).toEqual(before[field]);
});

test('当原文修改后重新读取依据时应该暂停历史关联并撤下旧口味候选', async ({ page }) => {
    const before = await exportData(page), sources = await prepareSources(page); await charts(page); await openBases(page);
    await saveTagBasis(page, sources.entries[0].key); await closeBases(page); await page.locator('#recMode').selectOption('TASTE');
    await page.locator('#recBasisSelection input').check(); await start(page); await expect(page.locator('#recGrid [data-subject]')).toHaveCount(4);
    const updated = await page.evaluate(async ({ id, memory }) => fetchApi(`/api/anime/${id}/memories/${memory.id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ key: memory.key, content: '改变后的虚构原文', context: 'NOTE', expectedVersion: memory.version }) }), sources); expect(updated.code).toBe(200);
    await page.getByRole('button', { name: '推荐依据', exact: true }).click(); await expect(page.locator('#recBasisRows')).toContainText('暂停使用');
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(0); await expect(page.locator('#recEvidence')).toBeEmpty(); await expect(page.locator('#recBasisSelection input')).toHaveCount(0);
    expect((await basisPage(page)).entries[0].state).toBe('STALE'); const backup = await exportData(page); expect(backup.recommendationBases).toHaveLength(1);
    const preview = await page.evaluate(async backup => fetchApi('/api/anime/import/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(backup) }), backup);
    expect(preview.code).toBe(200); for (const field of ['anime','episodes','aiCalls']) expect(backup[field]).toEqual(before[field]);
});

test('当手机关联保存响应丢失并重复恢复v6备份时应该防重且保持深浅布局和原文安全', async ({ page, context }, testInfo) => {
    const before = await exportData(page), sources = await prepareSources(page); await page.setViewportSize({ width: 360, height: 780 }); await charts(page, true); await openBases(page);
    await expect(page.locator('#recBasisSeedField')).toBeHidden(); await expect(page.locator('#recBasisTagField')).toBeVisible();
    await page.locator('#recBasisKind').selectOption('SEED'); await expect(page.locator('#recBasisTagField')).toBeHidden(); await expect(page.locator('#recBasisSeedField')).toBeVisible();
    await page.locator('#recBasisKind').selectOption('TAG');
    await expect(page.locator('#recBasisEvidence')).toContainText('<script>只是虚构原文</script>'); await expect(page.locator('#recBasisEvidence script')).toHaveCount(0);
    const bodies = []; let dropped = false;
    await context.route('**/api/recommendations/bases', async route => {
        if (route.request().method() !== 'POST') return route.continue(); bodies.push(route.request().postDataJSON());
        if (!dropped) { dropped = true; const response = await route.fetch(); expect(response.status()).toBe(200); return route.abort(); }
        return route.continue();
    });
    await page.locator('#recBasisTag').fill('剧情'); await page.getByRole('button', { name: '预览关联影响', exact: true }).click(); await expect(page.locator('#recBasisSave')).toBeEnabled();
    await page.locator('#recBasisSave').click(); await expect(page.locator('#recBasisSave')).toBeEnabled(); await page.locator('#recBasisSave').click();
    await expect(page.locator('#recModalStatus')).toContainText('关联已保存'); expect(bodies).toHaveLength(2); expect(bodies[0]).toEqual(bodies[1]);
    const backup = await exportData(page); expect(backup.version).toBe(6); expect(backup.recommendationBases).toHaveLength(1);
    await page.locator('#recommendationBasesModal').evaluate(async el => { await Promise.all(el.getAnimations().map(a => a.finished)); });
    for (const theme of ['light','dark']) {
        await page.evaluate(theme => document.documentElement.setAttribute('data-theme', theme), theme);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: testInfo.outputPath(`推荐依据手机-${theme}.png`), animations: 'disabled' });
    }
    await closeBases(page);
    for (let i = 0; i < 2; i++) {
        const restored = handleDialog(page, /推荐关联 1 条，其中新增 0 条/, true);
        await page.locator('input[type="file"][accept=".json"]').setInputFiles({ name: '虚构推荐依据备份.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) }); await restored;
        await expect.poll(async () => (await exportData(page)).recommendationBases).toEqual(backup.recommendationBases);
    }
    const after = await exportData(page); for (const field of ['anime','episodes','aiCalls']) expect(after[field]).toEqual(before[field]);
    expect(after.memories[0].key).toBe(sources.memory.key); expect((await basisPage(page)).entries[0].state).toBe('ACTIVE');
});
