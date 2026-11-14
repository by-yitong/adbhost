# AdbHost — Android ADB 协议服务端 App

在应用层实现 ADB 协议的设备端(adbd 角色),让电脑用**标准 adb 工具**通过 WiFi 直连这台手机:

```
电脑: adb connect 手机IP:5555   →  手机弹授权框  →  adb shell / adb push / adb pull / adb install
```

无需 root。Kotlin + Jetpack Compose 原生实现,协议层无 Android 依赖、纯 JVM 可测。

## 功能

- **标准 adb 全链路**:`adb devices` / `adb shell`(shell v2 协议,exit code 正确)/ `adb push` / `adb pull`(sync + stat_v2)/ `adb install`
- **接收的文件列表**:电脑 push 下来的文件自动登记,应用内点按直接打开(系统按类型分发)、保存到相册/下载、分享给其他应用;支持一键清空(记录+文件)
- **授权管理**:首次连接弹窗确认证书指纹,可记住主机;支持单个撤销(即时断开)与「全部撤销」;连接状态实时显示「已连接 N 台主机」
- **adb install 三模式**:Shizuku 静默安装 → 系统确认弹窗兜底(后台拉不起确认页时走高优先级通知)→ root 原生静默
- **TLS 1.3 加密**(与 Android 11+ 无线调试同代协议),旧版 adb 主机可切经典 RSA 兼容模式
- 多网卡显示全部 IPv4、端口可配、开机自启、前台服务通知(已连接数 + 已接收文件数)

## 使用

1. 安装 [AdbHost-v1.2.1-arm64.apk](AdbHost-v1.2.1-arm64.apk)(arm64-v8a,Android 9+)
2. 打开 App,打开「ADB 服务」开关(默认端口 5555,可改)
3. 电脑执行 `adb connect <手机IP>:5555`,手机上确认指纹并允许(可勾选「记住该主机」)
4. 电脑 push 的文件出现在「接收的文件」卡片,点按打开或保存
5. 「已授权主机」里可随时撤销;换电脑/公共网络环境建议「全部撤销」

## 能力与边界(诚实声明)

| 能力 | 无 root | root(开启 Root 提权后) |
|---|---|---|
| adb shell | 以本 App 身份执行(沙盒内受限) | su 提权执行 |
| adb push/pull | App 授权存储目录(/sdcard/*、/data/local/tmp/* 均映射到此处) | 同左 |
| adb devices/features | ✓ | ✓ |
| **adb install** | Shizuku 静默装 → 系统确认弹窗 兜底 | 原生静默(root shell) |

- 无 root 时**不能**:静默装 APK(除非装 Shizuku)、全局 input、访问其他应用数据——这是 Android 沙盒边界,协议无法突破
- shell 无真实 PTY(管道模拟),交互可用但无行编辑
- 文件传输覆盖写入;路径映射规则见 `docs/PROTOCOL.md`

## 安全模型

- 电脑身份 = 证书公钥 SHA-1 指纹;首次连接必须在本机确认,未授权的连接拿不到任何数据
- 主机名取自对方证书 CN,重连自动回填;指纹随时可撤销,撤销即断开
- 开放端口意味着局域网内可见——**请在可信网络使用**,不用时关闭服务
- release 签名密钥不入库(`adbhost/adbhost-release.jks` 已 gitignore);无 keystore 时 `assembleRelease` 产出未签名 APK

## 构建

```bash
cd adbhost
./gradlew assembleRelease            # 产物 app/build/outputs/apk/release/(仅 arm64-v8a)
./gradlew testDebugUnitTest          # 单元测试(79 个)
ADBHOST_INTEGRATION=1 ./gradlew :app:testDebugUnitTest \
  --tests "com.adbhost.app.engine.integration.RealAdbHostIntegrationTest"   # 真 adb 集成测试
```

要求:JDK 17+、Android SDK(minSdk 28 / targetSdk 37)。签名:放置 `adbhost/adbhost-release.jks`(密码可用环境变量 `ADBHOST_STORE_PASSWORD` / `ADBHOST_KEY_PASSWORD` 覆盖),文件缺失则跳过签名。

## 文档

- `docs/PROTOCOL.md` — 协议实证参考(CNXN/AUTH/STLS 握手顺序、24 字节帧格式、sync 消息尺寸、android_pubkey 编码,全部经真 adb 37 抓包校准)
- `docs/superpowers/specs/2026-11-12-adbhost-design.md` — 设计文档
- `probe/` — 真机验收用的最小测试 App(push/install 验收道具)

## 已知限制

- 经典 RSA 兼容模式对 adb ≥37 主机无效(其 TCP 默认 TLS-first,不签 TOKEN),仅用于旧版 adb
- Shizuku 模式需先安装并启动 Shizuku、授予 AdbHost 权限
- 开机自启在 Android 15+ 可能被系统限制启动前台服务
- 未实现 `reverse:` 端口反代、无线配对(adb pairing)等设备侧服务

## 版本历史

- **v1.2.1** — 授权主机名持久化(证书 CN 回填);「已连接 N 台主机」状态;通知栏计数
- **v1.2.0** — 接收的文件列表(打开/保存/分享/清空);全部撤销授权
- **v1.1.0** — install 输出 v2 分帧(Success 正常回显);UI 显示全部 IP
- **v1.0.x** — TLS 1.3 + 经典 AUTH 双模式、shell v2、sync push/pull、adb install 三模式
