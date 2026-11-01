// Cloudflare Worker version of the CKTV gateway (same routes as server.js):
// /api/*, /site/*, /img, /stream, /stream.m3u8. Deploy it, then put its URL in the app's Settings.
const DEFAULT_BASE = 'https://tv.chuankangkk.top';
const DEFAULT_PASS = 'cknb666';
const UA = 'CKTV-Android/1.0';
const BROWSER_UA = 'Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36';

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'X-CKTV-Base, X-CKTV-Pass, Range, Accept',
  'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges',
};

function json(status, body) {
  return new Response(JSON.stringify(body), {
    status, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function normalizeBase(value) {
  let base = String(value || DEFAULT_BASE).trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(base)) base = 'https://' + base;
  return base;
}

function isPrivateHost(host) {
  const h = host.toLowerCase();
  return h === 'localhost' || h.endsWith('.local') || h.endsWith('.internal')
    || /^(0|10|127)\./.test(h) || /^169\.254\./.test(h) || /^192\.168\./.test(h)
    || /^172\.(1[6-9]|2\d|3[01])\./.test(h) || /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(h)
    || h === '[::1]' || h.startsWith('[fc') || h.startsWith('[fd') || h.startsWith('[fe80');
}

function checkUrl(raw) {
  const url = new URL(raw);
  if (!/^https?:$/.test(url.protocol) || isPrivateHost(url.hostname)) throw new Error('Target not allowed');
  return url;
}

// Cookies are kept per request (a Worker has no persistent memory between requests).
class Session {
  constructor(base) { this.base = base; this.jar = new Map(); }
  async fetch(raw, init = {}) {
    let current = raw;
    for (let hop = 0; hop < 6; hop++) {
      const url = checkUrl(current);
      const same = url.origin === new URL(this.base).origin;
      const headers = { ...init.headers };
      if (same && this.jar.size) headers.Cookie = [...this.jar].map(([k, v]) => `${k}=${v}`).join('; ');
      const response = await fetch(url, { method: init.method || 'GET', headers, body: init.body, redirect: 'manual' });
      if (same) {
        for (const entry of response.headers.getSetCookie()) {
          const [pair] = entry.split(';');
          const i = pair.indexOf('=');
          if (i > 0) this.jar.set(pair.slice(0, i).trim(), pair.slice(i + 1).trim());
        }
      }
      const location = response.headers.get('location');
      if (response.status >= 300 && response.status < 400 && location) { current = new URL(location, url).toString(); continue; }
      response.finalUrl = url.toString();
      return response;
    }
    throw new Error('Too many redirects');
  }
  async gate(password) {
    const status = await this.fetch(this.base + '/api/gate', { headers: { Accept: 'application/json', 'User-Agent': UA } });
    let gate = {};
    try { gate = JSON.parse(await status.text()); } catch (e) { throw new Error('CK TV returned an invalid response.'); }
    if (gate.foreign && !gate.open) {
      const auth = await this.fetch(this.base + '/api/gate', {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8', 'User-Agent': UA },
        body: JSON.stringify({ code: password }),
      });
      let result = {};
      try { result = JSON.parse(await auth.text()); } catch (e) { /* rejected below */ }
      if (!auth.ok || !result.open) {
        const error = new Error('CK TV rejected the access password. Check it in Settings.');
        error.status = 401;
        throw error;
      }
    }
  }
}

async function relay(request, url, upstreamPath) {
  const base = normalizeBase(request.headers.get('x-cktv-base') || url.searchParams.get('_base'));
  const password = request.headers.get('x-cktv-pass') || DEFAULT_PASS;
  const params = new URLSearchParams(url.search);
  params.delete('_base');
  const query = params.toString();
  const session = new Session(base);
  checkUrl(base);
  await session.gate(password);
  const upstream = await session.fetch(base + upstreamPath + (query ? '?' + query : ''), {
    headers: { Accept: request.headers.get('accept') || 'application/json', 'User-Agent': UA },
  });
  const headers = { ...CORS, 'Cache-Control': 'no-store' };
  const type = upstream.headers.get('content-type');
  if (type) headers['Content-Type'] = type;
  if (type && type.startsWith('image/')) headers['Cache-Control'] = 'public, max-age=86400';
  return new Response(upstream.body, { status: upstream.status, headers });
}

function proxiedUrl(target, base, origin) {
  const route = /\.m3u8(\?|$)/i.test(target) ? '/stream.m3u8' : '/stream';
  return `${origin}${route}?url=${encodeURIComponent(target)}&_base=${encodeURIComponent(base)}`;
}

function rewritePlaylist(text, playlistUrl, base, origin) {
  const resolve = (value) => proxiedUrl(new URL(value, playlistUrl).toString(), base, origin);
  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (trimmed.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (m, v) => `URI="${resolve(v)}"`);
    return resolve(trimmed);
  }).join('\n');
}

async function stream(request, url) {
  const target = url.searchParams.get('url');
  if (!target) return json(400, { error: 'Missing url' });
  const base = normalizeBase(url.searchParams.get('_base') || request.headers.get('x-cktv-base'));
  const headers = { 'User-Agent': BROWSER_UA, Referer: base + '/', Origin: base, Accept: '*/*' };
  const range = request.headers.get('range');
  if (range) headers.Range = range;
  const session = new Session(base);
  // AI黃果 playlists are served from the gated CK TV origin itself and need the gate cookie.
  if (new URL(target).origin === new URL(base).origin) {
    await session.gate(request.headers.get('x-cktv-pass') || DEFAULT_PASS);
  }
  const upstream = await session.fetch(target, { headers });
  const type = upstream.headers.get('content-type') || '';
  if (/mpegurl/i.test(type) || /\.m3u8(\?|$)/i.test(target)) {
    const text = await upstream.text();
    if (upstream.ok && text.trimStart().startsWith('#EXTM3U')) {
      return new Response(rewritePlaylist(text, upstream.finalUrl, base, url.origin), {
        headers: { ...CORS, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' },
      });
    }
    return new Response(text, { status: upstream.status, headers: { ...CORS, 'Content-Type': type || 'text/plain' } });
  }
  const out = { ...CORS, 'Cache-Control': 'no-store' };
  for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
    const value = upstream.headers.get(name);
    if (value) out[name] = value;
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

async function image(url) {
  const raw = url.searchParams.get('url') || '';
  const target = raw.startsWith('//') ? 'https:' + raw : raw;
  const host = new URL(target).hostname;
  const upstream = await new Session(DEFAULT_BASE).fetch(target, {
    headers: {
      'User-Agent': BROWSER_UA, Accept: 'image/*,*/*;q=0.8',
      ...(/doubanio\.com$/.test(host) ? { Referer: 'https://movie.douban.com/' } : {}),
    },
  });
  const type = upstream.headers.get('content-type') || '';
  if (upstream.status !== 200 || !type.startsWith('image/')) return json(502, { error: 'Image unavailable' });
  return new Response(upstream.body, { headers: { ...CORS, 'Content-Type': type, 'Cache-Control': 'public, max-age=86400' } });
}

export default {
  async fetch(request) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: { ...CORS, 'Access-Control-Max-Age': '86400' } });
    if (request.method !== 'GET' && request.method !== 'HEAD') return json(405, { error: 'method not allowed' });
    const url = new URL(request.url);
    try {
      if (url.pathname.startsWith('/api/')) return await relay(request, url, url.pathname);
      if (url.pathname.startsWith('/site/')) return await relay(request, url, url.pathname.slice(5));
      if (url.pathname === '/img') return await image(url);
      if (url.pathname === '/stream' || url.pathname === '/stream.m3u8') return await stream(request, url);
      return json(200, { ok: true, service: 'cktv-gateway' });
    } catch (error) {
      return json(error.status || 502, { error: error.message || 'Gateway error' });
    }
  },
};
