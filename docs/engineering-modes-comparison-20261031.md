# 两套工程模式与16:12实车诊断复核

分析日期：2026-10-31（Asia/Shanghai）。仅离线检查用户提供的文件；没有连接车机、调用工程组件、修改属性或权限，没有修改运行代码或重新构建APK。

## 输入与完整性

- 诊断：`/Users/hongjunlong/Downloads/ES-ADB诊断-20261031-161227.json`。
- APK目录：`/Users/hongjunlong/Downloads/ES-系统APK-20261031-161230`。
- JSON与提取清单的collectionId均为`1793434334419`。两包均完整，合计31,244,717字节；本地大小和SHA-256与提取清单及车端sha256sum输出一致。
- JSON内采集起止是2026-10-31 16:12:14.419至16:12:22.830；文件名时间不是探针运行时间。
- 安装应用为0.3.7 / versionCode 13。72条命令均完成传输、未截断，但传统shell没有退出码，不能等同于所有命令业务成功。

| 项目 | 原先用于开启ADB的工程模式 | 本次另一套工程模式 |
| --- | --- | --- |
| 包名 | `com.mega.app.engineeringmode` | `com.mega.engineeringmode` |
| 安装目录 | `/system/priv-app/MegaEngineeringMode` | `/system/priv-app/RedwoodEngineeringMode` |
| versionCode / versionName | 33 / `13` | 1 / `1.0.20260304233723-release/rocky_hqx.4.5.5_20251024` |
| 字节数 | 15,650,869 | 15,593,848 |
| SHA-256 | `ad47bcc7be6ece6c538efec46a2851501acc5d06c71c635cd8b4131e834b1847` | `1c97fc4fbf05cf5e6708cf2c4f07eacd30ac65e422d346ca6285bec2d9ac7fff` |
| 实车UID | 1000，`android.uid.system` | 1000，`android.uid.system` |

第一包与此前`MegaEngineeringMode_13.apk`完全相同，不是更新版。第二包是不同包、不同菜单实现；不同包的版本号不能直接比较新旧。两包证书SHA-256相同：`83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d`。报告显示它们的共享系统UID已有车辆权限，不能把这种权限归因于包名，也不能由普通应用声明相同包名/共享UID取得。

## 1. 最新实车报告证明了什么

### 讯飞消费端：连通，但仍没有有效挡位

报告保留的PlatformVehicleProbe实际运行于16:04:30.090至16:05:07.529，约37.439秒，早于16:12导出。源为应用原UID10091，不是shell或系统UID。

- 已连接`com.iflytek.autofly.platformservice/.SystemService`，服务UID1000。
- unique-code-ready及service-ready各一次；这些不是车辆状态回调。
- 注册返回0，一次查询返回`gear=0`，`readValueRecognized=false`，可能为旧缓存。
- events中没有挡位状态回调；`droppedCallbacks=0`。停止后注销返回0，无待清理项。
- 结束态`registered=false`和`connected=false`是停止清理后的状态，不应误读为从未连接/注册。

因此用户此前看到的“0、变化0次”有了诊断佐证：客户端注册成功不等于上游提供有效数据。这个报告不能区分上游未适配、无有效状态或其他服务端问题，也不能单凭37秒无回调证明该接口在所有场景永久不可用。不要继续把相同消费端绑定/注册包装成新方案。

### Mega权限与调试属性

- 当前App的`CAR_DRIVING`、`ENTRY_LOCK`、`VEHICLE_CONTROL`、`SEQUOIA`仍为`granted=false`。
- ADB身份仍为UID2000 shell，SELinux为Enforcing；没有root证据。
- `persist.mega.car.userdebug`返回空字符串，报告正确记为`empty`，不是已开启，也不直接等于已证实false。
- 车端RedwoodCarService哈希为`38913715358e7556ef9a750d20d693349b3863eec5dc5f632965acb1e727ec32`，与此前分析样本一致。因此之前发现的`CarImpl.assertPermission`调试分支适用于同一APK样本；没有证据表明本次启用了该例外。
- 本次`nativeProbeRequested=false`，没有重新执行四类Mega读值测试；不能声称本次再次实测了四次权限异常。
- 0.3.7没有采集`persist.mega.need_auth`，本报告不能判断“关闭鉴权”的当前开关状态或效果。

定量日志240行，其中9条SELinux find拒绝对应shell枚举gatekeeper、dnsresolver、incident、installd、mdns、netd、suspend_control、suspend_control_internal、vold。它们不是本次App车辆读取/消息总线订阅被拒的证据。仍有MessageProperty状态日志，不等于已取得非日志接口。

### TCP ADB

- `vendor.adbd.disable_tcp=false`，`persist.adb.tcp.port=5555`，监听输出包含`*:5555`。
- Wi-Fi接口为`192.168.171.34/24`。报告实际连接目标仍是本机`127.0.0.1:5555`。
- 监听命令同时出现`Cannot open netlink socket: Permission denied`，不能把快照当完整进程归属/网络策略证明。
- 这些支持TCP ADB正在监听，但没有从另一台局域网设备验证可达性；IPv4/IPv6绑定、防火墙和网络隔离仍未确认，`lanReachabilityVerified=false`不能改成true。

## 2. 两套菜单里的“授权/ADB”不是同一件事

### 原工程模式的“关闭鉴权”

此前原始DEX已确认：`InfoActivity`读写`persist.mega.need_auth`，默认true，点击取反。它不是车辆权限检查读取的`persist.mega.car.userdebug`。本次第一包哈希完全相同，结论不变；need_auth的真实消费者、是否间接影响车辆服务仍没有证据。

### 另一套工程模式的ADB切换

`com.mega.engineeringmode.function.AdbActivity`读取`sys.usb.mode`显示状态。两个按钮经合并点击监听器分别调用`b.f.p`，实际是`SystemProperties.set`：

- ADB按钮：`persist.vendor.usb.mode=device`。
- USB按钮：`persist.vendor.usb.mode=host`。

这是USB角色切换；这个点击链本身没有设TCP端口，也没有车辆授权操作。不要把它与原工程模式的`vendor.adbd.disable_tcp`按钮混为一谈。

### “授权记录查询”

另一套工程模式的`main_btn_permission`点击链发出`ota.intent.action.History`广播，指定包`com.mega.carsettings`并附`path=/sdcard`。可确认这是请求历史记录的入口，不是给指定第三方包授予Mega车辆权限；没有运行该操作，也不预断历史记录文件内容。

### 导出的服务

`DidControllerService`与`IcEngService`虽exported，但各自声明`signature`权限；MainActivity也要求signature权限。车端包管理器输出与Manifest一致。

- DidControllerService实际处理工厂测试RGB显示，onBind返回null。
- IcEngService启动路径导出日志到USB并回写导出状态，onBind抛出未实现异常。

两者都不是给本App使用的通用只读车辆回调服务，不调用它们做代理。

## 3. 消息总线：有更具体的订阅证据，但没有开放授权证明

此前已知原工程模式使用`vendor.mega.message_center@1.0::IMessageCenter/default`。这次另一套工程模式补齐了具体CAN页面调用链，不是第一次发现总线，更不是已确认绕开权限。

调用链：

```text
CanDataViewModel
  -> v.i（MessageCenterUtil）
  -> l1.a（IMessageCenter，HIDL客户端）
  -> HwBinder.getService("vendor.mega.message_center@1.0::IMessageCenter", "default")
  -> 批量订阅topic / 接收回调
```

- `engine_can_data_insert.json`含176对映射，`engine_can_data_insert_out.json`含50对映射；它们是逗号分隔映射片段，不是独立合法JSON对象，页面按字符串拆分。
- 两组topic都走订阅；“发送数据页”在此处是观察发送方向数据，不应因页面名字就认定构造器主动发控制包。
- 原始DEX核对了HIDL描述符、映射加载、批量订阅transaction 4及取消transaction 5。该客户端还包含发送transaction 1，不能复用整个工程工具当只读后端。
- 接收回调解析JSON的`valid`和`value`，无效时显示`--`；不能把无效样本强转成0。
- 映射包含`ABS_2C0/AVHSts -> T1CF1/AutoHold`。这只是静态工程映射，`T1CF1`不等同于当前`E0X_REEV`车型，不能直接用于音效或据此声称四类事件齐全。

两份DEX及native库`libengineeringmodeso.so`中均没有这两个调试属性键的字节串：`persist.mega.need_auth`、`persist.mega.car.userdebug`。这只限制本样本的直接常量引用，不排除拼接、外部framework或vendor进程消费，不能证明全系统不存在其他入口。JADX整体报告23处错误；关键调用链已与原始DEX核对，没有据反编译失败处做肯定结论。

## 4. 下一步与停止条件

现阶段仍是日志路径有已验证的实际数据；没有确认可替代它的普通App非日志数据源。但不应把“当前未验证”说成“所有非日志接口都不可能”。

优先研究上述HIDL消息总线的服务端与只读可达性，而不是再次复测同一讯飞消费端：

1. 先辨别本机实际消息中心服务及安全策略。这类vendor服务可能是native二进制，不一定有可填包名提取的APK；当前两包只有客户端，不能推断服务端没有鉴权。
2. 若后续制作诊断，分别记录服务发现、原App UID的访问结果、订阅返回、真实回调、valid与注销；无权限或SELinux拒绝立即结束，不切换UID、改属性或发控制/注入消息。
3. 成功拿到有效回调后，再校验E0X当前车型topic、状态编码和新鲜度。注册成功、工程映射存在、初始0值或旧缓存均不算通过。

本轮只保存研究结论，不发布新版本，不要求用户为了检测切挡或行驶。
