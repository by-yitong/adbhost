# AdbHost 设计文档 — Android ADB 协议服务端 App

日期:2026-11-12 · 状态:已确认(用户批准开工;协议经实证探测修订)· 交付:arm64 APK

## 0. 协议实证结论(2026-11-12,针对 adb 37.0.0-14910828 + AOSP main 源码核对)

以下为本项目最高优先级契约,实现必须严格遵守;细节以仓库内 `docs/PROTOCOL.md` 为准。

1. 消息帧:24 字节小端 `command|arg0|arg1|data_len|checksum(字节和 mod 2^32)|magic(=command^0xFFFFFFFF)`
2. 常量:A_CNXN=0x4E584E43 A_AUTH=0x48555441 A_STLS=0x534C5453 A_OPEN=0x4E45504F
   A_OKAY=0x59414B4F A_CLSE=0x45534C43 A_WRTE=0x45545257;A_VERSION=0x01000001;MAX_PAYLOAD=256KB
3. TCP 握手(adb 37 实测):
   - 主机 connect 后**主动发 CNXN**(banner=`host::features=...`,maxpayload=1MB)
   - 设备必须**尽快回 CNXN**(`device::...`);主机 `adb connect` 等设备 CNXN,10 秒超时即判 failed 并关连接
   - **TLS 路径(默认)**:设备发 A_STLS(0x01000000) → 主机回 A_STLS → 进入 **TLS 1.3**(仅 1.3,min=max),双向证书
     → 握手完成后设备在 TLS 内再发 CNXN,后续全部报文走 TLS;TLS 模式下无经典 AUTH
   - **经典 RSA 路径(兼容模式)**:设备发 AUTH TOKEN(20B 随机) → 主机回 SIGNATURE(RSA-SHA1 签 token)
     → 验证失败再发 TOKEN → 主机回 RSAPUBLICKEY(base64(android_pubkey)+' '+user@host+'\0')→ 设备弹窗授权
4. TLS 证书:RSA-2048 自签;主机 CA 提示列表格式 `O=AdbKey-0;CN=<base64 公钥>`(仅提示用);
   设备端信任=主机证书公钥指纹 + 用户授权对话框(与经典模式同 UX)
5. 依赖(minSdk 28):Android SSLSocket 支持 TLS1.3;证书生成用 BouncyCastle bcpkix(唯一新增三方依赖)

## 1. 目标

原生 Android App(Kotlin + Jetpack Compose)在应用层实现 ADB 设备端(adbd 角色),
电脑 `adb connect 手机IP:5555` 直连,提供 `adb shell` 与 `adb push/pull`。

### 非目标(YAGNI)

- 不做 USB 传输;不做 `adb pair`(配对协议,无线调试专用端口场景)
- 不做 forward/reverse、`adb root`、`adb install` 等系统级服务
- root 模式仅影响 shell 提权;大范围文件访问用 MANAGE_EXTERNAL_STORAGE

### 硬限制(写进 App 说明文案)

- 无 root 时 shell 以 App uid 执行;文件传输范围为授权存储目录(授全文件访问后为共享存储)
- 经典兼容模式需在设置中手动开启(默认 TLS;老版本 adb 主机才需要)

## 2. 架构(4 层)

```
电脑 adb 客户端 ──TCP:5555──▶ AdbServer(ServerSocket)
                                │
              ① 协议层 core(纯 Kotlin,禁 android.* import,可 JVM 单测)
                 消息帧 / TLS 握手编排 / 经典 AUTH / ADB 公钥编解码 / 信任存储
                                │
              ② 服务层 engine:shell:v2/v1、sync:(SEND/RECV/STAT/LIST)、features:
                                │
              ③ 生命周期 engine:前台服务 + 通知 + 可选开机自启
                                │
              ④ UI:Compose 单页 + 授权居中弹窗
```

### 模块布局(单 module)

```
com.adbhost.app
├── core/                    卡片A:纯 Kotlin,禁 android.* import
│   ├── AdbMessage.kt          帧常量/编解码/checksum
│   ├── AdbConnection.kt       连接状态机:CNXN 交换→STLS/TOKEN 分支→在线→流分发
│   ├── TlsChannel.kt          TLS1.3 编排(抽象:SSLSocket 由 platform 注入,core 可测)
│   ├── RsaIdentity.kt         RSA-2048 密钥对生成/加载 + 自签证书(BC 注入抽象)
│   ├── AdbPublicKeyCodec.kt   android_pubkey 编解码(len|n0inv|n[64]|rr[64]|exp)+MD5 指纹
│   └── TrustStore.kt          已授权主机凭证(经典:pubkey 行;TLS:证书 DER)读写
├── engine/                  卡片B
│   ├── AdbServer.kt           ServerSocket accept 循环 + 连接管理 + 端口占用检测
│   ├── ServiceDispatcher.kt   OPEN 服务名解析分发;未知服务 CLSE+日志
│   ├── ShellService.kt        shell:v2/v1(pty/raw 修饰符);rootMode→su,否则 /system/bin/sh
│   ├── SyncService.kt         SEND/RECV/LIST/LSTAT/STAT/STAT_V2/LSTAT_V2;路径映射
│   ├── FeaturesService.kt     shell_v2,stat_v2
│   └── AdbServiceController.kt 启停/状态 StateFlow(UI/前台服务共用)
├── platform/                卡片B
│   ├── AdbForegroundService.kt dataSync 前台服务 + 通知(含停止 action)
│   └── BootReceiver.kt        开机自启(默认关)
├── data/                    卡片C
│   ├── SettingsStore.kt       SharedPreferences:port(5555)/tlsMode(true)/rootMode(false)
│                              /autoStart(false)/autoAcceptDebug(仅 debug 构建)
│   └── ConnectionLogStore.kt  环形 50 条 + StateFlow
└── ui/                      卡片C
    ├── MainActivity.kt / AdbHostScreen.kt / AuthorizeDialog.kt / theme/
```

## 3. 服务契约

- `shell:v2[:cmd]`/`shell:v1[:cmd]`/`shell[:cmd]`:修饰符 pty/raw 识别;无 cmd=交互
  - v2 帧:`id u8 + len u32LE + data`,id∈{STDIN=0,STDOUT=1,STDERR=2,EXIT=3,CLOSE=4}(AOSP 从 0 起)
  - 执行:rootMode 且有 su → `su -c sh`;否则 `/system/bin/sh`;管道模拟无真 PTY(文案注明)
- `sync:`:id=四字符 MKID;STAT_V2/LSTAT_V2 结构按 AOSP file_sync_service.h;路径映射:
  `/sdcard/*`→root/*、`/data/local/tmp/*`→root/tmp/*、其余绝对路径→root+path、相对→root/
- `features:` → `shell_v2,stat_v2`;`root:`/`tcpip:`/host: → CLSE+日志

## 4. 数据与配置

- SharedPreferences:port/tlsMode/rootMode/autoStart/autoAcceptDebug
- 信任存储:filesDir/adb_trust/(经典 pubkey 行格式 + TLS 证书 DER 文件)
- 日志:内存环形 50 条
- 权限:INTERNET、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC、POST_NOTIFICATIONS、
  RECEIVE_BOOT_COMPLETED、MANAGE_EXTERNAL_STORAGE(可选)

## 5. UI(Compose 单页 + 弹窗;ui-conventions/Hallmark 适配)

- 卡片风格、圆角浅阴影、无描边;60/30/10;8pt;主色蓝(#2563EB 系);禁绿/紫/琥珀
- 卡片:服务(开关+IP:端口可复制+端口内联编辑)| 模式(TLS 开关、Root 提权开关、开机自启)
  | 已授权主机(凭证指纹+时间,单个撤销,撤销即断活动连接)| 连接日志(清空)
- 居中授权弹窗:凭证指纹 + "一律允许"复选 + 拒绝/允许(≤2 按钮)
- 空态引导文案;通知权限引导

## 6. 异常与边界

1. 端口占用 → 明确报错引导换端口;2. 连接断开 → 回收子进程/流;3. TLS 握手失败/证书过期 →
断连+日志;4. 授权对话框超时(10s)→ 拒绝并断开;5. 撤销凭证 → 断开对应活动连接

## 7. 测试与验收

- JVM 单测(core):帧编解码/checksum、android_pubkey roundtrip(用本机 ~/.android/adbkey.pub 真实样例)、
  经典 AUTH 状态机(模拟主机全流程:SIGNATURE 通过/失败→RSAPUBLICKEY)、TLS 模式 STLS 分支、
  sync 路径映射、shell v2 帧编解码
- 集成(真环境,已验证可行):本机 adb 37 客户端直连 App(TLS 路径已在探测中打通 STLS 往返):
  装模拟器或真机 → `adb connect` → 授权 → `adb devices` → `adb shell whoami` → push/pull →
  撤销重连需再授权 → 兼容模式切换验证
- 交付:arm64 APK + 源码 + README(风险/限制说明)+ docs/PROTOCOL.md

## 8. 构建

- minSdk 28(底线:TLS 1.3)/ targetSdk 35 / Kotlin 2.x + Compose BOM
- 依赖:kotlinx-coroutines、androidx(compose/material 等)、bcpkix+jdk15on(证书生成,经主 agent 批准)
- 打包仅 arm64-v8a(abiFilters 显式声明);release 用本地生成 keystore 签名;版本 1.0.0(1)
