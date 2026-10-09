function createHttpSession(baseUrl) {
    const origin = new URL(baseUrl).origin;
    const cookies = new Map();
    let csrf;
    async function request(path, options = {}) {
        const url = new URL(path, origin);
        if (!path.startsWith('/') || path.startsWith('//') || url.origin !== origin) throw new Error('会话请求只能使用本站相对路径');
        const headers = new Headers(options.headers);
        if (cookies.size) headers.set('Cookie', [...cookies].map(([name, value]) => name + '=' + value).join('; '));
        if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) headers.set('X-CSRF-TOKEN', csrf);
        const response = await fetch(url, { ...options, headers, redirect: 'manual', signal: options.signal || AbortSignal.timeout(5000) });
        for (const cookie of response.headers.getSetCookie()) {
            const pair = cookie.split(';')[0], split = pair.indexOf('=');
            if (split > 0) cookies.set(pair.slice(0, split), pair.slice(split + 1));
        }
        return response;
    }
    async function login(username, password) {
        const loginPage = await request('/login');
        const token = /name="_csrf"[^>]*value="([^"]+)"/.exec(await loginPage.text())?.[1];
        if (!token || loginPage.status !== 200) throw new Error('演示登录页缺少 CSRF 令牌');
        const response = await request('/login', { method: 'POST', body: new URLSearchParams({ username, password, _csrf: token }) });
        if (response.status !== 302 || response.headers.get('location')?.includes('error')) throw new Error('演示临时凭据登录失败，停止导入');
        const page = await request('/');
        csrf = /<meta name="_csrf" content="([^"]+)"/.exec(await page.text())?.[1];
        if (page.status !== 200 || !csrf) throw new Error('演示主页缺少 CSRF 令牌');
    }
    return { request, login };
}

module.exports = { createHttpSession };
