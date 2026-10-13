const http = require('node:http');
const { test: base, expect, handleDialog } = require('./fixtures.cjs');
const test = base.extend({
    recommendationSource: async ({}, use) => {
        const requests = [];
        const server = http.createServer(async (req, res) => {
            res.setHeader('Content-Type', 'application/json; charset=utf-8');
            if (req.url.startsWith('/v0/search/subjects')) {
                let body = ''; for await (const chunk of req) body += chunk; const input = JSON.parse(body); requests.push(input);
                if (input.filter.tag.includes('失败')) { res.writeHead(503); res.end('{}'); return; }
                res.end(JSON.stringify({ data: [960001,960002,960003,960004].map(id => ({ id, name: '虚构召回' + id })) })); return;
            }
            if (req.url.startsWith('/v0/subjects/')) {
                const id = Number(req.url.split('/').pop()), tags = ['剧情','日常','奇幻','冒险','虚构演示',...Array.from({ length: 8 }, (_, i) => '补充标签' + i)];
                if (id === 960002) tags.push('机战');
                res.end(JSON.stringify({ id, type: 2, name: '虚构候选' + id + (id === 960002 ? '<script>仅为原文</script>' : ''), name_cn: '',
                    date: id === 960003 ? '2010-01-01' : id === 960004 ? null : '2020-01-01', tags: tags.map(name => ({ name, count: 1 })), rating: { score: 8 } })); return;
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
async function charts(page) {
    await page.getByRole('tab', { name: '数据图表' }).click();
    await page.locator('#recIncludeRecent').check();
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(4);
}
async function saveSettings(page, { min = '', max = '', excluded = '', recall = '剧情' } = {}) {
    await page.getByRole('button', { name: '推荐条件', exact: true }).click();
    await expect(page.locator('#recSaveSettings')).toBeEnabled();
    await page.locator('#recMinYear').fill(min); await page.locator('#recMaxYear').fill(max);
    await page.locator('#recExcludedTags').fill(excluded); await page.locator('#recRecallTags').fill(recall);
    await page.locator('#recSaveSettings').click(); await expect(page.locator('#recommendationSettingsModal')).toHaveCount(0);
}

test('当保存年份与排除标签后重新打开时应该保留硬条件且不更改原有业务数据', async ({ page, recommendationSource }) => {
    const before = await exportData(page); expect(recommendationSource.requests).toHaveLength(0);
    await charts(page); await saveSettings(page, { min: '2020', max: '2020', excluded: '机战' });
    await expect(page.locator('#recGrid [data-subject]')).toHaveCount(1); await expect(page.locator('#recGrid')).toContainText('虚构候选960001');
    await expect(page.locator('#recSummary')).toContainText('年份未知 1'); expect(recommendationSource.requests.some(r => r.keyword === '' && r.filter.tag[0] === '剧情')).toBe(true);
    await page.reload(); await page.getByRole('button', { name: '推荐条件', exact: true }).click();
    await expect(page.locator('#recMinYear')).toHaveValue('2020'); await expect(page.locator('#recExcludedTags')).toHaveValue('机战');
    const after = await exportData(page); expect(after.version).toBe(5); expect(after.recommendationPreferences).toHaveLength(1);
    for (const key of ['anime','episodes','memories','insights','aiCalls','externalCollections','importBatches']) expect(after[key]).toEqual(before[key]);
});

test('当连续反馈两部作品并撤销一条时应该保持防重历史且不产生观看日期', async ({ page }) => {
    await charts(page);
    await page.locator('[data-subject="960001"]').getByRole('button', { name: '不感兴趣', exact: true }).click();
    await expect(page.locator('[data-subject="960001"]')).toHaveCount(0);
    await page.locator('[data-subject="960002"]').getByRole('button', { name: '已看过', exact: true }).click();
    await expect(page.locator('[data-subject="960002"]')).toHaveCount(0);
    await page.getByRole('button', { name: '反馈列表', exact: true }).click();
    await expect(page.locator('#recFeedbackRows .rec-feedback-row')).toHaveCount(2);
    const dialog = handleDialog(page, /撤销/, true);
    await page.locator('#recFeedbackRows .rec-feedback-row').filter({ hasText: '虚构候选960001' }).getByRole('button', { name: '撤销', exact: true }).click(); await dialog;
    await expect(page.locator('#recFeedbackRows .rec-feedback-row')).toHaveCount(1);
    await page.getByRole('button', { name: '关闭推荐面板' }).click();
    await expect(page.locator('[data-subject="960001"]')).toHaveCount(1);
    const backup = await exportData(page); expect(backup.recommendationFeedback).toHaveLength(2); expect(backup.recommendationFeedback.filter(f => f.active)).toHaveLength(1);
    expect(backup.anime).toHaveLength(4); expect(backup.memories).toHaveLength(0);
});

test('当反馈已保存但响应丢失时应该用相同请求重试且不新增第二条', async ({ page, context }) => {
    const bodies = []; let dropped = false;
    await context.route('**/api/recommendations/feedback', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        bodies.push(route.request().postDataJSON());
        if (!dropped) { dropped = true; const response = await route.fetch(); expect(response.status()).toBe(200); return route.abort(); }
        return route.continue();
    });
    await charts(page); await page.locator('[data-subject="960001"]').getByRole('button', { name: '不感兴趣', exact: true }).click();
    await expect(page.locator('[data-subject="960001"]')).toContainText('重试提交反馈');
    await page.locator('[data-subject="960001"]').getByRole('button', { name: '重试提交反馈', exact: true }).click();
    await expect(page.locator('[data-subject="960001"]')).toHaveCount(0); expect(bodies).toHaveLength(2); expect(bodies[0]).toEqual(bodies[1]);
    expect((await exportData(page)).recommendationFeedback).toHaveLength(1);
});

test('当手机上来源失败再改回条件时应该可重试且保持深浅布局无横向溢出', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 780 }); await page.getByRole('button', { name: '图表', exact: true }).click();
    await page.locator('#recIncludeRecent').check(); await expect(page.locator('#recGrid [data-subject]')).toHaveCount(4);
    await saveSettings(page, { recall: '失败' }); await expect(page.locator('#recGrid')).toContainText('推荐加载失败');
    await saveSettings(page); await expect(page.locator('#recGrid [data-subject]')).toHaveCount(4);
    await page.getByRole('button', { name: '推荐条件', exact: true }).click(); await expect(page.locator('#recSaveSettings')).toBeEnabled();
    await page.locator('#recommendationSettingsModal').evaluate(async element => { await Promise.all(element.getAnimations().map(animation => animation.finished)); });
    await expect(page.locator('#recommendationSettingsModal')).toHaveCSS('opacity', '1');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('推荐条件手机.png') });
    await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('推荐条件手机深色.png') });
});
