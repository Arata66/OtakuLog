const { test, expect, readEpisodes } = require('./fixtures.cjs');

async function dailySnapshot(page, demo) {
    const response = await page.request.get(demo.url + '/api/watch/daily');
    expect(response.status()).toBe(200);
    return (await response.json()).data;
}

test('当当前日历只包含一部追中作品时应该在桌面和手机仅将它列入今日参考', async ({ page, demo }, testInfo) => {
    const daily = await dailySnapshot(page, demo);
    const current = daily.continueWatching.find(item => item.anime.name === '演示·星港巡游');
    await page.route('**/api/watch/airing', route => route.fulfill({ json: { code: 200,
        data: { ...daily, todayAiringCount: 1, todayAiring: [current], airingStatus: 'VERIFIED' } } }));
    for (const width of [1440, 360]) {
        await page.setViewportSize({ width, height: 1000 });
        if (width === 360) await page.locator('#themeToggle').click();
        await page.locator('#dailyWatch').getByRole('button', { name: '重新加载', exact: true }).click();
        const reference = page.locator('#dailyWatch [aria-label="今日放送参考"]');
        await expect(reference).toContainText('当前 Bangumi 日历');
        await expect(reference.locator('.daily-title')).toHaveText(['演示·星港巡游']);
        await expect(reference).not.toContainText('演示·雨巷来信');
        await expect(page.locator('#dailyWatch [aria-label="继续观看"]')).toContainText('演示·雨巷来信');
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.locator('#dailyWatch').screenshot({ path: testInfo.outputPath(`日历核对-${width}.png`), animations: 'disabled' });
    }
});

test('当日历请求失败时应该在桌面和手机显示未核实并保留可继续作品', async ({ page }) => {
    await page.route('**/api/watch/airing', route => route.fulfill({ status: 503, json: { message: '模拟日历暂不可用' } }));
    for (const width of [1440, 360]) {
        await page.setViewportSize({ width, height: 1000 });
        await page.locator('#dailyWatch').getByRole('button', { name: '重新加载', exact: true }).click();
        const reference = page.locator('#dailyWatch [aria-label="今日放送参考"]');
        await expect(reference).toContainText('放送信息未核实');
        await expect(reference.locator('h3 span')).toHaveText('未核实');
        await expect(reference.locator('.daily-title')).toHaveCount(0);
        await expect(page.locator('#dailyWatch [aria-label="继续观看"] .daily-title')).toHaveCount(2);
        await expect(page.locator('#dailyWatch [aria-label="继续观看"] .daily-record').first()).toBeEnabled();
    }
});

test('当手机日历核对尚未返回时应该仍能记看并在成功后恢复按钮', async ({ page, demo }) => {
    await page.setViewportSize({ width: 360, height: 1000 });
    const daily = await dailySnapshot(page, demo);
    const item = daily.continueWatching.find(entry => entry.anime.name === '演示·雨巷来信');
    let release;
    const blocked = new Promise(resolve => { release = resolve; });
    await page.route('**/api/watch/airing', async route => {
        await blocked;
        await route.fulfill({ json: { code: 200, data: { ...daily, todayAiring: [], todayAiringCount: 0, airingStatus: 'VERIFIED' } } });
    });
    try {
        await page.locator('#dailyWatch').getByRole('button', { name: '重新加载', exact: true }).click();
        await expect(page.locator('#dailyWatch')).toContainText('正在核对当前放送日历');
        const row = page.locator('#dailyWatch [aria-label="继续观看"] .daily-row').filter({ hasText: '演示·雨巷来信' });
        await row.getByRole('button', { name: '记看第 3 集', exact: true }).click();
        await expect(row.getByRole('button', { name: '记看第 4 集', exact: true })).toBeEnabled();
        const saved = await readEpisodes(page, item.anime.id);
        expect(saved.entries.find(entry => entry.episodeNumber === 3).source).toBe('WATCHED');
        await expect(page.locator('#dailyWatch')).toContainText('正在核对当前放送日历');
    } finally {
        release();
        await expect(page.locator('#dailyWatch [aria-label="今日放送参考"]')).toContainText('当前日历没有匹配');
    }
});
