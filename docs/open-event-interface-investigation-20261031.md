# 开放车辆事件接口：四包实码复核

日期：2026-10-31。来源：用户提供的`ES-系统APK-20261031-002355/`及同名ZIP；采集ID为`1793377386182`。本轮仅在电脑上核验和静态分析，没有连接车机、启动/绑定系统组件、查询Provider、发送广播或执行车辆指令；没有修改App运行代码或生成新安装包。

后续补充：同日已收到并复核PlatformService及00:23诊断。服务端挡位查询/回调有真实转发实现，存在缓存回退，普通App可用性尚未实测；SceneEngine实际UID为10025，不能仅凭Manifest判为system UID。更新结论见[平台服务实码复核](platform-service-investigation-20261031.md)；下文保留四包分析时点，不再要求重复提取已收到的平台APK。

## 直接结论

1. **0.3.5的四包提取成功**：四个APK与清单字节数、SHA-256及原ZIP内容全部一致，ZIP CRC检查通过；合计340,814,803字节，11个DEX。不是空包或仅提取了安装路径。
2. **四包中尚未确认普通第三方可用的门、挡位、中控锁、AutoHold事件出口**。场景服务确实有查询和回调，但已追踪到的通知是场景服务就绪、场景配置和场景执行状态，不是这四种车辆原始状态。
3. **发现值得继续核查的具体新宿主**：场景引擎通过讯飞`FlySystemManager`读取挡位，客户端协议有`getVehicleState("gear")`和`onVehicleStateChanged(String, int)`，默认连接`com.iflytek.autofly.platformservice`。该包在10月30日23:33实车清单中确实存在，但不在本次四包内。客户端接口存在不等于服务端允许本App访问，也不是四类信号都已找到。
4. 因此不能说“已经找到可用开放接口”，也不能说“只剩日志”。下一步应定点核查讯飞平台服务的实现、授权及数据源，而非再盲扫或重跑已拒绝的Mega读取。

## 样本核验

完整文件哈希、版本、DEX哈希、Manifest权限和组件信息见[四包清单](apk-research-20261031/apk-inventory.json)；关键调用的原始DEX指令见[字节码证据](apk-research-20261031/key-dex-evidence.txt)。

| 文件 | 实际包名 | 字节数 | versionCode |
| --- | --- | ---: | ---: |
| RockyEcologyCenter.apk | `com.mega.ecology` | 27,711,605 | 1 |
| RockySceneEngine.apk | `com.iflytek.autofly.sceneengine` | 131,639,358 | 25102221 |
| RockyLauncher.apk | `com.android.launcher3` | 162,946,695 | 30 |
| RockyLionSceneModeService.apk | `com.lion.scenemode` | 18,517,145 | 260115 |

四包均声明`android.uid.system`，签名验证通过，证书SHA-256均为`83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d`，与此前智行生活和车辆系统样本相同。Manifest声明和证书不替代本轮运行时UID/授权结果，更不会让本App加载其SDK后继承系统身份。

## 一、生态中心：明确的出口仍有签名限制

`com.mega.ecology`的三个主要导出服务：

| 服务 | 入口权限 | 实际范围 |
| --- | --- | --- |
| `EcologyMediaService` | `com.mega.ecology.export.permission.MediaService`，signature | 媒体 |
| `NaviService` | `com.mega.ecology.export.permission.NaviService`，signature | 导航 |
| `PhoneCallService` | `com.mega.ecology.export.permission.PhoneCallService`，signature | 通话 |

Manifest中三项保护级别均为`0x2`，不是仅因`exported=true`就能绑定。HiCar应用启动逻辑确实读挡位，但`HiCarAppListManager.startApplication()`调用的是`MegaCarProperty.getIntProp(Driving.ID_DRV_INFO_GEAR_POSITION)`，用于非P挡视频限制；不是向普通第三方发布挡位。

包内还带有旧`CabinProxy`/`VehicleProxy`，能看到门、挡位、AutoHold的get/register函数，但其`ServiceDef`把车辆模块6指向`com.lion.appfwk.vehicle/.service.LTCarService`。10月30日23:33提供的安装列表未列出这个包，不能因为SDK里有函数就判定实车上有可用服务。此结论只针对该次提供的清单，不冒充当前全用户安装结果。

## 二、原厂桌面：挡位用于内部限制

- `AppStarter.checkGearPosition()`仍调用`MegaCarProperty.getIntProp(Driving.ID_DRV_INFO_GEAR_POSITION)`，限制只能在P挡启动的应用；已用DEX指令核对。
- `LauncherProvider`是桌面数据库入口，Manifest分别要求`READ_SETTINGS`和`WRITE_SETTINGS`（该包声明`0x3`级别）；不是车辆状态Provider。
- 壁纸Provider/服务、数字人`VpaCommandService`、应用拖动/编辑广播处理的是壁纸、数字人和桌面行为。`app_move`等存在指向SystemUI的定向发送，不能当成通用车辆状态广播。
- 本次检查的发送方法及相关封装未确认四类原始状态的第三方发布链路；不是对全部原生库、反射或所有其他进程的不存在证明。

## 三、场景模式服务：有真实回调，但回调的不是车门/挡位

`SceneModeApiService`在Manifest中明确导出，未在application/service上声明入口权限；`BaseBusService.onBind()`返回Bus Binder。可见`BusServiceBinder`注册/请求链路中未发现额外调用者权限检查，仍不能据此承诺本App实车可访问或可安全启动。

- 请求包含`isServiceReady`、`getCurExecutingScene`、`getSceneModeList`、`getSceneModeByType`、`getCommonConfig`等，也混有`startScene`、`stopCurrentScene`、`updateActionsOfSceneMode`等修改/执行入口。`getCurrentUser`分支在本样本中返回不支持，不视为可用查询。
- `registerNotifyCallBack()`注册客户端；`addClientNode()`在服务已就绪时回调事件3。
- `EventType`列出3=服务就绪、4=场景配置更新、5=服务销毁、6=场景状态变化、7=用户切换后的场景更新等。
- 已追到`SceneExecutor.notifyState()`发送事件6，Bundle只写场景`type`与`state`，不是车门开关、挡位、锁或AutoHold的值。不能把“场景状态更新”偷换成“车辆属性订阅”。
- 该包的`CarInfoControl`/`PlatformAPIManager`读取车门、挡位、锁、AutoHold仍落到Mega属性。新门位置`Signal.ID_DOOR_CURRENT_POSITION`、中控锁`Signal.ID_DOOR_CENTRAL_LOCK`属于`Signal`域，该SDK注解要求`mega.car.permission.VEHICLE_CONTROL`，不是换个属性号就绕过已知权限拒绝。
- `isAutoHoldOn()`读取的是`Sequoia.ID_AUTO_HOLD`，且包含车型分支；仅凭此包装函数不能证明得到“刹停时保持已介入”的事件。启用开关、可用状态、实际介入要分开。

不运行此服务作为试验：其App启动过程会启动CoreService，API服务还初始化车辆SDK；必须把生命周期和场景执行副作用纳入审查，不能把绑定动作一律当无副作用读取。

## 四、讯飞场景引擎：排除两个误判，定位下一层

### 不是车辆事件出口的入口

- `VpaDataService.onBind()`返回`SceneEngineBinder`。其AIDL对外是`pushData`、Avatar回调和PGS回调；`pushData`把外部输入送入场景决策，不能拿来伪造车辆状态，也不是读取原始车辆属性的函数。
- `VpaService`/`ActiveCareService`等启动路径能驱动语音唤醒、TTS或主动关怀，不发送这些Intent做试探。
- 确实存在导出的`StateContentProvider`，authority的实际拼写是`com.ifltyek.autofly.appstate`（不是常见的iflytek）。其query仅匹配`table_app_state`及其子路径，落到SQLite的`name/value`表。可见业务写入方保存`currentActivity`、`currentPackage`、`currentcharacter`；不是已发现的门/挡位表。
- `OtherStatus.getBackupStatus()`虽然查询倒挡，但没有发现它被写入上述Provider表的链路；不能因为同包里有倒挡函数就声称Provider能读倒挡。该Provider也没有本轮可确认的车辆数据`notifyChange`链路。无需先把用户活动记录整表导出。

### 真正的新线索：讯飞平台服务

明确的客户端链路：

`OtherStatus.getBackupStatus()` → `SystemStatusManager.getGearReverse()` → `FlySystemManager.getVehicleState("gear")` → `ISystemServiceAIDL`。

- 服务action：`com.iflytek.autofly.systemservice`。
- 宿主由只读属性`ro.autofly.service.system`决定，默认`com.iflytek.autofly.platformservice`。
- 客户端定义挡位枚举：1000=`GEAR_REVERSE`、1001=`GEAR_NONE`、1002=`GEAR_PARKING`、1003=`GEAR_DRIVE`；1001不能擅自等同N挡，需服务端核对。这是客户端约定，不是实车有效样本。
- `ISystemServiceAIDLListener.onVehicleStateChanged(String, int)`提供状态变化回调协议；调用方代码也有注册/注销接口。回调存在不代表目标服务已发出有效挡位或第三方允许注册。
- 同包还带`CarPropertyManager`，通过action `com.iflytek.autofly.carpropertyservice`连接`ro.autofly.service.carproperty`指定的包（同样默认platformservice），具有`getCarProperty`和变化回调。当前调用方可见101～117一组油量、续航、速度、空气质量、车窗等ID，未由此确认门、锁、AutoHold可用。
- SDK接口同时包含升级、重启、恢复出厂、EEPROM写入及属性set方法。因此不直接套用整个SDK初始化或“枚举调用”，也不将导出组件的缺少检查当作代读权限的许可。

实车已有路径证据：`/system/app/RockyIflytekPlatformService/RockyIflytekPlatformService.apk=com.iflytek.autofly.platformservice`，来自10月30日23:33包列表。**下一份有明确依据的分析对象只需这一包，不必重复拉取本次四包和已拿到的SystemUI。** 若有本轮诊断JSON，可先核对是否已包含它的组件及权限；不能凭客户端默认值假定设备属性未重定向。

## 下一步与停止条件

1. 定点取得平台服务APK，以及`ro.autofly.service.system`、`ro.autofly.service.carproperty`、其包元数据；只读取，不改这些属性。
2. 确认真实宿主、组件导出/入口权限、Binder内部签名/UID/合作方限制，以及get/register/注销的实现和数据源。若只是内部未授权入口或明确拒绝，不以伪装包名、系统UID、通用请求接口或工程开关解决。
3. 仅在确认有明确第三方用途和无车辆写入副作用后，才考虑在车辆检测内加入本App原UID的窄范围只读验证；不启动车辆控制、不播放音效。合法合作接口的授权由厂商决定，shell成功也不当作本App成功。
4. 挡位协议不等于四类事件全覆盖；只找到一项就只记录一项。任何实车验证都不要求切挡、行驶或绕开原厂安全限制。

## 方法与限制

使用本地Android build-tools核对Manifest、版本及签名，解出11个DEX，使用JADX 1.5.3反编译并交叉核对关键DEX指令；广播发送扫描只是定位辅助，没有当作完整调用图。JADX分别报告生态中心9、桌面100、场景引擎15、场景模式7处恢复错误，不能把未恢复方法或未扫描到的动态路径当成不存在。

完整Java、DEX、Manifest和日志在忽略目录`build/open-event-interfaces-20261031/<APK简称>/`，后续构建会清理build。仓库只保留清单、少量关键字节码与本文，原上传材料仍在Downloads。**本轮没有实车访问结果，没有新事件后端，没有新APK；当前交付版本仍为0.3.5。**
