const CACHE_NAME = 'otakulog-v5';
const STATIC_ASSETS = [
    '/css/anime.css',
    '/js/anime-app.js',
    '/js/i18n.js',
    '/manifest.json'
];

// 预缓存静态资源（不缓存 HTML 页面）
self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => cache.addAll(STATIC_ASSETS))
    );
    self.skipWaiting();
});

// 清理旧版本缓存
self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k.startsWith('otakulog-') && k !== CACHE_NAME)
                .map(k => caches.delete(k)))
        )
    );
    self.clients.claim();
});

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);

    // 私人数据仅走网络，避免退出后仍能读取离线缓存。
    if (url.origin === self.location.origin && url.pathname.startsWith('/api/')) {
        event.respondWith(
            fetch(event.request, { cache: 'no-store' }).catch(() =>
                new Response(JSON.stringify({ code: 503, message: '网络离线，请联网后重试' }), {
                    status: 503,
                    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }
                })
            )
        );
        return;
    }

    // HTML 页面：network-first（不缓存，确保模板更新立即生效）
    if (event.request.mode === 'navigate' || url.pathname === '/') {
        event.respondWith(
            fetch(event.request).catch(() => caches.match(event.request))
        );
        return;
    }

    // 静态资源：stale-while-revalidate
    event.respondWith(
        caches.match(event.request).then(cached => {
            const fetchPromise = fetch(event.request).then(response => {
                if (response.ok) {
                    const cloned = response.clone();
                    caches.open(CACHE_NAME).then(cache => cache.put(event.request, cloned));
                }
                return response;
            }).catch(() => cached);

            return cached || fetchPromise;
        })
    );
});
