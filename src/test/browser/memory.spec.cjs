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
    await page.locator('#memoryContext').selectOption('INITIAL');
    await page.locator('#memoryOptional summary').click();
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
    await section(page).locator('.memory-original').scrollIntoViewIfNeeded();
    await page.screenshot({ path: testInfo.outputPath('手机深色记忆原文.png'), animations: 'disabled', style: '.mobile-nav { visibility: hidden; }' });
});

test('当完整备份导入感想时应该显示新增数并保留原编号日期与书写时间', async ({ page, demo }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    await save(page, '本地备份里的虚构记忆');
    const before = (await readMemories(page, id))[0];
    const exported = await (await page.request.get(demo.url + '/api/anime/export')).json();
    expect(exported.version).toBe(2); expect(exported.memories).toHaveLength(1);
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
