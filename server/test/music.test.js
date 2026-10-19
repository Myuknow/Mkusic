const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { createMusicServer } = require('../server');
const { migrate } = require('../scripts/import-legacy');

const password = 'test-only:music-password';
const auth = 'Basic ' + Buffer.from(':' + password).toString('base64');

async function fixture(t, options = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'mkusic-test-'));
  const app = await createMusicServer({ password, dataDir: path.join(dir, 'data'), ...options });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${app.server.address().port}`;
  t.after(async () => {
    await new Promise(resolve => { app.server.close(resolve); app.server.closeAllConnections(); });
    await app.cache.close();
    await app.store.queue;
    assert.ok(path.resolve(dir).startsWith(path.join(path.resolve(os.tmpdir()), 'mkusic-test-')));
    await fs.rm(dir, { recursive: true, force: true });
  });
  return { ...app, dir, base, request: (url, options = {}) => fetch(base + url, { ...options, headers: { Authorization: auth, ...options.headers } }) };
}

async function upload(app, bytes = Buffer.from('0123456789')) {
  const form = new FormData(); form.append('file', new Blob([bytes], { type: 'audio/wav' }), '测试音乐.wav');
  const response = await app.request('/api/audio/upload', { method: 'POST', body: form });
  assert.equal(response.status, 201); return response.json();
}
async function playlist(app, tracks) {
  return app.request('/api/playlist', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ playlist: tracks }) });
}
async function cacheLibrary(app) {
  await playlist(app, [
    { id: 'default', kind: 'bilibili', bvid: 'BV1xx411c7mD', aid: '100' },
    { id: 'part-two', kind: 'bilibili', bvid: 'BV1xx411c7mD', aid: '100', cid: '456' },
    { id: 'song', kind: 'biliaudio', sid: '200' }
  ]);
}

test('App contract: authentication also required on loopback, raw playlist array, safe static files', async t => {
  const app = await fixture(t);
  for (const url of ['/', '/api/playlist', '/api/health', '/app.js']) {
    const response = await fetch(app.base + url); assert.equal(response.status, 401);
    assert.match(response.headers.get('www-authenticate'), /Basic/);
  }
  assert.equal((await app.request('/api/playlist', { headers: { Authorization: 'Basic ' + Buffer.from(':wrong').toString('base64') } })).status, 401);
  assert.deepEqual(await (await app.request('/api/playlist')).json(), []);
  const track = { id: 'app-track', kind: 'bilibili', bvid: 'BV1xx411c7mD', cid: 123, title: '配套 App 音乐', group: '分组' };
  assert.equal((await playlist(app, [track])).status, 200);
  assert.deepEqual(await (await app.request('/api/playlist')).json(), [{ ...track, cid: '123' }]);
  assert.match(await (await app.request('/')).text(), /Mkusic/);
  for (const url of ['/.env', '/data/db.json', '/server.js', '/lib/store.js', '/api/files', '/api/state', '/api/news']) assert.equal((await app.request(url)).status, 404);
  assert.equal((await app.request('/api/playlist', { method: 'PUT', headers: { 'Content-Type': 'application/json', Origin: 'https://elsewhere.example' }, body: '{"playlist":[]}' })).status, 403);
  assert.equal((await app.request('/api/playlist', { method: 'PUT', body: '{bad' })).status, 400);
  assert.equal((await playlist(app, {})).status, 400);
  const health = await (await app.request('/api/health')).json();
  assert.equal(health.app, 'mkusic'); assert.equal(health.tracks, 1);
});

test('uploaded audio supports complete bytes, first/middle/suffix/zero-ended ranges, HEAD and 416', async t => {
  const app = await fixture(t); const record = await upload(app);
  assert.equal(record.type, 'audio/wav'); assert.equal(record.originalName, '测试音乐.wav');
  assert.equal((await fetch(app.base + record.url)).status, 401);
  assert.equal(await (await app.request(record.url)).text(), '0123456789');
  for (const [range, expected, contentRange] of [['bytes=0-0', '0', 'bytes 0-0/10'], ['bytes=4-6', '456', 'bytes 4-6/10'], ['bytes=7-', '789', 'bytes 7-9/10'], ['bytes=-3', '789', 'bytes 7-9/10']]) {
    const response = await app.request(record.url, { headers: { Range: range } });
    assert.equal(response.status, 206); assert.equal(response.headers.get('content-range'), contentRange); assert.equal(await response.text(), expected);
  }
  const head = await app.request(record.url, { method: 'HEAD' }); assert.equal(head.status, 200); assert.equal(head.headers.get('content-length'), '10'); assert.equal(await head.text(), '');
  for (const range of ['bytes=10-', 'bytes=3-1', 'bytes=-0', 'bytes=0-1,4-5']) {
    const response = await app.request(record.url, { headers: { Range: range } }); assert.equal(response.status, 416); assert.equal(response.headers.get('content-range'), 'bytes */10');
  }
  const form = new FormData(); form.append('file', new Blob(['bad']), 'run.html');
  assert.equal((await app.request('/api/audio/upload', { method: 'POST', body: form })).status, 400);
});

test('concurrent uploads preserve all records and deletion removes only the referenced audio', async t => {
  const app = await fixture(t);
  const records = await Promise.all(Array.from({ length: 4 }, () => upload(app)));
  assert.equal((await app.store.read()).audio.length, 4);
  await playlist(app, records.map(record => ({ id: record.id, kind: 'local', audioId: record.id, url: record.url })));
  assert.equal((await app.request('/api/audio/' + records[0].id, { method: 'DELETE' })).status, 200);
  assert.equal((await app.store.read()).audio.length, 3);
  assert.equal((await app.store.read()).playlist.length, 3);
  assert.equal((await app.request(records[0].url)).status, 404);
  assert.equal(await (await app.request(records[1].url)).text(), '0123456789');
});

test('music backup restores audio and rejects incomplete/malicious imports without replacing live data', async t => {
  const app = await fixture(t); const record = await upload(app);
  const track = { id: 'local-track', kind: 'local', audioId: record.id, title: '音乐备份', url: record.url };
  await playlist(app, [track]);
  const backup = await (await app.request('/api/export')).json();
  assert.equal(backup.app, 'mkusic'); assert.deepEqual(Object.keys(backup.db).sort(), ['audio', 'playlist']);
  await playlist(app, []);
  const restore = await app.request('/api/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(backup) });
  assert.equal(restore.status, 200); assert.equal((await restore.json()).tracks, 1);
  assert.equal(await (await app.request(record.url)).text(), '0123456789');
  assert.deepEqual(await (await app.request('/api/playlist')).json(), [track]);
  for (const bad of [{ ...backup, uploads: [] }, { ...backup, db: { ...backup.db, audio: [{ ...backup.db.audio[0], storedName: '../secrets.json' }] } }]) {
    assert.equal((await app.request('/api/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(bad) })).status, 400);
    assert.deepEqual(await (await app.request('/api/playlist')).json(), [track]);
  }
});

function biliFixture(url, options) {
  url = new URL(url);
  assert.equal(options.headers.Authorization, undefined);
  const ok = data => new Response(JSON.stringify({ code: 0, data }), { headers: { 'Content-Type': 'application/json' } });
  if (url.pathname === '/x/web-interface/view') return Promise.resolve(ok({ bvid: 'BV1xx411c7mD', aid: 100, title: 'B站测试音乐', owner: { name: '测试 UP' }, pic: 'https://i0.hdslb.com/cover.jpg', pages: [{ cid: 123, part: '第一部分', duration: 30 }, { cid: 456, part: '第二部分', duration: 40 }] }));
  if (url.pathname === '/x/player/playurl') return Promise.resolve(ok({ dash: { audio: [{ bandwidth: 10, baseUrl: 'https://test.bilivideo.com/audio.mp4' }] } }));
  if (url.pathname === '/x/v3/fav/resource/ids') return Promise.resolve(ok([{ id: 100, type: 2 }, { id: 200, type: 12 }]));
  if (url.pathname === '/x/v3/fav/folder/info') return Promise.resolve(ok({ title: '公开收藏夹' }));
  if (url.pathname.endsWith('/song/info')) return Promise.resolve(ok({ title: 'B站音频', uname: '音乐人' }));
  if (url.pathname.endsWith('/web/url')) return Promise.resolve(ok({ cdns: ['https://test.bilivideo.com/audio.mp4'] }));
  if (url.hostname === 'i0.hdslb.com') return Promise.resolve(new Response(Buffer.from([1, 2, 3]), { headers: { 'Content-Type': 'image/jpeg' } }));
  if (url.hostname === 'test.bilivideo.com') {
    assert.ok(options.signal); assert.equal(options.headers.Range, undefined);
    return Promise.resolve(new Response('0123456789', { headers: { 'Content-Type': 'audio/mp4', 'Content-Length': '10' } }));
  }
  throw new Error('Unexpected upstream request: ' + url);
}

test('Bilibili App compatibility: aid/cid/sid, stable proxy URLs, favorites, cover and Range without auth leak', async t => {
  const app = await fixture(t, { fetchImpl: biliFixture });
  await cacheLibrary(app);
  const video = await (await app.request('/api/bilibili/resolve?aid=100&cid=456')).json();
  assert.equal(video.cid, '456'); assert.match(video.audioUrl, /^\/api\/bilibili\/audio\?/); assert.equal(video.directAudioUrl, undefined);
  assert.equal(video.title, 'B站测试音乐 - 第二部分');
  const audio = await (await app.request('/api/bilibili/resolve?sid=200')).json(); assert.equal(audio.audioUrl, '/api/bilibili/audio?sid=200');
  const meta = await (await app.request('/api/bilibili/meta?bvid=BV1xx411c7mD')).json(); assert.equal(meta.artist, '测试 UP');
  const fav = await (await app.request('/api/bilibili/fav?fid=12')).json(); assert.equal(fav.tracks[0].aid, '100'); assert.equal(fav.tracks[1].sid, '200');
  const stream = await app.request('/api/bilibili/audio?aid=100&cid=456', { headers: { Range: 'bytes=2-4' } });
  assert.equal(stream.status, 206); assert.equal(stream.headers.get('content-range'), 'bytes 2-4/10'); assert.equal(await stream.text(), '234');
  assert.equal((await app.request('/api/bilibili/cover?url=' + encodeURIComponent('https://i0.hdslb.com/cover.jpg'))).status, 200);
  assert.equal((await app.request('/api/bilibili/cover?url=' + encodeURIComponent('https://example.com/private'))).status, 404);
});

test('placeholder song names are enriched and persisted for the App without changing manual names or groups', async t => {
  const app = await fixture(t, { fetchImpl: biliFixture });
  const tracks = [
    { id: 'video-name', kind: 'bilibili', bvid: 'BV1xx411c7mD', cid: '456', title: 'B站 · BV1xx411c7mD', artist: 'Bilibili', group: 'ACG' },
    { id: 'audio-name', kind: 'biliaudio', sid: '200', title: 'B站 · au200', artist: '自定义歌手', group: '收藏' },
    { id: 'manual-name', kind: 'bilibili', bvid: 'BV1xx411c7mD', title: '自己起的名字', artist: '自定义', group: '手工分类' }
  ];
  await playlist(app, tracks);
  const response = await app.request('/api/playlist/metadata', { method: 'POST', body: JSON.stringify({ ids: tracks.map(track => track.id) }) });
  assert.equal(response.status, 200);
  const result = await response.json(); assert.equal(result.updated, 2); assert.deepEqual(result.failed, []);
  const saved = await (await app.request('/api/playlist')).json();
  assert.equal(saved[0].title, 'B站测试音乐 - 第二部分'); assert.equal(saved[0].artist, '测试 UP');
  assert.equal(saved[0].cover, 'https://i0.hdslb.com/cover.jpg'); assert.equal(saved[0].group, 'ACG'); assert.equal(saved[0].cid, '456');
  assert.equal(saved[1].title, 'B站音频'); assert.equal(saved[1].artist, '自定义歌手');
  assert.deepEqual(saved[2], tracks[2]);
  const again = await (await app.request('/api/playlist/metadata', { method: 'POST', body: JSON.stringify({ ids: tracks.map(track => track.id) }) })).json();
  assert.equal(again.updated, 0);
});

test('song-name completion preserves edits made during the upstream request', async t => {
  let signalStarted, releaseRequest;
  const started = new Promise(resolve => { signalStarted = resolve; });
  const released = new Promise(resolve => { releaseRequest = resolve; });
  const app = await fixture(t, { fetchImpl: async (url, options) => { signalStarted(); await released; return biliFixture(url, options); } });
  const track = { id: 'editing', kind: 'bilibili', bvid: 'BV1xx411c7mD', title: 'B站 · BV1xx411c7mD', group: 'ACG' };
  await playlist(app, [track]);
  const pending = app.request('/api/playlist/metadata', { method: 'POST', body: JSON.stringify({ ids: [track.id] }) });
  await started;
  await playlist(app, [{ ...track, title: '刚刚手动修改的名字', group: '新分组' }]);
  releaseRequest();
  assert.equal((await (await pending).json()).updated, 0);
  const saved = await (await app.request('/api/playlist')).json();
  assert.equal(saved[0].title, '刚刚手动修改的名字'); assert.equal(saved[0].group, '新分组');
});

test('unavailable song metadata does not erase other songs or mark playable tracks as failed', async t => {
  const fetchImpl = (url, options) => new URL(url).searchParams.get('bvid') === 'BV0000000001' ? Promise.reject(new Error('temporarily unavailable')) : biliFixture(url, options);
  const app = await fixture(t, { fetchImpl });
  await playlist(app, [{ id: 'good', kind: 'bilibili', bvid: 'BV1xx411c7mD', title: 'B站 · BV1xx411c7mD' }, { id: 'later', kind: 'bilibili', bvid: 'BV0000000001', title: 'B站 · BV0000000001' }]);
  const result = await (await app.request('/api/playlist/metadata', { method: 'POST', body: JSON.stringify({ ids: ['good', 'later'] }) })).json();
  assert.equal(result.updated, 1); assert.equal(result.failed.length, 1); assert.equal(result.failed[0].id, 'later');
  assert.equal(result.playlist[1].title, 'B站 · BV0000000001'); assert.equal(result.playlist[1].failReason, undefined);
});

test('upstream stream interruption fails only the request and music service remains available', async t => {
  const fetchImpl = async (url, options) => {
    if (new URL(url).hostname !== 'test.bilivideo.com') return biliFixture(url, options);
    return new Response(new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('part')); setTimeout(() => controller.error(new Error('upstream disconnected')), 30); } }), { headers: { 'Content-Type': 'audio/mp4' } });
  };
  const app = await fixture(t, { fetchImpl });
  await cacheLibrary(app);
  await assert.rejects(async () => { const response = await app.request('/api/bilibili/audio?bvid=BV1xx411c7mD'); await response.text(); });
  assert.equal((await app.request('/api/health')).status, 200);
});

test('migration copies only music, leaves source untouched and refuses an occupied destination', async t => {
  const app = await fixture(t);
  const source = path.join(app.dir, 'legacy-library'); const sourceData = path.join(source, 'data');
  await fs.mkdir(path.join(sourceData, 'uploads'), { recursive: true });
  await fs.writeFile(path.join(sourceData, 'uploads', 'music.wav'), 'music-bytes');
  await fs.writeFile(path.join(sourceData, 'uploads', 'private.txt'), 'private document');
  await fs.writeFile(path.join(sourceData, 'secrets.json'), 'private password');
  const db = { playlist: [{ id: 'song', kind: 'local', audioId: 'audio', title: '迁移音乐' }], audio: [{ id: 'audio', storedName: 'music.wav', originalName: 'music.wav' }], files: [{ storedName: 'private.txt' }], state: { privateNotes: 'private note' } };
  const original = JSON.stringify(db); await fs.writeFile(path.join(sourceData, 'db.json'), original);
  const target = path.join(app.dir, 'new-music');
  const result = await migrate(source, target); assert.equal(result.tracks, 1);
  const migrated = JSON.parse(await fs.readFile(path.join(target, 'db.json'), 'utf8'));
  assert.deepEqual(Object.keys(migrated).sort(), ['audio', 'playlist']);
  assert.equal(await fs.readFile(path.join(target, 'uploads', migrated.audio[0].storedName), 'utf8'), 'music-bytes');
  assert.equal((await fs.readdir(path.join(target, 'uploads'))).length, 1);
  assert.equal(await fs.readFile(path.join(sourceData, 'db.json'), 'utf8'), original);
  await assert.rejects(fs.access(path.join(target, 'secrets.json')));
  await assert.rejects(migrate(source, target), /非空/);
  await assert.rejects(migrate(source, sourceData), /不能位于/);
});

test('empty password cannot start a server', async () => {
  await assert.rejects(createMusicServer({ password: '' }), /访问密码/);
});

test('whole-song cache survives a short range request, deduplicates downloads and works offline after restart', async t => {
  let downloads = 0, offline = false, release;
  const tail = new Promise(resolve => { release = resolve; });
  const app = await fixture(t, { fetchImpl: async (url, options) => {
    if (offline) throw new Error('Bilibili is offline');
    if (new URL(url).hostname !== 'test.bilivideo.com') return biliFixture(url, options);
    downloads++; assert.equal(options.headers.Range, undefined); assert.equal(options.headers.Authorization, undefined);
    return new Response(new ReadableStream({ async start(controller) {
      controller.enqueue(Buffer.from('01234')); await tail; controller.enqueue(Buffer.from('56789')); controller.close();
    } }), { headers: { 'Content-Type': 'audio/mp4', 'Content-Length': '10' } });
  } });
  const endpoint = '/api/bilibili/audio?aid=100&cid=456';
  await cacheLibrary(app);
  const first = await app.request(endpoint, { headers: { Range: 'bytes=2-4' } });
  assert.equal(first.status, 206); assert.equal(await first.text(), '234');
  const second = app.request(endpoint, { headers: { Range: 'bytes=-3' } });
  release();
  assert.equal(await (await second).text(), '789');
  await app.cache.lookup({ aid: '100', cid: '456' }).finished.promise;
  assert.equal(downloads, 1);
  const files = await fs.readdir(app.cache.directory);
  assert.equal(files.filter(name => name.endsWith('.audio')).length, 1); assert.equal(files.some(name => name.endsWith('.part')), false);
  offline = true;
  assert.equal(await (await app.request(endpoint)).text(), '0123456789');
  const resolved = await (await app.request('/api/bilibili/resolve?aid=100&cid=456')).json();
  assert.equal(resolved.cid, '456'); assert.equal(resolved.directAudioUrl, undefined);
  const byBvid = '/api/bilibili/audio?bvid=BV1xx411c7mD&cid=456';
  const cached = await app.request(byBvid); assert.equal(cached.headers.get('x-music-cache'), 'HIT'); assert.equal(await cached.text(), '0123456789');
  await app.cache.close();
  const restarted = await fixture(t, { dataDir: app.store.directory, fetchImpl: async () => { throw new Error('offline'); } });
  for (const [range, body] of [['bytes=0-0', '0'], ['bytes=7-', '789'], ['bytes=-3', '789']]) {
    const result = await restarted.request(byBvid, { headers: { Range: range } }); assert.equal(result.status, 206); assert.equal(await result.text(), body);
  }
  const head = await restarted.request(endpoint, { method: 'HEAD' }); assert.equal(head.status, 200); assert.equal(head.headers.get('content-length'), '10');
  const invalid = await restarted.request(endpoint, { headers: { Range: 'bytes=10-' } }); assert.equal(invalid.status, 416); assert.equal(invalid.headers.get('content-range'), 'bytes */10');
  assert.equal((await restarted.request('/api/bilibili/resolve?aid=100&cid=456')).status, 200);
  assert.equal((await restarted.request('/api/bilibili/resolve?aid=100&cid=123')).status, 502);
});

test('interrupted cache is not finalized, can be retried, and partial upstream responses are rejected', async t => {
  let mode = 'short';
  const app = await fixture(t, { fetchImpl: async (url, options) => {
    if (new URL(url).hostname !== 'test.bilivideo.com') return biliFixture(url, options);
    if (mode === 'partial') return new Response('01234', { status: 206, headers: { 'Content-Range': 'bytes 0-4/10' } });
    return new Response(mode === 'short' ? '01234' : '0123456789', { headers: { 'Content-Length': '10', 'Content-Type': 'audio/mp4' } });
  } });
  const endpoint = '/api/bilibili/audio?bvid=BV1xx411c7mD';
  await cacheLibrary(app);
  await assert.rejects(async () => { await (await app.request(endpoint)).text(); });
  await app.cache.lookup({ bvid: 'BV1xx411c7mD' }).finished.promise.catch(() => {});
  // Allow the failed download's cleanup to finish before retrying the same file.
  while (app.cache.active) await new Promise(resolve => setTimeout(resolve, 5));
  assert.deepEqual(await fs.readdir(app.cache.directory), []);
  mode = 'partial'; assert.equal((await app.request(endpoint)).status, 502);
  while (app.cache.active) await new Promise(resolve => setTimeout(resolve, 5));
  mode = 'full'; assert.equal(await (await app.request(endpoint)).text(), '0123456789');
  await app.cache.lookup({ bvid: 'BV1xx411c7mD' }).finished.promise;
  assert.equal(app.cache.status([]).count, 1);
});

test('precache uses the library, reports failures and evicts older complete files within the capacity limit', async t => {
  const app = await fixture(t, { fetchImpl: biliFixture, cacheLimitBytes: 15 });
  await playlist(app, [{ id: 'video', kind: 'bilibili', bvid: 'BV1xx411c7mD' }, { id: 'audio', kind: 'biliaudio', sid: '200' }, { id: 'external', kind: 'url', url: 'https://example.com/song.mp3' }]);
  assert.equal((await fetch(app.base + '/api/cache')).status, 401);
  assert.equal((await app.request('/api/cache/warm', { method: 'POST', body: '{"ids":"wrong"}' })).status, 400);
  assert.equal((await app.request('/api/cache/warm', { method: 'POST', headers: { Origin: 'https://elsewhere.example' }, body: '{}' })).status, 403);
  const response = await app.request('/api/cache/warm', { method: 'POST', body: '{}' });
  assert.equal(response.status, 202); assert.equal((await response.json()).total, 2);
  await app.cache.warming; await app.cache.pruning;
  const status = await (await app.request('/api/cache')).json();
  assert.equal(status.job.running, false); assert.equal(status.job.done, 2); assert.equal(status.job.failed, 0);
  assert.equal(status.count, 1); assert.equal(status.bytes, 10); assert.equal(status.tracks.find(track => track.id === 'audio').status, 'cached');
  assert.equal((await fs.readdir(app.cache.directory)).filter(name => name.endsWith('.audio')).length, 1);
  assert.equal((await app.request('/data/audio-cache/' + (await fs.readdir(app.cache.directory))[0])).status, 404);
});

test('cache rejects files over its limit and does not fall back to video-only media', async t => {
  const app = await fixture(t, { fetchImpl: biliFixture, cacheLimitBytes: 5 });
  await cacheLibrary(app);
  const response = await app.request('/api/bilibili/audio?bvid=BV1xx411c7mD');
  assert.equal(response.status, 502); assert.match((await response.json()).error, /容量/);
  const videoOnly = await fixture(t, { fetchImpl: async (url, options) => new URL(url).pathname === '/x/player/playurl'
    ? new Response(JSON.stringify({ code: 0, data: { durl: [{ url: 'https://test.bilivideo.com/video.mp4' }] } })) : biliFixture(url, options) });
  await cacheLibrary(videoOnly);
  const rejected = await videoOnly.request('/api/bilibili/audio?bvid=BV1xx411c7mD');
  assert.equal(rejected.status, 502); assert.match((await rejected.json()).error, /独立音频/);
});

test('complete CDN range headers and mislabeled audio are accepted, JSON errors are not cached', async t => {
  let mode = 'range';
  const mp4 = Buffer.concat([Buffer.from([0, 0, 0, 24]), Buffer.from('ftypisom00000000')]);
  const app = await fixture(t, { fetchImpl: async (url, options) => {
    if (new URL(url).hostname !== 'test.bilivideo.com') return biliFixture(url, options);
    if (mode === 'json') return new Response('{"error":"not music"}', { headers: { 'Content-Type': 'application/json' } });
    return new Response(mp4, { status: mode === '206' ? 206 : 200, headers: {
      'Content-Type': mode === 'range' ? 'application/octet-stream' : 'application/json',
      'Content-Range': `bytes 0-${mp4.length - 1}/${mp4.length}`, 'Content-Length': String(mp4.length)
    } });
  } });
  await cacheLibrary(app);
  for (const [cid, next] of [['123', 'range'], ['456', '206']]) {
    mode = next;
    const result = await app.request('/api/bilibili/audio?bvid=BV1xx411c7mD&cid=' + cid);
    assert.equal(result.status, 200); assert.deepEqual(Buffer.from(await result.arrayBuffer()), mp4);
  }
  mode = 'json';
  const rejected = await app.request('/api/bilibili/audio?sid=200');
  assert.equal(rejected.status, 502); assert.match((await rejected.json()).error, /独立音频/);
  assert.equal(app.cache.status([]).count, 2);
});

test('deleting a song frees its cached file, preserves shared sources, and clearing the list frees all cache', async t => {
  const app = await fixture(t, { fetchImpl: biliFixture });
  const video = { id: 'video', kind: 'bilibili', bvid: 'BV1xx411c7mD', aid: '100' };
  const duplicate = { id: 'same-video', kind: 'bilibili', aid: '100' };
  const audio = { id: 'audio', kind: 'biliaudio', sid: '200' };
  await playlist(app, [video, duplicate, audio]);
  await app.request('/api/cache/warm', { method: 'POST', body: '{}' }); await app.cache.warming;
  assert.equal(app.cache.status([]).count, 2);
  await playlist(app, [duplicate, audio]);
  assert.equal(app.cache.status([]).count, 2);
  assert.equal(await (await app.request('/api/bilibili/audio?aid=100')).text(), '0123456789');
  await playlist(app, [audio]);
  assert.equal(app.cache.status([]).count, 1);
  assert.equal((await fs.readdir(app.cache.directory)).length, 2);
  assert.equal((await app.request('/api/bilibili/audio?bvid=BV1xx411c7mD')).status, 404);
  await playlist(app, []);
  assert.equal(app.cache.status([]).count, 0); assert.deepEqual(await fs.readdir(app.cache.directory), []);
});

test('deleting during playback cancels the download and skips deleted songs in a precache job', async t => {
  let started, downloads = 0;
  const begun = new Promise(resolve => { started = resolve; });
  const app = await fixture(t, { fetchImpl: async (url, options) => {
    if (new URL(url).hostname !== 'test.bilivideo.com') return biliFixture(url, options);
    downloads++;
    const body = new ReadableStream({ start(controller) {
      controller.enqueue(Buffer.from('01234'));
      options.signal.addEventListener('abort', () => controller.error(options.signal.reason), { once: true });
    } });
    started();
    return new Response(body, { headers: { 'Content-Type': 'audio/mp4', 'Content-Length': '10' } });
  } });
  await playlist(app, [{ id: 'video', kind: 'bilibili', bvid: 'BV1xx411c7mD' }, { id: 'audio', kind: 'biliaudio', sid: '200' }]);
  await app.request('/api/cache/warm', { method: 'POST', body: '{}' }); await begun;
  const response = await app.request('/api/bilibili/audio?bvid=BV1xx411c7mD');
  const body = response.text().catch(error => error);
  assert.equal((await playlist(app, [])).status, 200);
  assert.ok(await body instanceof Error);
  await app.cache.warming;
  assert.equal(downloads, 1); assert.equal(app.cache.job.done, 2);
  assert.equal(app.cache.status([]).active, 0); assert.deepEqual(await fs.readdir(app.cache.directory), []);
});

test('startup removes earlier orphan caches while preserving files still referenced by the library', async t => {
  const app = await fixture(t, { fetchImpl: biliFixture });
  const video = { id: 'video', kind: 'bilibili', bvid: 'BV1xx411c7mD' };
  await playlist(app, [video, { id: 'audio', kind: 'biliaudio', sid: '200' }]);
  await app.request('/api/cache/warm', { method: 'POST', body: '{}' }); await app.cache.warming;
  await app.cache.close();
  // Reproduce cache files left behind by the previous version after a deletion.
  await app.store.mutate(db => { db.playlist = [video]; });
  const restarted = await fixture(t, { dataDir: app.store.directory, fetchImpl: async () => { throw new Error('offline'); } });
  assert.equal(restarted.cache.status([]).count, 1);
  assert.equal((await fs.readdir(restarted.cache.directory)).length, 2);
  assert.equal(await (await restarted.request('/api/bilibili/audio?bvid=BV1xx411c7mD')).text(), '0123456789');
});
