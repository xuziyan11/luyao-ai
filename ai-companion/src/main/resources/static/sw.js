/* 路瑶 PWA Service Worker —— 网络优先，仅缓存静态资源 */
const CACHE = 'luyao-v12';
// 只缓存静态文件，不缓存会 302 重定向的路径（如 '/'）
const SHELL = [
  '/login.html',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-180.png'
];

// 动态接口 / 页面路由 / 重定向路径 一律不走缓存
function bypass(pathname) {
  // API 和 WebSocket
  if (pathname.startsWith('/api/')) return true;
  if (pathname.startsWith('/ws')) return true;
  if (pathname.startsWith('/tts')) return true;
  if (pathname.startsWith('/avatar')) return true;
  if (pathname.startsWith('/ilink')) return true;
  if (pathname.startsWith('/upload')) return true;
  // 会触发登录态跳转的页面路由
  if (pathname === '/' || pathname === '/login') return true;
  if (pathname === '/voice-call.html') return true;
  return false;
}

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => c.addAll(SHELL))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting()) // 注册失败也不要阻塞激活
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  let url;
  try { url = new URL(e.request.url); } catch { return; }
  if (url.origin !== self.location.origin) return;
  if (bypass(url.pathname)) return;

  e.respondWith(
    fetch(e.request, { cache: 'no-cache' })
      .then((resp) => {
        // 只缓存 200 OK，跳过 3xx 重定向、4xx 错误
        if (resp && resp.status === 200 && resp.type === 'basic') {
          const copy = resp.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        }
        return resp;
      })
      .catch(() => caches.match(e.request)) // 离线时只返回缓存，不再 fallback 到 '/'
  );
});
