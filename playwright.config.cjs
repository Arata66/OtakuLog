const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
    testDir: './src/test/browser',
    testMatch: '*.spec.cjs',
    timeout: 120000,
    expect: { timeout: 10000 },
    workers: 1,
    retries: 0,
    forbidOnly: Boolean(process.env.CI),
    reporter: [['list'], ['html', { open: 'never' }]],
    use: {
        browserName: 'chromium',
        headless: true,
        serviceWorkers: 'block',
        timezoneId: 'Asia/Shanghai',
        viewport: { width: 1440, height: 1000 },
        actionTimeout: 10000,
        navigationTimeout: 15000,
        screenshot: 'only-on-failure',
        trace: 'retain-on-failure'
    }
});
