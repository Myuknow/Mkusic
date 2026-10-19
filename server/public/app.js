'use strict';
const $ = id => document.getElementById(id);
const audio = $('audio');
const historyKey = 'mkusic.history';
const modeKey = 'mkusic.mode';
const modes = ['none', 'all', 'one', 'random'];
const modeNames = { none: '顺序播放', all: '列表循环', one: '单曲循环', random: '随机循环' };
let playlist = [], selected = new Set(), currentId = '', order = [], busy = false, loadGeneration = 0, noticeTimer;
let metadataJob = null, metadataBatch = null;
let serverCache = null, cacheTimer;
let history = [], mode = 'none';
try { history = JSON.parse(localStorage.getItem(historyKey) || '[]'); mode = localStorage.getItem(modeKey) || 'none'; } catch {}
if (!Array.isArray(history)) history = [];
if (!modes.includes(mode)) mode = 'none';

function notice(message) {
  $('notice').textContent = message; $('notice').hidden = false;
  clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { $('notice').hidden = true; }, 6500);
}
async function api(url, options = {}) {
  const response = await fetch(url, options);
  const value = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(value.error || `请求失败（${response.status}）`);
  return value;
}
function run(action) {
  return async event => {
    event?.preventDefault();
    if (busy) return;
    busy = true; document.body.setAttribute('aria-busy', 'true');
    try { if (metadataBatch) await metadataBatch.catch(() => {}); await action(event); } catch (error) { notice(error.message || '操作失败'); }
    finally { busy = false; document.body.removeAttribute('aria-busy'); fillMissingMetadata(); }
  };
}
const title = track => track.title || track.filename || '未命名歌曲';
const group = track => track.group || ({ local: '本地音乐', bilibili: 'B站', biliaudio: 'B站', url: '网络音频' }[track.kind]) || '其他';
const sourceName = track => ({ local: '服务器音频', bilibili: 'B站视频', biliaudio: 'B站音频', url: '网络音频' }[track.kind]);
const key = track => [track.kind, track.bvid || track.aid || track.sid || track.audioId || track.blobId || track.url, track.cid || ''].join(':');
const needsMetadata = track => ['bilibili', 'biliaudio'].includes(track.kind) &&
  (!String(track.title || '').trim() || /^(?:B站(?:视频音频|视频|音频)?\s*[·:：-]?\s*)?(?:BV[0-9a-z]{10}|av\d+|au\d+)$/i.test(String(track.title).trim()));
function fillMissingMetadata() {
  if (metadataJob || busy || !playlist.some(needsMetadata)) return metadataJob;
  const button = $('loadTitles');
  button.disabled = true;
  metadataJob = (async () => {
    const attempted = new Set();
    let updated = 0, failed = 0;
    try {
      while (true) {
        if (busy) { await new Promise(resolve => setTimeout(resolve, 200)); continue; }
        const batch = playlist.filter(track => needsMetadata(track) && !attempted.has(track.id)).slice(0, 5);
        if (!batch.length) break;
        batch.forEach(track => attempted.add(track.id));
        button.textContent = `加载歌名 ${attempted.size}…`;
        metadataBatch = api('/api/playlist/metadata', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids: batch.map(track => track.id) }) });
        const result = await metadataBatch;
        metadataBatch = null;
        playlist = result.playlist;
        updated += result.updated; failed += result.failed.length;
        syncOrder(); render();
        const current = playlist.find(track => track.id === currentId);
        if (current) showTrack(current);
        history = history.map(item => { const track = playlist.find(track => track.id === item.id); return track ? { ...item, title: title(track) } : item; });
        try { localStorage.setItem(historyKey, JSON.stringify(history)); } catch {}
        renderHistory();
        await new Promise(resolve => setTimeout(resolve, 350));
      }
      if (failed) notice(`已加载 ${updated} 首歌名，${failed} 首暂未获取，可点击“加载歌名”重试`);
      else if (updated) notice(`已加载 ${updated} 首歌名`);
    } catch (error) { notice(`歌名加载暂未完成：${error.message}`); }
    finally { metadataBatch = null; metadataJob = null; button.disabled = false; button.textContent = '加载歌名'; }
  })();
  return metadataJob;
}
const visibleTracks = () => {
  const search = $('search').value.trim().toLocaleLowerCase();
  return playlist.filter(track => (!$('groupFilter').value || group(track) === $('groupFilter').value) &&
    [title(track), track.artist, group(track), track.bvid, track.aid, track.sid, track.url].filter(Boolean).join(' ').toLocaleLowerCase().includes(search));
};
function cover(track) {
  if (!track?.cover) return '/favicon.svg';
  try {
    const url = new URL(track.cover, location.origin);
    if (/(^|\.)(hdslb|bilibili)\.com$/i.test(url.hostname)) {
      url.protocol = 'https:'; url.port = '';
      return '/api/bilibili/cover?url=' + encodeURIComponent(url.href);
    }
    if (url.protocol === 'https:' || url.origin === location.origin) return url.href;
  } catch {}
  return '/favicon.svg';
}
function imageFor(track, className) {
  const image = document.createElement('img'); image.src = cover(track); image.alt = ''; image.className = className;
  image.loading = 'lazy'; image.onerror = () => { image.onerror = null; image.src = '/favicon.svg'; };
  return image;
}
function syncOrder() {
  const ids = playlist.map(track => track.id);
  order = order.filter(id => ids.includes(id));
  const added = ids.filter(id => !order.includes(id));
  if (mode === 'random') {
    for (let i = added.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [added[i], added[j]] = [added[j], added[i]]; }
    order.push(...added);
  } else order = ids;
}
function adjacent(delta) {
  const index = order.indexOf(currentId);
  if (index < 0) return order[0];
  const candidate = index + delta;
  if (candidate >= 0 && candidate < order.length) return order[candidate];
  if (mode === 'all' || mode === 'random') return order[(candidate + order.length) % order.length];
  return null;
}
function renderControls() {
  $('play').disabled = !playlist.length;
  $('previous').disabled = !currentId || (!adjacent(-1) && audio.currentTime < 3);
  $('next').disabled = !currentId || !adjacent(1);
  $('mode').textContent = modeNames[mode];
}
function renderSelection() {
  selected = new Set([...selected].filter(id => playlist.some(track => track.id === id)));
  const visible = visibleTracks();
  $('selectedCount').textContent = `已选 ${selected.size} 首`;
  for (const id of ['groupSelected', 'pinSelected', 'deleteSelected']) $(id).disabled = !selected.size;
  $('selectAll').checked = !!visible.length && visible.every(track => selected.has(track.id));
  $('selectAll').indeterminate = visible.some(track => selected.has(track.id)) && !$('selectAll').checked;
  $('cacheSongs').textContent = selected.size ? '缓存所选' : '缓存全部';
}
function render() {
  const filter = $('groupFilter').value;
  $('groupFilter').replaceChildren(new Option('全部分组', ''), ...[...new Set(playlist.map(group))].sort().map(name => new Option(name, name)));
  $('groupFilter').value = [...$('groupFilter').options].some(option => option.value === filter) ? filter : '';
  const visible = visibleTracks();
  $('trackCount').textContent = `${playlist.length} 首`;
  $('playlist').replaceChildren();
  $('emptyState').hidden = !!playlist.length;
  if (playlist.length && !visible.length) { const hint = document.createElement('p'); hint.className = 'empty muted'; hint.textContent = '没有找到匹配的音乐'; $('playlist').append(hint); }
  for (const track of visible) {
    const row = document.createElement('div'); row.className = 'track' + (track.id === currentId ? ' active' : '');
    const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.checked = selected.has(track.id);
    checkbox.setAttribute('aria-label', '选择 ' + title(track));
    checkbox.onchange = () => { if (checkbox.checked) selected.add(track.id); else selected.delete(track.id); renderSelection(); };
    const main = document.createElement('button'); main.className = 'track-main'; main.setAttribute('aria-label', '播放 ' + title(track));
    const heading = document.createElement('span'); heading.className = 'track-title'; heading.textContent = title(track);
    const detail = document.createElement('span'); detail.className = 'track-detail'; detail.dataset.trackId = track.id; detail.dataset.description = `${track.artist || sourceName(track)} · ${group(track)}`; detail.textContent = detail.dataset.description;
    main.append(heading, detail); main.onclick = () => loadTrack(track.id, true).catch(error => notice(error.message));
    const source = document.createElement('span'); source.className = 'source'; source.textContent = sourceName(track);
    const edit = document.createElement('button'); edit.className = 'track-edit'; edit.textContent = '编辑'; edit.setAttribute('aria-label', '编辑 ' + title(track));
    edit.onclick = run(async () => {
      const name = prompt('歌曲名称', title(track)); if (name === null) return;
      const artist = prompt('歌手 / UP主', track.artist || ''); if (artist === null) return;
      await persist(playlist.map(item => item.id === track.id ? { ...item, title: name.trim() || title(track), artist: artist.trim() } : item));
      if (currentId === track.id) showTrack(playlist.find(item => item.id === track.id));
    });
    row.append(checkbox, imageFor(track, 'track-cover'), main, source, edit); $('playlist').append(row);
  }
  renderSelection(); renderControls(); renderHistory(); renderCache();
}
function renderCache() {
  if (!serverCache) return;
  const mb = bytes => (bytes / 1024 ** 2).toFixed(1);
  const job = serverCache.job;
  let message = `已缓存 ${serverCache.count} 首 · ${mb(serverCache.bytes)} / ${mb(serverCache.limitBytes)} MB`;
  if (job?.running) message += ` · 处理中 ${job.done}/${job.total}：${job.current || '准备中'}`;
  else if (serverCache.active) message += ` · ${serverCache.active} 首正在缓存`;
  else if (job?.failed) message += ` · ${job.failed} 首未成功，可点击重试`;
  else message += ' · 首次播放自动缓存，也可提前缓存歌曲';
  $('cacheStatus').textContent = message;
  $('cacheSongs').disabled = !!job?.running || !playlist.some(track => ['bilibili', 'biliaudio'].includes(track.kind));
  const states = new Map(serverCache.tracks.map(track => [track.id, track.status]));
  const labels = { cached: '已缓存', downloading: '缓存中', queued: '等待缓存', failed: '缓存未成功' };
  for (const detail of document.querySelectorAll('.track-detail')) {
    const label = labels[states.get(detail.dataset.trackId)];
    detail.textContent = detail.dataset.description + (label ? ' · ' + label : '');
  }
}
async function refreshCache() {
  clearTimeout(cacheTimer);
  try { serverCache = await api('/api/cache'); renderCache(); }
  catch { $('cacheStatus').textContent = '暂时无法读取缓存状态'; }
  cacheTimer = setTimeout(refreshCache, serverCache?.active || serverCache?.job?.running ? 1500 : 10_000);
}
$('cacheSongs').onclick = run(async () => {
  const ids = playlist.filter(track => ['bilibili', 'biliaudio'].includes(track.kind) && (!selected.size || selected.has(track.id))).map(track => track.id);
  if (!ids.length) return notice('请选择 B站歌曲进行缓存');
  await api('/api/cache/warm', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids }) });
  await refreshCache(); notice('已开始缓存，关闭网页后服务器仍会继续');
});
async function persist(next) {
  playlist = await api('/api/playlist', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ playlist: next }) });
  syncOrder(); render();
  await refreshCache();
}
function showTrack(track) {
  $('trackTitle').textContent = title(track); $('trackArtist').textContent = track.artist || group(track);
  $('albumCover').src = cover(track); $('albumCover').hidden = false;
  $('albumCover').onerror = () => { $('albumCover').hidden = true; };
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = new MediaMetadata({ title: title(track), artist: track.artist || group(track), artwork: [{ src: new URL(cover(track), location.origin).href }] });
}
function resetPlayer(message) {
  ++loadGeneration; currentId = ''; audio.pause(); audio.removeAttribute('src'); audio.load();
  $('trackTitle').textContent = '选择一首歌曲'; $('trackArtist').textContent = '';
  $('albumCover').hidden = true; $('retry').hidden = true; $('progress').value = 0; $('progress').disabled = true;
  $('currentTime').textContent = '0:00'; $('duration').textContent = '0:00'; $('playerStatus').textContent = message;
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = null;
  renderControls();
}
function audioUrl(track) {
  const params = new URLSearchParams();
  if (track.kind === 'bilibili') { if (track.bvid) params.set('bvid', track.bvid); else if (track.aid) params.set('aid', track.aid); else throw new Error('缺少 B站视频编号'); if (track.cid) params.set('cid', track.cid); return '/api/bilibili/audio?' + params; }
  if (track.kind === 'biliaudio') { params.set('sid', track.sid); return '/api/bilibili/audio?' + params; }
  if (track.kind === 'local') return '/api/audio/' + encodeURIComponent(track.audioId || track.blobId || /\/api\/audio\/([^/]+)\/open/.exec(track.url || '')?.[1] || '') + '/open';
  const url = new URL(track.url); if (url.protocol !== 'https:') throw new Error('网络音频请使用 HTTPS 链接'); return url.href;
}
async function loadTrack(id, autoplay = false) {
  const track = playlist.find(item => item.id === id); if (!track) return;
  const generation = ++loadGeneration;
  audio.pause(); currentId = id; $('retry').hidden = true; $('playerStatus').textContent = '正在载入…';
  showTrack(track); render();
  try {
    let source = audioUrl(track);
    if (track.kind === 'bilibili' && !track.bvid && track.aid) {
      const params = new URLSearchParams({ aid: track.aid }); if (track.cid) params.set('cid', track.cid);
      const resolved = await api('/api/bilibili/resolve?' + params);
      const url = new URL(resolved.audioUrl, location.origin);
      if (url.origin !== location.origin || url.pathname !== '/api/bilibili/audio') throw new Error('音频解析地址无效');
      source = url.href;
    }
    if (generation !== loadGeneration) return;
    audio.src = source; audio.load(); $('playerStatus').textContent = autoplay ? '正在缓冲…' : '已准备，点击播放。';
    if (autoplay) await audio.play();
  } catch (error) {
    if (generation !== loadGeneration) return;
    $('playerStatus').textContent = error.name === 'NotAllowedError' ? '浏览器需要你点击播放按钮。' : error.message;
    $('retry').hidden = false;
  }
}
function renderHistory() {
  $('historyCount').textContent = history.length;
  $('historyList').replaceChildren();
  for (const item of history.slice(0, 12)) {
    const row = document.createElement('button'); row.className = 'history-row';
    const name = document.createElement('span'); name.textContent = item.title;
    const when = document.createElement('small'); when.textContent = new Date(item.at).toLocaleDateString('zh-CN');
    row.append(name, when); row.onclick = () => loadTrack(item.id, true).catch(error => notice(error.message)); row.disabled = !playlist.some(track => track.id === item.id);
    $('historyList').append(row);
  }
}
function parseBilibili(value) {
  const bvid = /BV[0-9A-Za-z]{10}/i.exec(value)?.[0];
  const sid = /(?:^au|\/audio\/au)(\d+)/i.exec(value)?.[1];
  const aid = /(?:^av|\/av)(\d+)/i.exec(value)?.[1];
  if (bvid) return { kind: 'bilibili', bvid };
  if (sid) return { kind: 'biliaudio', sid };
  if (aid) return { kind: 'bilibili', aid };
  return null;
}

for (const id of ['openAdd', 'emptyAdd']) $(id).onclick = () => $('addDialog').showModal();
for (const button of document.querySelectorAll('[data-close]')) button.onclick = () => $(button.dataset.close).close();
$('search').oninput = render; $('groupFilter').onchange = render;
$('refresh').onclick = run(async () => { playlist = await api('/api/playlist'); syncOrder(); render(); notice('音乐库已刷新'); });
$('loadTitles').onclick = () => fillMissingMetadata();
$('selectAll').onchange = () => { for (const track of visibleTracks()) { if ($('selectAll').checked) selected.add(track.id); else selected.delete(track.id); } render(); };
$('groupSelected').onclick = () => { $('groupDialog').showModal(); $('groupName').focus(); };
$('groupForm').onsubmit = run(async () => { await persist(playlist.map(track => selected.has(track.id) ? { ...track, group: $('groupName').value.trim() } : track)); $('groupDialog').close(); });
$('pinSelected').onclick = run(() => persist([...playlist.filter(track => selected.has(track.id)), ...playlist.filter(track => !selected.has(track.id))]));
$('deleteSelected').onclick = run(async () => {
  if (!confirm(`从歌单删除选中的 ${selected.size} 首音乐？上传文件暂不删除，可随音乐备份导出。`)) return;
  const removedCurrent = selected.has(currentId);
  await persist(playlist.filter(track => !selected.has(track.id)));
  if (removedCurrent) resetPlayer('当前歌曲已从歌单删除。');
  selected.clear(); render();
});
$('addForm').onsubmit = run(async () => {
  const value = $('trackUrl').value.trim(); const bili = parseBilibili(value);
  let track = { id: crypto.randomUUID(), kind: 'url', url: value, title: $('newTitle').value.trim() || value, group: $('newGroup').value.trim() };
  if (bili) {
    const params = new URLSearchParams(Object.entries(bili).filter(([key]) => key !== 'kind'));
    const meta = await api('/api/bilibili/meta?' + params);
    track = { ...track, ...bili, ...meta, title: $('newTitle').value.trim() || meta.title };
  } else {
    const url = new URL(value); if (url.protocol !== 'https:' || url.username || url.password) throw new Error('请填写 HTTPS 音频链接，或完整 BV / av / au 编号');
    track.url = url.href;
  }
  if (playlist.some(item => key(item) === key(track))) throw new Error('这首音乐已在音乐库中');
  await persist([...playlist, track]); $('addForm').reset(); $('addDialog').close(); notice('音乐已添加');
});
$('favForm').onsubmit = run(async () => {
  notice('正在读取公开收藏夹…');
  const result = await api('/api/bilibili/fav?fid=' + encodeURIComponent($('favUrl').value.trim()));
  const existing = new Set(playlist.map(key));
  const incoming = result.tracks.filter(track => { const value = key(track); if (existing.has(value)) return false; existing.add(value); return true; })
    .map(track => ({ ...track, group: $('favGroup').value.trim() || result.title }));
  await persist([...playlist, ...incoming]); $('favForm').reset(); $('addDialog').close(); notice(`已导入 ${incoming.length} 首，重复曲目已跳过`);
});
$('upload').onchange = run(async () => {
  let count = 0;
  for (const file of $('upload').files) {
    notice(`正在上传 ${file.name}…`);
    const form = new FormData(); form.append('file', file);
    const record = await api('/api/audio/upload', { method: 'POST', body: form });
    await persist([...playlist, { id: crypto.randomUUID(), kind: 'local', audioId: record.id, title: record.title, filename: record.originalName, url: record.url, artist: '本地音频', group: '本地音乐' }]); count++;
  }
  $('upload').value = ''; $('addDialog').close(); notice(`已上传 ${count} 首音频`);
});
$('play').onclick = async () => {
  if (!currentId) return loadTrack(playlist[0]?.id, true);
  if (audio.paused) { if (audio.error) return loadTrack(currentId, true); try { await audio.play(); } catch (error) { notice(error.message); } } else audio.pause();
};
$('retry').onclick = () => loadTrack(currentId, true);
$('next').onclick = () => { const id = adjacent(1); if (id) loadTrack(id, true); };
$('previous').onclick = () => { if (audio.currentTime > 3) audio.currentTime = 0; else { const id = adjacent(-1); if (id) loadTrack(id, true); } };
$('mode').onclick = () => { mode = modes[(modes.indexOf(mode) + 1) % modes.length]; order = []; syncOrder(); try { localStorage.setItem(modeKey, mode); } catch {} renderControls(); };
$('volume').oninput = () => { audio.volume = Number($('volume').value); };
$('progress').oninput = () => { if (Number.isFinite(audio.duration)) audio.currentTime = audio.duration * Number($('progress').value) / 1000; };
const time = value => Number.isFinite(value) ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}` : '0:00';
audio.onloadedmetadata = () => { $('duration').textContent = time(audio.duration); $('progress').disabled = !Number.isFinite(audio.duration); };
audio.ontimeupdate = () => { $('currentTime').textContent = time(audio.currentTime); $('progress').value = Number.isFinite(audio.duration) ? audio.currentTime / audio.duration * 1000 : 0; renderControls(); };
audio.onplaying = () => { $('play').textContent = 'Ⅱ'; $('play').setAttribute('aria-label', '暂停'); $('playerStatus').textContent = '正在播放'; $('retry').hidden = true;
  const track = playlist.find(item => item.id === currentId);
  if (track) { history = [{ id: track.id, title: title(track), at: Date.now() }, ...history.filter(item => item.id !== track.id)].slice(0, 50); try { localStorage.setItem(historyKey, JSON.stringify(history)); } catch {} renderHistory(); }
};
audio.onpause = () => { $('play').textContent = '▶'; $('play').setAttribute('aria-label', '播放'); if (!audio.error && currentId) $('playerStatus').textContent = '已暂停'; };
audio.onwaiting = () => { $('playerStatus').textContent = '正在缓冲…'; };
audio.onerror = () => { if (!currentId) return; $('playerStatus').textContent = '音频加载失败，请重试或切歌。B站音源可能需要稍后再试。'; $('retry').hidden = false; };
audio.onended = () => { if (mode === 'one') { audio.currentTime = 0; audio.play().catch(error => notice(error.message)); } else { const id = adjacent(1); if (id) loadTrack(id, true); else $('playerStatus').textContent = '已播放到队列末尾'; } };
if ('mediaSession' in navigator) {
  for (const [name, action] of [['play', () => $('play').click()], ['pause', () => audio.pause()], ['nexttrack', () => $('next').click()], ['previoustrack', () => $('previous').click()]]) {
    try { navigator.mediaSession.setActionHandler(name, action); } catch {}
  }
}
$('connectApp').onclick = () => { $('serverAddress').value = location.origin + '/'; $('appDialog').showModal(); };
$('copyAddress').onclick = run(async () => { await navigator.clipboard.writeText($('serverAddress').value); notice('服务器地址已复制'); });
$('export').onclick = run(async () => {
  const value = await api('/api/export');
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = 'mkusic-backup.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$('importFile').onchange = run(async () => {
  const file = $('importFile').files[0]; if (!file) return;
  if (!confirm('导入会替换当前音乐库，服务器会先保留当前库的备份。继续？')) { $('importFile').value = ''; return; }
  const value = JSON.parse(await file.text());
  await api('/api/import', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
  resetPlayer('音乐库已恢复，请选择一首歌曲。');
  playlist = await api('/api/playlist'); order = []; selected.clear(); syncOrder(); render(); $('importFile').value = ''; notice('音乐备份已导入');
});

(async () => {
  try { playlist = await api('/api/playlist'); syncOrder(); render(); $('connectionStatus').textContent = '服务已连接'; fillMissingMetadata(); }
  catch (error) { $('connectionStatus').textContent = '连接失败'; notice(error.message); }
  audio.volume = Number($('volume').value);
  refreshCache();
})();
