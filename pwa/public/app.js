'use strict';
/* CKTV PWA - port of the Android TV client (MainActivity, DetailActivity, PlayerActivity,
   LibraryStore, ApiClient). Library data lives in localStorage with the same shapes as the app. */
(() => {
const DEFAULT_BASE = 'https://tv.chuankangkk.top';
const DEFAULT_PASS = 'cknb666';
const HISTORY_LIMIT = 60;
const PROGRESS_SAVE_MS = 5000;
const STALL_TIMEOUT_MS = 30000;

/* ---------- helpers ---------- */
const str = (v) => (v === undefined || v === null ? '' : String(v));
const $app = document.getElementById('app');

function h(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(child));
  }
  return node;
}

let toastTimer;
function toast(message) {
  const node = document.getElementById('toast');
  node.textContent = message;
  node.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('show'), 2200);
}

const TRAD = [['电影', '電影'], ['剧集', '劇集'], ['电视剧', '電視劇'], ['动漫', '動漫'], ['综艺', '綜藝'],
  ['纪录', '紀錄'], ['番组', '番組'], ['哔哩', '嗶哩'], ['黄果', '黃果'], ['新闻', '新聞'], ['央视', '央視'],
  ['卫视', '衛視'], ['国产', '國產'], ['国内', '國內'], ['国外', '國外'], ['欧美', '歐美'], ['韩国', '韓國'],
  ['华语', '華語'], ['喜剧', '喜劇'], ['动作', '動作'], ['动画', '動畫'], ['悬疑', '懸疑'], ['爱情', '愛情'],
  ['剧情', '劇情'], ['惊悚', '驚悚'], ['战争', '戰爭'], ['历史', '歷史'], ['综合', '綜合'], ['财经', '財經'],
  ['体育', '體育'], ['赛事', '賽事'], ['中文国际', '中文國際'], ['国防军事', '國防軍事'], ['社会与法', '社會與法'],
  ['音乐', '音樂'], ['奥林匹克', '奧林匹克'], ['农业农村', '農業農村'], ['英语', '英語'], ['西语', '西語'],
  ['法语', '法語'], ['阿语', '阿語'], ['俄语', '俄語'], ['哈尔滨', '哈爾濱'], ['东莞', '東莞'], ['兰州', '蘭州'],
  ['江苏', '江蘇'], ['广东', '廣東'], ['东方', '東方'], ['辽宁', '遼寧'], ['黑龙江', '黑龍江'], ['云南', '雲南'],
  ['重庆', '重慶'], ['内蒙古', '內蒙古'], ['宁夏', '寧夏'], ['分类', '分類'], ['筛选', '篩選'], ['源站', '來源']];
function toTraditional(value) {
  let out = str(value);
  for (const [from, to] of TRAD) out = out.split(from).join(to);
  return out;
}

const titleKey = (title) => str(title).toLowerCase()
  .replace(/[\s!-/:-@[-`{-~，。！？：；、（）【】《》「」『』·…]/g, '');

/* ---------- media items ---------- */
function norm(json) {
  const j = json || {};
  return {
    id: str(j.id), title: str(j.title), poster: str(j.poster), year: str(j.year), rate: str(j.rate),
    source: str(j.source), source_name: str(j.source_name),
    desc: str(j.desc !== undefined ? j.desc : j.description), remarks: str(j.remarks),
    episodes: (Array.isArray(j.episodes) ? j.episodes : []).map(str).filter(Boolean),
  };
}
const identity = (item) => (item.source ? `${item.source}|${item.id}` : `${item.id}|${item.title}`);
const sourceLabel = (item) => item.source_name || item.source;
function parseMedia(list) {
  return (Array.isArray(list) ? list : []).filter((v) => v && typeof v === 'object').map(norm);
}
function episodeUrl(episode) {
  let value = str(episode).trim();
  if (value.includes('$')) value = value.split('$').slice(1).join('$').trim();
  return value;
}

/* ---------- settings + API ---------- */
const settings = {
  get base() {
    return (localStorage.getItem('cktv_base_url') || DEFAULT_BASE).trim().replace(/\/+$/, '');
  },
  get password() { return localStorage.getItem('cktv_gate_password') || DEFAULT_PASS; },
  save(base, password) {
    let normalized = str(base).trim().replace(/\/+$/, '') || DEFAULT_BASE;
    if (!/^https?:\/\//i.test(normalized)) normalized = 'https://' + normalized;
    localStorage.setItem('cktv_base_url', normalized);
    localStorage.setItem('cktv_gate_password', str(password).trim() || DEFAULT_PASS);
  },
};

const enc = encodeURIComponent;
async function apiGet(path) {
  let response;
  try {
    response = await fetch(path, { headers: { 'X-CKTV-Base': settings.base, 'X-CKTV-Pass': settings.password } });
  } catch (error) {
    throw new Error(navigator.onLine === false ? '目前離線，無法連線 CKTV。' : '無法連線到 CKTV 本機伺服器。');
  }
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch (error) { /* non-JSON body */ }
  if (!response.ok) {
    const message = json && json.error ? String(json.error) : '';
    if (message === 'region') throw new Error('此專區受網站地區限制，預設密碼無法略過地區限制。');
    if (message === 'gate') throw new Error('此專區需要存取密碼，請在設定中檢查密碼。');
    throw new Error(message ? `${message} (HTTP ${response.status})` : `CK TV request failed (HTTP ${response.status}).`);
  }
  if (!json || typeof json !== 'object') throw new Error('CK TV returned an invalid response.');
  return json;
}

const api = {
  catalog(category, page, douban, selectedCategory, type) {
    if (!douban) {
      const engine = category === 'bangumi' ? 'bangumi' : 'bilibili';
      const kind = category === 'bangumi' ? 'anime' : 'bangumi';
      return apiGet(`/api/catalog?engine=${enc(engine)}&kind=${enc(kind)}&page=${page}`);
    }
    const kind = category === 'movie' ? 'movie' : 'tv';
    const start = (page - 1) * 20;
    return apiGet(`/api/douban/categories?kind=${kind}&category=${enc(selectedCategory)}&type=${enc(type)}&limit=20&start=${start}`)
      .then((json) => {
        const list = Array.isArray(json.list) ? json.list : [];
        return { list, page, hasMore: list.length >= 20 };
      });
  },
  search: (query) => apiGet(`/api/search?q=${enc(query)}&slim=0`).then((j) => (Array.isArray(j.results) ? j.results : [])),
  detail: (source, id) => (source === 'huangguo'
    ? apiGet(`/api/huangguo?action=detail&id=${enc(id)}`)
    : apiGet(`/api/detail?source=${enc(source)}&id=${enc(id)}`)),
  adult(source, type, page, query) {
    let path = `/api/adult?action=${query ? 'search' : 'list'}&source=${enc(source)}&page=${page}`;
    if (type) path += `&t=${enc(type)}`;
    if (query) path += `&q=${enc(query)}`;
    return apiGet(path);
  },
  huangguo: (category, page) => apiGet(`/api/huangguo?action=list&t=${enc(category)}&page=${page}`),
  huangguoSearch: (query) => apiGet(`/api/huangguo?action=search&q=${enc(query)}`),
  live: (id) => apiGet(`/api/live?id=${enc(id)}`),
};

function posterSources(poster) {
  const value = str(poster).trim();
  if (!value) return { primary: '', fallback: '' };
  const base = `_base=${enc(settings.base)}`;
  const join = (u) => u + (u.includes('?') ? '&' : '?') + base;
  if (value.startsWith('/')) return { primary: join('/site' + value), fallback: settings.base + value };
  const absolute = value.startsWith('//') ? 'https:' + value : value;
  if (!/^https?:/i.test(absolute)) return { primary: join(`/api/image-proxy?url=${enc(absolute)}`), fallback: '' };
  // Direct gateway fetch first (handles IP-hosted/self-signed posters), then CK TV's image proxy.
  return { primary: `/img?url=${enc(absolute)}`, fallback: join(`/api/image-proxy?url=${enc(absolute)}`) };
}
function setPoster(img, poster) {
  const { primary, fallback } = posterSources(poster);
  img.referrerPolicy = 'no-referrer';
  img.decoding = 'async';
  img.alt = '';
  img.onerror = () => {
    img.onerror = null;
    if (fallback) img.src = fallback;
  };
  if (primary) img.src = primary; else img.removeAttribute('src');
}

/* ---------- library (LibraryStore) ---------- */
const lib = {
  read(key, fallback) {
    try { const v = JSON.parse(localStorage.getItem('cktv_' + key)); return v === null ? fallback : v; } catch (e) { return fallback; }
  },
  write(key, value) {
    try { localStorage.setItem('cktv_' + key, JSON.stringify(value)); } catch (e) { toast('儲存空間不足，無法儲存資料。'); }
  },
  list: (key) => lib.read(key, []).filter((v) => v && typeof v === 'object').map(norm),
  favorites() {
    const saved = lib.list('favorites');
    let changed = false;
    for (const item of saved) {
      if (item.id || item.source || item.source_name || item.episodes.length || item.remarks) {
        Object.assign(item, { id: '', source: '', source_name: '', episodes: [], remarks: '' });
        changed = true;
      }
    }
    if (changed) lib.write('favorites', saved);
    return saved;
  },
  history: () => lib.list('history'),
  isFavorite: (item) => lib.favorites().some((v) => titleKey(v.title) === titleKey(item.title)),
  toggleFavorite(item) {
    const saved = lib.favorites();
    const key = titleKey(item.title);
    const next = saved.filter((v) => titleKey(v.title) !== key);
    const removed = next.length !== saved.length;
    if (!removed) next.unshift({ ...norm(item), id: '', source: '', source_name: '', episodes: [], remarks: '' });
    lib.write('favorites', next);
    return !removed;
  },
  addHistory(item) {
    const saved = lib.history().filter((v) => identity(v) !== identity(item));
    saved.unshift(norm(item));
    lib.write('history', saved.slice(0, HISTORY_LIMIT));
  },
  isFailed: (key, source) => (lib.read('failed_sources', {})[key] || []).includes(source),
  preferredSource(title) {
    const key = titleKey(title);
    if (!key) return null;
    const saved = lib.read('working_sources', {})[key];
    if (saved) {
      const item = norm(saved);
      if (item.source && item.episodes.length && !lib.isFailed(key, item.source)) return item;
    }
    return lib.history().find((v) => titleKey(v.title) === key && v.source && v.episodes.length
      && !lib.isFailed(key, v.source)) || null;
  },
  rememberWorkingSource(item) {
    const key = titleKey(item && item.title);
    if (!item || !item.source || !item.episodes.length || !key) return;
    const working = lib.read('working_sources', {});
    delete working[key];
    working[key] = norm(item);
    const keys = Object.keys(working);
    while (keys.length > HISTORY_LIMIT) delete working[keys.shift()];
    const failed = lib.read('failed_sources', {});
    if (failed[key]) {
      failed[key] = failed[key].filter((s) => s !== item.source);
      if (!failed[key].length) delete failed[key];
    }
    lib.write('working_sources', working);
    lib.write('failed_sources', failed);
    lib.addHistory(item);
  },
  markSourceFailed(title, item) {
    const key = titleKey(title);
    if (!item || !item.source || !key) return;
    const failed = lib.read('failed_sources', {});
    failed[key] = failed[key] || [];
    if (!failed[key].includes(item.source)) failed[key].push(item.source);
    const working = lib.read('working_sources', {});
    if (working[key] && working[key].source === item.source) delete working[key];
    lib.write('working_sources', working);
    lib.write('failed_sources', failed);
  },
  progress(title) {
    const saved = lib.read('playback_progress', {})[titleKey(title)];
    if (!saved) return null;
    const episodeIndex = Number.isInteger(saved.episode_index) ? saved.episode_index : -1;
    const positionMs = Number(saved.position_ms) || 0;
    if (episodeIndex < 0 || positionMs < 10000) return null;
    return { episodeIndex, positionMs, durationMs: Number(saved.duration_ms) || 0 };
  },
  saveProgress(title, episodeIndex, positionMs, durationMs) {
    const key = titleKey(title);
    if (!key || episodeIndex < 0 || positionMs < 10000) return;
    const all = lib.read('playback_progress', {});
    if (durationMs > 0 && positionMs >= durationMs - 15000) delete all[key];
    else all[key] = { episode_index: episodeIndex, position_ms: Math.round(positionMs), duration_ms: Math.round(durationMs) };
    lib.write('playback_progress', all);
  },
  clearProgress(title, episodeIndex) {
    const key = titleKey(title);
    const all = lib.read('playback_progress', {});
    if (all[key] && all[key].episode_index === episodeIndex) {
      delete all[key];
      lib.write('playback_progress', all);
    }
  },
};

/* ---------- view stack (maps browser/iOS back gestures to screens) ---------- */
const views = [];
function pushView(view) {
  views.push(view);
  history.pushState({ depth: views.length }, '');
}
function popViews(depth) {
  while (views.length > depth) {
    const view = views.pop();
    view.destroy();
  }
}
window.addEventListener('popstate', (event) => popViews((event.state && event.state.depth) || 0));
history.replaceState({ depth: 0 }, '');
const goBack = () => history.back();

/* ---------- home ---------- */
const S = {
  kind: 'movie', label: '電影', primary: '热门', sub: '全部', source: 'zy91md', liveGroup: 'news',
  page: 1, catalogMode: true, showingHistory: false, requestId: 0, hasMore: false, pageCount: 1,
  adultSources: [], adultCategories: [], huangguoCategories: [],
};
const KINDS = [['電影', 'movie', true], ['劇集', 'tv', true], ['動漫', 'anime', true], ['綜藝', 'variety', true],
  ['紀錄片', 'documentary', true], ['番組', 'bangumi', false], ['嗶哩', 'bilibili', false], ['直播', 'live', false],
  ['18+專區', 'adult', false], ['AI黃果', 'huangguo', false]];

const home = {};
function buildHome() {
  home.root = h('section', { id: 'home' });
  home.search = h('input', {
    type: 'search', placeholder: '搜尋電影、劇集', enterkeyhint: 'search', autocomplete: 'off',
    onkeydown: (e) => { if (e.key === 'Enter') performSearch(); },
  });
  const top = h('div', { class: 'top' },
    h('div', { class: 'logo' }, 'CKTV'), home.search,
    h('div', { class: 'actions' },
      h('button', { onclick: performSearch }, '搜尋'),
      h('button', { onclick: () => showLocal('我的收藏', lib.favorites()) }, '收藏'),
      h('button', { onclick: () => showLocal('播放記錄', lib.history()) }, '播放記錄'),
      h('button', { onclick: showSettings }, '設定')));
  home.kinds = h('div', { class: 'strip' }, KINDS.map(([label, kind, douban]) =>
    h('button', { onclick: () => beginCatalog(kind, label, douban) }, label)));
  home.filters = h('div', {});
  home.heading = h('h1', {}, '電影');
  home.status = h('div', { class: 'status' }, '正在連線 CKTV');
  home.grid = h('div', { id: 'grid' });
  home.prev = h('button', { onclick: () => loadCatalogPage(Math.max(1, S.page - 1)) }, '上一頁');
  home.next = h('button', { onclick: () => loadCatalogPage(S.page + 1) }, '下一頁');
  home.pageLabel = h('span', {}, '第 1 頁');
  home.pager = h('div', { class: 'pager', hidden: true }, home.prev, home.pageLabel, home.next);
  home.root.append(top, home.kinds, home.filters,
    h('div', { class: 'titlebar' }, home.heading, home.status), home.grid, home.pager);
  $app.append(home.root);
}

function defaultSub(kind) {
  return ({ movie: '全部', anime: 'tv_animation', documentary: 'tv_documentary', variety: 'show', huangguo: 'hot', adult: '' })[kind] || 'tv';
}

function beginCatalog(kind, label, douban) {
  S.showingHistory = false;
  S.kind = kind; S.label = label;
  S.primary = kind === 'movie' ? '热门' : kind === 'variety' ? 'show' : 'tv';
  S.sub = defaultSub(kind);
  S.source = 'zy91md'; S.liveGroup = 'news';
  S.adultSources = []; S.adultCategories = []; S.huangguoCategories = [];
  S.catalogMode = true; S.page = 1; S.pageCount = 1;
  renderFilters();
  loadCatalogPage(1, douban);
}

function isDouban() { return S.kind !== 'bangumi' && S.kind !== 'bilibili'; }

function updatePager(hasMore) {
  S.hasMore = hasMore;
  home.pager.hidden = !S.catalogMode;
  home.pageLabel.textContent = `第 ${S.page} 頁`;
  home.prev.disabled = S.page <= 1;
  home.next.disabled = !(S.catalogMode && hasMore);
}

function replaceItems(items) {
  home.grid.replaceChildren(...items.map(renderCard));
  home.grid.scrollTop = 0;
}

function renderCard(item) {
  const img = h('img', { class: 'poster', loading: 'lazy' });
  setPoster(img, item.poster);
  let details = item.year;
  if (item.rate) details += (details ? '  ·  ' : '') + '★ ' + item.rate;
  if (item.source_name) details += (details ? '  ·  ' : '') + toTraditional(item.source_name);
  return h('button', { class: 'card', onclick: () => openItem(item) },
    img, h('div', { class: 'name' }, toTraditional(item.title)), h('div', { class: 'sub' }, details));
}

async function loadCatalogPage(requested, douban = isDouban()) {
  if (S.kind === 'adult') return loadSpecial(requested, 'adult');
  if (S.kind === 'huangguo') return loadSpecial(requested, 'huangguo');
  if (S.kind === 'live') return loadLiveGroup(S.liveGroup);
  S.page = requested;
  const id = ++S.requestId;
  home.heading.textContent = S.label;
  home.status.textContent = '載入中…';
  updatePager(false);
  try {
    const response = await api.catalog(S.kind, requested, douban, S.primary, S.sub);
    if (id !== S.requestId) return;
    const items = parseMedia(response.list);
    replaceItems(items);
    S.page = Number(response.page) || requested;
    const more = response.hasMore !== undefined ? !!response.hasMore : items.length >= 20;
    home.status.textContent = `${items.length} 部影片`;
    updatePager(more);
  } catch (error) {
    if (id !== S.requestId) return;
    home.status.textContent = '載入失敗：' + error.message;
    replaceItems([]);
    updatePager(false);
  }
}

async function loadSpecial(requested, type) {
  S.page = requested;
  const id = ++S.requestId;
  const isAdult = type === 'adult';
  home.heading.textContent = S.label;
  home.status.textContent = isAdult ? '正在載入18+專區…' : '正在載入AI黃果…';
  updatePager(false);
  try {
    const response = isAdult ? await api.adult(S.source, S.sub, requested, '') : await api.huangguo(S.sub, requested);
    if (id !== S.requestId) return;
    if (isAdult) {
      if (Array.isArray(response.sources)) S.adultSources = response.sources;
      if (Array.isArray(response.categories)) S.adultCategories = response.categories;
    } else if (Array.isArray(response.categories)) S.huangguoCategories = response.categories;
    const items = parseMedia(response.list);
    replaceItems(items);
    S.page = Number(response.page) || requested;
    S.pageCount = Math.max(1, Number(response.pagecount) || requested);
    renderFilters();
    home.status.textContent = `${items.length} 部影片`;
    updatePager(S.page < S.pageCount);
    home.pageLabel.textContent = `第 ${S.page} 頁 / ${S.pageCount}`;
  } catch (error) {
    if (id !== S.requestId) return;
    home.status.textContent = (isAdult ? '18+專區' : 'AI黃果') + '載入失敗：' + error.message;
    replaceItems([]);
    updatePager(false);
  }
}

function loadLiveGroup(group) {
  S.liveGroup = group;
  S.requestId++;
  S.catalogMode = false;
  updatePager(false);
  home.heading.textContent = S.label;
  const channels = liveChannels(group);
  replaceItems(channels);
  home.status.textContent = `${channels.length} 个频道`;
  renderFilters();
}

function liveChannels(group) {
  const make = (id, title, source, sourceName, remarks) => norm({ id, title, source, source_name: sourceName, remarks });
  const live = (list) => list.map(([id, title]) => make(id, title, 'live', group, '直播頻道'));
  const groups = {
    news: [['cctvplus1', 'CCTV+ 1'], ['cctvplus2', 'CCTV+ 2'], ['cgtn', 'CGTN 英語'], ['cgtn-doc', 'CGTN 紀錄'],
      ['cgtn-es', 'CGTN 西語'], ['cgtn-fr', 'CGTN 法語'], ['cgtn-ar', 'CGTN 阿語'], ['cgtn-ru', 'CGTN 俄語']],
    cctv: [['cctv1', 'CCTV-1 綜合'], ['cctv2', 'CCTV-2 財經'], ['cctv3', 'CCTV-3 綜藝'], ['cctv4', 'CCTV-4 中文國際'],
      ['cctv5', 'CCTV-5 體育'], ['cctv5plus', 'CCTV-5+ 賽事'], ['cctv6', 'CCTV-6 電影'], ['cctv7', 'CCTV-7 國防軍事'],
      ['cctv8', 'CCTV-8 電視劇'], ['cctv10', 'CCTV-10 科教'], ['cctv11', 'CCTV-11 戲曲'], ['cctv12', 'CCTV-12 社會與法'],
      ['cctv13', 'CCTV-13 新聞'], ['cctv15', 'CCTV-15 音樂'], ['cctv16', 'CCTV-16 奧林匹克'], ['cctv17', 'CCTV-17 農業農村']],
    satellite: [['hunan', '湖南衛視'], ['zhejiang', '浙江衛視'], ['dongfang', '東方衛視'], ['jiangsu', '江蘇衛視'],
      ['guangdong', '廣東衛視'], ['shenzhen', '深圳衛視'], ['shandong', '山東衛視'], ['anhui', '安徽衛視'],
      ['tianjin', '天津衛視'], ['chongqing', '重慶衛視'], ['dongnan', '東南衛視'], ['jiangxi', '江西衛視'],
      ['liaoning', '遼寧衛視'], ['henan', '河南衛視'], ['hubei', '湖北衛視'], ['heilongjiang', '黑龍江衛視'],
      ['sichuan', '四川衛視'], ['hebei', '河北衛視'], ['jilin', '吉林衛視'], ['guangxi', '廣西衛視'],
      ['yunnan', '雲南衛視'], ['guizhou', '貴州衛視'], ['xinjiang', '新疆衛視'], ['gansu', '甘肅衛視'],
      ['neimenggu', '內蒙古衛視'], ['ningxia', '寧夏衛視'], ['qinghai', '青海衛視'], ['xizang', '西藏衛視']],
    local: [['hrb-news', '哈爾濱新聞'], ['hrb-movie', '哈爾濱影視'], ['hebei-event', '河北活動'], ['lanzhou-news', '蘭州新聞'],
      ['lanzhou-tour', '蘭州文旅'], ['dongguan', '東莞綜合'], ['baicheng', '白城綜合'], ['bread', '麵包台'],
      ['abn', 'ABN 中文'], ['angel', '天使中文']],
  };
  if (groups[group]) return live(groups[group]);
  if (group === 'platform') {
    return [['抖音直播', 'https://live.douyin.com/'], ['快手直播', 'https://live.kuaishou.com/'],
      ['嗶哩直播', 'https://live.bilibili.com/'], ['鬥魚', 'https://www.douyu.com/'], ['虎牙', 'https://www.huya.com/'],
      ['央視頻', 'https://www.yangshipin.cn/']].map(([title, url]) => make(url, title, 'live_link', '平台', '開啟直播平台'));
  }
  return [];
}

function filterRow(label, options, selected, onSelect) {
  const strip = h('div', { class: 'strip' }, options.map(([text, value]) =>
    h('button', { class: value === selected ? 'on' : '', onclick: () => onSelect(value) }, text)));
  home.filters.append(h('div', { class: 'filter' }, h('span', {}, toTraditional(label)), strip));
}

function changeSub(value) {
  S.sub = value; S.page = 1;
  renderFilters();
  loadCatalogPage(1, isDouban());
}

function renderFilters() {
  home.filters.replaceChildren();
  const k = S.kind;
  if (k === 'movie') {
    filterRow('分類', [['熱門電影', '熱門'], ['最新電影', '最新'], ['豆瓣高分', '豆瓣高分'], ['冷門佳片', '冷门佳片']], S.primary,
      (v) => { S.primary = v; S.page = 1; renderFilters(); loadCatalogPage(1, true); });
    filterRow('篩選', [['全部', '全部'], ['華語', '华语'], ['歐美', '欧美'], ['韓國', '韩国'], ['日本', '日本'], ['喜劇', '喜剧'],
      ['動作', '动作'], ['科幻', '科幻'], ['動畫', '动画'], ['懸疑', '悬疑'], ['愛情', '爱情'], ['紀錄片', '纪录片'],
      ['劇情', '剧情'], ['犯罪', '犯罪'], ['驚悚', '惊悚'], ['奇幻', '奇幻'], ['戰爭', '战争'], ['歷史', '历史']], S.sub, changeSub);
  } else if (k === 'tv' || k === 'anime' || k === 'documentary') {
    filterRow('類型', [['全部', 'tv'], ['國產', 'tv_domestic'], ['歐美', 'tv_american'], ['日本', 'tv_japanese'],
      ['韓國', 'tv_korean'], ['動漫', 'tv_animation'], ['紀錄片', 'tv_documentary']], S.sub, changeSub);
  } else if (k === 'variety') {
    filterRow('類型', [['全部', 'show'], ['國內', 'show_domestic'], ['國外', 'show_foreign']], S.sub, changeSub);
  } else if (k === 'adult') {
    const sources = S.adultSources.filter(Boolean).map((s) => [toTraditional(s.name || s.key), str(s.key)]);
    if (sources.length) {
      filterRow('源站', sources, S.source, (v) => { S.source = v; S.sub = ''; S.page = 1; loadSpecial(1, 'adult'); });
    }
    const cats = [['全部', '']].concat(S.adultCategories.filter(Boolean)
      .map((c) => [toTraditional(c.type_name || c.name), str(c.type_id !== undefined ? c.type_id : c.id)]));
    if (cats.length > 1) filterRow('分類', cats, S.sub, (v) => { S.sub = v; S.page = 1; renderFilters(); loadSpecial(1, 'adult'); });
  } else if (k === 'huangguo') {
    const cats = S.huangguoCategories.filter(Boolean).map((c) => [toTraditional(c.name), str(c.id)]);
    if (cats.length) filterRow('分類', cats, S.sub, (v) => { S.sub = v; S.page = 1; renderFilters(); loadSpecial(1, 'huangguo'); });
  } else if (k === 'live') {
    filterRow('頻道', [['新聞', 'news'], ['央視', 'cctv'], ['衛視', 'satellite'], ['地方', 'local'], ['平台', 'platform']],
      S.liveGroup, loadLiveGroup);
  }
}

async function performSearch() {
  const query = home.search.value.trim();
  if (!query) {
    home.status.textContent = '請輸入片名搜尋。';
    home.search.focus();
    return;
  }
  S.showingHistory = false;
  home.search.blur();
  const id = ++S.requestId;
  S.catalogMode = false;
  home.filters.replaceChildren();
  updatePager(false);
  home.heading.textContent = '搜尋：' + query;
  const scoped = S.kind === 'adult' || S.kind === 'huangguo';
  home.status.textContent = scoped ? `正在搜尋${S.label}…` : '正在搜尋全站片源…';
  try {
    let raw;
    if (S.kind === 'adult') raw = (await api.adult(S.source, '', 1, query)).list;
    else if (S.kind === 'huangguo') raw = (await api.huangguoSearch(query)).list;
    else raw = await api.search(query);
    const items = parseMedia(raw);
    if (id !== S.requestId) return;
    replaceItems(items);
    home.status.textContent = items.length ? `${items.length} 筆結果` : '找不到相關影片。';
  } catch (error) {
    if (id !== S.requestId) return;
    home.status.textContent = '搜尋失敗：' + error.message;
    replaceItems([]);
  }
}

function showLocal(label, items) {
  S.requestId++;
  S.showingHistory = label === '播放記錄';
  S.catalogMode = false;
  home.filters.replaceChildren();
  updatePager(false);
  home.heading.textContent = label;
  replaceItems(items);
  home.status.textContent = `${items.length} 部影片`;
}

function openItem(item) {
  if (item.source === 'live_link') {
    window.open(item.id, '_blank', 'noopener');
    return;
  }
  if (item.source === 'live') {
    home.status.textContent = `正在連線 ${item.title}…`;
    api.live(item.id).then((response) => {
      const url = Array.isArray(response.urls) ? str(response.urls[0]) : '';
      if (!response.ok || !url) {
        home.status.textContent = `${item.title} 暫無可用直播串流。`;
        return;
      }
      home.status.textContent = '';
      openPlayer({ title: item.title, episodeLabel: '直播', episodeIndex: 0, resumeMs: 0, live: true,
        searchOnFailure: false, candidates: [{ url, label: '目前片源', item: null }] });
    }).catch((error) => { home.status.textContent = '直播連線失敗：' + error.message; });
    return;
  }
  openDetail(item, S.showingHistory);
}

/* ---------- dialogs ---------- */
function dialog(title, body, buttons) {
  const back = h('div', { class: 'dialog-back' });
  const close = () => back.remove();
  back.append(h('div', { class: 'dialog' }, h('h3', {}, title), body,
    h('div', { class: 'row' }, buttons.map(([label, handler]) => h('button', {
      onclick: () => { if (handler(close) !== false) close(); },
    }, label)))));
  back.addEventListener('click', (e) => { if (e.target === back) close(); });
  document.body.append(back);
  return close;
}

function showSettings() {
  const url = h('input', { type: 'url', value: settings.base, placeholder: 'CK TV address', autocapitalize: 'off', autocorrect: 'off' });
  const pass = h('input', { type: 'password', value: settings.password, placeholder: 'Access password', autocomplete: 'off' });
  const file = h('input', { type: 'file', accept: 'application/json', hidden: true, onchange: () => importLibrary(file) });
  const body = h('div', {},
    h('label', {}, 'CK TV 位址'), url, h('label', {}, '存取密碼'), pass, h('hr'),
    h('label', {}, '收藏與播放記錄'),
    h('p', {}, '資料只存在此裝置。可匯出備份，或在另一個裝置匯入。'),
    h('div', { class: 'row', style: 'justify-content:flex-start' },
      h('button', { onclick: exportLibrary }, '匯出'), h('button', { onclick: () => file.click() }, '匯入'), file));
  dialog('連線設定', body, [['取消', () => true], ['儲存', () => {
    settings.save(url.value, pass.value);
    beginCatalog(S.kind, S.label, isDouban());
  }]]);
}

const LIBRARY_KEYS = ['favorites', 'history', 'working_sources', 'failed_sources', 'playback_progress'];
function exportLibrary() {
  const data = { app: 'cktv', exported: new Date().toISOString() };
  for (const key of LIBRARY_KEYS) data[key] = lib.read(key, ['favorites', 'history'].includes(key) ? [] : {});
  const link = h('a', { href: URL.createObjectURL(new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' })),
    download: 'cktv-library.json' });
  document.body.append(link);
  link.click();
  link.remove();
}
async function importLibrary(input) {
  try {
    const data = JSON.parse(await input.files[0].text());
    if (data.app !== 'cktv') throw new Error('not a CKTV export');
    for (const key of LIBRARY_KEYS) if (data[key] !== undefined) lib.write(key, data[key]);
    toast('已匯入收藏與播放記錄');
  } catch (error) {
    toast('匯入失敗：檔案格式不正確');
  }
}

/* ---------- detail (DetailActivity) ---------- */
function openDetail(startItem, resumeFromHistory) {
  let selected = startItem;
  const sources = [];
  let requestId = 0;
  let preferredUsed = false;
  let alternateSearched = false;
  let historyResumeStarted = false;
  let destroyed = false;

  const preferred = lib.preferredSource(selected.title);
  if (preferred) { selected = preferred; preferredUsed = true; }

  const posterEl = h('img', { class: 'poster' });
  const titleEl = h('h2', {}, selected.title);
  const metaEl = h('div', { class: 'meta' });
  const descEl = h('div', { class: 'desc' }, selected.desc);
  const favBtn = h('button', { onclick: () => {
    const added = lib.toggleFavorite(selected);
    refreshFav();
    toast(added ? '已加入收藏' : '已取消收藏');
  } });
  const sourceStatus = h('div', { class: 'muted' }, '正在尋找可播放片源…');
  const sourceRow = h('div', { class: 'row' });
  const otherBtn = h('button', { hidden: true, onclick: () => {
    alternateSearched = true;
    otherBtn.disabled = true;
    otherBtn.textContent = '正在搜尋其他片源…';
    loadAlternateSources(selected);
  } }, '搜尋其他片源');
  const episodeStatus = h('div', { class: 'muted' }, '選擇片源後載入劇集。');
  const continueBtn = h('button', { hidden: true });
  const episodeGrid = h('div', { class: 'episodes' });
  const root = h('div', { class: 'overlay' },
    h('div', { class: 'toolbar' }, h('button', { onclick: goBack }, '返回'), h('span', { class: 'muted' }, 'CKTV  /  影片詳情')),
    h('div', { class: 'hero' }, posterEl, h('div', { class: 'info' }, titleEl, metaEl, descEl, favBtn)),
    h('h3', {}, '選擇片源'), sourceStatus, sourceRow, otherBtn,
    h('h3', {}, '選集'), episodeStatus, continueBtn, episodeGrid);
  document.body.append(root);
  setPoster(posterEl, selected.poster);
  refreshFav();
  pushView({ destroy() { destroyed = true; root.remove(); lastDetailRefresh = null; } });
  lastDetailRefresh = () => { if (selected.episodes.length) refreshContinue(selected); };

  function refreshFav() { favBtn.textContent = lib.isFavorite(selected) ? '★  已收藏' : '☆  加入收藏'; }

  function showSourceInfo(item) {
    titleEl.textContent = item.title;
    metaEl.textContent = [item.year, item.source_name, item.remarks].filter(Boolean).join('  ·  ');
    descEl.textContent = item.desc;
    setPoster(posterEl, item.poster);
    refreshFav();
  }

  function renderSources() {
    sourceRow.replaceChildren(...sources.map((item) => h('button', { onclick: () => chooseSource(item) },
      sourceLabel(item) + (item.year ? '  ·  ' + item.year : ''))));
  }

  const containsSource = (item) => sources.some((s) => identity(s) === identity(item));

  async function loadSources(query) {
    sourceStatus.textContent = `正在尋找《${query}》的可播放片源…`;
    try {
      const values = parseMedia(await api.search(query));
      if (destroyed) return;
      sources.length = 0;
      sources.push(...values.filter((v) => v.source && v.id));
      renderSources();
      if (!sources.length) sourceStatus.textContent = '找不到可播放片源。';
      else {
        sourceStatus.textContent = `找到 ${sources.length} 個片源，正在載入劇集…`;
        chooseSource(sources[0]);
      }
    } catch (error) {
      if (!destroyed) sourceStatus.textContent = '尋找片源失敗：' + error.message;
    }
  }

  async function loadAlternateSources(base) {
    try {
      const values = parseMedia(await api.search(base.title));
      if (destroyed) return;
      const key = titleKey(base.title);
      for (const candidate of values) {
        if (candidate.source && candidate.id && key === titleKey(candidate.title) && !containsSource(candidate)) sources.push(candidate);
      }
      renderSources();
      otherBtn.disabled = false;
      otherBtn.textContent = '搜尋其他片源';
      otherBtn.hidden = true;
      if (preferredUsed && identity(selected) === identity(base)) {
        sourceStatus.textContent = `已載入 ${sources.length} 個片源；播放失敗時將自動嘗試其他片源。`;
      }
    } catch (error) {
      if (destroyed || identity(selected) !== identity(base)) return;
      otherBtn.disabled = false;
      otherBtn.textContent = '搜尋其他片源';
      sourceStatus.textContent = `目前片源：${sourceLabel(base)}（無法載入自動備援清單：${error.message}）`;
    }
  }

  async function chooseSource(item) {
    const id = ++requestId;
    preferredUsed = preferredUsed && identity(selected) === identity(item);
    selected = item;
    showSourceInfo(item);
    episodeGrid.replaceChildren();
    episodeStatus.textContent = '正在載入劇集…';
    sourceStatus.textContent = '目前片源：' + sourceLabel(item);
    try {
      const detail = norm(await api.detail(item.source, item.id));
      if (id !== requestId || destroyed) return;
      if (!detail.title) detail.title = item.title;
      if (!detail.poster) detail.poster = item.poster;
      if (!detail.desc) detail.desc = item.desc;
      if (!detail.source) detail.source = item.source;
      if (!detail.source_name) detail.source_name = item.source_name;
      selected = detail;
      renderDetail(detail);
    } catch (error) {
      if (id === requestId && !destroyed) episodeStatus.textContent = '載入劇集失敗：' + error.message;
    }
  }

  function renderDetail(detail) {
    if (!detail.episodes.length) {
      episodeStatus.textContent = '此片源沒有提供可播放劇集。';
      continueBtn.hidden = true;
      return;
    }
    episodeStatus.textContent = `共 ${detail.episodes.length} 集`;
    refreshContinue(detail);
    detail.episodes.forEach((_, index) => {
      const label = detail.episodes.length === 1 ? '立即播放' : `第 ${String(index + 1).padStart(2, '0')} 集`;
      episodeGrid.append(h('button', { onclick: () => play(detail, index, 0) }, label));
    });
    if (resumeFromHistory && !historyResumeStarted) {
      const progress = lib.progress(detail.title);
      if (progress && progress.episodeIndex < detail.episodes.length) {
        historyResumeStarted = true;
        play(detail, progress.episodeIndex, progress.positionMs);
      }
    }
  }

  function refreshContinue(item) {
    const progress = lib.progress(item.title);
    if (!progress || progress.episodeIndex >= item.episodes.length) {
      continueBtn.hidden = true;
      return;
    }
    const label = item.episodes.length === 1 ? '正片' : `第 ${String(progress.episodeIndex + 1).padStart(2, '0')} 集`;
    continueBtn.textContent = `繼續播放 ${label} · ${formatPosition(progress.positionMs)}`;
    continueBtn.onclick = () => {
      const current = lib.progress(selected.title);
      if (current && current.episodeIndex < selected.episodes.length) play(selected, current.episodeIndex, current.positionMs);
    };
    continueBtn.hidden = false;
  }

  function play(item, episode, resumeMs) {
    const named = item.episodes[episode];
    let label = item.episodes.length === 1 ? '正片' : `第 ${String(episode + 1).padStart(2, '0')} 集`;
    if (named.includes('$')) label = named.split('$')[0].trim();
    lib.addHistory(item);
    const candidates = [];
    const add = (url, text, source) => {
      const value = str(url).trim();
      if (!value || candidates.some((c) => c.url === value)) return;
      candidates.push({ url: value, label: text || '其他片源', item: source });
    };
    if (item.source === 'huangguo') {
      const url = episodeUrl(named);
      add(settings.base + (url.startsWith('/') ? url : '/' + url), sourceLabel(item), item);
      openPlayer({ title: item.title, episodeLabel: label, episodeIndex: episode, resumeMs, searchOnFailure: false, candidates });
      return;
    }
    add(episodeUrl(named), sourceLabel(item), item);
    const key = titleKey(item.title);
    for (const candidate of sources) {
      if (candidate.source === 'huangguo' || candidate.episodes.length <= episode || key !== titleKey(candidate.title)) continue;
      add(episodeUrl(candidate.episodes[episode]), sourceLabel(candidate), candidate);
    }
    openPlayer({ title: item.title, episodeLabel: label, episodeIndex: episode, resumeMs,
      searchOnFailure: preferredUsed && !alternateSearched, candidates });
  }

  if (preferredUsed) {
    sources.push(selected);
    renderSources();
    showSourceInfo(selected);
    renderDetail(selected);
    otherBtn.hidden = false;
    sourceStatus.textContent = `上次成功播放片源：${sourceLabel(selected)}；將先使用此片源，失敗時再搜尋其他片源。`;
  } else if (!selected.source) {
    loadSources(selected.title);
  } else {
    sources.push(selected);
    renderSources();
    chooseSource(selected);
    loadAlternateSources(selected);
  }
}
let lastDetailRefresh = null;

function formatPosition(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

/* ---------- player (PlayerActivity) ---------- */
function proxiedStream(url) {
  const route = /\.m3u8(\?|$)/i.test(url) ? '/stream.m3u8' : '/stream';
  return `${route}?url=${enc(url)}&_base=${enc(settings.base)}`;
}

function openPlayer(opts) {
  const cands = opts.candidates.map((c) => ({ ...c, url: c.url.startsWith('//') ? 'https:' + c.url : c.url }));
  if (!cands.length) { toast('此集沒有可播放的網址。'); return; }
  const fallbackIndexes = cands.map(() => -1);
  const attemptedProviders = new Set(cands.map((c) => c.item && c.item.source).filter(Boolean));
  const fallbackCandidates = [];
  let attempt = 0;
  let hls = null;
  let stallTimer = null;
  let saveTimer = null;
  let barTimer = null;
  let lastFailure = '';
  let searchStarted = false;
  let resumeMs = opts.resumeMs || 0;
  let destroyed = false;

  const video = h('video', { playsinline: true, controls: true, preload: 'auto', 'webkit-playsinline': true });
  const fullscreenBtn = h('button', { onclick: toggleFullscreen }, '全螢幕');
  const bar = h('div', { class: 'bar' }, h('button', { onclick: goBack }, '返回'),
    h('div', { class: 'title' }, `${opts.title} · ${opts.episodeLabel || ''}`), fullscreenBtn);
  const errorEl = h('div', { class: 'err', hidden: true });
  const frame = h('div', { class: 'frame' }, video);
  const root = h('div', { class: 'overlay', id: 'player' }, bar, frame, errorEl);
  document.body.append(root);
  document.documentElement.style.overflow = 'hidden';

  function toggleFullscreen() {
    const active = document.fullscreenElement || document.webkitFullscreenElement;
    if (active) {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    } else if (frame.requestFullscreen) {
      frame.requestFullscreen().catch(() => {});
    } else if (video.webkitEnterFullscreen) {
      video.webkitEnterFullscreen();
    } else if (frame.webkitRequestFullscreen) {
      frame.webkitRequestFullscreen();
    }
  }

  const showError = (message) => { errorEl.textContent = message; errorEl.hidden = false; };

  const saveProgress = () => {
    if (opts.live || destroyed) return;
    const duration = video.duration;
    if (video.readyState < 3 || !Number.isFinite(duration)) return;
    lib.saveProgress(opts.title, opts.episodeIndex, video.currentTime * 1000, duration * 1000);
  };
  const onHide = () => { if (document.visibilityState === 'hidden') saveProgress(); };
  document.addEventListener('visibilitychange', onHide);
  window.addEventListener('pagehide', saveProgress);

  const rememberSource = (index) => { if (cands[index].item) lib.rememberWorkingSource(cands[index].item); };
  const markFailed = (index) => { if (cands[index].item) lib.markSourceFailed(opts.title, cands[index].item); };

  function teardownMedia() {
    clearTimeout(stallTimer);
    clearInterval(saveTimer);
    if (hls) { hls.destroy(); hls = null; }
    video.removeAttribute('src');
    video.load();
  }

  function startPlayback(index, proxy) {
    const myAttempt = ++attempt;
    teardownMedia();
    const original = cands[index].url;
    const src = proxy ? proxiedStream(original) : original;
    let ready = false;

    const fail = (reason) => {
      if (myAttempt !== attempt || destroyed) return;
      clearTimeout(stallTimer);
      if (!proxy && /^https?:/i.test(original)) {
        startPlayback(index, true);
        return;
      }
      lastFailure = `播放失敗：${reason}\n請檢查網路，或從影片詳情更換片源。`;
      markFailed(index);
      if (index + 1 < cands.length) {
        showError(`片源「${cands[index].label}」連線失敗，正在自動嘗試片源 ${index + 2}/${cands.length}：「${cands[index + 1].label}」…`);
        startPlayback(index + 1, needsProxyFirst(cands[index + 1].url));
      } else if (fallbackIndexes[index] >= 0) {
        tryFallbackCandidate(fallbackIndexes[index] + 1);
      } else if (opts.searchOnFailure && !searchStarted) {
        searchFallbackSources();
      } else {
        showError(`${lastFailure}\n已自動嘗試 ${cands.length} 個片源，請返回詳情手動選擇其他片源。`);
      }
    };

    const onReady = () => {
      if (myAttempt !== attempt || ready) return;
      ready = true;
      clearTimeout(stallTimer);
      errorEl.hidden = true;
      rememberSource(index);
      if (resumeMs <= 0 && !opts.live) lib.clearProgress(opts.title, opts.episodeIndex);
      clearInterval(saveTimer);
      saveTimer = setInterval(saveProgress, PROGRESS_SAVE_MS);
    };

    video.onloadedmetadata = () => {
      if (myAttempt === attempt && resumeMs > 0) { try { video.currentTime = resumeMs / 1000; } catch (e) { /* ignore */ } }
    };
    video.oncanplay = onReady;
    video.onplaying = onReady;
    video.onerror = () => fail(video.error ? `MEDIA_ERR_${video.error.code}` : '未知錯誤');
    video.onended = () => {
      if (myAttempt !== attempt) return;
      clearInterval(saveTimer);
      if (!opts.live) lib.clearProgress(opts.title, opts.episodeIndex);
    };
    stallTimer = setTimeout(() => fail('連線逾時'), STALL_TIMEOUT_MS);

    const isHls = /\.m3u8(\?|$)/i.test(original);
    const nativeHls = video.canPlayType('application/vnd.apple.mpegurl');
    if (isHls && !nativeHls && window.Hls && window.Hls.isSupported()) {
      hls = new window.Hls({ maxBufferLength: 30 });
      hls.on(window.Hls.Events.ERROR, (event, data) => { if (data.fatal) fail(`HLS ${data.details}`); });
      hls.loadSource(src);
      hls.attachMedia(video);
    } else {
      video.src = src;
    }
    const started = video.play();
    if (started && started.catch) started.catch(() => { /* needs a tap; controls are visible */ });
  }

  function needsProxyFirst(url) {
    return url.startsWith(settings.base) || (location.protocol === 'https:' && /^http:/i.test(url));
  }

  async function searchFallbackSources() {
    searchStarted = true;
    showError('上次使用的片源無法播放，正在搜尋其他片源…');
    try {
      const values = parseMedia(await api.search(opts.title));
      if (destroyed) return;
      const key = titleKey(opts.title);
      for (const candidate of values) {
        if (!candidate.source || !candidate.id || key !== titleKey(candidate.title) || attemptedProviders.has(candidate.source)) continue;
        attemptedProviders.add(candidate.source);
        fallbackCandidates.push(candidate);
      }
      if (!fallbackCandidates.length) {
        showError(`${lastFailure}\n找不到其他可用片源，請返回詳情手動搜尋片源。`);
        return;
      }
      tryFallbackCandidate(0);
    } catch (error) {
      if (!destroyed) showError(`${lastFailure}\n搜尋其他片源失敗：${error.message}\n請返回詳情手動搜尋片源。`);
    }
  }

  async function tryFallbackCandidate(candidateIndex) {
    if (destroyed) return;
    if (candidateIndex >= fallbackCandidates.length) {
      showError(`${lastFailure}\n已搜尋其他片源，但沒有可播放的劇集。\n請返回詳情手動選擇片源。`);
      return;
    }
    let item = fallbackCandidates[candidateIndex];
    if (!(opts.episodeIndex >= 0 && opts.episodeIndex < item.episodes.length)) {
      try {
        const detail = norm(await api.detail(item.source, item.id));
        if (destroyed) return;
        detail.title = detail.title || item.title;
        detail.poster = detail.poster || item.poster;
        detail.desc = detail.desc || item.desc;
        detail.source = detail.source || item.source;
        detail.source_name = detail.source_name || item.source_name;
        item = detail;
      } catch (error) {
        tryFallbackCandidate(candidateIndex + 1);
        return;
      }
    }
    const url = opts.episodeIndex < item.episodes.length ? episodeUrl(item.episodes[opts.episodeIndex]) : '';
    if (!url) { tryFallbackCandidate(candidateIndex + 1); return; }
    cands.push({ url: url.startsWith('//') ? 'https:' + url : url, label: sourceLabel(item), item });
    fallbackIndexes.push(candidateIndex);
    showError(`正在嘗試備用片源「${sourceLabel(item)}」…`);
    startPlayback(cands.length - 1, needsProxyFirst(url));
  }

  pushView({
    destroy() {
      saveProgress();
      destroyed = true;
      attempt++;
      document.removeEventListener('visibilitychange', onHide);
      window.removeEventListener('pagehide', saveProgress);
      clearTimeout(barTimer);
      teardownMedia();
      root.remove();
      document.documentElement.style.overflow = '';
      if (lastDetailRefresh) lastDetailRefresh();
    },
  });
  startPlayback(0, needsProxyFirst(cands[0].url));
}

/* ---------- boot ---------- */
buildHome();
beginCatalog(S.kind, S.label, true);

if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) {
  navigator.serviceWorker.register('/sw.js').catch(() => { /* offline shell unavailable */ });
}
if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
window.addEventListener('online', () => toast('已恢復連線'));
window.addEventListener('offline', () => toast('目前離線：仍可查看收藏與播放記錄'));
})();
