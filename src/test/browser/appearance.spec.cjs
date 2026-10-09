const { test, expect } = require('./fixtures.cjs');

test('当切换主题和语言后刷新时应该保留选择并清楚显示按钮用途与本地图标', async ({ page, demo }) => {
    await expect(page.locator('#accentBtn')).toHaveText('主题色');
    await expect(page.locator('#themeToggle')).toHaveText('深色');
    await page.locator('#accentBtn').click();
    await expect(page.locator('#accentBtn')).toHaveAttribute('aria-expanded', 'true');
    await page.getByRole('button', { name: '翡翠主题色', exact: true }).click();
    await expect(page.locator('#accentBtn')).toHaveAttribute('aria-expanded', 'false');
    await page.locator('#themeToggle').click();
    await expect(page.locator('#themeToggle')).toHaveText('浅色');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-accent', 'jade');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.locator('#themeToggle')).toHaveText('浅色');
    await page.getByRole('button', { name: 'Switch to English', exact: true }).click();
    await expect(page.locator('#accentBtn')).toHaveText('Accent');
    await expect(page.locator('#themeToggle')).toHaveText('Light');
    await page.getByRole('button', { name: '切换为中文', exact: true }).click();
    await page.locator('#themeToggle').click();
    await expect(page.locator('#themeToggle')).toHaveText('深色');
    const font = await page.evaluate(async () => {
        await document.fonts.ready;
        const icon = document.querySelector('#themeToggle .ph');
        return { loaded: [...document.fonts].some(font => font.family === 'Phosphor' && font.status === 'loaded'),
            glyph: getComputedStyle(icon, '::before').content,
            sources: performance.getEntriesByType('resource').filter(item => /phosphor/i.test(item.name)).map(item => item.name) };
    });
    expect(font.loaded).toBe(true);
    expect(font.glyph).not.toBe('none');
    expect(font.sources).toHaveLength(2);
    expect(font.sources.every(url => url.startsWith(demo.url + '/css/vendor/phosphor/'))).toBe(true);
});

test('当桌面平板手机切换深浅主题时应该完整显示表单且页面无横向溢出', async ({ page }, testInfo) => {
    await expect(page.getByLabel('番剧名', { exact: true })).toBeVisible();
    for (const width of [1440, 768, 390, 360]) {
        await page.setViewportSize({ width, height: 1000 });
        for (const theme of ['light', 'dark']) {
            const dark = await page.locator('html').getAttribute('data-theme') === 'dark';
            if (dark !== (theme === 'dark')) await page.locator('#themeToggle').click();
            await expect(page.locator('#animeForm')).toBeVisible();
            const layout = await page.locator('#animeForm').evaluate(form => {
                const rect = form.getBoundingClientRect();
                const controls = [...form.querySelectorAll('input:not([type="hidden"]):not([type="file"]):not([type="checkbox"]), select, textarea, button')];
                return { width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
                    fits: controls.every(control => { const box = control.getBoundingClientRect(); return box.left >= rect.left - 1 && box.right <= rect.right + 1; }),
                    smallest: Math.min(...controls.map(control => control.getBoundingClientRect().height)) };
            });
            expect(layout.scrollWidth).toBeLessThanOrEqual(layout.width);
            expect(layout.fits).toBe(true);
            expect(layout.smallest).toBeGreaterThanOrEqual(44);
            await page.locator('.header').screenshot({ path: testInfo.outputPath(`顶部-${width}-${theme}.png`), animations: 'disabled' });
            await page.locator('.add-form').screenshot({ path: testInfo.outputPath(`添加-${width}-${theme}.png`), animations: 'disabled', style: '.mobile-nav { visibility: hidden; }' });
        }
    }
    await page.getByLabel('备注 (Markdown)', { exact: true }).focus();
    await expect(page.getByLabel('备注 (Markdown)', { exact: true })).toBeFocused();
});

test('当从手机添加完整作品时应该校验必填字段并保存全部资料和选项', async ({ page, demo }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const form = page.locator('#animeForm');
    const writes = [];
    page.on('request', request => { if (request.url().endsWith('/api/anime/add')) writes.push(request); });
    await form.getByRole('button', { name: '添加', exact: true }).click();
    expect(await form.evaluate(form => form.checkValidity())).toBe(false);
    expect(writes).toHaveLength(0);
    await form.getByRole('button', { name: 'Bangumi 搜索', exact: true }).click();
    await expect(page.locator('#tw')).toContainText('请先输入番剧名称');
    const fileChooser = page.waitForEvent('filechooser');
    await form.getByRole('button', { name: '以图搜番', exact: true }).click();
    expect((await fileChooser).isMultiple()).toBe(false);
    const values = { '番剧名': '演示·白昼观测站', '总集数': '12', '季度': '2026秋', '评分': '8.4',
        '封面 URL': 'https://example.invalid/demo-cover.png', '观看季度': '2026-秋',
        '开播日期': '2026-10-01', '完结日期': '2026-12-20', '标签': '科幻,日常',
        '备注 (Markdown)': '## 观后记\n等待周末补完，保留 **Markdown**。' };
    for (const [label, value] of Object.entries(values)) await form.getByLabel(label, { exact: true }).fill(value);
    await form.getByLabel('状态', { exact: true }).selectOption('planning');
    await form.getByLabel('放送日', { exact: true }).selectOption('6');
    await form.getByLabel('旧番（不计入追番统计）', { exact: true }).check();
    await form.getByLabel('评分', { exact: true }).fill('11');
    await form.getByRole('button', { name: '添加', exact: true }).click();
    expect(writes).toHaveLength(0);
    await form.getByLabel('评分', { exact: true }).fill('8.4');
    const saved = page.waitForResponse(response => response.url().endsWith('/api/anime/add') && response.request().method() === 'POST');
    await form.getByRole('button', { name: '添加', exact: true }).click();
    expect((await saved).status()).toBe(200);
    expect(writes).toHaveLength(1);
    expect(writes[0].postDataJSON().endDate).toBe('2026-12-20');
    await expect(page.locator('#tw')).toContainText('已添加');
    await expect(form.getByLabel('番剧名', { exact: true })).toHaveValue('');
    await expect(form.getByLabel('旧番（不计入追番统计）', { exact: true })).not.toBeChecked();
    const response = await page.request.get(demo.url + '/api/anime/export');
    expect(response.status()).toBe(200);
    const backup = await response.json();
    const anime = backup.anime.find(item => item.data.name === values['番剧名']);
    // 计划作品按现有观看规则清空完成日期；表单传值与服务规范化分别核对。
    expect(anime.data).toMatchObject({ totalEpisodes: 12, season: '2026秋', score: 8.4, status: 'PLANNING',
        coverUrl: values['封面 URL'], watchSeason: '2026-秋', startDate: '2026-10-01', endDate: null,
        broadcastDay: 6, legacy: true, remark: values['备注 (Markdown)'] });
    expect(backup.tags.filter(tag => anime.tagKeys.includes(tag.key)).map(tag => tag.name).sort()).toEqual(['日常', '科幻']);
});
