# ADB 协议实现参考(实证版,针对 adb 37.0.0-14910828)

来源:AOSP `platform/packages/modules/adb`(main,2026-11)+ 本机 adb 37.0.0 实测抓包。
实现时如有冲突,以本文档为准(本文档已被真实抓包验证)。

## 1. 消息帧

24 字节小端头 + payload:

```
struct Header {
  uint32 command;   // A_CNXN/A_AUTH/A_STLS/A_OPEN/A_OKAY/A_CLSE/A_WRTE
  uint32 arg0;
  uint32 arg1;
  uint32 data_len;
  uint32 checksum;  // payload 每字节求和 mod 2^32
  uint32 magic;     // command ^ 0xFFFFFFFF
}
```

常量(adb.h):
```
A_CNXN 0x4E584E43  A_OPEN 0x4E45504F  A_OKAY 0x59414B4F  A_CLSE 0x45534C43
A_WRTE 0x45545257  A_AUTH 0x48555441  A_STLS 0x534C5453
A_VERSION 0x01000001  MAX_PAYLOAD 256*1024
A_STLS_VERSION 0x01000000
```
校验:主机对命令有 `command_to_string`(小端字节序直读),读线程校验 magic;**checksum 已被现代 adb
废弃(实测 adb 37 对 OPEN/WRTE 等数据报文写 0,仅 CNXN 可见非零值)——接收方必须忽略 checksum 不校验**,
发送方仍写正确字节和(新旧主机通吃)。

## 2. 连接与认证(TCP)

### 2.1 通用开头(实测)

1. 主机 connect 成功后**立即发** `CNXN ver=0x01000001 max=0x100000 data="host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send,openscreen_mdns,devicetracker_proto_format,devraw,app_info,server_status,track_mdns"`
2. 设备**尽快回 CNXN**(10 秒内,否则主机判 failed 关连接):
   `CNXN ver=0x01000001 max=256*1024 data="device::ro.product.name=adbhost;ro.product.model=AdbHost;ro.product.device=adbhost;features=shell_v2,stat_v2"`
   - banner 语法:`<type>::ro.product.name=..;ro.product.model=..;ro.product.device=..;features=..`(type=device)
   - 实测:设备早发 CNXN 后主机报 "connected to <ip:port>"
3. 然后分两条路径:**默认 TLS**;设置关闭 TLS 时走**经典 RSA**。

### 2.2 TLS 路径(默认;实测 adb 37 即此路径)

1. 设备发 `A_STLS arg0=0x01000000 arg1=0 data=空`
2. 主机回 `A_STLS arg0=0x01000000`
3. 在同一 TCP 上做 **TLS 1.3** 握手(adb 强制 min=max=TLS1.3,不能协商 1.2):
   - 设备=TLS 服务端,要求客户端证书(needClientAuth)
   - 设备证书:RSA-2048 自签,X.509;主机证书:由主机 `~/.android/adbkey` 派生
   - 设备可在握手 `SetClientCAList` 提示受信任主机密钥,格式:`O=AdbKey-0;CN=<base64(android_pubkey)>`
     (首次连接无受信主机,可不设)
   - 设备校验主机证书:不校验链,取 **公钥指纹(SHA-1,冒号分隔大写十六进制)** 展示给用户授权;
     授权过的指纹存本地(证书 DER 或公钥),后续连接自动放行;拒绝/10s 超时 → 断开
   - **经典 ADB 从不认证设备**(主机侧对设备证书默认接受);设备证书被主机接受无需配对
4. 握手成功后:**设备在 TLS 流内再发一次 CNXN**(同 2.1 的 device banner),此后所有 ADB 报文
   都在 TLS 通道内收发;TLS 模式不再出现 A_AUTH
5. 实测:发 A_STLS 后主机秒回 STLS(抓包确认 0x534C5453)

### 2.3 经典 RSA 路径(兼容模式;AOSP daemon/auth.cpp 行为)

1. 设备发 `A_AUTH arg0=1(TOKEN) data=20B 随机`
2. 主机回 `A_AUTH arg0=2(SIGNATURE) data=256B RSA-SHA1(token) 签名`(主机私钥 ~/.android/adbkey)
3. 设备用受信任公钥验证(java:Signature "SHA1withRSA"):
   - 通过 → 设备发 CNXN(device banner) → 在线
   - 失败 → 再发 A_AUTH TOKEN(换新随机 20B)
4. 主机回 `A_AUTH arg0=3(RSAPUBLICKEY) data=base64(android_pubkey)+' '+user@host+'\0'`
5. 设备弹授权框 → 允许:存公钥 → 发 CNXN → 在线;拒绝:保持 TOKEN 循环/断开
6. android_pubkey 编码(crypto_utils/android_pubkey.c):
   ```
   len u32LE = 64            // 2048/32
   n0inv u32LE               // = -n^-1 mod 2^32(即 (2^32 - n[0]^-1))
   n[64] u32LE               // 模数,小端字序
   rr[64] u32LE              // = 2^4096 mod n(Montgomery R^2)
   exponent u32LE            // 65537
   ```
   之后 `' '` + `user@host` + `'\0'`,整体 base64。SHA-1 指纹对 **公钥编码字节**(不含名字)计算。

## 3. 流与服务(在线后)

- OPEN:主机 `A_OPEN arg0=localId data=服务名字符串` → 设备 `A_OKAY arg0=localId(=主机localId) arg1=remoteId(设备分配)`… 注意:adbd 的 OKAY 回 arg0=设备侧 id、arg1=主机 localId(回显对端);WRTE 同理互带对端 id
- 数据:`A_WRTE`;关:`A_CLSE`(任一方)
- host: 前缀服务不会出现;`root:`/`tcpip:` 直接 CLSE

### shell(实现 v2 + v1)
- 服务名(AOSP services.cpp 语义):`shell[,args]:[command]`——**第一个冒号分割修饰符与命令**;修饰符 v2(启用 shell 协议)/pty/raw/TERM=x,未知参数忽略;`shell:v2` 按语义是命令 "v2" 而非 v2 协议
- v2 帧(在 WRTE payload 内):`streamId u8 + length u32LE + data`,streamId: STDIN=1 STDOUT=2 STDERR=3 EXIT=4 CLOSE=5;EXIT 携带 4 字节小端 exit code;交互式无命令时持续转发
- v1:裸字节流;进程退出即 CLSE
- 执行:`/system/bin/sh -c <cmd>` 或交互 `sh`;rootMode 开启且 `su` 存在 → `su -c sh -c <cmd>`

### sync(实现 v1 + stat_v2)
- 请求:先 `A_OPEN "sync:"`,随后 WRTE 承载 sync 报文;每个 sync 消息 = `id u32(4字符MKID) + arg(路径长度或长度) + data`
- id 常量(file_sync_service.h,MKID(a,b,c,d)=a|b<<8|c<<16|d<<24):
  ```
  LSTAT 0x4154534C  STAT 0x54415453  LIST 0x5453494C  SEND 0x444E4553
  RECV 0x56434552   DENT 0x544E4544  DONE 0x454E4F44  DATA 0x41544144
  OKAY 0x59414B4F   FAIL 0x4C494146  QUIT 0x54495551
  STAT_V2 0x32415453  LSTAT_V2 0x3254534C  LIST_V2 0x3253494C  DENT_V2 0x324E4544
  ```
- v1 STAT 请求:`id+路径长度u32+路径(NUL 结尾)`;应答:`id+mode u32+size u32+time u32`
- v2 STAT_V2 请求带 `id+长度u32+路径`;应答结构按 AOSP `SyncStatV2`: `id, error u32, dev u64, ino u64, mode u32, nlink u32, uid u32, gid u32, size u64, mtime u64(纳秒)`(以 adbhost 源码核对为准,实现时用真 adb push/pull 验证)
- LIST:逐条 DENT(id+mode+size+time+namelen+name),空 DENT 结束(v1)
- SEND:`SEND,pathlen,路径` → 多个 `DATA,len,data` → `DONE,mtimes` → 应答 OKAY/FAIL
- RECV:`RECV,pathlen,路径` → 应答 `DATA..*` + `DONE`(adbd 发 DONE 表示完)或 OKAY+DATA+DONE;以真 adb 验证为准
- 路径映射:入参均为绝对路径;`/sdcard/` 前缀 → root/;`/data/local/tmp/` → root/tmp/;其他 `/x` → root/x

## 4. 主机行为备忘(实测)

- 主机特性列表(2026-11 adb 37):`shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,...` —— 我们**只声明实现过的**:shell_v2,stat_v2(声明没实现的会改变主机行为)
- 主机 `adb connect`:等设备 CNXN 最长 10s;`failed to connect` = 没等到设备 CNXN;TLS 未完成授权时表现为 `offline` 或 `failed to authenticate`
- localhost 的 5554-5585 端口被主机按模拟器特殊处理,测试用非保留端口或真实 LAN IP
