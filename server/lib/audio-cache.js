const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { audioHeaders } = require('./audio-file');

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); promise.catch(() => {}); return { promise, resolve, reject }; };
const keyOf = source => JSON.stringify(source.sid ? ['au', String(source.sid)] : [source.bvid ? 'bv' : 'av', String(source.bvid || source.aid || ''), String(source.cid || '')]);
function trackSource(track) {
  if (track.kind === 'biliaudio' && track.sid) return { sid: track.sid };
  if (track.kind === 'bilibili' && (track.bvid || track.aid)) return { bvid: track.bvid, aid: track.aid, cid: track.cid };
  return null;
}
function mediaType(bytes) {
  if (['ftyp', 'styp'].includes(bytes.toString('ascii', 4, 8))) return 'audio/mp4';
  if (bytes.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]))) return 'audio/webm';
  if (bytes.toString('ascii', 0, 4) === 'fLaC') return 'audio/flac';
  if (bytes.toString('ascii', 0, 4) === 'OggS') return 'audio/ogg';
  if (bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WAVE') return 'audio/wav';
  if (bytes.toString('ascii', 0, 3) === 'ID3' || bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0) return 'audio/mpeg';
  return null;
}

class AudioCache {
  constructor({ directory, resolveSource, fetchImpl, headers, limitBytes = 2 * 1024 ** 3, fileLimit = 256 * 1024 ** 2 }) {
    if (!Number.isSafeInteger(limitBytes) || limitBytes < 1) throw new Error('音频缓存容量必须为正整数');
    Object.assign(this, { directory, resolveSource, fetchImpl, headers, limitBytes });
    this.fileLimit = Math.min(fileLimit, limitBytes);
    this.entries = new Map(); this.aliases = new Map(); this.pending = []; this.active = 0;
    this.pruning = Promise.resolve(); this.job = null; this.closed = false;
    this.libraryKeys = null;
  }

  async init() {
    await fs.mkdir(this.directory, { recursive: true });
    const names = await fs.readdir(this.directory);
    for (const name of names.filter(name => /^[a-f0-9]{64}\.json$/.test(name))) {
      const id = name.slice(0, -5);
      try {
        const manifest = JSON.parse(await fs.readFile(path.join(this.directory, name), 'utf8'));
        const file = path.join(this.directory, id + '.audio'), stat = await fs.stat(file);
        if (!stat.size || stat.size !== manifest.size || !Array.isArray(manifest.aliases) || !manifest.meta) throw new Error('incomplete');
        const entry = { ...manifest, id, file, written: stat.size, size: stat.size, status: 'cached', readers: 0, used: stat.mtimeMs };
        this.entries.set(id, entry);
        for (const alias of entry.aliases) this.aliases.set(alias, entry);
      } catch { await fs.rm(path.join(this.directory, name), { force: true }); }
    }
    // Only finalized files with a matching manifest count as a complete song.
    for (const name of names) if (/^[a-f0-9]{64}\.(part|audio)$/.test(name) && (name.endsWith('.part') || !this.entries.has(name.slice(0, -6)))) {
      await fs.rm(path.join(this.directory, name), { force: true });
    }
    await this.prune();
  }

  lookup(source) { return this.aliases.get(keyOf(source)); }
  notify(entry) { const old = entry.changed; entry.changed = deferred(); old?.resolve(); }
  retained(entry) { return this.libraryKeys == null || entry.aliases.some(key => this.libraryKeys.has(key)); }

  async reconcile(tracks) {
    const sources = tracks.map(trackSource).filter(Boolean);
    this.libraryKeys = new Set(sources.flatMap(source => [keyOf(source),
      ...(source.bvid && source.aid ? [keyOf({ aid: source.aid, cid: source.cid })] : [])]));
    this.libraryIds = new Set(tracks.map(track => track.id));
    await Promise.all([...this.entries.values()].filter(entry => !this.retained(entry)).map(entry => this.removeEntry(entry)));
  }

  removeEntry(entry) {
    if (entry.removing) return entry.removing;
    const wasDownloading = entry.status === 'downloading';
    const removed = Object.assign(new Error('歌曲已从音乐库删除，请刷新音乐库'), { status: 404 });
    entry.removed = true;
    entry.controller?.abort(removed);
    this.pending = this.pending.filter(item => item !== entry);
    if (entry.status === 'queued') {
      entry.status = 'removed'; entry.ready.reject(removed); entry.finished.reject(removed);
    }
    for (const res of entry.responses || []) res.destroy(removed);
    this.notify(entry);
    entry.removing = (async () => {
      if (wasDownloading) await entry.task;
      if (entry.readers) { entry.readersIdle ||= deferred(); await entry.readersIdle.promise; }
      for (const suffix of ['.part', '.audio', '.json']) await fs.rm(path.join(this.directory, entry.id + suffix), { force: true });
      if (this.entries.get(entry.id) === entry) this.entries.delete(entry.id);
      for (const alias of entry.aliases) if (this.aliases.get(alias) === entry) this.aliases.delete(alias);
    })();
    return entry.removing;
  }

  ensure(source, priority = true) {
    if (this.closed) throw new Error('音乐缓存正在关闭');
    const existing = this.lookup(source);
    if (existing?.removed) throw Object.assign(new Error('歌曲缓存正在清理，请稍后重试'), { status: 409 });
    if (existing && existing.status !== 'failed') {
      existing.used = Date.now();
      if (priority && existing.status === 'queued') { this.pending = this.pending.filter(item => item !== existing); this.pending.unshift(existing); }
      return existing;
    }
    const key = keyOf(source), id = crypto.createHash('sha256').update(key).digest('hex');
    const entry = { id, source, aliases: [key], status: 'queued', readers: 0, written: 0, size: null, used: Date.now(), ready: deferred(), finished: deferred(), changed: deferred() };
    this.entries.set(id, entry); this.aliases.set(key, entry);
    priority ? this.pending.unshift(entry) : this.pending.push(entry);
    this.pump();
    return entry;
  }

  pump() {
    while (!this.closed && this.active < 2 && this.pending.length) {
      const entry = this.pending.shift(); this.active++;
      entry.task = this.download(entry).finally(() => { this.active--; this.pump(); });
    }
  }

  async download(entry) {
    entry.status = 'downloading';
    const part = path.join(this.directory, entry.id + '.part');
    const file = path.join(this.directory, entry.id + '.audio');
    const controller = entry.controller = new AbortController();
    let output, timer, upstream;
    const deadline = () => { clearTimeout(timer); timer = setTimeout(() => controller.abort(new Error('下载音频超时')), 30_000); timer.unref(); };
    try {
      const resolved = await this.resolveSource(entry.source);
      entry.meta = resolved.meta;
      entry.aliases = [...new Set([...entry.aliases, ...(resolved.aliases || []).map(keyOf)])];
      if (entry.removed || !this.retained(entry)) throw Object.assign(new Error('歌曲已从音乐库删除，请刷新音乐库'), { status: 404 });
      for (const alias of entry.aliases) if (!this.aliases.has(alias) || this.aliases.get(alias).status === 'failed') this.aliases.set(alias, entry);
      const target = new URL(resolved.url);
      const allowed = ['bilivideo.com', 'bilivideo.cn', 'bilibili.com', 'hdslb.com', 'akamaized.net'];
      if (!allowed.some(host => target.hostname === host || target.hostname.endsWith('.' + host))) throw new Error('音频来源不是受支持的 B站 CDN');
      if (target.protocol === 'http:') target.protocol = 'https:';
      if (target.protocol !== 'https:' || target.username || target.password) throw new Error('无效的音频地址');
      deadline();
      // Always download the whole source, even when a phone asks for a byte range.
      upstream = await this.fetchImpl(target.href, { headers: { ...this.headers, 'Accept-Encoding': 'identity' }, redirect: 'error', signal: controller.signal });
      const contentRange = upstream.headers.get('content-range');
      const fullRange = /^bytes 0-(\d+)\/(\d+)$/.exec(contentRange || '');
      const fullSize = fullRange ? Number(fullRange[2]) : null;
      const isCompleteRange = fullRange && Number.isSafeInteger(fullSize) && fullSize > 0 && Number(fullRange[1]) === fullSize - 1;
      if (![200, 206].includes(upstream.status) || !upstream.body || (contentRange || upstream.status === 206) && !isCompleteRange) throw new Error(`B站完整音频下载失败（HTTP ${upstream.status}）`);
      const rawSize = upstream.headers.get('content-length');
      entry.size = rawSize == null ? fullSize : Number(rawSize);
      if (fullSize != null && entry.size !== fullSize) throw new Error('音频完整范围与长度不一致');
      if (entry.size != null && (!Number.isSafeInteger(entry.size) || entry.size < 1 || entry.size > this.fileLimit)) throw new Error('音频文件超过缓存单曲容量限制，或长度无效');
      entry.type = upstream.headers.get('content-type')?.split(';')[0].trim().toLowerCase() || 'application/octet-stream';
      if (/^video\/(mp4|webm)$/i.test(entry.type)) entry.type = entry.type.replace(/^video/i, 'audio');
      if (!/^(audio\/|application\/octet-stream$)/i.test(entry.type)) entry.type = null;
      output = await fs.open(part, 'w'); entry.file = part;
      if (entry.type) entry.ready.resolve(entry);
      let probe = Buffer.alloc(0);
      for await (let bytes of upstream.body) {
        deadline();
        if (!entry.type) {
          probe = Buffer.concat([probe, bytes]);
          if (probe.length < 12) continue;
          entry.type = mediaType(probe);
          if (!entry.type) throw new Error('上游没有返回独立音频');
          bytes = probe; probe = null; entry.ready.resolve(entry);
        }
        if (entry.written + bytes.length > this.fileLimit || entry.size != null && entry.written + bytes.length > entry.size) throw new Error('音频文件大小异常');
        await output.writeFile(bytes);
        entry.written += bytes.length; this.notify(entry);
      }
      if (!entry.type || !entry.written || entry.size != null && entry.written !== entry.size) throw new Error('音频下载不完整，请重试');
      await output.sync(); await output.close(); output = null;
      if (entry.removed) throw new Error('歌曲已删除');
      await fs.rename(part, file); entry.file = file; entry.size = entry.written;
      await fs.writeFile(path.join(this.directory, entry.id + '.json'), JSON.stringify({ aliases: entry.aliases, size: entry.size, type: entry.type, meta: entry.meta }), { flag: 'w' });
      if (entry.removed) throw new Error('歌曲已删除');
      entry.status = 'cached'; entry.finished.resolve(entry); this.notify(entry);
      await this.prune();
    } catch (cause) {
      controller.abort();
      await upstream?.body?.cancel().catch(() => {});
      await output?.close().catch(() => {});
      entry.error = cause.message || '缓存失败';
      await fs.rm(part, { force: true }).catch(() => {});
      await fs.rm(file, { force: true }).catch(() => {});
      await fs.rm(path.join(this.directory, entry.id + '.json'), { force: true }).catch(() => {});
      entry.status = entry.removed ? 'removed' : 'failed';
      entry.ready.reject(cause); entry.finished.reject(cause); this.notify(entry);
    } finally { clearTimeout(timer); }
  }

  async serve(req, res, source) {
    const entry = this.ensure(source);
    entry.readers++;
    entry.responses ||= new Set(); entry.responses.add(res);
    let handle;
    const disconnected = deferred();
    const onClose = () => disconnected.resolve();
    res.once('close', onClose);
    try {
      if (entry.ready) await entry.ready.promise;
      if (entry.size == null && req.headers.range) await entry.finished.promise;
      if (res.destroyed) return;
      res.setHeader('X-Music-Cache', entry.status === 'cached' ? 'HIT' : 'FILLING');
      const range = audioHeaders(req, res, entry.size, entry.type);
      if (req.method === 'HEAD') return res.end();
      try { handle = await fs.open(entry.file, 'r'); }
      catch (cause) { if (cause.code !== 'ENOENT') throw cause; handle = await fs.open(path.join(this.directory, entry.id + '.audio'), 'r'); }
      async function* read() {
        let position = range?.start || 0;
        const end = range?.end ?? (entry.size == null ? Infinity : entry.size - 1);
        while (!res.destroyed && position <= end) {
          if (entry.removed || entry.status === 'failed') throw new Error(entry.error || '歌曲已删除');
          if (position < entry.written) {
            const buffer = Buffer.alloc(Math.min(64 * 1024, entry.written - position, end - position + 1));
            const { bytesRead } = await handle.read(buffer, 0, buffer.length, position);
            if (!bytesRead) throw new Error('无法读取已缓存的音频');
            position += bytesRead; yield buffer.subarray(0, bytesRead);
          } else if (entry.status === 'cached') break;
          else await Promise.race([entry.changed.promise, disconnected.promise]);
        }
      }
      await pipeline(Readable.from(read()), res);
    } finally {
      res.off('close', onClose);
      await handle?.close(); entry.readers--; entry.responses.delete(res);
      if (!entry.readers) entry.readersIdle?.resolve();
      if (entry.status === 'cached') { const now = new Date(); await fs.utimes(entry.file, now, now).catch(() => {}); }
      this.prune().catch(() => {});
    }
  }

  prune() {
    this.pruning = this.pruning.catch(() => {}).then(async () => {
      const complete = [...this.entries.values()].filter(entry => entry.status === 'cached' && !entry.removed);
      let bytes = complete.reduce((sum, entry) => sum + entry.size, 0);
      for (const entry of complete.sort((a, b) => a.used - b.used)) {
        if (bytes <= this.limitBytes) break;
        if (entry.readers || entry.removed) continue;
        await this.removeEntry(entry);
        bytes -= entry.size;
      }
    });
    return this.pruning;
  }

  warm(tracks) {
    if (this.job?.running) return this.job;
    this.job = { running: true, total: tracks.length, done: 0, failed: 0, current: '' };
    const job = this.job;
    this.warming = (async () => {
      for (const track of tracks) {
        if (this.closed) break;
        if (this.libraryIds && !this.libraryIds.has(track.id)) { job.done++; continue; }
        job.current = track.title || track.id;
        try { const entry = this.ensure(trackSource(track), false); if (entry.finished) await entry.finished.promise; }
        catch { job.failed++; }
        job.done++;
      }
      job.running = false; job.current = '';
    })();
    return job;
  }

  status(tracks) {
    const entries = [...this.entries.values()].filter(entry => !entry.removed);
    return { count: entries.filter(e => e.status === 'cached').length, bytes: entries.filter(e => e.status === 'cached').reduce((sum, e) => sum + e.size, 0), limitBytes: this.limitBytes,
      active: entries.filter(e => ['queued', 'downloading'].includes(e.status)).length, job: this.job,
      tracks: tracks.filter(trackSource).map(track => { const entry = this.lookup(trackSource(track)); return { id: track.id, status: entry?.status || 'missing', size: entry?.written || 0, error: entry?.error }; }) };
  }

  async close() {
    this.closed = true;
    for (const entry of this.entries.values()) {
      entry.controller?.abort();
      if (entry.status === 'queued') { entry.status = 'failed'; entry.ready.reject(new Error('服务器关闭')); entry.finished.reject(new Error('服务器关闭')); }
    }
    await Promise.allSettled([...this.entries.values()].filter(e => e.finished).map(e => e.finished.promise));
    await this.warming; await this.pruning;
  }
}

module.exports = { AudioCache, trackSource };
