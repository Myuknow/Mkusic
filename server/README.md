# Mkusic Server

网页负责管理音乐，也可直接播放；Android Mkusic App 连接同一服务播放。

每份部署拥有一个音乐库和一个访问密码。多人连接同一个服务时会共用音乐库，本版本不提供独立用户账户。

## 包含什么

- 独立音乐网页：搜索、分组、编辑曲名/歌手、批量分组、置顶、删除、播放历史。
- 网页播放：播放/暂停、切歌、拖动进度、音量、顺序/列表循环/单曲循环/随机循环。
- MP3、FLAC、WAV、M4A、AAC、OGG、OPUS、MP4、WebM 文件上传与 Range 拖动播放。
- HTTPS 音频链接、B站视频 BV/av、B站音频 au、公开收藏夹导入、封面代理。
- 收藏夹导入和打开旧音乐库后，自动补全 BV/av/au 占位名称、UP主与封面，保存后 App 也能读取；失败可点“加载歌名”重试，手动编辑的歌名保留。
- 音乐备份导入/导出，以及从旧版音乐库中仅迁移音乐的脚本。
- 保留现有 Android App 接口；Basic Auth 用户名留空，密码由部署者自行设置。

## 在本机启动

安装 **Node.js 22 或以上版本**。运行时零第三方依赖，不需要 `npm install`。

Windows 可以双击 `start.cmd`；命令行方式（Windows / Linux / macOS）：

```bash
npm run setup
npm start
```

首次 `setup` 会生成 `.env`，为这份部署创建独立随机访问密码。已有 `.env` 不会被覆盖。

浏览器打开 **http://127.0.0.1:4066/**。认证弹窗中用户名留空，密码查看 `.env` 的 `ACCESS_PASSWORD`。

Linux 也可用 `sh start.sh`。停止服务按 Ctrl+C。

## 服务器部署与 App 连接

### 1. 启动服务端

将项目复制到服务器，安装 **Node.js 22 或以上版本**，进入服务端目录（完整仓库中的 `server/`），运行：

```bash
npm run setup
```

修改生成的 `.env`，设置自己的访问密码。以下配置适用于 Caddy 与 Mkusic 部署在同一台服务器的情况：

```dotenv
HOST=127.0.0.1
PORT=4066
ACCESS_PASSWORD=请改成自己的密码
```

然后启动服务：

```bash
npm start
```

长期运行可使用自己的进程管理器，或采用下面的 Docker 方式。

### 2. 配置公网 HTTPS

App 连接公网服务器时需要有效的 HTTPS 证书。可以使用域名，也可以直接使用公网 IP，不一定要购买域名。

下面使用 Caddy 负责证书申请、续期和反向代理，Mkusic 在本机通过 HTTP 提供服务。这种方式无需设置 Mkusic 的 `HTTPS_CERT_FILE` 和 `HTTPS_KEY_FILE`。

先安装新版稳定版 Caddy，在云服务器安全组和系统防火墙中放行 **TCP 80、443 端口**，并确保这两个端口可由 Caddy 使用。服务器还需要能够访问证书机构的服务。

以下两种配置任选一种，写入 `/etc/caddy/Caddyfile`。

#### 方式一：使用域名

将域名的 DNS 记录解析到服务器公网 IP，然后配置：

```caddyfile
music.example.com {
    reverse_proxy 127.0.0.1:4066
}
```

将 `music.example.com` 替换成自己的域名。Caddy 会自动申请和续期受信任的 HTTPS 证书。

App 中填写：

```text
https://你的音乐域名/
```

#### 方式二：直接使用公网 IP，无需域名

Let’s Encrypt 已支持免费签发公网 IP 证书。使用支持该功能的新版 Caddy，并显式指定正式证书接口和 `shortlived` 配置：

```caddyfile
{
    default_sni 203.0.113.10
}

https://203.0.113.10 {
    tls {
        issuer acme {
            dir https://acme-v02.api.letsencrypt.org/directory
            profile shortlived
        }
    }

    reverse_proxy 127.0.0.1:4066
}
```

将示例中的两处 `203.0.113.10` 都替换成自己的真实公网 IP。该示例 IP 仅用于说明，不能直接使用。

IP 证书有效期约 **6 天**，Caddy 会自动续期。需要保持验证端口可达，并持久保存 Caddy 的证书数据；使用容器运行 Caddy 时也要配置持久化存储。

App 中填写：

```text
https://你的公网IP/
```

上述配置使用默认 HTTPS 端口 443，因此地址无需填写 `:4066`。

仅添加反向代理、使用自签名证书或 `tls internal`，不能让公开版 App 自动信任服务器。需要申请正式、受系统信任的证书。

#### 检查并加载配置

对于使用 systemd 管理 Caddy 的 Linux 服务器，可执行：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl enable --now caddy
sudo systemctl reload caddy
```

查看运行和证书申请日志：

```bash
sudo journalctl -u caddy -n 100 --no-pager
```

如果配置校验或证书申请失败，应先根据日志解决问题，再连接 App。若修改了 Mkusic 的监听端口，也要同步修改 Caddy 的 `reverse_proxy` 地址。

### 3. 连接 App

在 App 连接页填写配置好的 HTTPS 地址，以及这份部署的 `ACCESS_PASSWORD`。网页添加的歌曲在 App 刷新音乐库后可见。

App 使用 Android 系统信任的 CA。服务器证书有效、受系统信任，且与访问的域名或 IP 匹配时，手机无需安装额外证书，也无需重新编译 App。

安卓源码位于仓库的 `android/` 目录，安装包在 Releases 中。

### 局域网连接

**App 0.2.2 起支持局域网 HTTP，无需配置证书。**

手机与电脑连接同一 Wi-Fi，服务端 `.env` 设置：

```dotenv
HOST=0.0.0.0
PORT=4066
```

重启服务后，App 填写电脑的局域网地址，例如：

```text
http://192.168.1.100:4066/
```

密码仍使用这份部署的 `ACCESS_PASSWORD`。同时需要在电脑防火墙中允许局域网访问该端口。

手机端不能用 `127.0.0.1` 连接电脑，因为这个地址指向手机自身。

HTTP 支持私有 IPv4 地址（10.x、172.16–31.x、192.168.x）及回环地址，不支持公网 HTTP。连接未加密，请仅在可信网络使用。旧版 0.2.1 需要升级 APK 才能使用局域网 HTTP。

### 其他说明

- 所有网页、API、音频和封面请求均需要访问密码，包括本机请求。
- 反向代理需保留 `Authorization` 请求头；上面的 Caddy 配置默认会保留。
- 每份部署独立配置访问密码和音乐数据，不要将自己的 `.env` 或 `data/` 作为公开安装包分享。
- 如已有受信任的证书，也可同时设置 `HTTPS_CERT_FILE` 和 `HTTPS_KEY_FILE`，让 Mkusic 直接提供 HTTPS。配置错误时服务会报错，不会降级为 HTTP；自行管理的证书还需安排续期，并在更新后重启 Mkusic。
- 环境文件通过 Node.js 的 `process.loadEnvFile` 读取，操作系统环境变量优先于 `.env`。

### Docker

先创建 `.env` 并填写 `ACCESS_PASSWORD`，再运行：

```bash
docker compose up -d --build
docker compose logs -f music
```

容器默认只向本机发布 4066 端口，配合本机 Caddy 使用。数据保存在宿主机 `./data`。有意在局域网直接访问 HTTP 网页或 App 0.2.2 时，可在 `.env` 设置 `BIND_ADDRESS=0.0.0.0`；公网仍需 HTTPS 入口。

## 配置

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `ACCESS_PASSWORD` | 必填 | 网页与 App 共用的访问密码；修改后重启生效 |
| `HOST` | `127.0.0.1` | 原生 Node 服务监听地址 |
| `PORT` | `4066` | 服务端口 |
| `DATA_DIR` | `./data` | 音乐数据库和上传音频目录 |
| `AUDIO_CACHE_MAX_MB` | `2048` | B站完整音频缓存上限，MB；超限时清理最久未使用且未在播放的缓存 |
| `HTTPS_CERT_FILE` / `HTTPS_KEY_FILE` | 不设置 | 直接 HTTPS 模式的证书链/私钥路径，必须同时配置 |
| `BIND_ADDRESS` | `127.0.0.1` | 仅 Docker 的宿主机端口发布地址 |

`.env.example` 只是配置模板，不含可用的固定密码。

## 音乐数据与迁移

常规备份可在网页点击“导出音乐备份”；恢复使用“导入备份”，会替换音乐库，导入前自动保存旧 `db.json`，并保留原有音频文件。JSON 备份支持总计 128 MiB 的音频；导入请求上限 192 MiB。更大的音乐库请停止服务后备份或迁移整个 `data/` 目录。

`data/db.json` 只包含 `playlist` 和 `audio`，音频在 `data/uploads/`。播放历史保存在当前浏览器；网页和 App 的播放进度彼此独立。

B站歌曲首次播放会边播边保存完整音频到 `data/audio-cache/`。手机切歌、暂停或断开连接不影响已经开始的下载；仅完整下载成功的文件会标记为已缓存。再次播放、拖动进度及 App 的 aid 解析会优先读取缓存，不再依赖 B站；重启服务后仍有效。只选择独立音轨，不回退下载视频。

网页“缓存全部”可以提前顺序下载歌曲；选中歌曲后按钮变为“缓存所选”。关闭网页后缓存任务仍继续，服务重启会停止未完成的任务，可重新点击继续。状态栏显示缓存数量、占用空间、进度和失败数量，失败歌曲可再次点击重试。默认上限 2 GiB，单曲最多 256 MiB，并发下载最多 2 首；临时下载也会占用磁盘空间。空间超限时自动移除最久未使用的缓存，之后播放会重新下载。

网页与 App 共用服务器缓存，无需升级 App。JSON 导出不包含可重新下载的 B站缓存；若要连缓存一起迁移或备份，请停止服务后复制整个 `data/` 目录。修改缓存容量后重启服务生效，Docker 同样支持该变量。

删除歌曲、清空或导入替换歌单时，会自动清理不再被音乐库使用的缓存；同一音源还有其他条目引用时保留。删除正在播放或缓存的歌曲会停止相应传输并删除临时文件，预缓存任务会跳过已删除的条目。服务启动时也会清理旧版本遗留的无引用缓存，缓存数量与空间占用会随之更新。

## App 兼容接口

| 请求 | 用途 |
| --- | --- |
| `GET /api/playlist` | 直接返回歌曲数组，字段兼容现有 App |
| `PUT /api/playlist` | 保存 `{ "playlist": [...] }` |
| `POST /api/playlist/metadata` | 按 `ids` 分批补全歌名，每批最多 5 首；仅更新仍是编号占位的条目 |
| `POST /api/audio/upload` | `multipart/form-data`，字段 `file`，返回 `id` 和 `url` |
| `GET/HEAD /api/audio/:id/open` | 音频、Range、206/416 响应 |
| `DELETE /api/audio/:id` | 删除上传音频及关联歌单条目 |
| `GET /api/bilibili/resolve` | `bvid/aid/sid` 与可选 `cid`，返回稳定 `audioUrl` |
| `GET /api/bilibili/audio` | B站音频代理与 Range，支持 `bvid/aid/cid/sid` |
| `GET /api/bilibili/meta` | 标题、作者、封面 |
| `GET /api/bilibili/fav` | 公开收藏夹 |
| `GET /api/bilibili/cover?url=…` | B站 HTTPS 图片代理 |
| `GET /api/cache` | 完整歌曲缓存状态、容量、预缓存任务和每首歌状态 |
| `POST /api/cache/warm` | 提前缓存全部 B站歌曲，或通过 `{ "ids": ["歌曲ID"] }` 缓存所选歌曲 |
| `GET /api/health` | 服务版本和音乐条目计数 |
| `GET /api/export`、`POST /api/import` | 音乐专用备份 |

## 检查与测试

```bash
npm test
```

