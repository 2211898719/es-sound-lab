# 两个系统 APK 与 22:58 ADB 诊断核对

> 开放接口后续排查：Mega四类读取被拒不等于所有合作事件协议不存在。现已初筛车辆服务Provider与SystemUI广播路径，尚无确认可用的四类开放事件接口；候选宿主、排除项及普通App只读验证边界见[专项记录](open-event-interface-investigation-20261030.md)。

> 后续0.3.4实车结果（23:33）：采用本批SDK公开两参数查询重载后，原shell UID2000成功取得车辆服务与属性管理器，未绑定服务。但四类属性各一次真实读取均被对应Mega权限明确拒绝；readAttempted=true、readVerified=false、outcome=permissions-denied，订阅未测试。初始化问题与权限障碍已分开确认，不继续重复初始化或接同权限持续桥。下文保留22:58 APK分析时点的证据与结论；[最新诊断](adb-diagnostics.md)。

分析日期：2026-10-30。材料为用户导出的 `ES-系统APK-20261030-225845.zip` 和 `ES-ADB诊断-20261030-225851.json`，不是其他车型或固件的通用结论。本轮只做本地完整性检查、Manifest/资源读取和静态代码分析，没有连接车机、修改系统、变更权限、调用车辆接口或重建应用。

## 完整性与版本

归档含两个 APK 及 `extraction-info.json`。逐文件读取完成，ZIP 校验未报错；采集 ID `1793372279738` 与诊断 `startedAt` 一致，归档元数据、诊断、实际文件的大小和 SHA-256 全部吻合。

| 文件 | 实际包名 | 字节 | MiB |
| --- | --- | ---: | ---: |
| RedwoodCarService.apk | `com.mega.car` | 1577477 | 1.5044 |
| RockySystemUI.apk | `com.android.systemui` | 269936797 | 257.4318 |

SHA-256：

```text
RedwoodCarService.apk
38913715358e7556ef9a750d20d693349b3863eec5dc5f632965acb1e727ec32
RockySystemUI.apk
0143b5d7981f3efea7b8f4407756051b982d51b82c086ae6833c802d9fb44908
```

两个 APK 均通过 `apksigner verify`，证书 SHA-256 均为 `83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d`，与此前原厂样本一致，不是本项目自签名证书。

- 车辆服务：versionCode 1，versionName `1.20260304233723-release/rocky_hqx.4.5.5_20251024`，minSdk 25、targetSdk 30，声明 `android.uid.system`。
- SystemUI：versionCode 1，versionName `1.20260304233725-release/rocky_hqx.4.5.5_20251024`，minSdk 31、targetSdk 30。
- 新诊断 51 条命令传输完成且无截断，整次采集约 29.483 秒，`apkExtraction.complete=true`。SystemUI 确实超过旧 256MiB 上限，0.3.3 的大文件提取本次实车通过；不再只是本地模拟结果。

## 车辆探针：失败在连接，不在读值

本次探针已以 UID 2000 启动，SDK 加载来源为 `com.mega.car`，但四类属性 `samples` 均为空。约 34ms 后记录：

```text
SecurityException: Unable to find app for caller ... when binding service
Intent { pkg=com.mega.car cmp=com.mega.car/.MegaCarService }
```

静态代码与当前探针对照：

1. `AdbVehicleProbe` 使用独立 `app_process`、`ActivityThread.systemMain()` 和 shell 包 Context 调用 SDK 四参数 `MegaCar.createCar`。
2. 本批 SDK 的四参数重载先查询系统车辆服务，随后仍进入 `startCarService()`；即便已经取到服务对象，也存在直接调用该绑定方法的分支。不是只在服务尚未启动时才绑定。
3. `startCarService()` 显式调用 `Context.bindService`，目标与实车异常中的 `MegaCarService` 一致。异常针对调用方进程身份，当前独立进程启动方式与 SDK 的普通应用绑定路径不兼容；仅获得 shell 包 Context 不等于成为正常启动的该应用进程。
4. 尚未取得属性管理器或执行 `getProperty`，不能把这次失败归类为四项属性读取都被服务端拒绝。`readVerified=false`、`changeObserved=false`、`subscriptionVerified=false` 必须保留。

Manifest 中 ENTRY_LOCK、CAR_DRIVING、VEHICLE_CONTROL、SEQUOIA 的保护级别均为 `0x12`，即 `signature|privileged`。服务端 `CarPropertyService.getProperty` 和 `registerListener` 均有按属性权限检查，连接成功不会自动获得这些权限。当前报告中的 shell 四项授权检查仍为 false；本次没有实际样本，不能宣称 shell 已可读或永久不可读。

本轮不改成后台车辆桥，也不通过伪造调用方、改系统授权或修改原厂 APK 解决连接与权限问题。后续应先明确受支持的客户端初始化方式和真实授权，再分别验证读取、状态变化与订阅；不能把服务发现或 SDK 加载成功当作这三项成功。

## SystemUI：普通分屏路径有明确限制

### 正确名称和权限

- 实际包名是 `com.android.systemui`；`com.mega.systemui.chery.Split` 仍是正确的 Provider authority。
- Provider 实现为 `com.android.wm.CherySplitProvider`，虽 exported=true，但访问权限 `com.mega.systemui.provider` 的保护级别是 `0x2`，即 signature。
- 在普通侧载应用 Manifest 中声明此权限，并不代表获授。Provider 不能作为不受权限限制的通用第三方入口。

### 普通 Activity 启动拦截

本批资源 `array/split_white_list` 默认仅有三项：

```text
com.lion.appfwk.navi
com.lion.media
ai.weride.compass.voyah
```

`CheryMegaSplitFacade.shouldInterceptCurrentPackageStart` 先检查被启动包是否属于该列表，再检查 `SplitScreenManager.isSplitEnabled()`。后者要求分屏开关开启且 `NaviManager.isNavigating()` 为 true；导航状态不是“已经打开地图”的同义词，也不应通过驾驶操作来验证。运行时资源覆盖仍可能改变列表，本次 APK 静态资源不能代替运行时读取。

`cn.es.soundlab` 不在 APK 默认列表中。因此历史探测中的“启动自己”请求，不具备这条普通拦截路径的默认接入条件；“启动地图”也不意味着系统会接受任意另一个应用。

当前 `handleInterceptFromSource` 会记录 `METHOD`、`IS_HANDLE_LEFT`、`COMPONENT`，但处理 `enter_split` 时传入固定的地图主包及原启动 Intent，不按 `COMPONENT` 为任意第三方组配对。此前地图调用方中的 extra 形态，不能再被描述成该固件支持任意侧载应用分屏的证据。`setupSplitPkg` 对记录的主/副包也按同一列表过滤。

这些是本批代码中已确认的路径和默认条件，不等于已穷举所有系统内部调用，也不等于已实车验证每一分支。不再建议反复换把手方向、增加 extra 或重复已失败的组合来解决接入条件问题。

## 后续范围

1. APK 提取问题已闭环，不需要再为本次文件重试上传或重新提取。
2. 车辆检测应将 SDK 加载、服务连接、权限、实际读值、变化和订阅分阶段呈现；当前证据只确认到连接失败。
3. 分屏按本批固件的真实限制修正预期；停止将普通 Intent 发出成功视为接入成功，不修改系统白名单或签名。
4. 在受支持的连接与授权确认前，暂不接入持续 ADB 车辆桥或自动音效。

## 本地证据与恢复质量

完整 APK、Manifest/证书输出、资源表和反编译结果存于忽略目录 `build/system-apks-20261030/`，不将厂商 APK 或完整源码纳入版本库。核心位置：

- `redwood/sources/mega/car/MegaCar.java`、`MegaCar-simple.java`：四参数初始化和绑定；普通反编译未完整恢复该重载，已用 simple 指令式输出核对相关分支。
- `redwood/sources/com/mega/car/service/CarPropertyService.java`：正常属性读值及订阅检查。
- `systemui/sources/com/android/wm/CheryMegaSplitFacade.java`：白名单、启动拦截和配对记录。
- `systemui/sources/com/android/systemui/splitscreen/SplitScreenManager.java`：分屏开关与导航状态条件。
- `systemui/sources/com/android/wm/CherySplitProvider.java`、`RockySystemUI-manifest.txt`、`systemui-resources.txt`：Provider 权限和默认列表。

JADX 1.5.3 对整个 SystemUI 报告 95 处恢复错误；上述选定分屏方法没有未反编译占位，不能将其余恢复失败的方法当成真实实现。所有结论仍只限本批固件，未生成新 APK，未做新的实车验证。
