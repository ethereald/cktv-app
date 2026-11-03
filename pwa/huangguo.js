'use strict';
// AI黃果 scraper shared by server.js (Node) and worker/worker.js (Cloudflare).
// The site keeps moving between domains, so the current routes are read on demand from a
// "latest address" portal page and the last working origin is reused until it stops answering.
const PORTALS = ['https://huangguo1.com/'];
const FALLBACK_ORIGINS = ['https://huangguoai.com'];
const ROUTE_TTL_MS = 10 * 60 * 1000;
const DEFAULT_CATEGORY = 'ai-duanju';
const SOURCE = 'huangguo';
const SOURCE_NAME = '黃果';
const MEDIA_PATH = '/api/huangguo/media/playlist.m3u8';
const IMAGE_PATH = '/api/huangguo/image';
const KEY_PATH = '/static/web/js/plugins/crypto-image-protocol.js';

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

function decode(value) {
  return String(value || '').replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (match, name) => {
    if (name[0] === '#') {
      const code = name[1].toLowerCase() === 'x' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : match;
    }
    return ENTITIES[name.toLowerCase()] || match;
  });
}

const stripTags = (html) => decode(String(html || '').replace(/<[^>]+>/g, '')).replace(/\s+/g, ' ').trim();

function absolute(value, origin) {
  const v = decode(value).trim();
  if (!v) return '';
  if (v.startsWith('//')) return 'https:' + v;
  return /^https?:/i.test(v) ? v : origin + (v.startsWith('/') ? v : '/' + v);
}

// Cover images are AES-CBC encrypted by the site, so they are served through the gateway.
const imageUrl = (url) => (/^https?:/i.test(url) ? `${IMAGE_PATH}?url=${encodeURIComponent(url)}` : '');

function imageType(bytes) {
  if (bytes[0] === 0xff && bytes[1] === 0xd8) return 'image/jpeg';
  if (bytes[0] === 0x89 && bytes[1] === 0x50) return 'image/png';
  if (bytes[0] === 0x47 && bytes[1] === 0x49) return 'image/gif';
  if (bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[8] === 0x57) return 'image/webp';
  return '';
}

function afterHeader(html) {
  const i = html.indexOf('</header>');
  return i >= 0 ? html.slice(i + 9) : html;
}

// Cards after the header belong to the page itself (the header holds the "guess you like" popup);
// the first <template> starts the hidden hot/original/random tabs, which are skipped.
function parseCards(html, origin) {
  let body = afterHeader(html);
  const template = body.indexOf('<template');
  if (template >= 0) body = body.slice(0, template);
  const seen = new Set();
  const list = [];
  for (const part of body.split('<div class="hg-drama-card"').slice(1)) {
    const id = (/href="\/video\/(\d+)\//.exec(part) || [])[1];
    if (!id || seen.has(id)) continue;
    seen.add(id);
    const title = decode((/data-track-title="([^"]*)"/.exec(part) || [])[1])
      || stripTags((/<h3 class="hg-drama-card__title">([\s\S]*?)<\/h3>/.exec(part) || [])[1]).replace(/全集在线观看$/, '');
    const score = (/hg-drama-card__score">\s*([\d.]+)/.exec(part) || [])[1] || '';
    const remarks = decode((/data-ep-base="([^"]*)"/.exec(part) || [])[1]);
    const desc = stripTags((/<p class="hg-drama-card__desc">([\s\S]*?)<\/p>/.exec(part) || [])[1]);
    const poster = imageUrl(absolute((/data-src="([^"]+)"/.exec(part) || [])[1], origin));
    list.push({
      id, title, poster, year: '', rate: score, source: SOURCE, source_name: SOURCE_NAME,
      desc, remarks, episodes: [],
    });
  }
  return list;
}

function parsePageCount(html) {
  const declared = Number((/data-pages="(\d+)"/.exec(html) || [])[1]);
  if (declared > 0) return declared;
  const pages = [...html.matchAll(/class="hg-pager__page[^"]*"[^>]*>(\d+)</g)].map((m) => Number(m[1]));
  return pages.length ? Math.max(...pages) : 1;
}

function parseCategories(html) {
  const categories = [];
  const pattern = /<a class="hg-topbar-nav__item[^"]*"[^>]*data-nav-key="video_category_\d+"[^>]*data-nav-name="([^"]+)"[^>]*data-nav-route="([a-z0-9-]+)"/g;
  for (const match of html.matchAll(pattern)) {
    if (!categories.some((c) => c.id === match[2])) categories.push({ id: match[2], name: decode(match[1]) });
  }
  return categories;
}

function parseVideoData(html) {
  const match = /<script id="videoInitialData" type="application\/json">([\s\S]*?)<\/script>/.exec(html);
  if (!match) throw new Error('AI黃果 detail page layout changed');
  return JSON.parse(match[1]);
}

// A video page only embeds playable URLs for neighbouring episodes, so the full list
// comes from the episode selector (falling back to the tracking data).
function episodeNumbers(html, data) {
  const numbers = new Set([...html.matchAll(/data-ep-id="(\d+)"/g)].map((m) => Number(m[1])));
  if (!numbers.size) {
    for (const source of [data.trackingEpisodes, data.epPlaySrcs]) {
      Object.keys(source || {}).filter((key) => /^\d+$/.test(key)).forEach((key) => numbers.add(Number(key)));
    }
  }
  return [...numbers].filter((n) => n > 0).sort((a, b) => a - b);
}

function detailItem(data, id, html) {
  const numbers = episodeNumbers(html, data);
  return {
    id: String(id), title: String(data.title || ''), poster: imageUrl(String(data.posterSrc || data.coverSrc || '')),
    year: String(data.time || '').slice(0, 4), rate: '', source: SOURCE, source_name: SOURCE_NAME,
    desc: String(data.description || ''), remarks: numbers.length ? `更新至${numbers.length}集` : '',
    episodes: numbers.map((n) => `第${n}集$${MEDIA_PATH}?id=${id}&ep=${n}`),
  };
}

function createClient({ fetchText, fetchBytes, portals = PORTALS, fallbacks = FALLBACK_ORIGINS, now = Date.now }) {
  let routes = [];
  let routesAt = 0;
  let good = '';
  let knownCategories = [];

  async function discover() {
    const found = [];
    for (const portal of portals) {
      try {
        const html = await fetchText(portal);
        for (const m of html.matchAll(/class="route-card[^"]*"[^>]*data-url="(https?:\/\/[^"]+)"/g)) found.push(m[1].replace(/\/+$/, ''));
        if (found.length) break;
      } catch (error) { /* try the next portal */ }
    }
    if (found.length) { routes = [...new Set(found)]; routesAt = now(); }
    return routes;
  }

  async function origins(refresh) {
    if (refresh || !routes.length || now() - routesAt > ROUTE_TTL_MS) await discover();
    return [...new Set([good, ...routes, ...fallbacks].filter(Boolean))];
  }

  // Tries the last working origin, then every route; if all fail, re-reads the portal once.
  async function get(path, validate) {
    const tried = new Set();
    let failure = new Error('AI黃果 is unreachable');
    for (const refresh of [false, true]) {
      for (const origin of await origins(refresh)) {
        if (tried.has(origin)) continue;
        tried.add(origin);
        try {
          const html = await fetchText(origin + path);
          if (validate(html)) { good = origin; return { html, origin }; }
          failure = new Error('AI黃果 returned an unexpected page');
        } catch (error) { failure = error; }
      }
    }
    throw failure;
  }

  let imageKey = null;
  async function loadImageKey(refresh) {
    if (imageKey && !refresh) return imageKey;
    const { html } = await get(KEY_PATH, (h) => /decryptKey/.test(h));
    const key = (/"decryptKey"\s*:\s*"([^"]+)"/.exec(html) || [])[1];
    const iv = (/"decryptIV"\s*:\s*"([^"]+)"/.exec(html) || [])[1];
    if (!key || !iv) throw new Error('AI黃果 image key unavailable');
    const encoder = new TextEncoder();
    imageKey = { iv: encoder.encode(iv), key: await crypto.subtle.importKey('raw', encoder.encode(key), 'AES-CBC', false, ['decrypt']) };
    return imageKey;
  }

  async function decryptImage(bytes, refresh) {
    const plain = imageType(bytes);
    if (plain) return { bytes, type: plain };
    try {
      const { key, iv } = await loadImageKey(refresh);
      const out = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, key, bytes));
      const type = imageType(out);
      if (type) return { bytes: out, type };
    } catch (error) { /* retried below with a fresh key */ }
    if (!refresh) return decryptImage(bytes, true);
    throw new Error('Image decryption failed');
  }

  const categoryPath = (id, page) => `/${id}/` + (page > 1 ? `${page}/` : '');
  const hasCards = (html) => html.includes('hg-drama-card');

  return {
    async list(category, page) {
      const current = Math.max(1, Math.floor(Number(page)) || 1);
      let wanted = /^[a-z0-9-]+$/.test(category || '') ? category : '';
      if (wanted && knownCategories.length && !knownCategories.includes(wanted)) wanted = '';
      const load = (id) => get(categoryPath(id, current), hasCards);
      let result;
      try {
        result = await load(wanted || knownCategories[0] || DEFAULT_CATEGORY);
      } catch (error) {
        if (!wanted || wanted === DEFAULT_CATEGORY) throw error;
        result = await load(DEFAULT_CATEGORY);
      }
      const categories = parseCategories(result.html);
      if (categories.length) knownCategories = categories.map((c) => c.id);
      return { list: parseCards(result.html, result.origin), page: current, pagecount: parsePageCount(result.html), categories };
    },
    async search(query) {
      const { html, origin } = await get(`/search/?keyword=${encodeURIComponent(String(query || '').trim())}`, (h) => h.includes('hg-layout'));
      return { list: parseCards(html, origin) };
    },
    async detail(id) {
      if (!/^\d+$/.test(id || '')) throw new Error('Invalid id');
      const { html } = await get(`/video/${id}/`, (h) => h.includes('id="videoInitialData"'));
      return detailItem(parseVideoData(html), id, html);
    },
    async image(url) {
      if (!/^https?:\/\//i.test(url || '')) throw new Error('Invalid image url');
      return decryptImage(await fetchBytes(url));
    },
    async episodeSource(id, episode) {
      if (!/^\d+$/.test(id || '') || !/^\d+$/.test(episode || '')) throw new Error('Invalid id or episode');
      const number = Number(episode);
      const page = number > 1 ? `/video/${id}/ep-${number}/` : `/video/${id}/`;
      const { html, origin } = await get(page, (h) => h.includes('id="videoInitialData"'));
      const url = (parseVideoData(html).epPlaySrcs || {})[String(number)];
      if (!url) throw new Error('Episode not found');
      return { url, origin };
    },
  };
}

module.exports = { createClient, MEDIA_PATH, IMAGE_PATH, parseCards, parseCategories, parsePageCount, parseVideoData, detailItem };
