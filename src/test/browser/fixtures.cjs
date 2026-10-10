const { test: base, expect } = require('@playwright/test');
const path = require('node:path');
const net = require('node:net');
const { runDemo } = require('../../../scripts/demo.cjs');
const { createDemoConfig, executeSql } = require('../../../scripts/lib/demo-config.cjs');

async function configureContext(context, demo) {
    const assets = {
        '/npm/chart.js@4.4.1/dist/chart.umd.min.js': 'chart.js/dist/chart.umd.js',
        '/npm/sortablejs@1.15.0/Sortable.min.js': 'sortablejs/Sortable.min.js',
        '/npm/marked@12/marked.min.js': 'marked/marked.min.js',
        '/npm/dompurify@3/dist/purify.min.js': 'dompurify/dist/purify.min.js'
    };
    await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        // 浏览器验收使用虚构结果，上游匹配和故障分支由后端回归覆盖。
        if (url.origin === demo.url && url.pathname === '/api/watch/airing')
            return route.fulfill({ json: { code: 200, data: { airingStatus: 'UNAVAILABLE', todayAiringCount: null, todayAiring: [] } } });
        if (url.origin === demo.url) return route.continue();
        const asset = url.origin === 'https://cdn.jsdelivr.net' && assets[url.pathname];
        if (asset) return route.fulfill({ path: path.join(demo.config.root, 'node_modules', asset), contentType: 'application/javascript' });
        return route.abort();
    });
}

async function login(page, demo) {
    await page.goto(demo.url + '/login');
    await page.locator('[name="username"]').fill(demo.username);
    await page.locator('[name="password"]').fill(demo.password);
    await Promise.all([page.waitForURL(demo.url + '/'), page.getByRole('button', { name: '登录', exact: true }).click()]);
    await expect(page.locator('#dailyWatch')).toContainText('可继续 2 部');
}

const test = base.extend({
    bangumiBaseUrl: ['', { option: true }],
    webdavConfig: [{}, { option: true }],
    demo: async ({ bangumiBaseUrl, webdavConfig }, use, testInfo) => {
        const server = net.createServer();
        await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
        const port = server.address().port;
        await new Promise(resolve => server.close(resolve));
        const config = createDemoConfig({ port, env: { ...process.env, OTAKULOG_BANGUMI_BASE_URL: bangumiBaseUrl,
            OTAKULOG_DEMO_WEBDAV_URL: webdavConfig.url || '', OTAKULOG_DEMO_WEBDAV_USERNAME: webdavConfig.username || '',
            OTAKULOG_DEMO_WEBDAV_PASSWORD: webdavConfig.password || '' } });
        const controller = new AbortController();
        let readyResolve, readyReject;
        const ready = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject; });
        const lifetime = runDemo(config, controller.signal, value => readyResolve({ ...value, config }));
        // 提前启动失败必须传给等待就绪的测试，且不能形成未处理拒绝。
        lifetime.catch(readyReject);
        try { await use(await ready); }
        finally {
            controller.abort();
            try { await lifetime; } catch (error) { if (error.name !== 'AbortError') throw error; }
            const count = executeSql(config, `SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${config.database}'`);
            expect(count, '本次演示库应已清理').toBe('0');
            await testInfo.attach('演示清理核对', { body: Buffer.from(JSON.stringify({ database: config.database, cleaned: true })), contentType: 'application/json' });
        }
    },
    page: async ({ page, context, demo }, use) => {
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await configureContext(context, demo);
        await login(page, demo);
        await use(page);
        expect(errors, '页面不应发生脚本异常').toEqual([]);
    },
    secondPage: async ({ browser, demo }, use, testInfo) => {
        const context = await browser.newContext({ serviceWorkers: 'block', timezoneId: 'Asia/Shanghai' });
        const errors = [];
        try {
            await configureContext(context, demo);
            const page = await context.newPage();
            page.on('pageerror', error => errors.push(error.message));
            await login(page, demo);
            await use(page);
            expect(errors, '第二窗口不应发生脚本异常').toEqual([]);
        } finally {
            const failed = testInfo.status !== testInfo.expectedStatus;
            try {
                if (failed && context.pages()[0])
                    await context.pages()[0].screenshot({ path: testInfo.outputPath('第二窗口失败.png') });
            } finally { await context.close(); }
        }
    }
});

async function openAnime(page, name) {
    await page.locator('#dailyWatch [aria-label="继续观看"]').getByRole('button', { name, exact: true }).click();
    await expect(page.locator('#episodeHistorySection .episode-history-row')).toHaveCount(2);
    return Number(await page.locator('#detailModal').getAttribute('data-anime-id'));
}

async function readEpisodes(page, id) {
    const response = await page.request.get(new URL(`/api/anime/${id}/episodes`, page.url()).href);
    expect(response.status()).toBe(200);
    return (await response.json()).data;
}

function handleDialog(page, message, accept) {
    return page.waitForEvent('dialog').then(async dialog => {
        try { expect(dialog.type()).toBe('confirm'); expect(dialog.message()).toMatch(message); }
        catch (error) { await dialog.dismiss(); throw error; }
        await (accept ? dialog.accept() : dialog.dismiss());
    });
}

module.exports = { test, expect, openAnime, readEpisodes, handleDialog };
