'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createClient } = require('../huangguo');

test('catalog requests the upstream popularity order and its requested page', async () => {
  const requests = [];
  const category = '<div class="hg-drama-card"></div>'
    + '<a class="hg-topbar-nav__item" data-nav-key="video_category_1" data-nav-name="Drama" data-nav-route="ai-duanju">';
  const client = createClient({
    portals: [],
    fallbacks: ['https://example.test'],
    fetchText: async (url) => {
      requests.push(url);
      if (url.endsWith('/ai-duanju/')) return category;
      if (url.includes('/api/videos/category/')) return JSON.stringify({
        data: { items: [
          { id: 8, title: 'Popular', score: 9.5, cover: '/cover.jpg', episode_count: 7 },
          { id: 9, title: 'Other', score: 8.2 },
        ], pagination: { pages: 4 } },
      });
      throw new Error('Unexpected request');
    },
  });
  const result = await client.list('ai-duanju', 2);
  assert.ok(requests.includes('https://example.test/api/videos/category/ai-duanju?sort=hot_window&page=2&size=21'));
  assert.deepEqual(result.list.map((item) => item.title), ['Popular', 'Other']);
  assert.equal(result.list[0].rate, '9.5');
  assert.equal(result.list[0].remarks, '更新至7集');
  assert.equal(result.page, 2);
  assert.equal(result.pagecount, 4);
  assert.equal(result.categories[0].id, 'ai-duanju');
});

test('catalog retries another route when popularity response is invalid', async () => {
  const calls = [];
  const client = createClient({
    portals: [],
    fallbacks: ['https://first.test', 'https://second.test'],
    fetchText: async (url) => {
      calls.push(url);
      if (url.endsWith('/ai-duanju/')) return '<div class="hg-drama-card"></div>';
      if (url.startsWith('https://first.test')) return 'not json';
      return JSON.stringify({ data: { items: [], pagination: { pages: 1 } } });
    },
  });
  const result = await client.list('ai-duanju', 1);
  assert.equal(result.list.length, 0);
  assert.ok(calls.some((url) => url.startsWith('https://second.test/api/videos/category/')));
});

test('resolves episodes using the new playback API and CDN headers', async () => {
    const calls = [];
    const client = createClient({
      portals: [],
      fallbacks: ['https://example.test'],
      fetchText: async (url, options) => {
        calls.push({ url, options });
        if (url.endsWith('/video/12/ep-2/')) {
          return '<script id="videoInitialData" type="application/json">{"epPlaySrcs":{}}</script>';
        }
        return JSON.stringify({ status: 1, data: {
          manifest_method: 'GET', streams: { main: 'https://cdn.test/video.m3u8' },
          cdn: { m3u8Enabled: true, authKey: 'x-test-key', authValue: 'value', app: 'app' },
        } });
      },
    });
    const source = await client.episodeSource('12', '2');
    assert.equal(source.url, 'https://cdn.test/video.m3u8');
    assert.deepEqual(source.headers, { 'x-test-key': 'value', 'x-cdn-app': 'app' });
    assert.equal(calls[1].url, 'https://example.test/api/videos/12/episodes/2/playback');
    assert.deepEqual(calls[1].options, { method: 'POST', body: '{}' });
  });

test('uses legacy embedded episode sources without a playback API request', async () => {
    let calls = 0;
    const client = createClient({
      portals: [], fallbacks: ['https://example.test'],
      fetchText: async () => {
        calls++;
        return '<script id="videoInitialData" type="application/json">{"epPlaySrcs":{"1":"https://cdn.test/legacy.m3u8"}}</script>';
      },
    });
    assert.equal((await client.episodeSource('12', '1')).url, 'https://cdn.test/legacy.m3u8');
    assert.equal(calls, 1);
  });
