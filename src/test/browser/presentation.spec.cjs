const { test, expect } = require('./fixtures.cjs');

test('当桌面与手机浏览三种视图时应该能搜索并用键盘打开关闭详情', async ({ page }, testInfo) => {
    for (const width of [1440, 360]) {
        await page.setViewportSize({ width, height: 1000 });
        for (const theme of ['light', 'dark']) {
            if ((await page.locator('html').getAttribute('data-theme') === 'dark') !== (theme === 'dark')) await page.locator('#themeToggle').click();
            await page.screenshot({ path: testInfo.outputPath(`首页-${width}-${theme}.png`), fullPage: true, animations: 'disabled' });
            await expect(page.getByLabel('搜索', { exact: true })).toBeVisible();
            await page.getByLabel('搜索', { exact: true }).fill('雨巷');
            await expect(page.locator('tbody tr')).toHaveCount(1);
            for (const [view, root] of [['viewTable', '.table-card'], ['viewDetail', '#detailGrid'], ['viewGallery', '#galleryGrid']]) {
                await page.locator('#' + view).click();
                const name = page.locator(root).getByRole('button', { name: '演示·雨巷来信', exact: true });
                await name.focus();
                await name.press('Enter');
                const dialog = page.getByRole('dialog', { name: '番剧详情', exact: true });
                await expect(dialog).toBeVisible();
                const next = dialog.getByRole('button', { name: '下一集', exact: true });
                await expect(next).toBeVisible();
                await page.screenshot({ path: testInfo.outputPath(`详情-${view}-${width}-${theme}.png`), animations: 'disabled' });
                const layout = await dialog.evaluate(el => ({ fits: el.scrollWidth <= el.clientWidth + 1,
                    controls: [...el.querySelectorAll('button')].filter(b => ['关闭', '下一集', '上一集'].includes(b.textContent)).map(b => Math.round(b.getBoundingClientRect().height * 100) / 100) }));
                expect(layout.fits).toBe(true);
                expect(Math.min(...layout.controls)).toBeGreaterThanOrEqual(44);
                await dialog.getByRole('button', { name: '关闭', exact: true }).click();
                await expect(dialog).toHaveCount(0);
                await expect(name).toBeFocused();
            }
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
            await page.locator('#viewTable').click();
            await page.getByLabel('搜索', { exact: true }).fill('');
            await expect(page.locator('tbody tr')).toHaveCount(4);
        }
    }
});

test('当已加载图表后切换深浅主题时应该同步文字与网格且保留数据', async ({ page }, testInfo) => {
    await page.getByRole('tab', { name: '数据图表', exact: true }).click();
    await expect.poll(() => page.evaluate(() => Boolean(Chart.getChart('c-monthly')))).toBe(true);
    const snapshot = () => page.evaluate(() => [...document.querySelectorAll('#tab-charts canvas')].map(el => Chart.getChart(el)).filter(Boolean).map(c => ({
        id: c.id, data: JSON.stringify(c.data), text: c.options.plugins.legend.labels.color,
        ticks: Object.values(c.options.scales || {}).map(s => s.ticks.color), grid: Object.values(c.options.scales || {}).map(s => s.grid.color)
    })));
    const light = await snapshot();
    await page.screenshot({ path: testInfo.outputPath('图表-light.png'), fullPage: true, animations: 'disabled' });
    await page.locator('#themeToggle').click();
    const dark = await snapshot();
    await page.screenshot({ path: testInfo.outputPath('图表-dark.png'), fullPage: true, animations: 'disabled' });
    expect(dark.map(c => [c.id, c.data])).toEqual(light.map(c => [c.id, c.data]));
    expect(dark[0].text).not.toBe(light[0].text);
    for (let i = 0; i < dark.length; i++) {
        for (let j = 0; j < dark[i].ticks.length; j++) expect(dark[i].ticks[j]).not.toBe(light[i].ticks[j]);
        for (let j = 0; j < dark[i].grid.length; j++) expect(dark[i].grid[j]).not.toBe(light[i].grid[j]);
    }
    await page.locator('#themeToggle').click();
    expect(await snapshot()).toEqual(light);
});
