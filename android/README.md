# Mkusic Android

基于 Sonora 的 Android 音乐播放器，连接同仓库 `server/` 的 Mkusic 服务，也兼容旧版音乐服务接口。

当前版本 `0.2.4`，最低 Android 8.0。支持音乐库、搜索与分组、播放队列、进度拖动、随机/循环、后台媒体服务、通知控制和音频缓存。

## 构建

推荐使用 Android Studio 打开本目录，并按提示安装 Android SDK 34。也可配置 JDK 17+、Android SDK 后执行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

Windows 使用 `gradlew.bat`。输出文件为 `app/build/outputs/apk/debug/app-debug.apk`。

服务端地址在首次连接时输入，访问密码通过 Android Keystore 加密保存在设备上。公开版默认地址为空，HTTPS 使用系统信任证书；局域网私有 IP 支持 HTTP。

## 签名与安装更新

Debug 构建适合本地开发。不同电脑或 CI 生成的 Debug APK 可能使用不同签名，不保证互相覆盖安装。正式发布时应使用并长期保管自己的固定签名。

项目支持 `keystore.properties` 配置，参照 `keystore.properties.example`。签名文件与密码只留在本地或 CI Secrets 中，不提交源码。配置好签名后执行 `./gradlew assembleRelease`。

## 设备测试

`app/src/androidTest/` 中的集成测试需要运行中的测试音乐服务及设备/模拟器，并通过运行参数提供：

- `workstationServerUrl`：测试服务器地址。
- `workstationPassword`：测试服务密码。

播放、队列与重连测试需要至少两首可播放的 B站歌曲；封面测试需要带封面的歌曲。界面连接测试会修改测试手机的连接配置，请使用测试设备和测试服务。常规 CI 仅运行不访问个人服务器的单元测试与构建检查。

原 Sonora MIT 许可保存在 `LICENSE`，来源说明见仓库根目录 `THIRD_PARTY_NOTICES.md`。
