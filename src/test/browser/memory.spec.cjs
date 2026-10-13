const { test, expect, openAnime, readEpisodes, handleDialog } = require('./fixtures.cjs');

const section = page => page.locator('#animeMemorySection');
const entry = (page, text) => section(page).locator('.memory-entry').filter({ hasText: text });
async function readMemories(page, id) {
    const response = await page.request.get(new URL(`/api/anime/${id}/memories`, page.url()).href);
    expect(response.status()).toBe(200);
    return (await response.json()).data.entries;
}
async function save(page, text) {
    await page.locator('#memoryContent').fill(text);
    await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
}

test('当初看后追加回望并修改删除时应该保留各次原文且不改变观看记录', async ({ page }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    const before = await readEpisodes(page, id);
    const original = '  虚构感想：这次反转让我很投入。\n结幕时还想再听一次音乐。  ';
    await page.locator('#memoryOptional summary').click();
    await page.locator('#memoryContext').selectOption('INITIAL');
    await page.locator('#memoryLiked').fill('演出与音乐');
    await page.locator('#memoryDisliked').fill('中段有些拖沓');
    await page.locator('#memoryScope').fill('动画第一季');
    await expect(page.locator('#memoryWatchedDate')).toHaveValue('');
    await save(page, original);
    let rows = await readMemories(page, id);
    expect(rows[0]).toMatchObject({ content: original, watchedDate: null, context: 'INITIAL', version: 0 });
    await section(page).getByRole('button', { name: '写一条回望', exact: true }).click();
    await expect(page.locator('#memoryContext')).toHaveValue('REFLECTION');
    await save(page, '现在回想，最喜欢的仍然是结尾。');
    await expect(section(page).locator('.memory-entry')).toHaveCount(2);
    await entry(page, '现在回想').getByRole('button', { name: '编辑', exact: true }).click();
    await save(page, '现在回想，人物的选择更值得回味。');
    rows = await readMemories(page, id);
    expect(rows.find(row => row.context === 'REFLECTION').version).toBe(1);
    expect(rows.find(row => row.context === 'INITIAL').content).toBe(original);
    const dialog = handleDialog(page, /删除这条观影记忆/, true);
    await entry(page, '虚构感想').getByRole('button', { name: '删除', exact: true }).click();
    await dialog;
    await expect(section(page).locator('.memory-entry')).toHaveCount(1);
    await page.reload(); await openAnime(page, '演示·雨巷来信');
    await expect(section(page)).toContainText('人物的选择更值得回味');
    expect(await readEpisodes(page, id)).toEqual(before);
});

test('当从三种作品视图和继续观看写感想时应该直接聚焦正文并仅保存随手记', async ({ page }) => {
    const name = '演示·雨巷来信';
    const sources = [
        [null, '#dailyWatch [aria-label="继续观看"] .daily-row'],
        ['viewTable', '.table-card tbody tr'],
        ['viewDetail', '#detailView .dt-card'],
        ['viewGallery', '#galleryView .g-card']
    ];
    let id;
    for (const [view, selector] of sources) {
        if (view) await page.locator('#' + view).click();
        await page.locator(selector).filter({ hasText: name }).getByRole('button', { name: '写感想', exact: true }).click();
        await expect(page.locator('#memoryContent')).toBeFocused();
        await expect(page.locator('#memoryContext')).toBeHidden();
        await expect(page.locator('#memoryOptional')).not.toHaveAttribute('open', '');
        id = Number(await page.locator('#detailModal').getAttribute('data-anime-id'));
        const position = await page.locator('#memoryContent').evaluate(el => ({
            top: el.getBoundingClientRect().top,
            toolbarBottom: document.querySelector('#detailModal .detail-toolbar').getBoundingClientRect().bottom
        }));
        expect(position.top).toBeGreaterThanOrEqual(position.toolbarBottom);
        await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
    }
    await page.locator('#dailyWatch [aria-label="继续观看"] .daily-row').filter({ hasText: name })
        .getByRole('button', { name: '写感想', exact: true }).click();
    await save(page, '虚构的一句话感想。');
    expect((await readMemories(page, id))[0]).toMatchObject({ content: '虚构的一句话感想。', context: 'NOTE',
        liked: null, disliked: null, scope: null, watchedDate: null });
    await expect(page.locator('#tastePanel')).toHaveCount(0);
});

test('当重复直达同作品并取消跨作品切换时应该保留草稿和原编号', async ({ page }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await page.locator('#memoryContent').fill('虚构的未完成草稿');
    let first = true, savedKey;
    await page.route('**/api/anime/*/memories', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        if (!first) { expect(route.request().postDataJSON().key).toBe(savedKey); return route.continue(); }
        first = false; savedKey = route.request().postDataJSON().key;
        await route.fulfill({ status: 503, json: { code: 503, message: '虚构保存失败' } });
    });
    await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toContainText('输入已保留');
    let reads = 0;
    page.on('request', request => { if (/\/memories\?/.test(request.url())) reads++; });
    await page.evaluate(id => openAnimeMemory(id), id);
    await expect(page.locator('#memoryContent')).toBeFocused();
    await expect(page.locator('#memoryContent')).toHaveValue('虚构的未完成草稿');
    expect(reads).toBe(0);
    const anotherId = await page.locator('#dailyWatch [aria-label="继续观看"] .daily-row button[onclick^="openAnimeMemory"]')
        .evaluateAll((buttons, id) => buttons.map(button => Number(button.getAttribute('onclick').match(/\d+/)[0])).find(value => value !== id), id);
    const dialog = handleDialog(page, /未保存的感想/, false);
    await page.evaluate(id => openAnimeMemory(id), anotherId); await dialog;
    await expect(page.locator('#detailModal')).toHaveAttribute('data-anime-id', String(id));
    await expect(page.locator('#memoryContent')).toHaveValue('虚构的未完成草稿');
    await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
    expect(await readMemories(page, id)).toHaveLength(1);
});

test('当编辑已有补充字段或仅回望语境时应该自动展开并完整保留', async ({ page }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await page.locator('#memoryOptional summary').click();
    await page.locator('#memoryContext').selectOption('REWATCH');
    await page.locator('#memoryLiked').fill('虚构音乐');
    await page.locator('#memoryDisliked').fill('虚构节奏');
    await page.locator('#memoryScope').fill('电影');
    await page.locator('#memoryWatchedDate').fill('2020-01-02');
    await save(page, '带补充的虚构感想');
    await entry(page, '带补充').getByRole('button', { name: '编辑', exact: true }).click();
    await expect(page.locator('#memoryOptional')).toHaveAttribute('open', '');
    await expect(page.locator('#memoryContext')).toHaveValue('REWATCH');
    await expect(page.locator('#memoryLiked')).toHaveValue('虚构音乐');
    await expect(page.locator('#memoryDisliked')).toHaveValue('虚构节奏');
    await expect(page.locator('#memoryScope')).toHaveValue('电影');
    await expect(page.locator('#memoryWatchedDate')).toHaveValue('2020-01-02');
    await save(page, '只修改正文后的虚构感想');
    expect((await readMemories(page, id))[0]).toMatchObject({ context: 'REWATCH', liked: '虚构音乐',
        disliked: '虚构节奏', scope: '电影', watchedDate: '2020-01-02' });
    await section(page).getByRole('button', { name: '写一条回望', exact: true }).click();
    await expect(page.locator('#memoryContext')).toBeVisible();
    await expect(page.locator('#memoryContext')).toHaveValue('REFLECTION');
    await save(page, '仅有回望语境的虚构记录');
    await entry(page, '仅有回望').getByRole('button', { name: '编辑', exact: true }).click();
    await expect(page.locator('#memoryContext')).toBeVisible();
    await expect(page.locator('#memoryContext')).toHaveValue('REFLECTION');
    expect(await readMemories(page, id)).toHaveLength(2);
});

test('当手机深色下从继续观看和封面墙写感想时应该定位可编辑正文并能保存', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 780 });
    await page.locator('#themeToggle').click();
    const row = page.locator('#dailyWatch [aria-label="继续观看"] .daily-row').filter({ hasText: '演示·雨巷来信' });
    await row.getByRole('button', { name: '写感想', exact: true }).click();
    await expect(page.locator('#memoryContent')).toBeFocused();
    await expect(page.locator('#memoryContext')).toBeHidden();
    const bounds = await page.locator('#memoryContent').evaluate(el => ({
        top: el.getBoundingClientRect().top, bottom: el.getBoundingClientRect().bottom,
        toolbarBottom: document.querySelector('#detailModal .detail-toolbar').getBoundingClientRect().bottom,
        height: window.innerHeight, overflow: document.documentElement.scrollWidth > window.innerWidth
    }));
    expect(bounds.top).toBeGreaterThanOrEqual(bounds.toolbarBottom);
    expect(bounds.bottom).toBeLessThan(bounds.height); expect(bounds.overflow).toBe(false);
    await page.screenshot({ path: testInfo.outputPath('手机快速感想.png'), animations: 'disabled' });
    await save(page, '手机里的一句虚构感想');
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('#viewGallery').click();
    await page.locator('#galleryView .g-card').filter({ hasText: '演示·雨巷来信' })
        .getByRole('button', { name: '写感想', exact: true }).click();
    await expect(page.locator('#memoryContent')).toBeFocused();
    await save(page, '封面墙里的另一句虚构感想');
    await expect(section(page).locator('.memory-entry')).toHaveCount(2);
});

test('当双窗口修改同一条感想时应该保留冲突输入并要求重新加载核对', async ({ page, secondPage }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await save(page, '虚构初稿');
    await openAnime(secondPage, '演示·雨巷来信');
    await entry(page, '虚构初稿').getByRole('button', { name: '编辑', exact: true }).click();
    await entry(secondPage, '虚构初稿').getByRole('button', { name: '编辑', exact: true }).click();
    await save(page, '第一窗口修改');
    await secondPage.locator('#memoryContent').fill('第二窗口尚未保存的想法');
    await secondPage.locator('#memorySave').click();
    await expect(secondPage.locator('#memoryStatus')).toContainText('输入已保留，请重新加载');
    await expect(secondPage.locator('#memorySave')).toBeDisabled();
    await expect(secondPage.locator('#memoryContent')).toHaveValue('第二窗口尚未保存的想法');
    let dialog = handleDialog(secondPage, /未保存的感想/, false);
    await section(secondPage).getByRole('button', { name: '重新加载', exact: true }).click(); await dialog;
    await expect(secondPage.locator('#memoryContent')).toHaveValue('第二窗口尚未保存的想法');
    dialog = handleDialog(secondPage, /未保存的感想/, true);
    await section(secondPage).getByRole('button', { name: '重新加载', exact: true }).click(); await dialog;
    await expect(section(secondPage)).toContainText('第一窗口修改');
    await expect(secondPage.locator('#memorySave')).toBeEnabled();
    expect((await readMemories(page, id))[0]).toMatchObject({ content: '第一窗口修改', version: 1 });
});

test('当未保存或正在保存时关闭详情应该尊重放弃确认并防止重复写入', async ({ page }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await page.locator('#memoryContent').fill('暂时还不想提交');
    let dialog = handleDialog(page, /未保存的感想/, false);
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
    await expect(page.locator('#memoryContent')).toHaveValue('暂时还不想提交');
    dialog = handleDialog(page, /未保存的感想/, true);
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
    await expect(page.locator('#detailModal')).toHaveCount(0);
    expect(await readMemories(page, id)).toHaveLength(0);
    await openAnime(page, '演示·雨巷来信');
    let release, received;
    const held = new Promise(resolve => { release = resolve; });
    const started = new Promise(resolve => { received = resolve; });
    let writes = 0;
    await page.route('**/api/anime/*/memories', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        writes++; received(); await held; await route.continue();
    });
    try {
        await page.locator('#memoryContent').fill('等待中的感想');
        await page.locator('#memorySave').click(); await started;
        await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
        await expect(page.locator('#detailModal')).toHaveCount(1);
        await expect(page.locator('#memoryStatus')).toContainText('等待完成后再离开');
        await page.evaluate(id => saveAnimeMemory(id), id);
        expect(writes).toBe(1);
    } finally { release(); }
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
    expect(await readMemories(page, id)).toHaveLength(1);
});

test('当服务端已保存但响应丢失时应该保留草稿并使用相同编号重试', async ({ page }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    let first = true;
    const keys = [];
    await page.route('**/api/anime/*/memories', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        keys.push(route.request().postDataJSON().key);
        if (!first) return route.continue();
        first = false;
        const response = await route.fetch(); expect(response.status()).toBe(200);
        await route.abort('failed');
    });
    await page.locator('#memoryContent').fill('响应丢失也不应该出现两条。');
    await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toContainText('输入已保留');
    await expect(page.locator('#memoryContent')).toHaveValue('响应丢失也不应该出现两条。');
    await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
    expect(keys).toHaveLength(2); expect(keys[1]).toBe(keys[0]);
    expect(await readMemories(page, id)).toHaveLength(1);
});

test('当手机深色下读取失败后重试并写入长文本时应该安全展示并保持可操作布局', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 780 });
    await page.locator('#themeToggle').click();
    let failed = false;
    await page.route('**/api/anime/*/memories?*', async route => {
        if (failed) return route.continue();
        failed = true; await route.fulfill({ status: 503, json: { code: 503, message: '虚构读取失败' } });
    });
    await openAnime(page, '演示·雨巷来信');
    await expect(section(page)).toContainText('感想加载失败');
    await section(page).getByRole('button', { name: '重试', exact: true }).click();
    await expect(section(page)).toContainText('还没有观影记忆');
    const text = '<script>window.memoryUnsafe = true</script>\n' + '虚构长感想'.repeat(100);
    await save(page, text);
    await expect(section(page).locator('.memory-original')).toHaveText(text);
    expect(await page.evaluate(() => window.memoryUnsafe)).toBeUndefined();
    await page.locator('#memoryOptional summary').click();
    const layout = await section(page).evaluate(el => ({
        overflow: document.documentElement.scrollWidth > window.innerWidth,
        heights: [...el.querySelectorAll('button:not([hidden]), textarea, input, select, summary')]
            .filter(control => control.getClientRects().length).map(control => control.getBoundingClientRect().height)
    }));
    expect(layout.overflow).toBe(false); expect(layout.heights.every(height => height >= 44)).toBe(true);
    await page.locator('#memoryContent').scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath('手机深色记忆输入.png'), animations: 'disabled', style: '.mobile-nav { visibility: hidden; }' });
    await page.locator('#memoryOptional summary').click();
    await section(page).locator('.memory-text summary').click();
    await section(page).locator('.memory-original').scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath('手机深色记忆原文.png'), animations: 'disabled', style: '.mobile-nav { visibility: hidden; }' });
});

test('当带着未保存感想往返回看列表时应该保留草稿且不重载或确认放弃', async ({ page }) => {
    await openAnime(page, '演示·雨巷来信');
    await save(page, '已经保存的虚构感想');
    await page.locator('#memoryContent').fill('还没写完的另一条虚构感想');
    const dialogs = [], requests = [];
    page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss(); });
    page.on('request', request => { if (/\/memories(?:[/?]|$)/.test(new URL(request.url()).pathname)) requests.push(request.method()); });
    await section(page).getByRole('button', { name: '回看感想', exact: true }).click();
    await expect(page.locator('#memoryList')).toBeFocused();
    await expect(page.locator('#memoryList')).toContainText('1 条 · 最新记录在前');
    await expect(entry(page, '已经保存')).toBeVisible();
    const position = await page.locator('#memoryList').evaluate(el => ({
        top: el.getBoundingClientRect().top,
        toolbarBottom: document.querySelector('#detailModal .detail-toolbar').getBoundingClientRect().bottom
    }));
    expect(position.top).toBeGreaterThanOrEqual(position.toolbarBottom);
    await page.locator('#memoryList').getByRole('button', { name: '回到输入', exact: true }).click();
    await expect(page.locator('#memoryContent')).toBeFocused();
    await expect(page.locator('#memoryContent')).toHaveValue('还没写完的另一条虚构感想');
    expect(dialogs).toEqual([]); expect(requests).toEqual([]);
});

test('当回看长短感想并展开收起后编辑时应该始终保留完整原文', async ({ page }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await save(page, '短文直接显示');
    const text = '长文的虚构开头\n' + '🌸虚构感想'.repeat(90) + '\n<script>window.readUnsafe=true</script>';
    await save(page, text);
    const longEntry = entry(page, '长文的虚构开头'), shortEntry = entry(page, '短文直接显示');
    await section(page).getByRole('button', { name: '回看感想', exact: true }).click();
    await expect(longEntry.locator('.memory-text')).not.toHaveAttribute('open', '');
    await expect(longEntry.locator('.memory-original')).toBeHidden();
    await expect(shortEntry.locator('.memory-text')).toHaveCount(0);
    await expect(shortEntry.locator('.memory-original')).toBeVisible();
    let requests = 0;
    page.on('request', request => { if (/\/memories(?:[/?]|$)/.test(new URL(request.url()).pathname)) requests++; });
    await longEntry.locator('summary').click();
    await expect(longEntry.locator('.memory-original')).toBeVisible();
    await expect(longEntry.locator('.memory-original')).toHaveText(text);
    expect(await page.evaluate(() => window.readUnsafe)).toBeUndefined();
    await longEntry.locator('summary').press('Enter');
    await expect(longEntry.locator('.memory-original')).toBeHidden();
    expect(requests).toBe(0);
    await longEntry.getByRole('button', { name: '编辑', exact: true }).click();
    await expect(page.locator('#memoryContent')).toHaveValue(text);
    expect((await readMemories(page, id)).find(row => row.content.startsWith('长文的虚构开头')).content).toBe(text);
});

test('当手机深色下回看展开长文并回到输入时应该保持安全布局和草稿', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 780 });
    await page.locator('#themeToggle').click();
    await openAnime(page, '演示·雨巷来信');
    const text = '手机回看的虚构长文\n' + '虚构原文🌸'.repeat(120);
    await save(page, text);
    await page.locator('#memoryContent').fill('手机里尚未保存的虚构草稿');
    await section(page).getByRole('button', { name: '回看感想', exact: true }).click();
    await expect(page.locator('#memoryList')).toBeFocused();
    await expect(page.locator('#memoryContent')).toHaveValue('手机里尚未保存的虚构草稿');
    await page.screenshot({ path: testInfo.outputPath('手机感想摘要.png'), animations: 'disabled' });
    const summary = entry(page, '手机回看').locator('summary');
    await summary.click();
    await expect(entry(page, '手机回看').locator('.memory-original')).toHaveText(text);
    const layout = await section(page).evaluate(el => ({
        overflow: document.documentElement.scrollWidth > window.innerWidth,
        heights: [...el.querySelectorAll('button:not([hidden]), summary')]
            .filter(control => control.getClientRects().length).map(control => control.getBoundingClientRect().height)
    }));
    expect(layout.overflow).toBe(false); expect(layout.heights.every(height => height >= 44)).toBe(true);
    await summary.scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath('手机完整感想.png'), animations: 'disabled' });
    await summary.click();
    await page.locator('#memoryList').getByRole('button', { name: '回到输入', exact: true }).click();
    await expect(page.locator('#memoryContent')).toBeFocused();
    await expect(page.locator('#memoryContent')).toHaveValue('手机里尚未保存的虚构草稿');
});

test('当完整备份导入感想时应该显示新增数并保留原编号日期与书写时间', async ({ page, demo }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await save(page, '本地备份里的虚构记忆');
    const before = (await readMemories(page, id))[0];
    const exported = await (await page.request.get(demo.url + '/api/anime/export')).json();
    expect(exported.version).toBe(6); expect(exported.memories).toHaveLength(1);
    let dialog = handleDialog(page, /删除这条观影记忆/, true);
    await entry(page, '虚构记忆').getByRole('button', { name: '删除', exact: true }).click(); await dialog;
    await expect(section(page).locator('.memory-entry')).toHaveCount(0);
    for (const count of [1, 0]) {
        dialog = handleDialog(page, new RegExp(`观影记忆 1 条，其中新增 ${count} 条`), true);
        await page.locator('input[type="file"][accept=".json"]').setInputFiles({ name: '虚构观影备份.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(exported)) });
        await dialog;
        await expect(page.locator('#detailModal')).toHaveCount(0);
        const { id: localId, ...original } = before;
        // 恢复会重新分配库内主键，跨备份身份由稳定UUID保证。
        expect((await readMemories(page, id))[0]).toMatchObject(original);
        if (count === 1) await openAnime(page, '演示·雨巷来信');
    }
});
