const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { MusicStore, safeFile, normalizePlaylist } = require('./lib/store');
const { createBilibili, needsMetadata } = require('./lib/bilibili');
const { parseRange, streamFile } = require('./lib/audio-file');

const ROOT = __dirname;
const VERSION = require('./package.json').version;
const MIME = { '.mp3': 'audio/mpeg', '.flac': 'audio/flac', '.wav': 'audio/wav', '.m4a': 'audio/mp4', '.mp4': 'audio/mp4', '.ogg': 'audio/ogg', '.opus': 'audio/ogg', '.aac': 'audio/aac', '.webm': 'audio/webm' };
const error = (status, message) => Object.assign(new Error(message), { status });
const json = (res, status, value) => {
  if (res.destroyed) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const notFound = res => json(res, 404, { error: '未找到资源' });

async function readBody(req, limit) {
  if (Number(req.headers['content-length']) > limit) { req.resume(); throw error(413, '文件或请求过大'); }
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw error(413, '文件或请求过大');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJson(req, limit = 20 * 1024 * 1024) {
  try { return JSON.parse((await readBody(req, limit)).toString('utf8')); }
  catch (cause) { if (cause.status) throw cause; throw error(400, 'JSON 格式不正确'); }
}

async function createMusicServer(options) {
  const password = String(options.password || '');
  if (!password.trim()) throw new Error('未设置访问密码：先运行 npm run setup，或配置 ACCESS_PASSWORD');
  const expected = crypto.createHash('sha256').update(password).digest();
  const store = new MusicStore(path.resolve(options.dataDir || path.join(ROOT, 'data')));
  await store.init();
  const bili = createBilibili({ fetchImpl: options.fetchImpl, json, notFound, cacheDir: path.join(store.directory, 'audio-cache'), cacheLimitBytes: options.cacheLimitBytes });
  await bili.ready;
  await bili.cache.reconcile((await store.read()).playlist);

  function authorized(req) {
    const basic = /^Basic\s+(\S+)$/i.exec(req.headers.authorization || '');
    if (!basic) return false;
    const text = Buffer.from(basic[1], 'base64').toString('utf8');
    const separator = text.indexOf(':');
    if (separator < 0) return false;
    const actual = crypto.createHash('sha256').update(text.slice(separator + 1)).digest();
    return crypto.timingSafeEqual(expected, actual);
  }

  async function streamAudio(req, res, id) {
    const record = (await store.read()).audio.find(item => item.id === id);
    if (!record) return notFound(res);
    const file = path.join(store.uploads, safeFile(record.storedName));
    try { await streamFile(req, res, file, record.type || 'audio/mpeg'); }
    catch (cause) { if (cause.code === 'ENOENT') return notFound(res); throw cause; }
  }

  async function uploadAudio(req, res) {
    const type = req.headers['content-type'] || '';
    if (!type.startsWith('multipart/form-data;')) throw error(400, '请使用 multipart/form-data 上传音频');
    const raw = await readBody(req, options.uploadLimit || 128 * 1024 * 1024);
    let form;
    try { form = await new Response(raw, { headers: { 'Content-Type': type } }).formData(); }
    catch { throw error(400, '上传表单格式不正确'); }
    const file = form.get('file');
    if (!file || typeof file.arrayBuffer !== 'function') throw error(400, '缺少音频文件');
    const ext = path.extname(file.name).toLowerCase();
    if (!MIME[ext]) throw error(400, '支持 MP3、FLAC、WAV、M4A、AAC、OGG、OPUS、MP4、WebM');
    const data = Buffer.from(await file.arrayBuffer());
    if (!data.length) throw error(400, '不能上传空音频');
    const id = crypto.randomUUID();
    const record = { id, title: String(form.get('title') || file.name.replace(/\.[^.]+$/, '')), originalName: file.name,
      storedName: id + ext, type: MIME[ext], size: data.length, createdAt: new Date().toISOString(), url: `/api/audio/${id}/open` };
    await store.mutate(async db => {
      await fsp.writeFile(path.join(store.uploads, record.storedName), data, { flag: 'wx' });
      db.audio.unshift(record);
    });
    json(res, 201, record);
  }

  async function route(req, res, url) {
    const method = req.method;
    const pathname = url.pathname;
    if (pathname === '/api/health' && method === 'GET') {
      const db = await store.read();
      return json(res, 200, { ok: true, app: 'mkusic', version: VERSION, tracks: db.playlist.length, audio: db.audio.length });
    }
    if (pathname === '/api/playlist' && method === 'GET') return json(res, 200, (await store.read()).playlist);
    if (pathname === '/api/playlist' && method === 'PUT') {
      const playlist = normalizePlaylist((await readJson(req)).playlist);
      await store.mutate(db => { db.playlist = playlist; });
      await bili.cache.reconcile((await store.read()).playlist);
      return json(res, 200, playlist);
    }
    if (pathname === '/api/playlist/metadata' && method === 'POST') {
      const body = await readJson(req);
      if (!Array.isArray(body.ids) || body.ids.length > 5 || body.ids.some(id => typeof id !== 'string')) throw error(400, '每次最多补全 5 首歌曲');
      const ids = new Set(body.ids);
      const targets = (await store.read()).playlist.filter(track => ids.has(track.id) && needsMetadata(track));
      const results = [], failed = [];
      // Bound upstream concurrency; do network work outside the database write queue.
      for (let i = 0; i < targets.length; i += 2) {
        await Promise.all(targets.slice(i, i + 2).map(async track => {
          try {
            const meta = await bili.getMeta({ bvid: track.bvid, aid: track.aid, cid: track.cid, sid: track.kind === 'biliaudio' ? track.sid || track.aid : undefined });
            if (!meta.title || needsMetadata({ ...track, title: meta.title })) throw new Error('B站暂未返回歌曲名称');
            results.push({ track, meta });
          } catch (cause) { failed.push({ id: track.id, error: cause.message || '获取歌名失败' }); }
        }));
      }
      let updated = 0;
      const identity = track => JSON.stringify([track.kind, track.bvid, track.aid, track.cid, track.sid]);
      if (results.length) await store.mutate(db => {
        for (const { track: snapshot, meta } of results) {
          const track = db.playlist.find(item => item.id === snapshot.id);
          // Preserve edits/removals made while an upstream request was in flight.
          if (!track || !needsMetadata(track) || identity(track) !== identity(snapshot)) continue;
          track.title = String(meta.title);
          if (!track.artist || track.artist === 'Bilibili') track.artist = meta.artist || track.artist || '';
          if (!track.cover && meta.cover) track.cover = meta.cover;
          if (!track.bvid && meta.bvid) track.bvid = meta.bvid;
          if (!track.cid && meta.cid) track.cid = String(meta.cid);
          updated++;
        }
      });
      return json(res, 200, { playlist: (await store.read()).playlist, updated, failed });
    }
    if (pathname === '/api/cache' && method === 'GET') return json(res, 200, bili.cache.status((await store.read()).playlist));
    if (pathname === '/api/cache/warm' && method === 'POST') {
      const body = await readJson(req);
      if (body.ids != null && (!Array.isArray(body.ids) || body.ids.some(id => typeof id !== 'string'))) throw error(400, 'ids 必须是歌曲 ID 数组');
      const tracks = (await store.read()).playlist.filter(track => bili.trackSource(track) && (!body.ids || body.ids.includes(track.id)));
      const job = bili.cache.warm(tracks);
      return json(res, 202, { ...job });
    }
    if (pathname === '/api/audio/upload' && method === 'POST') return uploadAudio(req, res);
    const audio = /^\/api\/audio\/([^/]+)(\/open)?$/.exec(pathname);
    if (audio && audio[2] && ['GET', 'HEAD'].includes(method)) return streamAudio(req, res, decodeURIComponent(audio[1]));
    if (audio && !audio[2] && method === 'DELETE') {
      const id = decodeURIComponent(audio[1]);
      await store.mutate(async db => {
        const item = db.audio.find(record => record.id === id);
        if (item) await fsp.rm(path.join(store.uploads, safeFile(item.storedName)), { force: true });
        db.audio = db.audio.filter(record => record.id !== id);
        db.playlist = db.playlist.filter(track => !(track.kind === 'local' && (track.audioId === id || track.blobId === id || track.url === `/api/audio/${id}/open`)));
      });
      return json(res, 200, { ok: true });
    }
    const biliAction = /^\/api\/bilibili\/(fav|resolve|meta|audio|cover)$/.exec(pathname)?.[1];
    if (biliAction && (method === 'GET' || biliAction === 'audio' && method === 'HEAD')) return bili[biliAction](req, res, url);
    if (pathname === '/api/export' && method === 'GET') {
      res.setHeader('Content-Disposition', 'attachment; filename="mkusic-backup.json"');
      return json(res, 200, await store.export());
    }
    if (pathname === '/api/import' && method === 'POST') {
      const result = await store.import(await readJson(req, 192 * 1024 * 1024));
      await bili.cache.reconcile((await store.read()).playlist);
      return json(res, 200, result);
    }
    if (pathname.startsWith('/api/')) return notFound(res);
    const assets = { '/': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js', 'text/javascript; charset=utf-8'], '/style.css': ['style.css', 'text/css; charset=utf-8'], '/favicon.svg': ['favicon.svg', 'image/svg+xml'] };
    if (!assets[pathname]) return notFound(res);
    if (!['GET', 'HEAD'].includes(method)) { res.setHeader('Allow', 'GET, HEAD'); return json(res, 405, { error: '不支持此请求方法' }); }
    const [name, type] = assets[pathname];
    const body = await fsp.readFile(path.join(ROOT, 'public', name));
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': body.length, 'Cache-Control': 'no-cache' });
    res.end(method === 'HEAD' ? undefined : body);
  }

  const handler = async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    try {
      if (!authorized(req)) {
        res.setHeader('WWW-Authenticate', 'Basic realm="Mkusic", charset="UTF-8"');
        return json(res, 401, { error: '请输入访问密码，用户名留空' });
      }
      const url = new URL(req.url, 'http://localhost');
      const origin = req.headers.origin;
      if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) && origin && new URL(origin).host !== req.headers.host) throw error(403, '不允许跨站修改音乐库');
      await route(req, res, url);
    } catch (cause) {
      if (res.destroyed) return;
      if (res.headersSent) return res.destroy(cause);
      const status = cause.status || 500;
      if (status >= 500) console.error('[music]', cause.message);
      json(res, status, { error: status === 500 ? '音乐服务发生错误，请查看服务日志' : cause.message });
    }
  };
  const server = options.tls ? https.createServer(options.tls, handler) : http.createServer(handler);
  return { server, store, cache: bili.cache };
}

async function start() {
  const envFile = path.join(ROOT, '.env');
  if (fs.existsSync(envFile)) process.loadEnvFile(envFile);
  let tls;
  if (process.env.HTTPS_CERT_FILE || process.env.HTTPS_KEY_FILE) {
    if (!process.env.HTTPS_CERT_FILE || !process.env.HTTPS_KEY_FILE) throw new Error('HTTPS_CERT_FILE 和 HTTPS_KEY_FILE 必须同时配置');
    tls = { cert: fs.readFileSync(path.resolve(ROOT, process.env.HTTPS_CERT_FILE)), key: fs.readFileSync(path.resolve(ROOT, process.env.HTTPS_KEY_FILE)) };
  }
  const { server, store, cache } = await createMusicServer({ password: process.env.ACCESS_PASSWORD,
    dataDir: path.resolve(ROOT, process.env.DATA_DIR || 'data'), cacheLimitBytes: process.env.AUDIO_CACHE_MAX_MB ? Number(process.env.AUDIO_CACHE_MAX_MB) * 1024 ** 2 : undefined, tls });
  const host = process.env.HOST || '127.0.0.1';
  const port = Number(process.env.PORT || 4066);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, resolve); });
  console.log(`Mkusic ${VERSION}: ${tls ? 'https' : 'http'}://${host}:${server.address().port}`);
  console.log('用户名留空；访问密码请查看 .env 的 ACCESS_PASSWORD 或部署环境变量。');
  const shutdown = () => {
    cache.close().catch(() => {});
    server.close(async () => { await store.queue; await cache.close(); process.exit(0); });
    setTimeout(() => process.exit(1), 8_000).unref();
  };
  process.once('SIGINT', shutdown); process.once('SIGTERM', shutdown);
}

if (require.main === module) start().catch(cause => { console.error(cause.message); process.exitCode = 1; });
module.exports = { createMusicServer, parseRange };
