// Explicit, music-only migration. The source library is read-only.
const fs = require('node:fs/promises');
const path = require('node:path');
const { MusicStore, safeFile } = require('../lib/store');

async function migrate(sourceRoot, targetDirectory) {
  if (!sourceRoot) throw new Error('用法：npm run import:legacy -- --source 旧音乐库目录 [--target 新项目data目录]');
  const sourceData = path.resolve(sourceRoot, 'data');
  const target = path.resolve(targetDirectory);
  if (sourceData === target || target.startsWith(sourceData + path.sep)) throw new Error('目标目录不能位于源 data 目录内');
  const db = JSON.parse(await fs.readFile(path.join(sourceData, 'db.json'), 'utf8'));
  const playlist = Array.isArray(db.playlist) ? db.playlist : [];
  const ids = new Set(playlist.filter(track => track.kind === 'local').map(track => track.audioId || track.blobId || /\/api\/audio\/([^/]+)\/open/.exec(track.url || '')?.[1]).filter(Boolean));
  const audio = (db.audio || []).filter(item => ids.has(item.id));
  const uploads = await Promise.all(audio.map(async item => ({ name: safeFile(item.storedName), data: (await fs.readFile(path.join(sourceData, 'uploads', safeFile(item.storedName)))).toString('base64') })));
  const store = new MusicStore(target); await store.init();
  const existing = await store.read();
  if (existing.playlist.length || existing.audio.length) throw new Error('目标音乐库非空。请先备份并选择新的空目录，不会覆盖现有音乐库');
  return store.import({ db: { playlist, audio }, uploads });
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const option = name => args[args.indexOf(name) + 1];
  const source = args.includes('--source') ? option('--source') : '';
  const target = args.includes('--target') ? option('--target') : path.join(__dirname, '..', 'data');
  migrate(source, target).then(result => console.log(`仅迁移音乐：${result.tracks} 首、${result.audio} 个音频文件。源目录未修改。`))
    .catch(error => { console.error(error.message); process.exitCode = 1; });
}
module.exports = { migrate };
