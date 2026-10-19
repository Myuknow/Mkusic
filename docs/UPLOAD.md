# GitHub 上传范围

仓库根目录直接使用本目录的内容。建议创建名为 `Mkusic` 的空仓库；本目录已提供 README、MIT 许可证和忽略规则。

## 提交到源码仓库

| 路径 | 内容 |
| --- | --- |
| `README.md` | 项目简介、启动、App 连接和构建说明 |
| `LICENSE`、`THIRD_PARTY_NOTICES.md` | 许可证与上游来源 |
| `.gitignore`、`.gitattributes` | 排除私人数据、统一脚本换行 |
| `.github/workflows/ci.yml` | 服务端测试和安卓构建检查 |
| `server/` | 服务端、网页、示例配置、Docker、测试 |
| `android/` | 安卓代码、资源、Gradle 包装器、单元与设备测试 |
| `docs/UPLOAD.md` | 本说明 |

`server/data/` 只提交 `.gitkeep` 占位文件。`android/gradle/wrapper/gradle-wrapper.jar` 是构建所需文件，应提交。

## 不提交

- `.env`、真实访问密码、服务器证书、SSH 密钥。
- 音乐库数据库、上传歌曲、服务器音频缓存、运行日志。
- `.git/`、`.gradle/`、`.kotlin/`、`build/`、`local.properties`、IDE 设置。
- APK、AAB、源码或服务端 ZIP 包、正式签名文件。
- 原工作站其他模块、个人开发记录和本机截图。

APK 和服务端安装 ZIP 应作为 GitHub Releases 附件提供。源码上传包需要解压后提交内容，避免把整个 ZIP 当作仓库源码上传。

## 当前整理状态

- 服务端与安卓代码已放入同一仓库目录。
- 公开版 App 的默认服务器地址为空，个人 IP 的证书配置已移除。
- 设备测试改为通过参数接收测试服务器地址。
- 已保留 Sonora 的 MIT 声明；使用统一项目说明。
- CI 只做测试与构建，不自动发布安装包，也不需要提供个人服务器密码。
