# 讯飞平台服务：挡位查询与回调实码复核

日期：2026-10-31。仅电脑端静态分析；没有连接车机、调用车辆接口、启动或绑定系统组件，也没有修改App运行代码或编译APK。当前安装包仍为0.3.5。

## 结论

**这次找到的不再只是客户端声明，而是服务端实际实现的车辆状态查询和变化转发。挡位是有依据的候选，但仍未证明本App可以获得新鲜实车数据。**

- `SystemService`与`CarPropertyService`在样本中均导出，默认资源均启用，`singleUser=false`；application和这两个service未声明入口permission。
- 检查的onBind、Binder分发、读取及监听注册路径未发现额外签名/UID/权限判断。`enforceInterface`检查协议描述符，不能当成系统签名鉴权；CarProperty实现的caller UID/PID记录是日志，不是授权判断。这不是整个系统不存在拦截的证明，更不是厂商授权声明。
- `getVehicleState("gear")`有真实转发实现；监听端也有真实`onVehicleStateChanged(String,int)`通知实现，不是空函数。
- 平台服务是中转层，读值还依赖注册到CustomSystem的数据提供者；这份APK没有给出其真实车型实现。不能认定平台服务自己读取了Mega，或既有CarControlAdapter必定就是该提供者。
- 查询在数据提供者不可用并返回`-1003`时会读SharedPreferences。一次返回1002不能证明实时P挡；ready回调、注册成功和实时状态变化也必须分开记录。
- 门、中控锁和AutoHold尚未确认由这条链提供，不把挡位协议推广成四类事件全部可用。此前shell直读Mega四域被拒的结论不变。

## 样本与来源

| 项目 | 核验结果 |
| --- | --- |
| 用户文件 | `/Users/hongjunlong/Downloads/PlatformService_8155_E0X_8295_1.0.4220251022.apk` |
| 字节数 | 827,359 |
| SHA-256 | `bd1bb28896dd63484d536370f5d489ed20bd35b7f43d1c8c4bd1d964faf7182e` |
| 包名 | `com.iflytek.autofly.platformservice` |
| versionName / versionCode | `8155_E0X_8295_1.0.4220251022` / `20251022` |
| minSdk / targetSdk | 26 / 28 |
| 身份声明 | `android.uid.system`，persistent application |
| 签名验证 | 通过；证书SHA-256 `83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d` |
| classes.dex | 555,224字节；SHA-256 `8223236135e61fbcd4912ee179b3f7a8bfa99f77d10f701206aea1f544ac75cb` |
| 解包 / 反编译 | ZIP CRC通过；JADX 1.5.3完成，无最终错误汇总；registerListener有恢复警告，已复核DEX |

这份APK是单独上传，不属于四包ZIP的采集清单。实车清单的包名和路径与之对应，但没有同次采集哈希，不能宣称APK字节已与实车版本完全绑定。原始DEX的少量关键证据见[平台服务证据](apk-research-20261031/platform-service-evidence.txt)。完整分析在忽略目录`build/open-event-interfaces-20261031/PlatformService/`，构建会清理该目录。

## 查询、通知与缓存

查询链：

```text
场景引擎 FlySystemManager.getVehicleState("gear")
  → platformservice.SystemService.onBind
  → platformservice.a.v.getVehicleState
  → PlatformApplication["custom_system"] / customservice.a.r.b(String)
  → 已注册数据提供者的 onGetVehicleState(String)
  → 若结果为 -1003，回退到 FeiYuOS SharedPreferences
```

`SystemService` action为`com.iflytek.autofly.systemservice`；客户端宿主由`ro.autofly.service.system`决定，默认platformservice。当前诊断没有读取该属性，不硬编码默认值就是实车宿主。

通知链：数据提供者调用`customservice.a.r.notifyVehicleStateChanged` → Handler消息2 → `platformservice.a.v.b(String,int)` → 普通消费端listener的`onVehicleStateChanged`。通知携带类型和整数，没有源时间戳/质量标识；本App接收时间不能当作车辆采样时间。

消费端registerListener加入监听列表、linkToDeath，依初始化状态发送unique-code/service-ready通知；未见其自动给每个状态发送初值。DEX确认service-ready受`g=true`条件控制，不能沿用JADX恢复代码中错误的`!g`。注销移除消费端listener。CustomSystem那一套registerListener则注册**数据提供者**，用途不同，不能把本App注册为数据提供者或伪造状态通知。

初始化`h()`读取screen/power/gear并写入SharedPreferences；变化转发`b(String,int)`本身未同步刷新该缓存。因此查询回退尤其可能是初始化时的旧值，不能仅靠连续多次读相同数值证明实时性。客户端枚举1000=REVERSE、1001=GEAR_NONE、1002=PARKING、1003=DRIVE，尚不是车型适配器输出的实车校准结果，1001不擅自标成N挡。

## CarProperty路径及数据源缺口

`CarPropertyService` action为`com.iflytek.autofly.carpropertyservice`，客户端路由属性为`ro.autofly.service.carproperty`。读取实现`platformservice.a.i.a(int)` → `customservice.a.h.a(int)` → 数据提供者的`onGetCarProperty(int)`；没有Custom对象或provider时返回null。变化通过Custom的`notifyReceiveCarProperty`转发给消费端。

这证实它是实际中转实现，但并未确认门、锁或AutoHold的可用ID、区域、编码、支持范围与数据提供者。平台APK资源中的`config_iflytekAdapterComponents`只列出`E0XRadioAdapter`，不能拿该内置收音机适配器填补车辆数据源缺口。

已有CarControlAdapter研究文档与属性目录仍在，但本轮没有找到文档所指的旧完整APK/反编译目录；不能将旧客户端SDK目录当成本轮确认的CustomSystem提供者，也不要求用户重复上传已经取得的四包或平台包。

## 生命周期与验证边界

读取方法未见车辆写入，但**首次绑定并不保证整个进程无副作用**：

- Application启动会向自身发`SERVICE_CHECK`，接收器启动服务巡检线程；线程按资源和路由属性启动各模块。
- System服务初始化还可能生成/更新`persist.sys.navi.uuid`和`persist.sys.navi.vin`。
- 同一Binder还包含属性写入等方法；不复用整个SDK自动初始化、不调用set、升级、重启、恢复出厂、notify或Custom provider注册。

后续若加入检测，先只读确认真实路由、组件是否启用及现有运行状态；任何会唤起未运行系统进程的操作应单独说明并征得同意。授权范围明确后，仅用本App原UID验证消费端查询/监听，并做有界超时、注销和解绑；不借shell成功代替本App结果，不绕过拒绝。日志分开记录绑定、读取返回、ready、状态回调和数据新鲜度，首样本静默，不接入播放。没有新鲜状态变化证据时标记为“候选/缓存可能”，不标记为“实时挡位可用”；无需要求用户行驶或为验证专门切挡。

## 同批00:23诊断复核

`ES-ADB诊断-20261031-002358.json`：SHA-256 `b7d25fb8a0163dce0b1f36f56e06dbb9170da2152b0f0c8e4d445966c1db186a`，开始于2026-10-31 00:23:06.182 +08:00，耗时38.275秒。59条命令传输完成、无截断；旧shell协议不提供exitCode，不能称为59条业务执行全部成功。四包提取信息与ZIP清单逐字段一致；本轮没有运行已知失败的native probe。

实测SceneEngine为UID10025，并非1000；其Manifest虽声明sharedUserId，另有sharedUserMaxSdkVersion=32。该包仍是平台签名priv-app，不能用它能调用来证明普通自签名App也能调用。另三个候选包实测UID1000/shared system。本App仍为UID10091，四项Mega权限false。

诊断确认平台包安装路径，但没有平台包的定点组件/授权dump和上述两个路由属性；本轮上传的APK补齐了静态实现，没有补齐本App的实车访问结果。最近日志没有四类有效变化，且本轮未运行probe，不能由“无异常”推断权限已解决。
