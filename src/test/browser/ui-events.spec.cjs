const { test, expect, openAnime } = require('./fixtures.cjs');

test('当编辑含引号名称并展示伪造备注命令时应该保存一次且拒绝原文中的命令', async ({ page, demo }) => {
    const id = await openAnime(page, '演示·雨巷来信');
    const name = `虚构'作品"<&>`, writes = [], dialogs = [];
    page.on('request', request => { if (request.method() !== 'GET') writes.push({ url: request.url(), method: request.method() }); });
    page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.dismiss(); });
    await page.locator('#detailModal .detail-actions').getByRole('button', { name: '编辑', exact: true }).click();
    await page.locator('#m-name').fill(name);
    await page.locator('#m-remark').fill(`<button data-ui-click="deleteAnime" data-ui-args="[${id}]">虚构命令</button>`);
    await page.locator('#editModal').getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.locator('#editModal')).toHaveCount(0);
    expect(writes.filter(write => write.url.endsWith(`/api/anime/${id}/update`))).toHaveLength(1);
    await page.locator('.table-card').getByRole('button', { name, exact: true }).click();
    await expect(page.locator('#detailModal .detail-title')).toHaveText(name);
    const original = page.locator('#detailModal .detail-remark');
    await expect(original.locator('[data-ui-click]')).toHaveCount(0);
    await original.getByRole('button', { name: '虚构命令' }).click();
    await original.getByRole('button', { name: '虚构命令' }).evaluate((element, animeId) => {
        element.dataset.uiClick = 'deleteAnime'; element.dataset.uiArgs = JSON.stringify([animeId]);
    }, id);
    await original.getByRole('button', { name: '虚构命令' }).click();
    expect(dialogs).toEqual([]); expect(writes.filter(write => write.method === 'DELETE')).toHaveLength(0);
    const backup = await (await page.request.get(`${demo.url}/api/anime/export`)).json();
    expect(backup.anime.some(anime => anime.data.name === name)).toBe(true);
    await page.locator('#detailModal .detail-toolbar').getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('#viewDetail').click();
    const remark = page.locator('#detailView .dt-remark').filter({ hasText: '虚构命令' });
    await expect(remark.locator('[data-ui-click]')).toHaveCount(0);
    await remark.getByRole('button', { name: '虚构命令' }).click();
    expect(dialogs).toEqual([]);
});

test('当浏览卡片内添加与图片失败时应该保留参数且不触发父卡详情', async ({ page }) => {
    const name = `虚构'浏览"<&>`, requests = [];
    page.on('request', request => { if (request.url().includes('/api/bangumi/detail/')) requests.push(request.url()); });
    await page.evaluate(displayName => {
        const host = document.createElement('section'); host.id = 'eventBrowseProbe';
        host.appendChild(renderBrowseCard({ id: 999901, name: displayName, eps: 12, date: '2024-04-01', image: '/missing-event-cover' }));
        document.body.prepend(host);
    }, name);
    await expect(page.locator('#eventBrowseProbe .browse-cover-empty')).toHaveText('虚');
    await page.locator('#eventBrowseProbe').getByRole('button', { name: '+ 添加', exact: true }).click();
    await expect(page.locator('#animeName')).toHaveValue(name);
    await expect(page.locator('#animeBangumiId')).toHaveValue('999901');
    await expect(page.locator('#detailModal')).toHaveCount(0); expect(requests).toEqual([]);
});

test('当反复打开分组面板及选择遮罩时应该每次只写一次且保留引号名称', async ({ page, demo }) => {
    const name = `虚构'分组"<&>`, writes = [];
    page.on('request', request => { if (request.url().includes('/api/groups') && request.method() !== 'GET') writes.push(request); });
    await page.locator('[data-ui-click="toggleGroupPanel"]').click();
    await page.locator('#newGroupName').fill(name);
    await page.locator('#groupPanel').getByRole('button', { name: '创建', exact: true }).click();
    const group = page.locator('#groupList .group-item').filter({ hasText: name });
    await expect(group).toHaveCount(1);
    await group.getByRole('button', { name: '查看', exact: true }).click();
    await expect(page.locator('#filterTag')).toHaveValue('');
    await page.locator('[data-ui-click="resetSearch"]').click();
    for (let i = 0; i < 2; i++) {
        await page.locator('[data-ui-click="toggleGroupPanel"]').click();
        await expect(page.locator('#groupPanel')).toHaveClass(/active/);
        await page.locator('#groupPanel').getByRole('button', { name: '关闭', exact: true }).click();
    }
    const id = await openAnime(page, '演示·雨巷来信');
    await page.locator('#detailModal .detail-actions').getByRole('button', { name: '分组', exact: true }).click();
    const picker = page.locator('.detail-overlay').filter({ has: page.getByRole('heading', { name: '选择分组' }) });
    await picker.getByRole('heading', { name: '选择分组' }).click(); await expect(picker).toHaveCount(1);
    await picker.getByRole('checkbox', { name }).check();
    await expect.poll(() => writes.filter(request => request.url().endsWith(`/anime/${id}`)).length).toBe(1);
    await picker.getByRole('button', { name: '完成', exact: true }).click(); await expect(picker).toHaveCount(0);
    const groups = await (await page.request.get(`${demo.url}/api/groups`)).json();
    expect(groups.data.filter(group => group.name === name)).toHaveLength(1);
    expect(writes.filter(request => request.url().endsWith('/api/groups'))).toHaveLength(1);
});

test('当点击年报内容与遮罩时应该只在遮罩点击后关闭', async ({ page }) => {
    await page.getByRole('tab', { name: '数据图表', exact: true }).click();
    await page.getByRole('button', { name: '查看年度报告', exact: true }).click();
    const modal = page.locator('#annualReportModal'); await expect(modal).toBeVisible();
    await modal.locator('.modal-content').click({ position: { x: 20, y: 20 } }); await expect(modal).toBeVisible();
    await modal.click({ position: { x: 3, y: 3 } }); await expect(modal).toBeHidden();
});
