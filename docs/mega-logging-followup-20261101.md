# Mega 日志生成、过滤与落盘复查

日期：2026-11-01。离线复查已有 APK 和诊断，并执行一次本机 `adb devices -l`，设备列表为空。没有向车辆发送请求、修改属性、调用日志导出／清理／上传、改变权限或构建 APK。

## 结论

不能将“用户未找到 `/log/android/logcat.log`”解释为“Mega 完全不落盘”。已确认车辆日志的输出和过滤代码，也确认原厂存在文件收集与原生导出链，但尚未取得实际落盘端的实现和当前运行配置。

本次有用的新证据在日志生成侧，而不是找到了可读取的新文件：车辆服务有信号日志过滤配置，门位置参与部分打印位置的节流；工程模式的 Android 日志级别选择也不是文件保存开关。

## 本次实际核对的证据

### 1. 信号日志配置并非落盘开关

RedwoodCarService 的 `com.mega.car.utils.CarLogControl`：

- 初始化及 `checkDebug()` 读取 `persist.mega.car.signal.log`，代码默认值为 false。
- **默认值不代表实车当前值**。最新 `ES-ADB诊断-20261101-023905.json` 没有查询这个属性。
- `canPrint(propertyId)` 在 debug=true 时返回 true；其他情况下结合忽略集合、节流集合决定是否打印。
- 节流集合中的属性按 propertyId 记录时间，间隔不足 2000ms 返回 false。
- `RedwoodSomeIpProperty.prohibitFrequentPrint()` 把 `Signal.ID_DOOR_CURRENT_POSITION`（`0x6700008d`）加入该节流集合。

这说明某些日志位置不保证记录每一次快速变化；不代表车辆属性更新或 SDK 回调也被限制为两秒一次。SomeIP 的 `getProperty()` 打印在 canPrint=false 时改为 VERBOSE，而 MessageProperty 的接收打印还有独立判断，不能把所有情况归纳成“直接丢弃”。

`CarLog.allowLog()` 另外检查静态／动态忽略集合；即使某个 debug 判断放行，也不能推导成所有车门事件必然输出。`MessageProperty` 接收记录还受值缓存／变化判断及 payload 长度限制：较大消息可能只有 topic 而没有 value。

### 2. 工程模式的日志级别不是保存到文件

真工程模式的 `LogLevelActivity` 使用监听器 `com.mega.engineeringmode.function.m.onCheckedChanged()`，Android 级别选项写入 `persist.log.tag`，值为 V/D/I/W/E，并保存本地界面选择。

所检查分支没有建立文本文件或启动文件采集。这不能证明系统其他组件不会响应属性，但没有依据把这个界面当成“开启 Mega 落盘”。本次没有改变该属性，也不建议为了试探切换全局级别。

### 3. 文件收集端与文件写入端仍未接上

- 车辆服务的 `mega.log.LogImpl` 最终调用 `android.util.Log`，不是该类自己维护共享文本文件。
- Owl 的 `AndroidLogHandler` 检查既有 `/log/android`，选择 `logcat.log` 和匹配归档，读取压缩；该路径不是已确认的持续写入端。
- 工程模式的 `MegaLoggerNative` 加载 `MegaLogger`，提供 `saveLogToPath` 和 `clearLog`。样本 APK 没有携带 `libMegaLogger.so`，不能从 Java native 声明还原实际写入路径、刷盘、轮转或开关。
- 最新 02:39 诊断列出 `MegaLoggerService: []`，只证明当时服务清单有该项，不证明接口、健康状态、写文件行为或外部调用权限。
- 10 月 31 日 17:48 诊断有 loggerhal 声明及 shell 查找的 SELinux 拒绝。这与普通文件能否打开是不同的检查，不能相互代替。

## 样本与交叉核对

车辆服务样本：`/Users/hongjunlong/Downloads/ES-系统APK-20261030-225845/RedwoodCarService.apk`。

本次重新计算 SHA-256：`38913715358e7556ef9a750d20d693349b3863eec5dc5f632965acb1e727ec32`，与旧记录相同。

现有反编译源码位于 `/private/tmp/es-log-research/`。本次另用项目自带 dexdump 对原 APK 的 classes.dex 交叉核对，临时输出为 `/private/tmp/es-mega-log-followup-20261101/car-dexdump.txt`：

- `CarLogControl.<clinit>`：code offset `0x08b40c`，读取上述信号日志属性。
- `CarLogControl.canPrint`：`0x08b320`，使用 elapsedRealtime 与 2000ms 比较。
- `CarLogControl.checkDebug`：`0x08b4e8`，重新读取属性。
- `RedwoodSomeIpProperty.prohibitFrequentPrint`：`0x079af4`，把 `0x6700008d` 加入节流集合。

工程模式旧 DEX 输出 `/private/tmp/es-log-research/engineering-dexdump2.txt` 中，`function.m.onCheckedChanged`（`0x05e2d4`）包含 `persist.log.tag` 写入分支。临时目录不是需要长期保留的项目依赖。

## 尚缺的最小实车证据

最新 ADB 报告没有信号日志属性、Android 日志级别、MegaLoggerService dump、日志目录 stat、原生日志库位置或 init 日志采集配置；现有检测报告的 fileLog 来自 Carota，不能代替 Mega 路径的实测。

下一轮若用户要求实现定点诊断，应只收集：

1. `persist.mega.car.signal.log`、`persist.log.tag` 的只读当前值。属性为空单独记录，不解释成明确关闭。
2. 服务／进程的只读元数据；若服务 dump 被拒绝或无输出，保留结果，不换身份或调用未知事务。
3. 已有证据路径 `/log`、`/log/android`、`/log/android/logcat.log` 的 stat／实际打开结果，区分 App 和 shell 身份，明确 ENOENT、EACCES 等错误。不全盘扫描、不挂载。
4. 有界的实际开关四门系统日志对照，分别记录动作时间；只采集明确授权可读的数据。

这轮没有实现新的采集功能或产生车端结果。只有找到可读、持续更新且覆盖四门的文件，才能讨论文件方案；日志级别、文件导出按钮、服务存在都不能代替这些条件。
