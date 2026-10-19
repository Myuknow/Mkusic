const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const target = path.join(__dirname, '..', '.env');
const password = crypto.randomBytes(18).toString('base64url');
try {
  fs.writeFileSync(target, `HOST=127.0.0.1\nPORT=4066\nACCESS_PASSWORD=${password}\nDATA_DIR=./data\n`, { flag: 'wx', mode: 0o600 });
  console.log('已创建 .env；访问密码保存在 ACCESS_PASSWORD 中。');
  console.log('接下来运行 npm start，浏览器访问 http://127.0.0.1:4066，用户名留空。');
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
  console.log('.env 已存在，保留现有配置。');
}
