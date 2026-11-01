'use strict';
// Zero-dependency static server + CK TV gateway for the CKTV PWA.
// Browsers cannot call tv.chuankangkk.top directly (no CORS headers), so /api/*,
// /site/* and /stream* are relayed here, handling the CK TV access gate like the Android app.
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const dns = require('dns').promises;
const net = require('net');
const { Readable } = require('stream');

const ROOT = path.join(__dirname, 'public');
const CERT_DIR = path.join(__dirname, 'certs');
const PORT = Number(process.env.PORT || 8080);
const HTTPS_PORT = Number(process.env.HTTPS_PORT || 8443);
const DEFAULT_BASE = process.env.CKTV_BASE || 'https://tv.chuankangkk.top';
const DEFAULT_PASS = process.env.CKTV_PASSWORD || 'cknb666';
const UA = 'CKTV-Android/1.0';
const BROWSER_UA = 'Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36';

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8', '.png': 'image/png',
  '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
};

const jars = new Map(); // base -> Map(cookie name -> value)
const gated = new Set(); // base|password pairs that passed the gate

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function isPrivateAddress(address) {
  if (net.isIPv4(address)) {
    const [a, b] = address.split('.').map(Number);
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254)
      || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
      || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  const v = address.toLowerCase();
  if (v.startsWith('::ffff:')) return isPrivateAddress(v.slice(7));
  return v === '::' || v === '::1' || v.startsWith('fc') || v.startsWith('fd')
    || v.startsWith('fe8') || v.startsWith('fe9') || v.startsWith('fea') || v.startsWith('feb');
}

// The gateway must never become a way to reach devices on the user's LAN.
async function assertPublicUrl(raw) {
  const url = new URL(raw);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw httpError(400, 'Unsupported URL scheme');
  const host = url.hostname.replace(/^\[|\]$/g, '');
  const addresses = net.isIP(host) ? [{ address: host }] : await dns.lookup(host, { all: true });
  if (!addresses.length || addresses.some((entry) => isPrivateAddress(entry.address))) {
    throw httpError(403, 'Private network targets are not allowed');
  }
  return url;
}

function normalizeBase(value) {
  let base = String(value || DEFAULT_BASE).trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(base)) base = 'https://' + base;
  return base;
}

function cookieHeader(base) {
  const jar = jars.get(base);
  return jar ? [...jar].map(([k, v]) => `${k}=${v}`).join('; ') : '';
}

function storeCookies(base, response) {
  const list = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  if (!list.length) return;
  const jar = jars.get(base) || new Map();
  for (const entry of list) {
    const [pair] = entry.split(';');
    const index = pair.indexOf('=');
    if (index > 0) jar.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
  }
  jars.set(base, jar);
}

async function safeFetch(rawUrl, options, base) {
  let current = rawUrl;
  for (let hop = 0; hop < 6; hop++) {
    const url = await assertPublicUrl(current);
    const sameSite = base && url.origin === new URL(base).origin;
    const headers = { ...options.headers };
    if (sameSite) {
      const cookie = cookieHeader(base);
      if (cookie) headers.Cookie = cookie;
    }
    const response = await fetch(url, {
      method: options.method || 'GET', headers, body: options.body,
      redirect: 'manual', signal: AbortSignal.timeout(options.timeout || 30000),
    });
    if (sameSite) storeCookies(base, response);
    const location = response.headers.get('location');
    if (response.status >= 300 && response.status < 400 && location) {
      current = new URL(location, url).toString();
      continue;
    }
    response.finalUrl = url.toString();
    return response;
  }
  throw httpError(502, 'Too many redirects');
}

async function ensureGate(base, password) {
  const key = base + '|' + password;
  if (gated.has(key)) return;
  const status = await safeFetch(base + '/api/gate', {
    headers: { Accept: 'application/json', 'User-Agent': UA }, timeout: 15000,
  }, base);
  let gate;
  try { gate = JSON.parse(await status.text()); } catch (error) { throw httpError(502, 'CK TV returned an invalid response.'); }
  if (gate.foreign && !gate.open) {
    const auth = await safeFetch(base + '/api/gate', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8', 'User-Agent': UA },
      body: JSON.stringify({ code: password }), timeout: 15000,
    }, base);
    let result = {};
    try { result = JSON.parse(await auth.text()); } catch (error) { /* rejected below */ }
    if (!auth.ok || !result.open) {
      throw httpError(auth.status === 429 ? 429 : 401, auth.status === 429
        ? 'Too many password attempts. Try again later.'
        : 'CK TV rejected the access password. Check it in Settings.');
    }
  } else if (status.status >= 500) {
    throw httpError(502, `CK TV access check failed (HTTP ${status.status}).`);
  }
  gated.add(key);
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function clientContext(req, url) {
  const base = normalizeBase(req.headers['x-cktv-base'] || url.searchParams.get('_base'));
  const header = req.headers['x-cktv-pass'];
  return { base, password: header ? String(header) : DEFAULT_PASS };
}

async function relay(req, res, url, upstreamPath) {
  const { base, password } = clientContext(req, url);
  const params = new URLSearchParams(url.search);
  params.delete('_base');
  const query = params.toString();
  const target = base + upstreamPath + (query ? '?' + query : '');
  const request = () => safeFetch(target, {
    headers: { Accept: req.headers.accept || 'application/json', 'User-Agent': UA },
  }, base);
  await assertPublicUrl(base);
  await ensureGate(base, password);
  let upstream = await request();
  if (upstream.status === 401 || upstream.status === 403) {
    const text = await upstream.clone().text();
    if (/"error"\s*:\s*"gate"/.test(text)) {
      gated.delete(base + '|' + password);
      jars.delete(base);
      await ensureGate(base, password);
      upstream = await request();
    }
  }
  const headers = { 'Cache-Control': 'no-store' };
  const type = upstream.headers.get('content-type');
  if (type) headers['Content-Type'] = type;
  if (type && type.startsWith('image/')) headers['Cache-Control'] = 'public, max-age=86400';
  res.writeHead(upstream.status, headers);
  if (!upstream.body) return res.end();
  Readable.fromWeb(upstream.body).on('error', () => res.destroy()).pipe(res);
}

function proxiedUrl(target, base) {
  const route = /\.m3u8(\?|$)/i.test(target) ? '/stream.m3u8' : '/stream';
  return `${route}?url=${encodeURIComponent(target)}&_base=${encodeURIComponent(base)}`;
}

function rewritePlaylist(text, playlistUrl, base) {
  const resolve = (value) => proxiedUrl(new URL(value, playlistUrl).toString(), base);
  return text.split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (trimmed.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (match, value) => `URI="${resolve(value)}"`);
    return resolve(trimmed);
  }).join('\n');
}

async function stream(req, res, url) {
  const target = url.searchParams.get('url');
  if (!target) throw httpError(400, 'Missing url');
  const { base } = clientContext(req, url);
  const headers = { 'User-Agent': BROWSER_UA, Referer: base + '/', Origin: base, Accept: '*/*' };
  if (req.headers.range) headers.Range = req.headers.range;
  const targetUrl = await assertPublicUrl(target);
  if (targetUrl.origin === new URL(base).origin) await ensureGate(base, clientContext(req, url).password);
  const upstream = await safeFetch(target, { headers, timeout: 30000 }, base);
  const type = upstream.headers.get('content-type') || '';
  const isPlaylist = /mpegurl/i.test(type) || /\.m3u8(\?|$)/i.test(target);
  if (isPlaylist) {
    const text = await upstream.text();
    if (upstream.ok && text.trimStart().startsWith('#EXTM3U')) {
      res.writeHead(200, { 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' });
      return res.end(rewritePlaylist(text, upstream.finalUrl, base));
    }
    res.writeHead(upstream.status, { 'Content-Type': type || 'text/plain', 'Cache-Control': 'no-store' });
    return res.end(text);
  }
  const out = { 'Cache-Control': 'no-store' };
  for (const name of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
    const value = upstream.headers.get(name);
    if (value) out[name] = value;
  }
  res.writeHead(upstream.status, out);
  if (!upstream.body) return res.end();
  const body = Readable.fromWeb(upstream.body);
  body.on('error', () => res.destroy());
  res.on('close', () => body.destroy());
  body.pipe(res);
}

// Some providers (e.g. 91麻豆) host posters on bare IPs with self-signed certificates, which the
// CK TV image proxy cannot fetch. Certificate checks are skipped for image responses only.
async function image(req, res, url) {
  const target = url.searchParams.get('url') || '';
  let current = target.startsWith('//') ? 'https:' + target : target;
  for (let hop = 0; hop < 4; hop++) {
    const parsed = await assertPublicUrl(current);
    const lib = parsed.protocol === 'https:' ? https : http;
    const upstream = await new Promise((resolve, reject) => {
      const request = lib.request(parsed, {
        method: 'GET', rejectUnauthorized: false, timeout: 15000,
        headers: { 'User-Agent': BROWSER_UA, Accept: 'image/*,*/*;q=0.8',
          ...(/doubanio\.com$/.test(parsed.hostname) ? { Referer: 'https://movie.douban.com/' } : {}) },
      }, resolve);
      request.on('timeout', () => request.destroy(new Error('timeout')));
      request.on('error', reject);
      request.end();
    });
    const location = upstream.headers.location;
    if (upstream.statusCode >= 300 && upstream.statusCode < 400 && location) {
      upstream.resume();
      current = new URL(location, parsed).toString();
      continue;
    }
    const type = String(upstream.headers['content-type'] || '');
    if (upstream.statusCode !== 200 || !type.startsWith('image/')) {
      upstream.resume();
      throw httpError(502, 'Image unavailable');
    }
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'public, max-age=86400' });
    upstream.on('error', () => res.destroy());
    return upstream.pipe(res);
  }
  throw httpError(502, 'Too many redirects');
}

function serveStatic(req, res, url) {
  let pathname;
  try { pathname = decodeURIComponent(url.pathname); } catch (error) { return sendJson(res, 400, { error: 'bad path' }); }
  if (pathname === '/cktv-local.crt') {
    const cert = path.join(CERT_DIR, 'cert.pem');
    if (!fs.existsSync(cert)) return sendJson(res, 404, { error: 'no certificate' });
    res.writeHead(200, { 'Content-Type': 'application/x-x509-ca-cert' });
    return fs.createReadStream(cert).pipe(res);
  }
  if (pathname.endsWith('/')) pathname += 'index.html';
  let file = path.normalize(path.join(ROOT, pathname));
  if (!file.startsWith(ROOT + path.sep)) return sendJson(res, 403, { error: 'forbidden' });
  fs.stat(file, (error, stat) => {
    if (error || !stat.isFile()) {
      if (path.extname(pathname)) return sendJson(res, 404, { error: 'not found' });
      file = path.join(ROOT, 'index.html');
    }
    const noCache = /sw\.js$|\.webmanifest$|\.html$|app\.js$|styles\.css$/.test(file);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': noCache ? 'no-cache' : 'public, max-age=3600',
    });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).pipe(res);
  });
}

async function handler(req, res) {
  try {
    const url = new URL(req.url, 'http://localhost');
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'X-CKTV-Base, X-CKTV-Pass, Range, Accept');
    res.setHeader('Access-Control-Expose-Headers', 'Content-Range, Content-Length, Accept-Ranges');
    if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Max-Age': '86400' }); return res.end(); }
    if (req.method !== 'GET' && req.method !== 'HEAD') return sendJson(res, 405, { error: 'method not allowed' });
    if (url.pathname.startsWith('/api/')) return await relay(req, res, url, url.pathname);
    if (url.pathname.startsWith('/site/')) return await relay(req, res, url, url.pathname.slice(5));
    if (url.pathname === '/img') return await image(req, res, url);
    if (url.pathname === '/stream' || url.pathname === '/stream.m3u8') return await stream(req, res, url);
    return serveStatic(req, res, url);
  } catch (error) {
    if (res.headersSent) return res.destroy();
    const status = error.status || 502;
    const cause = error.cause && error.cause.code ? ` (${error.cause.code})` : '';
    sendJson(res, status, { error: (error.message || 'Gateway error') + (status === 502 ? cause : '') });
  }
}

function lanAddresses() {
  return Object.values(os.networkInterfaces()).flat()
    .filter((entry) => entry && entry.family === 'IPv4' && !entry.internal).map((entry) => entry.address);
}

http.createServer(handler).listen(PORT, '0.0.0.0', () => {
  console.log('CKTV PWA server');
  console.log(`  Local  http://localhost:${PORT}/`);
  for (const ip of lanAddresses()) console.log(`  LAN    http://${ip}:${PORT}/`);
});

const certFile = path.join(CERT_DIR, 'cert.pem');
const keyFile = path.join(CERT_DIR, 'key.pem');
if (fs.existsSync(certFile) && fs.existsSync(keyFile)) {
  https.createServer({ cert: fs.readFileSync(certFile), key: fs.readFileSync(keyFile) }, handler)
    .listen(HTTPS_PORT, '0.0.0.0', () => {
      for (const ip of lanAddresses()) console.log(`  HTTPS  https://${ip}:${HTTPS_PORT}/  (use this on iPhone)`);
    });
} else {
  console.log('  No HTTPS certificate yet. Run "npm run cert" so the iPhone can install the offline app.');
}
