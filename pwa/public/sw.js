'use strict';
const VERSION = 'cktv-v6';
const SHELL = `${VERSION}-shell`;
const IMAGES = `${VERSION}-images`;
const DATA = `${VERSION}-data`;
const SHELL_FILES = [
  '/', '/index.html', '/styles.css', '/app.js', '/manifest.webmanifest', '/vendor/hls.min.js',
  '/icons/icon-192.png', '/icons/icon-512.png', '/icons/icon-maskable-512.png',
  '/icons/apple-touch-icon.png', '/icons/favicon-32.png',
];
const DATA_PATTERN = /^\/api\/(catalog|douban|search|detail|adult|huangguo)/;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL)
    .then((cache) => cache.addAll(SHELL_FILES.map((file) => new Request(file, { cache: 'reload' }))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((key) => !key.startsWith(VERSION)).map((key) => caches.delete(key))))
    .then(() => self.clients.claim()));
});

async function trim(name, max) {
  const cache = await caches.open(name);
  const keys = await cache.keys();
  for (let i = 0; i < keys.length - max; i++) await cache.delete(keys[i]);
}

async function networkFirst(request) {
  const cache = await caches.open(DATA);
  try {
    const response = await fetch(request);
    if (response.ok) {
      cache.put(request, response.clone());
      trim(DATA, 150);
    }
    return response;
  } catch (error) {
    const cached = await cache.match(request);
    if (cached) return cached;
    throw error;
  }
}

async function cacheFirstImage(request) {
  const cache = await caches.open(IMAGES);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) {
    cache.put(request, response.clone());
    trim(IMAGES, 400);
  }
  return response;
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  const sameOrigin = url.origin === self.location.origin;
  if (url.pathname === '/stream' || url.pathname === '/stream.m3u8') return;
  if (request.mode === 'navigate') {
    if (!sameOrigin) return;
    // Cache-first so the installed app opens with no server running; refresh in the background.
    event.respondWith(caches.match('/index.html').then((cached) => {
      const refresh = fetch(request).then((response) => {
        if (response.ok) caches.open(SHELL).then((cache) => cache.put('/index.html', response.clone()));
        return response;
      });
      if (cached) { refresh.catch(() => {}); return cached; }
      return refresh;
    }));
    return;
  }
  if (url.pathname === '/img' || url.pathname.startsWith('/api/image-proxy') || url.pathname.startsWith('/site/')) {
    event.respondWith(cacheFirstImage(request));
    return;
  }
  if (DATA_PATTERN.test(url.pathname)) {
    event.respondWith(networkFirst(request));
    return;
  }
  if (!sameOrigin || url.pathname.startsWith('/api/')) return;
  event.respondWith(caches.match(request).then((cached) => {
    const refresh = fetch(request).then((response) => {
      if (response.ok) caches.open(SHELL).then((cache) => cache.put(request, response.clone()));
      return response;
    });
    if (cached) { refresh.catch(() => {}); return cached; }
    return refresh;
  }));
});
