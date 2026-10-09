const { test, expect, openAnime, readEpisodes, handleDialog } = require('./fixtures.cjs');

test('当取消历史日期核对时应该保留历史来源且不发出写请求', async ({ page, demo }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    const before = await readEpisodes(page, id);
    const writes = [];
    page.on('request', request => { if (request.method() === 'PUT') writes.push(request.url()); });
    const dialog = handleDialog(page, /实际观看日期/, false);
    await page.locator('.episode-history-row').first().getByRole('button', { name: '核对并保存' }).click();
    await dialog;
    await expect(page.locator('.episode-history-row').first()).toContainText('历史来源不明');
    expect(await readEpisodes(page, id)).toEqual(before);
    expect(writes).toEqual([]);
});

test('当取消清空已有日期时应该保留记录且保留未保存输入', async ({ page }) => {
    const id = await openAnime(page, '演示·星港巡游');
    const before = await readEpisodes(page, id);
    await page.getByLabel('第 1 集观看日期', { exact: true }).fill('');
    const dialog = handleDialog(page, /清空第 1 集日期/, false);
    await page.locator('.episode-history-row').first().getByRole('button', { name: '保存', exact: true }).click();
    await dialog;
    await expect(page.getByLabel('第 1 集观看日期', { exact: true })).toHaveValue('');
    expect(await readEpisodes(page, id)).toEqual(before);
});

test('当取消放弃日期草稿时应该保持输入而确认后应该重载已保存日期', async ({ page }) => {
    const id = await openAnime(page, '演示·星港巡游');
    await page.getByLabel('第 2 集观看日期', { exact: true }).fill('2024-03-02');
    let dialog = handleDialog(page, /未保存的日期/, false);
    await page.locator('#episodeHistorySection').getByRole('button', { name: '重新加载', exact: true }).click();
    await dialog;
    await expect(page.getByLabel('第 2 集观看日期', { exact: true })).toHaveValue('2024-03-02');
    expect((await readEpisodes(page, id)).entries[1].watchedDate).toBeNull();
    dialog = handleDialog(page, /未保存的日期/, true);
    await page.locator('#episodeHistorySection').getByRole('button', { name: '重新加载', exact: true }).click();
    await dialog;
    await expect(page.getByLabel('第 2 集观看日期', { exact: true })).toHaveValue('');
});

test('当最后一集记看后退集时应该同步详情和首页且移除本次逐集记录', async ({ page }, testInfo) => {
    const id = await openAnime(page, '演示·星港巡游');
    await page.locator('#detailModal').getByRole('button', { name: '下一集', exact: true }).click();
    await expect(page.locator('#detailStatus')).toHaveText('已完成');
    await expect(page.locator('#detailEpisodeCount')).toHaveText('3 / 3');
    await expect(page.locator('#dailyWatch')).toContainText('可继续 1 部');
    expect((await readEpisodes(page, id)).entries[2].source).toBe('WATCHED');
    await page.locator('#detailModal').getByRole('button', { name: '上一集', exact: true }).click();
    await expect(page.locator('#detailStatus')).toHaveText('追中');
    await expect(page.locator('#detailEpisodeCount')).toHaveText('2 / 3');
    await expect(page.locator('#dailyWatch')).toContainText('可继续 2 部');
    expect((await readEpisodes(page, id)).entries).toHaveLength(2);
    await page.screenshot({ path: testInfo.outputPath('完成与退集.png'), fullPage: true });
});

test('当两个窗口保存同一集时应该拒绝过期快照并保留草稿直到确认重载', async ({ page, secondPage: other }) => {
    const id = await openAnime(page, '演示·星港巡游');
    await openAnime(other, '演示·星港巡游');
    await page.getByLabel('第 2 集观看日期', { exact: true }).fill('2024-03-02');
    await other.getByLabel('第 2 集观看日期', { exact: true }).fill('2024-03-03');
    const saved = page.waitForResponse(response => response.url().endsWith(`/episodes/2`) && response.request().method() === 'PUT');
    await page.locator('.episode-history-row').nth(1).getByRole('button', { name: '保存', exact: true }).click();
    expect((await saved).status()).toBe(200);
    await expect(page.locator('.episode-history-row').nth(1)).toContainText('手动补录');
    const conflict = other.waitForResponse(response => response.url().endsWith(`/episodes/2`) && response.request().method() === 'PUT');
    await other.locator('.episode-history-row').nth(1).getByRole('button', { name: '保存', exact: true }).click();
    expect((await conflict).status()).toBe(409);
    await expect(other.locator('#episodeHistoryStatus')).toContainText('输入已保留');
    await expect(other.getByLabel('第 2 集观看日期', { exact: true })).toHaveValue('2024-03-03');
    await expect(other.locator('.episode-history-row').nth(1).getByRole('button', { name: '保存', exact: true })).toBeDisabled();
    expect((await readEpisodes(other, id)).entries[1].watchedDate).toBe('2024-03-02');
    let dialog = handleDialog(other, /未保存的日期/, false);
    await other.locator('#episodeHistorySection').getByRole('button', { name: '重新加载', exact: true }).click();
    await dialog;
    await expect(other.getByLabel('第 2 集观看日期', { exact: true })).toHaveValue('2024-03-03');
    dialog = handleDialog(other, /未保存的日期/, true);
    await other.locator('#episodeHistorySection').getByRole('button', { name: '重新加载', exact: true }).click();
    await dialog;
    await expect(other.getByLabel('第 2 集观看日期', { exact: true })).toHaveValue('2024-03-02');
    await expect(other.locator('.episode-history-row').nth(1).getByRole('button', { name: '保存', exact: true })).toBeEnabled();
});
