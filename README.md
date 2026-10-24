# Mkusic

一个为了听 B站音乐做的小播放器。Android App 可以独立导入、解析、缓存和播放 B站音频，不需要部署服务器；也可以把服务端部署在自己的服务器上，用网页或 App 连接播放。

安卓端是在 Sonora 的基础上修改的，感谢原作者。项目还比较简单，如果刚好合用，欢迎拿去折腾。

## 目前能做什么

- 网页与 App 共用音乐库，支持搜索、分组、播放队列、切歌、进度拖动和随机/循环播放。
- 上传自己的音频，或添加 B站 BV / av / au 与 fid 公开收藏夹，自动补全标题和封面。
- B站音频完整缓存到服务器；支持预缓存、重启后复用，以及删除歌曲时清理无引用缓存。
- 音乐库备份、恢复；Docker 部署；使用访问密码连接网页和 App。

每份服务拥有一个共享音乐库和一个访问密码。多人连接同一服务时共同管理音乐库；当前不支持独立用户账户。

## 启动服务端

安装 Node.js 22 或以上版本，在仓库根目录执行：

```bash
cd server
npm run setup
npm start
```

浏览器打开 `http://127.0.0.1:4066/`。用户名留空，访问密码查看生成的 `server/.env` 中的 `ACCESS_PASSWORD`。运行时无需安装第三方依赖。

Windows 也可双击 `server/start.cmd`。服务器部署、Docker、缓存和备份说明见 [服务端文档](server/README.md)。

## Android App

安卓源码位于 [android/](android/README.md)。默认使用手机独立模式：点右上角 `+` 添加 BV/av/au 号、视频链接或公开收藏夹链接即可播放。歌曲菜单可完整缓存音频，完成后可离线播放。

服务器模式保留在「设置 → 连接自己的音乐服务器」中，连接时填写自己的服务器地址和密码：

- 局域网：手机与服务器处于同一网络，服务端设置 `HOST=0.0.0.0` 后重启，App 填写电脑的局域网 IP，例如 `http://192.168.1.100:4066/`。
- 公网：使用带有效证书的 HTTPS 地址，例如 `https://music.example.com/`。

HTTP 连接未加密，仅用于可信网络。

## 源码目录

```text
Mkusic/
├── README.md
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── .github/workflows/ci.yml
├── docs/UPLOAD.md
├── server/                  # 网页、音乐服务、Docker、测试
└── android/                 # Android 源码、Gradle 包装器、测试
```

## 构建与测试

服务端：

```bash
cd server
npm test
```

安卓端：直接使用apk安装包，大概率都能用。

Windows 使用 `gradlew.bat`。

## 当前限制

- 尚未缓存的 B站歌曲仍依赖上游接口；失效、私密或登录限定内容可能不可用。
- 网页最近播放保存在当前浏览器本地。
- 安卓端后台设置和控制窗口可能不稳定。

## 开源许可

代码采用 [MIT License](LICENSE)。安卓端基于 [Sonora](https://github.com/SikamikanikoBG/Sonora) 修改，保留其版权和许可声明，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
