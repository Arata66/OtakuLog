const { test, expect, openAnime } = require('./fixtures.cjs');

async function csrf(page) {
    return { [await page.locator('meta[name="_csrf_header"]').getAttribute('content')]: await page.locator('meta[name="_csrf"]').getAttribute('content') };
}

test('当筛选无结果或加载失败时应该说明原因并能重试恢复列表', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 900 });
    await page.locator('#filterStatus').selectOption('dropped');
    await expect(page.locator('#emptySearch')).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('筛选无结果.png'), fullPage: true, animations: 'disabled' });
    await expect(page.locator('#emptySearch')).toContainText('没有匹配的作品');
    await page.locator('#emptySearch').getByRole('button', { name: '清除筛选', exact: true }).click();
    await expect(page.locator('tbody tr[id]')).toHaveCount(4);
    await page.locator('#viewDetail').click();
    await expect(page.locator('#detailView')).toBeVisible();
    await expect(page.locator('#detailGrid .dt-card')).toHaveCount(4);
    await page.locator('#viewTable').click();
    expect((await page.locator('tbody button.clickable-name').first().boundingBox()).width).toBeGreaterThanOrEqual(140);
    let fail = true;
    await page.route('**/api/anime/page?*', route => fail ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":503,"message":"测试服务暂时不可用"}' }) : route.continue());
    await page.getByLabel('搜索', { exact: true }).fill('星港');
    await expect(page.locator('#searchFeedback')).toContainText('列表加载失败');
    await expect(page.locator('tbody tr[id]')).toHaveCount(4);
    await expect(page.locator('.skeleton')).toHaveCount(0);
    await page.screenshot({ path: testInfo.outputPath('加载失败.png'), fullPage: true, animations: 'disabled' });
    fail = false;
    await page.locator('#searchFeedback').getByRole('button', { name: '重试', exact: true }).click();
    await expect(page.locator('tbody tr[id]')).toHaveCount(1);
    await expect(page.locator('tbody')).toContainText('演示·星港巡游');
    await page.unroute('**/api/anime/page?*');
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await expect(page.locator('tbody tr[id]')).toHaveCount(4);
    const result = await (await page.request.get(new URL('/api/anime/search', page.url()).href)).json();
    expect(result.code).toBe(200);
    const headers = await csrf(page);
    for (const anime of result.data) expect((await page.request.delete(new URL('/api/anime/' + anime.id, page.url()).href, { headers })).status()).toBe(200);
    await page.reload();
    await expect(page.locator('#emptySearch')).toContainText('还没有追番记录');
    await page.locator('#emptySearch').getByRole('button', { name: '添加第一部作品', exact: true }).click();
    await expect(page.locator('#animeName')).toBeFocused();
    fail = true;
    await page.route('**/api/anime/page?*', route => fail ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":503}' }) : route.continue());
    await page.reload();
    await expect(page.locator('#searchFeedback')).toContainText('列表加载失败');
    await expect(page.locator('#emptySearch')).toBeHidden();
    await expect(page.locator('.skeleton')).toHaveCount(0);
    fail = false;
    await page.locator('#searchFeedback').getByRole('button', { name: '重试', exact: true }).click();
    await expect(page.locator('#emptySearch')).toContainText('还没有追番记录');
});

test('当旧搜索响应较晚返回时应该保留新的筛选结果', async ({ page }) => {
    let release;
    const held = new Promise(resolve => { release = resolve; });
    await page.route('**/api/anime/page?*', async route => {
        if (new URL(route.request().url()).searchParams.get('name') !== '雨巷') return route.continue();
        const response = await route.fetch();
        await held;
        await route.fulfill({ response });
    });
    try {
        const oldRequest = page.waitForRequest(r => r.url().includes('/api/anime/page?') && new URL(r.url()).searchParams.get('name') === '雨巷');
        await page.getByLabel('搜索', { exact: true }).fill('雨巷');
        await oldRequest;
        await page.getByLabel('搜索', { exact: true }).fill('星港');
        await expect(page.locator('tbody')).toContainText('演示·星港巡游');
        await expect(page.locator('tbody tr[id]')).toHaveCount(1);
        const oldResponse = page.waitForResponse(r => r.url().includes('/api/anime/page?') && new URL(r.url()).searchParams.get('name') === '雨巷');
        release();
        await (await oldResponse).finished();
        await page.locator('#viewDetail').click();
        await expect(page.locator('#detailGrid .dt-card')).toHaveCount(1);
        await expect(page.locator('#detailGrid')).toContainText('演示·星港巡游');
        await page.locator('#viewTable').click();
        await expect(page.locator('tbody')).toContainText('演示·星港巡游');
        await expect(page.locator('tbody')).not.toContainText('演示·雨巷来信');
    } finally { release(); }
});

test('当作品含长标题和连续备注时应该完整查看且详情无横向溢出', async ({ page }, testInfo) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('#r-' + id).getByRole('button', { name: '编辑', exact: true }).click();
    const name = '演示·长途来信_' + '风越过山与海'.repeat(8) + 'Journey'.repeat(5);
    await page.locator('#m-name').fill(name);
    await page.locator('#m-remark').fill('长备注：`' + 'abcdef0123456789'.repeat(14) + '`');
    const updated = page.waitForResponse(r => r.url().endsWith('/api/anime/' + id + '/update') && r.request().method() === 'POST');
    await page.locator('#editModal').getByRole('button', { name: '保存', exact: true }).click();
    expect(await (await updated).json()).toMatchObject({ code: 200 });
    await expect(page.locator('#editModal')).toHaveCount(0);
    for (const width of [1440, 360]) {
        await page.setViewportSize({ width, height: 1000 });
        for (const theme of ['light', 'dark']) {
            if ((await page.locator('html').getAttribute('data-theme') === 'dark') !== (theme === 'dark')) await page.locator('#themeToggle').click();
            await page.locator('#r-' + id).getByRole('button', { name, exact: true }).click();
            const dialog = page.getByRole('dialog', { name: '番剧详情', exact: true });
            await expect(dialog.locator('.detail-title')).toHaveText(name);
            await dialog.screenshot({ path: testInfo.outputPath(`长内容-${width}-${theme}.png`), animations: 'disabled' });
            expect(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
            await dialog.getByRole('button', { name: '关闭', exact: true }).click();
        }
    }
});

test('当作品超过一页时应该加载更多并在三种视图保留完整结果', async ({ page }, testInfo) => {
    const headers = await csrf(page);
    for (let i = 0; i < 18; i++) {
        const response = await page.request.post(new URL('/api/anime/add', page.url()).href, { headers,
            data: { name: '演示·分页作品' + i, totalEpisodes: 12, season: '2026秋', score: 0, status: 'planning', legacy: false } });
        expect(response.status()).toBe(200);
    }
    await page.reload();
    await expect(page.locator('tbody tr[id]')).toHaveCount(12);
    let fail = true;
    await page.route('**/api/anime/page?*', route => fail && new URL(route.request().url()).searchParams.get('page') === '1'
        ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":503}' }) : route.continue());
    await page.locator('#scrollSentinel').scrollIntoViewIfNeeded();
    await expect(page.locator('#searchFeedback')).toContainText('列表加载失败');
    await expect(page.locator('tbody tr[id]')).toHaveCount(12);
    fail = false;
    await page.locator('#searchFeedback').getByRole('button', { name: '重试', exact: true }).click();
    await expect(page.locator('tbody tr[id]')).toHaveCount(22);
    for (const [view, selector] of [['viewDetail', '#detailGrid .dt-card'], ['viewGallery', '#galleryGrid .g-card'], ['viewTable', 'tbody tr[id]']]) {
        await page.locator('#' + view).click();
        await expect(page.locator(selector)).toHaveCount(22);
    }
    await page.screenshot({ path: testInfo.outputPath('完整分页.png'), fullPage: true, animations: 'disabled' });
});
