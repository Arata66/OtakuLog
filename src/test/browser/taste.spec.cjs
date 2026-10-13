const { test: base, expect, openAnime, handleDialog } = require('./fixtures.cjs');
const { createAiServer } = require('./ai-server.cjs');
const { randomUUID } = require('node:crypto');

const sourceText = '虚构音乐很动人。结尾的人物选择让我投入。';
const negativeText = '中段音乐太满';
const candidates = [
    { quoteField: 'CONTENT', quote: '虚构音乐很动人', statement: '喜欢结尾音乐的留白', direction: 'LIKE', aspect: 'AUDIOVISUAL', factor: '音乐' },
    { quoteField: 'DISLIKED', quote: negativeText, statement: '不喜欢中段过密的音乐', direction: 'DISLIKE', aspect: 'AUDIOVISUAL', factor: '音乐' }
];
const provider = insights => ({ choices: [{ finish_reason: 'stop', message: { content: JSON.stringify({ insights }) } }], usage: { prompt_tokens: 12, completion_tokens: 3 } });
const test = base.extend({
    aiServer: async ({}, use) => {
        const state = { mode: 'success' };
        const server = await createAiServer(async request => {
            if (state.wait) await state.wait;
            return state.mode === 'failure' ? { status: 503, body: { error: '虚构供应商故障' } } : { status: 200, body: provider(state.mode === 'empty' ? [] : candidates) };
        });
        try { await use({ ...server, state }); } finally { await server.close(); }
    },
    aiBaseUrl: async ({ aiServer }, use) => { await use(aiServer.url); }
});
const panel = page => page.locator('#tastePanel');
const row = (page, text) => page.locator('.taste-entry').filter({ hasText: text });
async function prepare(page, text = sourceText) {
    const id = await openAnime(page, '演示·雨巷来信');
    await page.locator('#memoryContent').fill(text);
    await page.locator('#memoryOptional summary').click();
    await page.locator('#memoryDisliked').fill(negativeText); await page.locator('#memoryScope').fill('动画第一季');
    await page.locator('#memoryContext').selectOption('INITIAL'); await page.locator('#memorySave').click();
    await expect(page.locator('#memoryStatus')).toHaveText('感想已保存。');
    await page.locator('.memory-entry').first().getByRole('button', { name: '整理观点', exact: true }).click();
    await expect(panel(page)).toContainText('在这部作品中');
    const response = await page.request.get(new URL(`/api/anime/${id}/memories`, page.url()).href);
    return { id, memory: (await response.json()).data.entries[0] };
}
async function preview(page) {
    await panel(page).locator('.taste-ai > summary').click();
    await panel(page).getByRole('button', { name: '预览发送内容与预算', exact: true }).click();
    await expect(page.locator('#tastePreview')).toContainText('发送前核对');
}
async function extract(page) {
    await preview(page); await page.locator('#tasteSend').click();
    await expect(page.locator('.taste-entry')).toHaveCount(2);
}
async function manual(page, statement = '手工喜欢结尾音乐') {
    await page.locator('#tasteQuote').fill('虚构音乐很动人'); await page.locator('#tasteStatement').fill(statement);
    await page.locator('#tasteFactor').fill('音乐'); await page.locator('#tasteAspect').selectOption('AUDIOVISUAL');
    await page.locator('#tasteSave').click(); await expect(page.locator('#tasteStatus')).toContainText('观点已保存');
}
async function profile(page, demo) { return (await (await page.request.get(demo.url + '/api/taste/profile')).json()).data; }

test('当手工整理并改写拒绝删除时应该直接更新本地总结且不调用AI', async ({ page, aiServer, demo }) => {
    await prepare(page); await manual(page);
    await expect(page.locator('#tasteProfile')).toContainText('1 部作品 · 1 条已确认依据');
    await row(page, '手工喜欢').getByRole('button', { name: '改写观点', exact: true }).click();
    await page.locator('#tasteStatement').fill('改写后的个人观点'); await page.locator('#tasteSave').click();
    await expect(page.locator('#tasteProfile')).toContainText('改写后的个人观点');
    await row(page, '改写后').getByRole('button', { name: '拒绝观点', exact: true }).click();
    await expect(page.locator('#tasteProfile')).toContainText('0 条已确认依据');
    await row(page, '改写后').getByRole('button', { name: '确认观点', exact: true }).click();
    await expect(page.locator('#tasteProfile')).toContainText('1 条已确认依据');
    const dialog = handleDialog(page, /删除这条观点/, true);
    await row(page, '改写后').getByRole('button', { name: '删除观点', exact: true }).click(); await dialog;
    await expect(page.locator('.taste-entry')).toHaveCount(0);
    expect((await profile(page, demo)).evidenceCount).toBe(0); expect(aiServer.requests).toHaveLength(0);
});

test('当预览取消后明确发送AI并核对正负观点时应该显示预算用量且保留原建议与缓存', async ({ page, aiServer, demo }) => {
    await prepare(page); await preview(page);
    await expect(page.locator('#tastePreview')).toContainText('deepseek-flash');
    await expect(page.locator('#tastePreview')).toContainText('仅估算');
    expect(aiServer.requests).toHaveLength(0);
    await panel(page).getByRole('button', { name: '取消预览', exact: true }).click(); expect(aiServer.requests).toHaveLength(0);
    await panel(page).getByRole('button', { name: '预览发送内容与预算', exact: true }).click(); await page.locator('#tasteSend').click();
    await expect(page.locator('.taste-entry')).toHaveCount(2); expect(aiServer.requests).toHaveLength(1);
    expect((await profile(page, demo)).evidenceCount).toBe(0);
    await expect(page.locator('#tasteResult')).toContainText('实际输入 12');
    await row(page, '喜欢结尾').getByRole('button', { name: '改写观点', exact: true }).click();
    await page.locator('#tasteStatement').fill('我喜欢的是安静段落'); await page.locator('#tasteSave').click();
    await row(page, '我喜欢的是').locator('summary').click(); await expect(row(page, '我喜欢的是')).toContainText('喜欢结尾音乐的留白');
    await row(page, '不喜欢中段').getByRole('button', { name: '确认观点', exact: true }).click();
    await expect(page.locator('#tasteProfile')).toContainText('正负观点并存');
    expect((await profile(page, demo)).animeCount).toBe(1); expect((await profile(page, demo)).evidenceCount).toBe(2);
    await row(page, '不喜欢中段').getByRole('button', { name: '拒绝观点', exact: true }).click();
    await panel(page).getByRole('button', { name: '预览发送内容与预算', exact: true }).click(); await page.locator('#tasteSend').click();
    await expect(page.locator('#tasteResult')).toContainText('已复用缓存');
    await expect(row(page, '我喜欢的是')).toContainText('已确认'); await expect(row(page, '不喜欢中段')).toContainText('已拒绝');
    expect(aiServer.requests).toHaveLength(1);
    for (const text of ['我喜欢的是', '不喜欢中段']) {
        const dialog = handleDialog(page, /删除这条观点/, true); await row(page, text).getByRole('button', { name: '删除观点', exact: true }).click(); await dialog;
    }
    await panel(page).getByRole('button', { name: '预览发送内容与预算', exact: true }).click(); await page.locator('#tasteSend').click();
    await expect(page.locator('.taste-entry')).toHaveCount(0); expect(aiServer.requests).toHaveLength(1);
});

test('当服务端AI提交成功后响应丢失时应该GET核对原编号且只调用一次供应商', async ({ page, aiServer }) => {
    await prepare(page); let lost = false, key;
    await page.route('**/insights/ai/extract', async route => {
        key = route.request().postDataJSON().requestKey; const response = await route.fetch(); expect(response.status()).toBe(200);
        lost = true; await route.abort('failed');
    });
    await extract(page); expect(lost).toBe(true); expect(aiServer.requests).toHaveLength(1);
    await expect(page.locator('#tasteResult')).toContainText(key);
    await panel(page).getByRole('button', { name: '核对本次调用记录', exact: true }).click(); expect(aiServer.requests).toHaveLength(1);
});

test('当双窗口观点冲突时应该保留草稿并显式重新加载', async ({ page, secondPage, aiServer }) => {
    await prepare(page); await manual(page); await openAnime(secondPage, '演示·雨巷来信');
    await secondPage.locator('.memory-entry').getByRole('button', { name: '整理观点', exact: true }).click();
    await row(page, '手工喜欢').getByRole('button', { name: '改写观点', exact: true }).click();
    await row(secondPage, '手工喜欢').getByRole('button', { name: '改写观点', exact: true }).click();
    await page.locator('#tasteStatement').fill('第一窗口观点'); await page.locator('#tasteSave').click();
    await secondPage.locator('#tasteStatement').fill('第二窗口未保存观点'); await secondPage.locator('#tasteSave').click();
    await expect(secondPage.locator('#tasteStatus')).toContainText('输入已保留，请重新加载');
    await expect(secondPage.locator('#tasteSave')).toBeDisabled(); await expect(secondPage.locator('#tasteStatement')).toHaveValue('第二窗口未保存观点');
    let dialog = handleDialog(secondPage, /未保存的观点/, false); await panel(secondPage).getByRole('button', { name: '重新加载原文与观点', exact: true }).click(); await dialog;
    await expect(secondPage.locator('#tasteStatement')).toHaveValue('第二窗口未保存观点');
    dialog = handleDialog(secondPage, /未保存的观点/, true); await panel(secondPage).getByRole('button', { name: '重新加载原文与观点', exact: true }).click(); await dialog;
    await expect(panel(secondPage)).toContainText('第一窗口观点'); expect(aiServer.requests).toHaveLength(0);
});

test('当原文变更时应该排除旧观点并保留另一份草稿直到显式重核', async ({ page, demo }) => {
    await prepare(page); await manual(page); await row(page, '手工喜欢').getByRole('button', { name: '改写观点', exact: true }).click();
    await page.locator('#tasteStatement').fill('另一份尚未保存的观点');
    const dialog = handleDialog(page, /未保存的观点/, true);
    await page.locator('.memory-entry').getByRole('button', { name: '编辑', exact: true }).click(); await dialog;
    await page.locator('#memoryContent').fill('新的原文：虚构音乐很动人，但我想法变了。'); await page.locator('#memorySave').click();
    await expect(page.locator('#tasteStatement')).toHaveValue('另一份尚未保存的观点');
    await expect(panel(page)).toContainText('原文已变动'); expect((await profile(page, demo)).evidenceCount).toBe(0);
    const reload = handleDialog(page, /未保存的观点/, true); await panel(page).getByRole('button', { name: '重新加载原文与观点', exact: true }).click(); await reload;
    await panel(page).getByRole('button', { name: '核对新原文并重新引用', exact: true }).click();
    await page.locator('#tasteQuote').fill('虚构音乐很动人'); await page.locator('#tasteStatement').fill('核对新原文后的观点'); await page.locator('#tasteSave').click();
    await expect(page.locator('#tasteProfile')).toContainText('核对新原文后的观点');
    expect((await profile(page, demo)).staleCount).toBe(0);
});

test('当超长原文或选文变更时应该完整保留并使预算预览失效', async ({ page, aiServer }) => {
    const text = sourceText + '很长的虚构感想'.repeat(1000); await prepare(page, text);
    await panel(page).locator('.taste-ai > summary').click(); await expect(page.locator('#tasteSelectContent')).toHaveValue(text);
    await panel(page).getByRole('button', { name: '预览发送内容与预算', exact: true }).click();
    await expect(page.locator('#tasteStatus')).toContainText('6000'); expect(aiServer.requests).toHaveLength(0);
    await page.locator('#tasteSelectContent').fill(sourceText); await panel(page).getByRole('button', { name: '预览发送内容与预算', exact: true }).click();
    await expect(page.locator('#tasteSend')).toBeVisible(); await page.locator('#tasteSelectContent').fill('虚构音乐很动人');
    await expect(page.locator('#tasteSend')).toHaveCount(0); expect(aiServer.requests).toHaveLength(0);
});

test('当手机明暗主题展示含HTML观点时应该安全文本且触控44像素不溢出', async ({ page, aiServer }, testInfo) => {
    const text = sourceText + '<img src=x onerror="window.tasteUnsafe=1">'; await page.setViewportSize({ width: 360, height: 780 }); await prepare(page, text);
    await page.locator('#tasteQuote').fill('<img src=x onerror="window.tasteUnsafe=1">'); await page.locator('#tasteStatement').fill('<script>window.tasteUnsafe=2</script>');
    await page.locator('#tasteFactor').fill('<svg onload="window.tasteUnsafe=3">'); await page.locator('#tasteSave').click();
    await expect(page.locator('#tasteStatus')).toContainText('观点已保存');
    for (const width of [360, 390]) {
        await page.setViewportSize({ width, height: 780 });
        const layout = await panel(page).evaluate(el => ({ overflow: document.documentElement.scrollWidth > window.innerWidth,
            heights: [...el.querySelectorAll('button, input, textarea, select, summary')].filter(x => x.getClientRects().length).map(x => x.getBoundingClientRect().height) }));
        expect(layout.overflow).toBe(false); expect(layout.heights.every(height => height >= 44)).toBe(true);
        await panel(page).scrollIntoViewIfNeeded(); await page.screenshot({ path: testInfo.outputPath(`画像手机${width}.png`), animations: 'disabled' });
        await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await page.locator('#themeToggle').click();
        await openAnime(page, '演示·雨巷来信'); await page.locator('.memory-entry').getByRole('button', { name: '整理观点', exact: true }).click();
    }
    expect(await page.evaluate(() => window.tasteUnsafe)).toBeUndefined(); expect(aiServer.requests).toHaveLength(0);
});

test('当观点草稿或AI选文未保存时关闭与原文重载应该要求放弃确认', async ({ page }) => {
    await prepare(page); await page.locator('#tasteStatement').fill('未写完观点');
    let dialog = handleDialog(page, /未保存的观点/, false); await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
    await expect(page.locator('#tasteStatement')).toHaveValue('未写完观点');
    dialog = handleDialog(page, /未保存的观点/, false); await page.locator('#memoryPages').getByRole('button', { name: '重新加载', exact: true }).click(); await dialog;
    await expect(page.locator('#tasteStatement')).toHaveValue('未写完观点');
    dialog = handleDialog(page, /未保存的观点/, true); await panel(page).getByRole('button', { name: '取消编辑', exact: true }).click(); await dialog;
    await panel(page).locator('.taste-ai > summary').click(); await page.locator('#tasteSelectContent').fill('虚构音乐很动人');
    dialog = handleDialog(page, /AI选文/, false); await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
    await expect(page.locator('#tasteSelectContent')).toHaveValue('虚构音乐很动人');
});

test('当AI发送期间重复点击与关闭时应该阻止退出及重复供应商调用', async ({ page, aiServer }) => {
    await prepare(page); await preview(page); let release; aiServer.state.wait = new Promise(resolve => { release = resolve; });
    try {
        await page.locator('#tasteSend').click(); await expect(page.locator('#tasteStatus')).toContainText('正在发送');
        await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click();
        await expect(panel(page)).toBeVisible(); await expect(page.locator('#tasteStatus')).toContainText('请等待完成');
        await page.evaluate(() => sendTasteAi()); await expect.poll(() => aiServer.requests.length).toBe(1);
    } finally { release(); }
    await expect(page.locator('.taste-entry')).toHaveCount(2); expect(aiServer.requests).toHaveLength(1);
});

test('当供应商失败时应该保留编号及选文并显示未知用量不自动重试', async ({ page, aiServer }) => {
    await prepare(page); aiServer.state.mode = 'failure'; await preview(page); await page.locator('#tasteSend').click();
    await expect(page.locator('#tasteResult')).toContainText('实际用量未知');
    await expect(page.locator('#tasteStatus')).toContainText('不会自动重试'); expect(aiServer.requests).toHaveLength(1);
    await panel(page).getByRole('button', { name: '核对本次调用记录', exact: true }).click(); expect(aiServer.requests).toHaveLength(1);
    await expect(page.locator('#tasteSelectContent')).toHaveValue(sourceText);
    await expect(page.locator('#tasteUsage')).toContainText('1 次实际用量未知');
});

test('当完整v3备份往返时应该恢复用户观点原建议调用记录并刷新总结用量', async ({ page, aiServer, demo }) => {
    await prepare(page); await extract(page);
    await row(page, '喜欢结尾').getByRole('button', { name: '确认观点', exact: true }).click();
    await expect(row(page, '喜欢结尾')).toContainText('已确认');
    const backup = await (await page.request.get(demo.url + '/api/anime/export')).json();
    expect(backup.version).toBe(5); expect(backup.insights).toHaveLength(2); expect(backup.aiCalls).toHaveLength(1);
    const deleting = handleDialog(page, /删除这条观影记忆/, true); await page.locator('.memory-entry').getByRole('button', { name: '删除', exact: true }).click(); await deleting;
    await expect(panel(page)).toHaveCount(0);
    const restore = handleDialog(page, /观点 2 条，其中新增 2 条[\s\S]*AI调用 1 条/, true);
    await page.locator('input[type="file"][accept=".json"]').setInputFiles({ name: '虚构画像备份.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(backup)) }); await restore;
    await expect(page.locator('#detailModal')).toHaveCount(0); await expect(page.locator('#tasteProfile')).toContainText('1 条已确认依据');
    await expect(page.locator('#tasteUsage')).toContainText('本应用今日调用 1/20');
    const restored = await (await page.request.get(demo.url + '/api/anime/export')).json();
    for (const field of ['insights', 'aiCalls']) expect(restored[field]).toEqual(backup[field]); expect(aiServer.requests).toHaveLength(1);
});

test('当恢复失败时应该保留观点草稿并恢复控件', async ({ page, demo }) => {
    await prepare(page); await page.locator('#tasteStatement').fill('恢复失败应保留的草稿');
    const backup = await (await page.request.get(demo.url + '/api/anime/export')).text();
    await page.route('**/api/anime/import', route => route.fulfill({ status: 503, json: { code: 503, message: '虚构恢复失败' } }));
    const leave = page.waitForEvent('dialog').then(async d => {
        expect(d.message()).toMatch(/确认恢复/);
        // Edge 会立即打开下一个原生弹窗，必须在确认前开始监听。
        const nextDialog = page.waitForEvent('dialog');
        await d.accept();
        const next = await nextDialog; expect(next.message()).toMatch(/未保存的观点/); await next.accept();
    });
    // 直接调用入口可验证失败保留，不伪造成功写入。
    await page.evaluate(json => { window.restorePromise = importBackupJson(json); }, backup); await leave;
    await page.evaluate(() => window.restorePromise); await expect(page.locator('#tasteStatement')).toHaveValue('恢复失败应保留的草稿'); await expect(page.locator('#tasteSave')).toBeEnabled();
    await page.unroute('**/api/anime/import');
});

test('当当日20次额度用尽时应该禁用新AI发送且仍允许手工观点', async ({ page, aiServer, demo }) => {
    aiServer.state.mode = 'empty'; const long = sourceText + '额度用虚构片段0123456789'; const { id, memory } = await prepare(page, long);
    const csrf = await page.locator('meta[name="_csrf"]').getAttribute('content');
    const csrfHeader = await page.locator('meta[name="_csrf_header"]').getAttribute('content');
    for (let i = 1; i <= 20; i++) {
        const response = await page.request.post(`${demo.url}/api/anime/${id}/memories/${memory.id}/insights/ai/extract`, { headers: { [csrfHeader]: csrf }, data: { requestKey: randomUUID(), sourceVersion: memory.version, selections: { content: long.slice(0, i), liked: '', disliked: '' } } }); expect(response.status()).toBe(200);
    }
    await preview(page); await expect(page.locator('#tastePreview')).toContainText('剩余 0 次'); await expect(page.locator('#tasteSend')).toBeDisabled();
    await manual(page); expect(aiServer.requests).toHaveLength(20);
});

test('当调用尚未到达后端且GET未知时应该保留草稿并显式复用同编号重试', async ({ page, aiServer }) => {
    await prepare(page); let first = true; const keys = [];
    await page.route('**/insights/ai/extract', async route => {
        keys.push(route.request().postDataJSON().requestKey);
        if (first) { first = false; return route.abort('failed'); } return route.continue();
    });
    await preview(page); await page.locator('#tasteSend').click();
    await expect(page.locator('#tasteStatus')).toContainText('暂未核对到'); expect(aiServer.requests).toHaveLength(0);
    await expect(page.locator('#tasteResult')).toContainText(keys[0]);
    await page.locator('#tasteStatement').fill('独立的未保存观点');
    let dialog = handleDialog(page, /未保存的观点/, false); await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
    dialog = handleDialog(page, /使用原编号和原选文重试/, true); await panel(page).getByRole('button', { name: '确认后使用同编号重试', exact: true }).click(); await dialog;
    await expect(page.locator('.taste-entry')).toHaveCount(2); expect(keys).toHaveLength(2); expect(keys[1]).toBe(keys[0]); expect(aiServer.requests).toHaveLength(1);
    await expect(page.locator('#tasteStatement')).toHaveValue('独立的未保存观点');
    dialog = handleDialog(page, /未保存的观点/, false); await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
});

test('当成功整理后更改选文时应该核对旧调用后显式新预览并保护手工草稿', async ({ page, aiServer }) => {
    await prepare(page); await page.locator('#tasteStatement').fill('未完成的手工草稿'); await extract(page);
    await expect(page.locator('#tasteStatement')).toHaveValue('未完成的手工草稿');
    let dialog = handleDialog(page, /未保存的观点/, false); await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await dialog;
    await page.locator('#tasteSelectContent').fill('虚构音乐很动人');
    dialog = handleDialog(page, /新的整理会使用新编号/, true); await panel(page).getByRole('button', { name: '核对后开始新的预览', exact: true }).click(); await dialog;
    await expect(page.locator('#tasteSend')).toBeVisible(); expect(aiServer.requests).toHaveLength(1);
    await expect(page.locator('#tasteCallHistory')).toContainText('旧调用编号');
    await page.locator('#tasteSend').click(); await expect(page.locator('#tasteResult')).toContainText('实际输入 12');
    expect(aiServer.requests).toHaveLength(2); await expect(page.locator('#tasteStatement')).toHaveValue('未完成的手工草稿');
});

test('当首页证据引用在感想分页之外时应该通过单条GET准确打开原文', async ({ page, demo }) => {
    const { id, memory } = await prepare(page); await manual(page);
    await page.evaluate(async id => {
        for (let i = 0; i < 21; i++) {
            const result = await fetchApi(`/api/anime/${id}/memories`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key: crypto.randomUUID(), content: `分页虚构感想${i}`, context: 'NOTE' }) });
            if (result.code !== 200) throw new Error('虚构分页记忆写入失败');
        }
    }, id);
    await page.locator('#detailModal').getByRole('button', { name: '关闭', exact: true }).click(); await page.reload();
    await page.locator('#tasteProfile > details > summary').click();
    let exactReads = 0; page.on('request', request => { if (new URL(request.url()).pathname === `/api/anime/${id}/memories/${memory.id}`) exactReads++; });
    await page.locator('#tasteProfile').getByRole('button', { name: '查看准确原文并核对', exact: true }).click();
    await expect(panel(page)).toContainText('手工喜欢结尾音乐');
    await panel(page).locator('.taste-source > summary').click(); await expect(page.locator('#tasteSourceBody')).toContainText(sourceText);
    await expect(page.locator('#memoryList')).not.toContainText(sourceText); expect(exactReads).toBe(1); expect((await profile(page, demo)).evidenceCount).toBe(1);
});

test('当旧AI响应在新详情后返回时应该隔离迟到结果并保护新草稿', async ({ page, aiServer }) => {
    await prepare(page); await preview(page); let release; aiServer.state.wait = new Promise(resolve => { release = resolve; });
    try {
        await page.locator('#tasteSend').click(); await expect.poll(() => aiServer.requests.length).toBe(1);
        await page.evaluate(() => closeDetailModal(true)); await openAnime(page, '演示·星港巡游');
        await page.locator('#memoryContent').fill('新弹窗里的虚构草稿');
        const returned = page.waitForResponse(response => response.url().endsWith('/insights/ai/extract'));
        release(); await returned;
        await expect(page.locator('#memoryContent')).toHaveValue('新弹窗里的虚构草稿'); await expect(panel(page)).toHaveCount(0);
        await expect(page.locator('#detailModal')).toHaveAttribute('data-anime-id', '1');
    } finally { release(); }
});

test('当旧恢复成功响应迟到时应该不关闭或恢复新详情实例的控件', async ({ page, demo }) => {
    await prepare(page); const backup = await (await page.request.get(demo.url + '/api/anime/export')).text();
    let release, reached; const held = new Promise(resolve => { release = resolve; }); const started = new Promise(resolve => { reached = resolve; });
    await page.route('**/api/anime/import', async route => { const response = await route.fetch(); expect(response.status()).toBe(200); reached(); await held; await route.fulfill({ response }); });
    const confirming = handleDialog(page, /确认恢复/, true);
    await page.evaluate(json => { window.oldRestore = importBackupJson(json); }, backup); await confirming; await started;
    try {
        await page.evaluate(() => closeDetailModal(true)); await openAnime(page, '演示·雨巷来信');
        await page.locator('.memory-entry').getByRole('button', { name: '整理观点', exact: true }).click(); await expect(panel(page)).toContainText('在这部作品中');
        await page.locator('#tasteStatement').fill('新实例的观点草稿'); release(); await page.evaluate(() => window.oldRestore);
        await expect(page.locator('#tasteStatement')).toHaveValue('新实例的观点草稿'); await expect(page.locator('#tasteSave')).toBeEnabled(); await expect(page.locator('#detailModal')).toHaveCount(1);
    } finally { release(); }
});
