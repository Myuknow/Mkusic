// Music-specific Bilibili functions extracted from workstation-dashboard/server.js.
const crypto = require('node:crypto');
const { AudioCache, trackSource } = require('./audio-cache');
const BILI_HEADERS = { 'User-Agent': 'Mozilla/5.0 WorkstationMusic/1.0', Referer: 'https://www.bilibili.com/' };
function needsMetadata(track) {
  return ['bilibili', 'biliaudio'].includes(track.kind) &&
    (!String(track.title || '').trim() || /^(?:B站(?:视频音频|视频|音频)?\s*[·:：-]?\s*)?(?:BV[0-9a-z]{10}|av\d+|au\d+)$/i.test(String(track.title).trim()));
}
function createBilibili({ fetchImpl = globalThis.fetch, json, notFound, cacheDir, cacheLimitBytes }) {
  const coverMemoryCache = new Map();
  const fetch = (url, options = {}) => fetchImpl(url, { redirect: 'error', ...options, signal: options.signal || AbortSignal.timeout(20_000) });
  const cache = cacheDir ? new AudioCache({ directory: cacheDir, resolveSource: loadAudioSource, fetchImpl, headers: BILI_HEADERS, limitBytes: cacheLimitBytes }) : null;
  const ready = cache ? cache.init() : Promise.resolve();
async function handleBiliFav(req, res, url) {
  const raw = url.searchParams.get("fid") || url.searchParams.get("url") || "";
  const fid = /fid=(\d+)/.exec(raw)?.[1] || /ml(\d+)/.exec(raw)?.[1] || /^\d+$/.test(raw) && raw;
  if (!fid) return json(res, 400, { error: "请输入 fid、media_id 或收藏夹链接" });
  const api = `https://api.bilibili.com/x/v3/fav/resource/ids?media_id=${encodeURIComponent(fid)}`;
  const resp = await fetch(api, {
    headers: BILI_HEADERS
  });
  const data = await resp.json();
  if (data.code !== 0 || !Array.isArray(data.data)) {
    return json(res, 502, { error: data.message || "B站收藏夹读取失败，可能是私密收藏夹或需要登录 Cookie" });
  }
  let title = `B站收藏夹 ${fid}`;
  try {
    const infoResp = await fetch(`https://api.bilibili.com/x/v3/fav/folder/info?media_id=${encodeURIComponent(fid)}`, { headers: BILI_HEADERS });
    const info = await infoResp.json();
    if (info.code === 0 && info.data?.title) title = info.data.title;
  } catch {}
  const tracks = data.data
    .filter(item => item.type === 2 || item.type === 12)
    .map(item => ({
      id: crypto.randomUUID(),
      kind: item.type === 12 ? "biliaudio" : "bilibili",
      bvid: item.bvid || item.bv_id || "",
      sid: item.type === 12 ? String(item.id) : "",
      aid: item.type !== 12 ? String(item.id) : "",
      title: `B站 · ${item.bvid || item.bv_id || `av${item.id}`}`,
      artist: "Bilibili",
      playable: Boolean(item.bvid || item.bv_id || item.id),
      url: item.bvid || item.bv_id ? `https://www.bilibili.com/video/${item.bvid || item.bv_id}` : `https://www.bilibili.com/audio/au${item.id}`
    }));
  json(res, 200, { fid, title, count: tracks.length, tracks });
}

async function fetchBiliJson(url) {
  const resp = await fetch(url, { headers: BILI_HEADERS });
  const data = await resp.json();
  if (data.code !== 0) throw new Error(data.message || "B站接口返回错误");
  return data.data;
}

function bestAudioFromPlayData(data) {
  const pickBest = list => [...(Array.isArray(list) ? list : [list])].sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0))[0];
  if (data?.flac?.audio) return pickBest(data.flac.audio).baseUrl || pickBest(data.flac.audio).base_url;
  if (data?.dolby?.audio?.length) return pickBest(data.dolby.audio).baseUrl || pickBest(data.dolby.audio).base_url;
  if (data?.dash?.audio?.length) return pickBest(data.dash.audio).baseUrl || pickBest(data.dash.audio).base_url;
  throw new Error("没有找到独立音频流，未使用视频流回退");
}

async function resolveBiliVideo(bvid, cid, aid) {
  if (!bvid && !aid) throw new Error("缺少 BVID 或 AVID");
  const viewUrl = bvid
    ? `https://api.bilibili.com/x/web-interface/view?bvid=${encodeURIComponent(bvid)}`
    : `https://api.bilibili.com/x/web-interface/view?aid=${encodeURIComponent(aid)}`;
  const view = await fetchBiliJson(viewUrl);
  bvid = view.bvid || bvid;
  const page = cid ? view.pages.find(item => String(item.cid) === String(cid)) : view.pages[0];
  if (!page) throw new Error("没有找到对应分P");
  const play = await fetchBiliJson(`https://api.bilibili.com/x/player/playurl?cid=${encodeURIComponent(page.cid)}&bvid=${encodeURIComponent(bvid)}&qn=64&fnval=16&try_look=1&voice_balance=1`);
  return {
    kind: "bilibili",
    bvid,
    cid: String(page.cid),
    title: view.pages.length > 1 ? `${view.title} - ${page.part}` : view.title,
    artist: view.owner?.name || "Bilibili",
    cover: view.pic || "",
    duration: page.duration || view.duration || 0,
    audioUrl: `/api/bilibili/audio?bvid=${encodeURIComponent(bvid)}&cid=${encodeURIComponent(page.cid)}`,
    sourceUrl: `https://www.bilibili.com/video/${bvid}${page.page ? `?p=${page.page}` : ""}`,
    cacheAliases: [bvid && { bvid, cid: String(page.cid) }, (view.aid || aid) && { aid: String(view.aid || aid), cid: String(page.cid) },
      ...(page === view.pages[0] ? [bvid && { bvid }, (view.aid || aid) && { aid: String(view.aid || aid) }] : [])].filter(Boolean),
    directAudioUrl: bestAudioFromPlayData(play)
  };
}

async function resolveBiliAudio(sid) {
  if (!sid) throw new Error("缺少音频 sid");
  const info = await fetchBiliJson(`https://www.bilibili.com/audio/music-service-c/web/song/info?sid=${encodeURIComponent(sid)}`);
  return {
    kind: "biliaudio",
    sid: String(sid),
    title: info.title || `B站音频 au${sid}`,
    artist: info.uname || "Bilibili",
    cover: info.cover || "",
    duration: info.duration || 0,
    audioUrl: `/api/bilibili/audio?sid=${encodeURIComponent(sid)}`,
    sourceUrl: `https://www.bilibili.com/audio/au${sid}`
  };
}

async function loadAudioSource({ bvid, cid, sid, aid }) {
  if (sid) {
    const meta = await resolveBiliAudio(sid);
    const data = await fetchBiliJson(`https://www.bilibili.com/audio/music-service-c/web/url?sid=${encodeURIComponent(sid)}`);
    if (!data?.cdns?.[0]) throw new Error("没有找到 B站音频播放地址");
    return { url: data.cdns[0], meta, aliases: [{ sid }] };
  }
  const resolved = await resolveBiliVideo(bvid, cid, aid);
  const { directAudioUrl, cacheAliases, ...meta } = resolved;
  return { url: directAudioUrl, meta, aliases: cacheAliases };
}

async function handleBiliResolve(req, res, url) {
  try {
    await ready;
    const bvid = url.searchParams.get("bvid");
    const cid = url.searchParams.get("cid");
    const sid = url.searchParams.get("sid");
    const aid = url.searchParams.get("aid");
    const cached = cache?.lookup({ bvid, cid, sid, aid });
    const data = cached?.meta && cached.status !== 'failed' ? cached.meta : sid ? await resolveBiliAudio(sid) : await resolveBiliVideo(bvid, cid, aid);
    const { directAudioUrl, cacheAliases, ...publicData } = data;
    json(res, 200, publicData);
  } catch (error) {
    json(res, 502, { error: error.message || "B站音频解析失败" });
  }
}

async function fetchBiliMeta({ bvid, aid, cid, sid }) {
  if (sid) {
    const info = await fetchBiliJson(`https://www.bilibili.com/audio/music-service-c/web/song/info?sid=${encodeURIComponent(sid)}`);
    return {
      kind: "biliaudio",
      sid: String(sid),
      title: info.title || `B站音频 au${sid}`,
      artist: info.uname || "Bilibili",
      cover: info.cover || ""
    };
  }
  if (!bvid && !aid) throw new Error("缺少 BVID 或 AVID");
  const viewUrl = bvid
    ? `https://api.bilibili.com/x/web-interface/view?bvid=${encodeURIComponent(bvid)}`
    : `https://api.bilibili.com/x/web-interface/view?aid=${encodeURIComponent(aid)}`;
  const view = await fetchBiliJson(viewUrl);
  const page = cid ? (view.pages || []).find(item => String(item.cid) === String(cid)) : null;
  return {
    kind: "bilibili",
    bvid: view.bvid || bvid,
    cid: page ? String(page.cid) : "",
    title: page && (view.pages || []).length > 1 ? `${view.title} - ${page.part}` : view.title,
    artist: view.owner?.name || "Bilibili",
    cover: view.pic || ""
  };
}

async function handleBiliMeta(req, res, url) {
  try {
    const data = await fetchBiliMeta({
      bvid: url.searchParams.get("bvid"),
      aid: url.searchParams.get("aid"),
      cid: url.searchParams.get("cid"),
      sid: url.searchParams.get("sid")
    });
    json(res, 200, data);
  } catch (error) {
    json(res, 502, { error: error.message || "B站元数据获取失败" });
  }
}

async function handleBiliCover(req, res, url) {
  try {
    const raw = url.searchParams.get("url") || "";
    const coverUrl = new URL(raw);
    if (coverUrl.protocol !== "https:") return notFound(res);
    if (!/(\.|^)hdslb\.com$/i.test(coverUrl.hostname) && !/(\.|^)bilibili\.com$/i.test(coverUrl.hostname)) {
      return notFound(res);
    }
    const key = coverUrl.href;
    const cached = coverMemoryCache.get(key);
    if (cached && Date.now() - cached.time < 24 * 60 * 60 * 1000) {
      res.writeHead(200, { "Content-Type": cached.type, "Cache-Control": "public, max-age=86400" });
      return res.end(cached.bytes);
    }
    const resp = await fetch(key, {
      headers: {
        ...BILI_HEADERS,
        "Accept": "image/avif,image/webp,image/png,image/jpeg,image/*,*/*;q=0.8"
      }
    });
    if (!resp.ok) return notFound(res);
    const type = resp.headers.get("content-type") || "image/jpeg";
    const bytes = Buffer.from(await resp.arrayBuffer());
    if (!bytes.length || bytes.length > 8 * 1024 * 1024) return notFound(res);
    const entry = { bytes, type: type.startsWith("image/") ? type : "image/jpeg", time: Date.now() };
    coverMemoryCache.set(key, entry);
    res.writeHead(200, { "Content-Type": entry.type, "Cache-Control": "public, max-age=86400" });
    res.end(entry.bytes);
  } catch {
    notFound(res);
  }
}


async function handleBiliAudioStream(req, res, url) {
  try {
    await ready;
    await cache.serve(req, res, Object.fromEntries(['bvid', 'cid', 'sid', 'aid'].map(key => [key, url.searchParams.get(key)])));
  } catch (error) {
    if (res.destroyed) return;
    if (res.headersSent) res.destroy(error); else json(res, error.status || 502, { error: error.message || 'B站音频缓存失败' });
  }
}

return { fav: handleBiliFav, resolve: handleBiliResolve, meta: handleBiliMeta, audio: handleBiliAudioStream, cover: handleBiliCover, getMeta: fetchBiliMeta,
  cache, ready, trackSource };
}
module.exports = { createBilibili, needsMetadata };
