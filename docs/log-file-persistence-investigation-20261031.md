# 原厂日志落盘链检查（2026-10-31）

> 2026-11-01补充：用户未找到旧候选文件，尚不能断言Mega没有任何落盘。本轮复查确认信号日志过滤配置、门位置相关打印节流和工程模式日志级别设置，但未取得实际写入端实现或车端当前配置；没有连接车机或改变开关。见[生成与落盘复查](mega-logging-followup-20261101.md)。

## 范围与结论

本次只分析已有APK、DEX及已导出的诊断，不连接车机、不调用工程组件、不启动日志导出/上传、
不挂载目录、不修改属性/权限、不构建新APK。当前发布仍为0.3.11/code17。

**确认原厂软件具有日志文件收集与导出链，最值得检查的目标是`/log/android/logcat.log`。**
这比泛泛猜测共享日志路径更具体：Owl不仅保存路径常量，还实际列举该目录、选中该文件并打开文件压缩。
但已有报告没有目录清单、stat或实际文件打开结果，因此不能据此宣称本车当前文件必然存在、正在持续增长，
更不能宣称普通App可读、文件含实时车况或无需任何授权。

## 样本与完整性

| 样本 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `ES-系统APK-20261031-175727/packages/com.mega.owl.apk` | 9,426,139 | `a7538e9bd65b9ee4c5954aa72694c1ba49d3a3551266f329fbb9ce0f15fc069f` |
| `ES-系统APK-20261031-161230/packages/com.mega.engineeringmode.apk` | 15,593,848 | `1c97fc4fbf05cf5e6708cf2c4f07eacd30ac65e422d346ca6285bec2d9ac7fff` |
| `ES-系统APK-20261030-225845/RedwoodCarService.apk` | 1,577,477 | `38913715358e7556ef9a750d20d693349b3863eec5dc5f632965acb1e727ec32` |

输入目录位于`/Users/hongjunlong/Downloads/`，哈希均在本次重新计算，与先前研究一致。
实车静态服务/SELinux证据来自`ES-ADB诊断-20261031-174857.json`，不把旧报告当作当前在线快照。

JADX 1.5.3：Owl完整反编译产出、RedwoodCarService正常完成；工程模式报告23个方法恢复错误，
关键`MegaLoggerNative`加载库、native方法声明及导出调用已用SDK dexdump指令交叉核对。
临时源位于`/tmp/es-log-research/`，可删除，关键摘录保存在同目录文档引用的持久证据文件中。

## 1. 车辆日志生成方：写入Android日志，不直接证明写文件

RedwoodCarService：

- `com.mega.car.hal.message.MessageProperty.handleTopic`在解析topic后使用
  `MLog.i("MessageProperty", "receive " + topic + ", value : " + CarPropertyValue)`记录状态。
- 是否打印受`CarLog.allowLog(propertyId)`控制，较大payload还可能只打印topic而无value。
- 本包`mega.log.MLog`走`mega.log.LogImpl.print`，INFO分支最终调用`android.util.Log.i`。

因此，已验证的MessageProperty日志是Android日志链的输出，不是该类自己维护的一个共享文本文件。
若系统另有采集器将对应buffer落盘，才有机会在文件中取得同一批状态日志；静态打印代码不保证所有事件持续打印。

## 2. Owl：明确处理原厂已存在的日志文件

类`com.mega.owl.config.LogcatConstant`：

| 用途 | 路径/规则 | 对本任务的意义 |
| --- | --- | --- |
| Android日志目录 | `/log/android` | 首选只读探查目录 |
| 当前日志名 | `logcat.log` | 拼成`/log/android/logcat.log`，最有价值的实时增量候选 |
| 轮转归档匹配规则 | `logcat.log.\d{3}_(\d{4}_\d{2}_\d{2}_\d{2}_\d{2}_\d{2}).*` | 支持带序号、时间的归档文件；具体扩展名和轮转周期待实车核对 |
| 默认收集路径 | `/log/android,/qlog/dltlogs,/log/btsnoop` | 说明有Android/Linux/蓝牙多类来源，不能混当车况文本 |
| 其他Android诊断 | `/log/anr`、`/log/tombstones` | 崩溃/卡死，不是日常挡位源 |

`AndroidLogHandler.onHandleCommand`：

1. 对处理路径做`File.exists()`检查，路径为`/log/android`时调用`addLogcatFile`及归档选择逻辑。
2. `addLogcatFile`调用`androidLogcatDir.list(FilenameFilter)`，过滤条件精确为`name == "logcat.log"`。
3. 当前文件通过`LogcatFileUtil.appendFileToFileMap`走压缩；`FileUtil.compressSingleFile`用
   `FileInputStream(inFile)`读取，写入`GZIPOutputStream`。
4. 压缩缓存目标来自Owl的`Context.getCacheDir()`，不是公开共享目录。
5. 归档通过`File.list()`与上述正则按时间选择。类名虽带Gz，正则本身末尾是`.*`，不能据此断言全部归档均为gzip。

这是一条**实际文件访问实现**，不是仅有文件名字符串；但它体现的是Owl的运行权限，不能移植为普通App的权限证明。
Owl样本声明系统共享UID，原报告包管理器也将Owl列为系统共享用户成员。
`ReportLogcatService`属于诊断收集/压缩/上报链，而非已证实的持续日志写入进程；不得为检查文件而启动它。

## 3. 真工程模式：MegaLogger原生导出链

`com.mega.engineeringmode`的`com.mega.EngineeringMode.function.LogExportActivity`：

- 选定导出目录后，经`w.a`（源文件名`MegaLogManager.java`）持有`com.mega.logger.MegaLoggerNative`。
- 按不同日志类型调用`saveLogToPath(path, type)`，目标包含用户选定外部卷及按目录组织的导出路径。
- 代码也有`clearLog(type)`；本次没有调用任何导出或清理操作，不能把它包装成无副作用的实时读取入口。
- `MegaLoggerNative`静态初始化`System.loadLibrary("MegaLogger")`，声明native的`saveLogToPath`与`clearLog`。
- APK只带`lib/arm64-v8a/libengineeringmodeso.so`，**没有`libMegaLogger.so`**；实际库解析来源/路径及内部实现需车端证据。
  不把相似名字的`libengineeringmodeso.so`冒充日志库，不猜测绝对库路径或其调用的守护进程名。

因此“原厂支持把日志导出到U盘”有实现依据，但导出的快照不等于持续更新的共享日志。
有导出能力也不证明普通App能调用native服务或读原始目录。

## 4. 已有实车服务证据及限制

17:48报告：

- `binder-services`存在`MegaLoggerService: []`，方括号为空，报告没有取得接口描述符/方法。
- VINTF声明`vendor.mega.loggerhal@1.0::ILoggerBridge/default`及`ILoggerRxCallback/default`。
- `lshal`对ILoggerBridge显示`cannot be fetched from service manager (null)`。
- 同轮系统日志有shell身份的SELinux `{ find }`拒绝，目标是
  `vendor.mega.loggerhal::ILoggerBridge`、类型`megaloggervendor_hwservice`。

这些确认存在厂商日志服务线索；**不证明该服务就是`logcat.log`的具体写入进程**，也不证明文件读会被同一规则拒绝。
HIDL服务查找与文件打开是不同访问检查，不应拿其中之一替代另一个的实测。

当前材料未包含native日志守护进程二进制、init配置、文件属主/mode/SELinux label或写入端fd归属。
具体写入服务、buffer过滤、刷盘/轮转周期及是否受开关影响，仍未确认；不自动开启任何工程日志开关。

## 5. `/qlog`不是本次应优先调用的备用入口

Owl还处理`/qlog/dltlogs`、`/qlog/dump_info.txt`、`/qlog/mcu/log_mcu_`等。
其中`QLogHandler.onPrepare`会调用`PlatformInfoUtil.mountQLog()`：若未挂载，设置
`vendor.sys.fs_qlog=1`并等待`vendor.nfs.qlog.mounted`。

这已超出纯读取边界。因此不启动此handler、不写该属性、不尝试挂载。Linux/DLT日志格式及其车辆事件覆盖也未核实。
即便已有`/qlog`可读文件，也只能先作有界元数据核对，不能默认把它当普通UTF-8实时车辆日志。

## 6. 下一步最小车端只读验证

先检测固定目标`/log`、`/log/android`、`/log/android/logcat.log`，不全盘扫描、不启动日志采集/上传、不导出历史大文件。

1. **App原UID直接尝试**：对目录/文件做`stat/lstat`和实际`open`，记录uid、SELinux上下文、mode、size、mtime、
   errno/异常。`exists=false`也可能是上级目录不可访问，必须区分ENOENT与EACCES，不能据布尔值就说“文件不存在”。
2. **ADB shell单独核对**：若已有用户授权的连接，做同一组只读元数据/文件打开检查，结果独立标注shell身份。
   shell读到不等于App可读；不请求grant、不chmod/chown、不关闭SELinux、不调用loggerhal。
3. **有界增量检查**：仅在文件可读后，从当前末尾开始观察约20—30秒，限制新增读取量，
   对比size、mtime、inode及有限新增文本，核对MessageProperty及真实操作时间。避免重放历史车辆状态。
4. **延迟与轮转**：确认更新是否实时、是否能看到对应buffer/tag/原值，是否因缓冲延迟；
   轮转时检查inode替换/文件截断，不能拿同一个旧fd的EOF当成数据永久不更新。

判断：

| 结果 | 可行性 |
| --- | --- |
| App可直接读，且文件持续新增真实事件 | 有希望不向App发起设备日志读取请求，改为文件追踪；仍需延迟/重启验证 |
| 只有ADB可读 | 仍依赖用户授权的ADB桥，不是普通App无授权直读 |
| 两身份均拒绝 | 当前权限下文件路线不可用；不修改权限来“修复” |
| 文件不增长/只有旧档/没有目标tag | 不能作实时音效源，不能靠读取已有历史文件补实时事件 |

**当前结论停在“原厂有明确落盘收集链和精确候选路径”，尚未到“免日志授权方案已可用”。**
本次只更新研究记录，没有修改App行为；下一版若实现文件诊断，应独立手动、有界、只读，失败或旧值均明确报告。
