const fs = require('node:fs');
const fsp = require('node:fs/promises');
const { pipeline } = require('node:stream/promises');

function parseRange(range, size) {
  if (!range) return null;
  const fail = () => { throw Object.assign(new Error('音频范围超出文件'), { status: 416 }); };
  const match = /^bytes=(\d*)-(\d*)$/.exec(range);
  if (!match || !size || (!match[1] && !match[2])) return fail();
  const start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
  const end = match[1] && match[2] ? Math.min(Number(match[2]), size - 1) : size - 1;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= size || end < start) return fail();
  return { start, end };
}

function audioHeaders(req, res, size, type, cache = 'private, no-cache') {
  let range;
  try { range = parseRange(req.headers.range, size); }
  catch (cause) { res.setHeader('Content-Range', `bytes */${size}`); throw cause; }
  const headers = { 'Content-Type': type, 'Accept-Ranges': size == null ? 'none' : 'bytes', 'Cache-Control': cache };
  if (size != null) headers['Content-Length'] = range ? range.end - range.start + 1 : size;
  if (range) headers['Content-Range'] = `bytes ${range.start}-${range.end}/${size}`;
  res.writeHead(range ? 206 : 200, headers);
  return range;
}

async function streamFile(req, res, file, type) {
  const { size } = await fsp.stat(file);
  const range = audioHeaders(req, res, size, type);
  if (req.method === 'HEAD') return res.end();
  await pipeline(fs.createReadStream(file, range || {}), res);
}

module.exports = { parseRange, audioHeaders, streamFile };
