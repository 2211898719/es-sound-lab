# 四门专项：证据边界与下一步

分析日期：2026-11-01。仅离线复查用户已提供的材料；没有连接车辆、启动原厂服务、请求车辆控制、改变权限或生成新 APK。已工作的 ICC 来源不变。

## 结论

目前没有确认本应用可用的四门独立实时来源。不是证明车机没有门数据，而是没有证实普通应用可以读取有效、持续更新的数据。

下一步优先做一次有明确开关门顺序的短时日志对照，而不是重做 Mega 权限探测、换 OTA SDK 或继续盲目提取系统应用。日志对照也不保证成功；如果只能得到聚合状态或控制指令，就不能交付四门识别。

## 既有路线不重复

- Mega 原生路径：2026-10-30 实测在取得属性管理器后，主驾门等属性读取被签名／特权权限拒绝；订阅没有完成实车验证。见 `native-vehicle-events.md:5`。
- Mega OTA SDK：实现内部也使用 MegaCarProperty，不是已证实权限条件不同的新出口。数据对象中没有明确四门开闭字段。
- Carota 文件：此前样本未发现四门／尾门状态；档位有变化不能推出门状态也在同一日志中。见 `carota-file-route.md:9`。
- 车设公开组件、SystemUI 私有缓存和 Owl 上报接口已有研究，不把内部消费车况等同于对外发布。见 `system-apps-investigation-20261031-175727.md` 与 `offline-event-alternatives-20261031.md`。

## 本次新增定点证据

样本：`/Users/hongjunlong/Downloads/ES-系统APK-20261031-175727/packages/com.mega.carsettings.apk`。

SHA-256：`56b9474d9834083412b9cf35c2baf73e6add2651326351275ccad52fb34cd1e4`，与此前核验记录相同。

使用现有 Android build-tools 的 dexdump 读取实际 DEX 指令，避免把未恢复的反编译占位当作实现。只解出四个 DEX 至临时目录；没有改写 APK。完整中间文件位于 `/private/tmp/es-four-door-20261101/`，该目录是临时分析材料，不依赖其长期存在。

### VisionDetectService 的门日志不能直接接为四门事件

在 `classes3.dex` 的 `com.mega.carsettings.service.VisionDetectService.onChangeEvent`（code offset `0x2caf0c`）中：

- 属性 `0x6700008d` 转到 `isAllDoorClosed()`，打印 `ID_DOOR_CURRENT_POSITION allDoorClosed: <boolean>`。
- `isAllDoorClosed()`（code offset `0x2c999c`）依次读取 VehSettings 的 `ID_DOOR_SET_POSITION_FL/FR/RL/RR`，四值均为 0 时返回 true。
- 这段日志使用的是四个 **SET_POSITION** 字段的聚合计算，不是把收到的四门实际位置逐项输出；不能仅凭日志名中的 CURRENT_POSITION 就认定它是四门实际开闭状态。
- 属性 `0x06000009` 分支打印 `ID_DOOR allDoorClosed1: <boolean>`，也只有聚合布尔，不能从这一行区分左前、右前、左后、右后。

本次没有验证该服务在目标车上何时运行、实际是否打印、日志是否可读或各车型分支是否生效。不能把这些静态打印语句当成已收到的实车事件。

### 定义存在不等于可读

`classes4.dex` 中的 Signal 定义包括 `ID_DOOR_CURRENT_POSITION_FRONT_LEFT/FRONT_RIGHT/REAR_LEFT/REAR_RIGHT`，数值依次为 `1728054017`～`1728054020`。

它们只是候选标识。尚未证明本车型有新鲜数据、可被本应用读取、会上报日志，或其数值可直接按 0/1 解读；不据此增加盲测请求。

## 最小实车对照方案

车辆停稳后进行，无须行驶；不要为了测试启动工程开关、车辆控制或 OTA 功能。

1. 在明确授权的只读系统日志采集下，先保留几秒全关基线；若现有来源只能看到 Carota 文件，不把它冒充完整系统日志。
2. 按左前、右前、左后、右后的顺序，一次只开一扇，等待几秒再关上；各动作记录时间标记。
3. 如原厂页面能显示实际四门状态，可保持该页面可见作为对照；不从本应用向原厂程序注入车门事件。
4. 只分析本次时间窗的车门属性标识、区域、值和时间；不把 VIN、账号或整段行程日志纳入项目资料。

这只是下一步实验设计，本次没有新增采集功能，也没有要求用户立即操作。

## 接入与停止条件

- 有可复现的逐门状态、可区分开关、更新及时：才实现独立解析、校准、首样本静默及过期处理。
- 只有“任意门开／全部关闭”：最多是聚合状态，不能声称支持四门独立识别。
- 只有 SET_POSITION、按钮点击、控制命令或缓存：不能据此认定真实车门已经动作。
- 没有相关事件或明确拒绝：记录该次采集范围内的失败并停止这条候选，不换包名、反射或 shell 身份重复已拒绝的读取。
- 不因一次无输出认定全系统无门数据；但没有新证据时，不继续发布同一失败路线的盲测 APK。
