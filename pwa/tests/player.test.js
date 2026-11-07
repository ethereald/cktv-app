'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

test('failed Huangguo playback searches and plays the matching alternative once', async () => {
  const code = fs.readFileSync(require.resolve('../public/app.js'), 'utf8');
  const playerCode = code.slice(code.indexOf('function openPlayer(opts) {'), code.indexOf('/* ---------- boot ---------- */'));
  let video;
  let searches = 0;
  const errors = [];
  const failed = [];
  const node = () => ({ append() {}, remove() {}, removeAttribute() {}, load() {}, play() { return Promise.resolve(); },
    canPlayType() { return ''; }, style: {}, hidden: false });
  const context = {
    h(tag) { const result = node(); if (tag === 'video') video = result; return result; },
    document: { body: node(), documentElement: node(), addEventListener() {}, removeEventListener() {} },
    window: { addEventListener() {}, removeEventListener() {} },
    location: { protocol: 'https:' }, settings: { base: 'https://site.test' },
    lib: { markSourceFailed(title, item) { failed.push(item.source); }, rememberWorkingSource() {},
      saveProgress() {}, clearProgress() {} },
    api: {
      async search() { searches++; return [{ source: 'other', id: '2', title: 'Title', episodes: [] }]; },
      async detail() { return { source: 'other', id: '2', title: 'Title', episodes: ['Episode$https://other.test/video.m3u8'] }; },
    },
    parseMedia: (items) => items, norm: (item) => item, titleKey: (title) => title.toLowerCase(),
    sourceLabel: (item) => item.source, episodeUrl: (episode) => episode.split('$').pop(),
    proxiedStream: (url) => 'proxy:' + url,
    pushView() {}, goBack() {}, toast: (message) => errors.push(message),
    setTimeout() { return 1; }, clearTimeout() {}, setInterval() { return 1; }, clearInterval() {},
    STALL_TIMEOUT_MS: 30000, PROGRESS_SAVE_MS: 5000,
  };
  vm.createContext(context);
  vm.runInContext(playerCode, context);
  context.openPlayer({
    title: 'Title', episodeIndex: 0, searchOnFailure: true,
    candidates: [{ url: 'https://gateway.test/api/huangguo/media/playlist.m3u8', label: '黃果', item: { source: 'huangguo' } }],
  });
  video.onerror();
  const proxyError = video.onerror;
  proxyError();
  proxyError();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(searches, 1);
  assert.deepEqual(failed, ['huangguo']);
  assert.equal(video.src, 'https://other.test/video.m3u8');
  assert.deepEqual(errors, []);
});
