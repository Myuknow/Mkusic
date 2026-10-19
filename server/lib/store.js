const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');

function safeFile(name) {
  if (typeof name !== 'string' || !name || name !== path.basename(name) || /[\\/:\x00-\x1f]/.test(name) || name.startsWith('.')) {
    throw Object.assign(new Error('无效的音频文件名'), { status: 400 });
  }
  return name;
}

function normalizePlaylist(value) {
  if (!Array.isArray(value)) throw Object.assign(new Error('playlist 必须是数组'), { status: 400 });
  const ids = new Set();
  const fields = ['id', 'kind', 'title', 'artist', 'group', 'cover', 'url', 'bvid', 'aid', 'cid', 'sid', 'audioId', 'blobId', 'filename', 'failReason', 'failedAt'];
  return value.map(track => {
    if (!track || typeof track !== 'object' || !['local', 'url', 'bilibili', 'biliaudio'].includes(track.kind)) {
      throw Object.assign(new Error('歌单中有不支持的音源类型'), { status: 400 });
    }
    const item = Object.fromEntries(fields.filter(key => track[key] != null).map(key => [key, String(track[key])]));
    item.id ||= crypto.randomUUID();
    if (ids.has(item.id)) throw Object.assign(new Error('歌单中存在重复 id'), { status: 400 });
    ids.add(item.id);
    if (track.playable != null) item.playable = Boolean(track.playable);
    return item;
  });
}

class MusicStore {
  constructor(directory) {
    this.directory = directory;
    this.uploads = path.join(directory, 'uploads');
    this.file = path.join(directory, 'db.json');
    this.queue = Promise.resolve();
  }

  async init() {
    await fs.mkdir(this.uploads, { recursive: true });
    try { await fs.access(this.file); }
    catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await fs.writeFile(this.file, JSON.stringify({ playlist: [], audio: [] }, null, 2), { flag: 'wx' });
    }
    await this.read();
  }

  async read() {
    await this.queue;
    const value = JSON.parse(await fs.readFile(this.file, 'utf8'));
    if (!Array.isArray(value.playlist) || !Array.isArray(value.audio)) throw new Error('音乐数据库格式不正确，请检查 data/db.json');
    return { playlist: value.playlist, audio: value.audio };
  }

  mutate(update) {
    const operation = this.queue.then(async () => {
      const value = JSON.parse(await fs.readFile(this.file, 'utf8'));
      const result = await update(value);
      const temp = this.file + '.' + crypto.randomUUID() + '.tmp';
      try {
        await fs.writeFile(temp, JSON.stringify({ playlist: value.playlist, audio: value.audio }, null, 2));
        await fs.rename(temp, this.file);
      } finally { await fs.rm(temp, { force: true }); }
      return result;
    });
    this.queue = operation.catch(() => {});
    return operation;
  }

  async export() {
    const db = await this.read();
    if (db.audio.reduce((size, item) => size + Number(item.size || 0), 0) > 128 * 1024 * 1024) {
      throw Object.assign(new Error('音频总大小超过 JSON 备份上限，请直接备份整个 data 目录'), { status: 413 });
    }
    const uploads = await Promise.all(db.audio.map(async item => ({
      name: safeFile(item.storedName), data: (await fs.readFile(path.join(this.uploads, safeFile(item.storedName)))).toString('base64')
    })));
    return { app: 'workstation-music', version: 1, exportedAt: new Date().toISOString(), db, uploads };
  }

  async import(payload) {
    const source = payload?.db ?? payload;
    const playlist = normalizePlaylist(source?.playlist);
    const required = new Set(playlist.filter(item => item.kind === 'local').map(item => item.audioId || item.blobId || /\/api\/audio\/([^/]+)\/open/.exec(item.url || '')?.[1]).filter(Boolean));
    const audio = (Array.isArray(source.audio) ? source.audio : []).filter(item => payload.app === 'workstation-music' || required.has(item.id)).map(item => ({
      id: String(item.id), title: String(item.title || ''), originalName: String(item.originalName || ''),
      storedName: safeFile(item.storedName), type: String(item.type || 'audio/mpeg'), size: Number(item.size || 0),
      createdAt: item.createdAt || new Date().toISOString(), url: `/api/audio/${encodeURIComponent(item.id)}/open`
    }));
    if ([...required].some(id => !audio.some(item => item.id === id))) throw Object.assign(new Error('导入包缺少本地音频信息，请使用完整音乐备份或迁移脚本'), { status: 400 });
    if (payload.uploads != null && !Array.isArray(payload.uploads)) throw Object.assign(new Error('uploads 必须是数组'), { status: 400 });
    const files = new Map((payload.uploads || []).map(item => [item.name, item.data]));
    const writes = await Promise.all(audio.map(async item => {
      const encoded = files.get(item.storedName);
      if (typeof encoded !== 'string') throw Object.assign(new Error(`缺少音频文件：${item.originalName}`), { status: 400 });
      const data = Buffer.from(encoded, 'base64');
      // Imported files get new storage names, so an interrupted import cannot replace live audio.
      item.storedName = crypto.randomUUID() + path.extname(item.storedName);
      item.size = data.length;
      return { name: item.storedName, data };
    }));
    return this.mutate(async db => {
      const backup = `backup-before-import-${Date.now()}-${crypto.randomUUID()}.json`;
      await fs.writeFile(path.join(this.directory, backup), JSON.stringify(db, null, 2));
      for (const item of writes) await fs.writeFile(path.join(this.uploads, item.name), item.data, { flag: 'wx' });
      db.playlist = playlist; db.audio = audio;
      return { ok: true, tracks: playlist.length, audio: audio.length, backup };
    });
  }
}

module.exports = { MusicStore, safeFile, normalizePlaylist };
