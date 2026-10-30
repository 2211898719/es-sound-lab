# 新提取车机 APK：车辆事件、音频与空调接口分析

分析日期：2026-10-30。目标为用户当前星纪元 ES 车机提取的11个新 APK；不是对所有年款的适配声明。本次做清单/证书核对、Java反编译、属性注解整理及关键Smali核对，没有安装应用、连接车机或下发车辆/音频指令，也没有修改App运行逻辑、版本、签名或生成安装包。

## 可以据此推进的方向

1. **不依赖日志的音效有明确接口基础**：MegaCar 的属性读值和回调已找到，不必从零猜协议。但普通侧载应用是否获准、真实属性是否有效，仍要逐层验证。
2. **车外音频的线索比此前完整得多**：原厂车外声音使用厂商 `usage=2007`、对应音量组及 `outside_mode`。这为当前BUS设备路由提供解释，尚不能宣称只改usage就会响。
3. **空调有直接属性与现成语音车控服务两种研究路线**；温度编码、区域和车型分支已找到，不能照搬某个数值写入。
4. **智行生活的价值成立，但安装来源不能代表权限身份**：用户确认它从应用商店安装；本次提取包由同一车机平台证书签名，与普通自签名App有差别。
5. **桌面/空调“找不到路径”应先解决包名与位置**：本批“设置”是 `com.android.car.settings`，并非车控代码引用的 `com.mega.carsettings`。`com.mega.redwoodhvac.voicelib` 是Provider authority，不能直接当APK路径。

## 样本与可复核材料

| APK名称 | 实际包名 | 本次价值 |
| --- | --- | --- |
| MegaEngineeringMode_13 | `com.mega.app.engineeringmode` | ADB开关实码、USB模式、Mega消息中心调用 |
| 智行生活_1.2.0.3 | `com.chery.aiot` | 平台签名、权限、官方按键/旋钮协议资产；业务DEX被加固 |
| CarControlAdapter | `com.iflytek.autofly.carcontroladapter` | Mega SDK、属性编号、空调语音服务和控制封装，最有价值 |
| 车外声音 | `com.chery.auto.carsound` | 实际音频输出、焦点、音量组、内外切换 |
| 情景模式服务 | `com.lion.scenemode` | 场景查询/执行Binder、车辆/空调封装 |
| Car service_13 | `com.android.car` | Android CarService代理壳，真正实现包另行定位 |
| MegaAudioTestTools_13 | `com.mega.audiotesttools` | CarAudio音量组、厂商音频参数调试界面 |
| MegaConfigService | `com.mega.config` | 配置Provider；写入路径有UID检查 |
| Script executor service | `com.android.car.scriptexecutor` | 车载遥测Lua执行器；显式只允许系统调用 |
| 设置_1.0 | `com.android.car.settings` | 基础Android车载设置；不等于原厂空调/车辆设置App |
| 应用商店 | `com.lion.appstore` | 安装/应用管理及集成SDK；不能将其安装白名单理解为车辆权限白名单 |

全部文件名、版本、文件SHA-256、证书SHA-256、声明权限及显式exported组件见[样本清单](apk-research-20261030/apk-inventory.json)。

本地完整材料在外层工作区 `analysis/vehicle-apks-20261030/<样本简称>/`：`resources/AndroidManifest.xml`、`sources/`、`certificate.txt`、`jadx.log`；关键字节码在 `smali-evidence/`。完整APK和反编译源码未纳入仓库。

JADX对部分APK报告方法恢复错误，数量已写入清单。不能把含 `throw null`、`Method not decompiled` 的Java当作真实有效控制实现。智行生活虽报告0错误，但DEX头部 `class_defs_size=4`，普通反编译只得到加固壳；0错误不代表业务代码可见。

从CarControlAdapter的运行时注解提取3130条属性声明，选出门锁、驾驶、空调及相关信号275条存入[属性目录CSV](apk-research-20261030/mega-property-catalog.csv)。这是SDK定义目录，不是实车支持/已授权/有有效值的列表，包含发布主题也不代表应当写入。

## 智行生活为什么值得看，又为什么不能直接类比

本批10个APK，包括智行生活、工程模式、Car service、CarControlAdapter及车外声音，证书SHA-256相同：

```text
83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d
```

证书主体标注 `Megatronix chery-8295 platform CA`。另一个Script executor样本使用不同的releasekey证书。证书经本地 `apksigner verify --print-certs` 核对，不仅比较证书显示名称。

智行生活没有声明 `android.uid.system`，但声明了 `mega.car.permission.VEHICLE_CONTROL`、`CLIMATE`、`ENTRY_LOCK`、`SEQUOIA` 等，以及原厂设置的合作业务/Slice权限。**应用商店后装 + 独立UID + 平台签名可以同时成立**。同平台签名是其可能获准访问signature接口的关键证据；具体已授予哪些权限、是否还有包白名单，仍需车机 `dumpsys package com.chery.aiot` 与权限定义确认。

本项目原0.2.x证书并非该证书。公钥证书可用于核对身份，不能拿它重新签出平台应用，也不应通过替换包名冒充智行生活。

能直接看到的官方配件资产：

| 资产 | 配件 |
| --- | --- |
| `assets/protocolData/pid_119.json` | 物理按键 |
| `pid_121.json` / `pid_126.json` | 灵动按键 |
| `pid_133.json` | 智能旋钮 |
| `pid_23.json` | 车载空气净化器 |
| `pid_111.json` | 儿童座椅灵犀 |
| `pid_122.json` | 智能香氛机 |
| `pid_124.json` | 蓝牙音箱 |

其中 `pid_119.json` 的 `ssid=1` 服务包含具体能力，已不只是配件名称：

| 能力 | 配件属性piid | 资产描述 |
| --- | --- | --- |
| 主驾 / 副驾温度 | `12` / `1` | 15～31，步长1 |
| 风量 / 出风模式 | `13` / `14` | 上拨、下拨 |
| AUTO / AC | `15、16` / `17、18` | 分别定义指示灯与拨动 |
| 内外循环 / 温区同步 | `19、20` / `21、22` | 分别定义指示灯与拨动 |
| 前除霜 / 后除霜 | `23、24` / `27、26` | 分别定义指示灯与拨动 |
| 主驾 / 副驾座椅加热 | `28、29` / `30、31` | 分别定义指示灯与拨动 |
| 手套箱 | `32、33` | 指示灯与拨动 |

这些是**配件协议的属性编号**，须结合pid及服务ssid解释，不是Mega车辆属性ID或CAN报文ID；温度范围也只是该配件资产的约束，不能直接用作车机写值范围。`res/values/strings.xml` 还保留 `str_control02Cmd029`风量+、`030`风量-、`051/052`主驾温度±、`053/054`副驾温度±、`055`空调循环模式及`062`空调开关等功能文案。这直接印证官方配件可以承载哪些空调操作，但尚未暴露实际授权和车辆调用链。

Manifest里还暴露 `TestReceiver` 与 `com.cheryaiot.action.settemperature` 等action，但加固后看不到可读的业务实现，extra、权限检查和执行条件未确定。不能只凭action名字构造“已经可用”的调温请求。蓝牙按键事件到空调控制的完整链路本次未还原。

## MegaEngineeringMode：ADB的开关在哪里

已找到 `AdvancedOptionsActivity.onClick()` 中 `enable_tcp_adbd` 分支：

```java
SystemProperties.set("vendor.adbd.disable_tcp", "false");
```

该分支与Smali核对一致。这是工程模式的TCP ADB使能开关；APK本身以系统UID运行，能写该属性不意味着普通App也能写。

USB模式代码 `engineering/sources/f/a.java` 另有 `sys.usb.mode` / `sys.usb1.mode` 的host/device切换，及对应 `device_mode=enable_oneshot` / `disable_persist`。因此不能承诺所有调试开关跨整机重启保持。

用户已通过此应用打开ADB并提取本批APK，**ADB可用不再是未知项**。但下列条件仍不能从APK推出：实际连接端口、shell是否root、`adb root`是否允许、SELinux是否限制车辆服务、是否有无线调试配对界面。不要默认端口5555或宣称获得完整系统权限。

工程模式还有一个独立的消息通道：

```text
vendor.mega.message_center@1.0::IMessageCenter / default
```

`common/MegaConnector.java` 使用HIDL代理 `t.a` 订阅/取消订阅topic、接收字节数组并发送消息；`t.a`显示transaction 1发送、2订阅、3取消订阅。这证明日志背后有消息总线，并非只能读取logcat。

但当前工程模式可见调用以调试/工程topic为主，没有证明普通App可以订阅所有车辆topic。HIDL/SELinux及服务端策略仍可能限制shell或应用。它是次级研究路线，先用MegaCar属性接口；不要把导出的 `EngineerModeCommandService` 当通用权限代理，该服务可见业务是清除DDR数据，不适合音效接入。

## 不依赖日志的音效：接口已明确，尚缺可用性验证

### 直接订阅路径

CarControlAdapter内有完整客户端链路：

```text
MegaCar.createCar(context, handler, timeout, lifecycle)
  → com.mega.car.MegaCarService / mega.car.ICar
  → getCarManager("property")
  → mega.car.hardware.property.CarPropertyManager
  → getProperty(Class, propertyId, areaId)
  → registerCallback(CarPropertyEventCallback, SparseArray<Float>)
  → onChangeEvent(CarPropertyValue)
```

Binder服务名是 `com.mega.car.CarService`。SDK中保留旧的 `mega.car.CarPropertyManager`，而本次 `MegaCar.createCarManagerLocked()` 实际创建hardware路径的新类，不能混用两种回调类型。

`CarPropertyValue`提供ID、区域、status、原始值、时间戳；`status=0`才表示available。SDK默认sticky回调会分发初值，因此音效仍需“初值建基线，后续有效变化才播放”，不能注册成功就播报。

### 与音效相关的已确认编号

| 状态 | ID | SDK类型 | SDK声明的权限域 |
| --- | --- | --- | --- |
| 主驾/副驾/左后/右后门 | `0x0600002c`～`0x0600002f` | Integer | `ENTRY_LOCK` |
| 尾门 | `0x06000031` | Integer | `ENTRY_LOCK` |
| 挡位 | `0x0400000a` | Integer | `CAR_DRIVING` |
| 车速 | `0x0400000b` | Float | `CAR_DRIVING` |
| 油门踏板 | `0x0400005a` | Float | `CAR_DRIVING` |
| 中控锁 | `0x67000096` | Integer | `VEHICLE_CONTROL` |
| 按区域读取车门位置 | `0x6700008d` | Integer | `VEHICLE_CONTROL` |
| AutoHold | `0x50000022` | Integer | `SEQUOIA` |

权限完整前缀为 `mega.car.permission.`。表中来自DomainDefine/PropertyDefine；定义权限保护级别的服务包未提取，不能据此说这些权限都是normal或都能用 `pm grant` 获取。旧 `EntryLocks.ID_LOCKS_CENTRAL_LOCK_STATUS` 也不是实车已观察到的 `Signal.ID_DOOR_CENTRAL_LOCK`，不要混用枚举。

SDK有油门属性定义，只能说明值得检测；此前行程仍没有有效油门/车速，不构成模拟声浪已适配的证据。AutoHold本次仍未找到可据以固定实车保持/释放方向的完整枚举，继续保留用户校准。

### 现有App需要改进的具体位置

本次只记录问题，没有顺手改动车辆读取/音效行为：

1. **中控锁/AutoHold原生接线未完成。** `VehicleAccess.Reader.wanted()`常规模式没有主动选择这两个属性；Manifest未声明 `VEHICLE_CONTROL`、`SEQUOIA`；`Protocol.property()`也尚未把它们映射为专用锁/AutoHold事件。扩展扫描或自定义规则不等于专用音效已接好。后续需连同区域、校准、来源优先级及失效处理一起补齐。仅加权限不足以解决。
2. **SDK加载路径值得增强。** 当前先从应用类加载器查Mega SDK，再从 `com.mega.car` 的sourceDir加载。新样本证明SDK也随客户端打包，但尚未拿到 `com.mega.car` 本体，不能断言它的APK内一定有SDK。应该先在车上记录能否找到类、实际manager类型、方法签名与依赖缺失；不能把ClassNotFoundException归为权限拒绝。另一个App内带SDK也不代表可随意完整打包进本项目。
3. **时间戳不能混用单位/时钟。** 本次Mega `CarPropertyValue`便利构造器以 `SystemClock.uptimeMillis()`写时间戳，Smali已核对；现有 `Reader.accept()`将Mega车速也与 `elapsedRealtimeNanos()`比较。若服务端按该构造器的毫秒时间戳分发，正常车速也会被当成过期。应先采集原时间戳、进程uptime/elapsed及服务端约定，按数据源处理。它不能解释已经以 `status=1, raw=null`到来的所有旧样本。
4. **SDK字典不等于服务支持表。** 部分SDK目录按注解默认生成area0，但调用实码又使用16/64等区域。优先保留服务实际返回的区域、状态和类型；SDK定义只能作为候选，不能盲扫所有3130条或给所有区域循环写入。

这些都说明原生路线值得优先推进，但没有证明它已经能替代所有日志。首轮验收应临时关闭日志采集，只记录少量指定原生属性，验证读值、订阅、状态变化、断线恢复，再接音效。

### 已有ADB带来的另一条实用路线

可研究用户明确授权的shell/Shizuku UserService来承载读取，再通过有限Binder接口向普通App推送事件。它与日志后端是两件事：桥内直接读Mega/Android Car属性才算不依赖日志；桥内运行logcat只解决读取身份/弹窗问题，仍依赖日志。

shell UID2000并非系统UID1000，不能预先保证它拥有车辆权限。应先做只读能力验证，并将后台存活、重启后的启动方式与撤权处理分开验收。当前项目尚未实现该桥。

## 车外音频：找到原厂实际播放路径

车外声音样本的关键实码：

| 层次 | 原厂调用 |
| --- | --- |
| 播放 | `AudioTrack`，`AudioAttributes.Builder().setUsage(2007).setContentType(2)` |
| 音频焦点 | `AudioFocusRequest` 同样使用2007 |
| 音量 | `CarAudioManager.getVolumeGroupIdForUsage(2007)`，再操作该组音量 |
| 输出模式 | `AudioManager.setParameters("outside_mode=...")` |
| 模式读回 | `AudioManager.getParameters("outside_mode")` |

对应 `i0/b.java`（源名AudioVolumeHelper）、`h0/a.java`、`h0/f.java`、`i0/a.java`及MainActivity。usage、模式映射和音量组调用已与Smali核对。

| outside_mode值 | 原厂代码标注 |
| --- | --- |
| `0` | CAR_IN，车内 |
| `2` | CAR_OUT，车外 |
| `1` | CAR_IN_OUT，内外同时 |

当前App使用usage13和显式音频设备路由，尚未接入上述厂商usage/HAL模式。`2007`与此前设备地址 `BUS2007_VENDOR_CONTEXT2`在命名上相符，是很强的关联线索，最终音频策略/功放映射仍需车机配置或实测确认。

不能立即把播放器改成2007便宣称解决锁车不响：该原厂App是平台签名、系统UID；普通App调用可能被拒绝、usage被转换、厂商HAL不执行或路由不到目标。音量组还涉及 `android.car.permission.CAR_CONTROL_AUDIO_VOLUME`，不等同于本App播放器音量。

后续探测应先读usage是否被保留、焦点、目标音量组、实际路由和 `outside_mode`。该参数属于厂商音频模式，可能影响其他音源，不应在无读回/恢复策略时默默全局切换。任何新路径仍须保留人工车外确认、失败不回退车内、过期播放取消；物理功放供电和锁车休眠仍要独立验证。

MegaAudioTestTools还提供通用 `AudioManager.setParameters` / `CarAudioManager.setParameters` 输入及PCM/QXDM抓取，说明厂商HAL有调试能力，不代表通用参数输入能赋予第三方权限。抓取开关不应作为日常音效方案。

## 空调控制：有哪些有证据的思路

### 直接车辆属性

CarControlAdapter的 `com.mega.voiceapi.hvac.TemperatureControl` 有完整的主/副驾读写逻辑：

```text
setDriverTemp / setPassengerTemp
  → setTempProp(Climate.ID_TEMPERATURE, area, value)
  → PropertyControl → MegaCarPropHelper → CarPropertyManager.setProperty
```

| 功能 | ID/类型 | 已看到的区域或条件 |
| --- | --- | --- |
| 设定温度 | `33554442 / Float` | 主驾16、副驾64；后排另有区域 |
| 风量 | `33554448 / Integer` | CarHvacControl使用前排区域80 |
| AC模式 | `33554438 / Integer` | 开/关编码有车型分支 |
| 车内温度 | `33554497 / Float` | 读值候选 |
| 车外温度 | `33554496 / Float` | 读值候选 |

温度参数不能始终把24℃直接写24：可见库区分CAN车型原温度和非CAN编码，后者还有 `温度-15` 与半度步长编码两套实现。FlavorUtils/HvacConfigUtil使用车型与系统属性分支，不能仅凭“ES”名称选分支。区域也不能拿Android标准seat area套入所有Mega属性。

这条路需要实际CLIMATE权限、服务端支持与确认后的编码。先读取、用原车UI做一次人工调温并比较回调，确定类型/区域/编码后再设计单项控制，不把写成功返回当实际执行证明。

### 调用现成服务

CarControlAdapter导出：

```text
组件：com.iflytek.autofly.carcontroladapter.nlp.NlpService
action：com.iflytek.autofly.carcontroladapter.nlp.NlpService
String extra键：com.iflytek.autofly.carcontroladapter.nlp.NlpService.data
```

`BaseNlpService → BaseWorker → AirControlDispatcher(service="airControl")`解析JSON后走原厂空调处理。Manifest该服务未声明调用权限，在已检查的入口及基类没有找到调用者UID/签名检查。**这是值得验证的合作入口候选，不是已经证明任意App可控**：还可能有系统拦截、语义校验、后台启动限制和后续业务条件。完整slots/operation及回执须继续核对，当前没有生成未经验证的调温命令。

情景模式服务还导出 `com.lion.scenemode.server.api.SceneModeApiService`，Binder为 `com.lion.bus.bus.sync.ILionBus`；请求Bundle用 `KEY_METHOD` 和UTF-8字节数组 `KEY_BYTE_CONTENT`。看到 `isServiceReady`、`getCurExecutingScene`、`getSceneModeList`、`getMaxMinValues`等查询，以及start/stop场景操作。入口/Binder可见代码未发现显式调用者鉴权，但这不是逐个操作都可用的保证。

可先尝试查询就绪状态和场景列表，不能把“启动场景”当单一空调开关：场景会改变多项车辆设置。此接口的通知也不是任意门锁/挡位/AutoHold订阅的替代。

`com.mega.redwoodhvac.voicelib` 的 `GET_AIR_PANEL_OPEN_STATUS`也出现在客户端中，但只说明有空调面板状态查询Provider。未提取提供者本体，包名、导出权限和其他能力待确认。

### 低优先级/不能误用的服务

- `ScriptExecutor`虽然exported，却在执行方法里检查调用者appId必须1000；它是遥测Lua环境，不是普通App可用的root shell，也不是车辆订阅服务。
- `ConfigProvider`虽然exported，但非通用数据域的写入显式限制系统/root UID。配置读取可帮助识别能力，不应通过改配置冒充空调控制。
- 空调、分屏、车辆读取、音频HAL、日志读取是不同权限面。得到ADB或READ_LOGS不能合并推导为全部可控。

## 为什么Car service包还不够

提取的 `com.android.car` 只有约285KiB，`CarService → ServiceProxy`再加载 `com.android.car.CarServiceImpl`。真正实现包由系统属性决定：

```sh
adb shell getprop ro.android.car.carservice.package
```

`UpdatablePackageContext`按该属性找系统/更新系统包并加载其类。不能仅凭显示名“Car service”就认为已经拿到车辆权限检查和音频实现。本批也没有 `com.mega.car.MegaCarService` 服务端本体；客户端Binder Stub并不包含真实授权策略。

## 解决“找不到路径”的采集方法

用户确认问题是找不到路径，不是Permission denied。下一次先读取安装信息，不手写 `/system/app/xxx/xxx.apk`：

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm list packages -f
adb shell pm path com.mega.car
adb shell pm path com.mega.carsettings
adb shell pm path com.mega.systemui
adb shell getprop ro.android.car.carservice.package
```

HOME解析得到真实桌面组件；`pm path`输出 `package:/实际目录/base.apk`，去掉开头的 `package:`后再 `adb pull`。如输出多个路径，base及所有split都要保留。没有输出不证明应用被隐藏或不可读，还要核对拼写、当前用户及实际包列表。Provider authority应从包/Provider信息定位所属包，不能直接替代包名。

仓库新增[只读采集脚本](../tools/collect_vehicle_interfaces.py)，可在已连接并授权的ADB环境运行：

```sh
python3 tools/collect_vehicle_interfaces.py
python3 tools/collect_vehicle_interfaces.py --pull-apks
```

多设备时加 `--serial 设备序列号`；指定未自动匹配到的包可加 `--package 实际包名`。脚本读取UID、SELinux、已安装包及实际APK路径、共享库、Binder服务、权限定义和指定包授权；可选按实际路径复制APK。输出默认在gitignored的 `build/vehicle-interfaces-时间/`，保留每条失败信息，不执行root、grant、setprop、应用启动或车辆写操作。

脚本未在真实车机运行。本地用模拟ADB检查了CRLF、带空格安装路径、多split、缺失包/命令失败及CarService实现包动态发现。输出包含设备/应用元数据，应保存在本地，提交报告时摘取必要部分。

优先补齐：`com.mega.car`、真实CarService实现包、实际HOME桌面包、原厂车辆设置及空调Provider所属包、`com.mega.systemui`。若Java SDK来自共享framework，还需要对应jar及权限定义XML；这次“设置”或小体积代理APK不能替代它们。

## 推荐实施顺序

1. 先用只读采集补齐包路径、SDK与权限定义，记录普通App和shell各自能访问到什么。
2. 增加少量指定属性的“原生接口检测”：类/方法是否可用、授权、原始status/value/area/timestamp及回调计数分别展示，修正时钟处理后关闭日志对照测试。
3. 原生门/挡位/中控锁/AutoHold成功后接回既有音效引擎，继续保留初值基线、来源去重、R→D打断及锁音效专用输出。
4. 单独做厂商音频只读检测，再手动验证2007与outside_mode，保留回读/恢复和车外确认。
5. 空调先读状态，再验证一个明确请求；语音服务与场景查询作为替代候选。当前分析没有把任何控制入口直接加入产品。

本轮最有把握的下一步是完善直接车辆读取与车外音频探测；尚不能承诺普通侧载App完全免日志、锁车休眠自动播音或无需额外权限控制空调。
