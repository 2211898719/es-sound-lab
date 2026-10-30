# App 内 ADB 只读诊断（0.3.1）

用户在车机开启ADB后，在App里直接连接并采集；不需要另外安装Shizuku或把电脑常驻车内。当前只支持工程模式常见的传统TCP ADB及RSA授权；不支持配对码/TLS无线调试，不自动开启ADB或寻找端口。

## 在车上怎么用

1. 在原有工程模式中开启ADB，确认实际连接端口。
2. 进入“车辆检测 → ADB 只读诊断”。地址先用 `127.0.0.1`；端口填写工程模式实际端口。默认留空，不能把5555当已确认值。
3. 点击“连接并采集诊断”。如系统询问，允许此次 `ES Sound Lab` 的ADB连接；首次认证最多等待约60秒。不弹窗也不一定失败，已授权连接或固件配置可能直接连接，以页面结果为准。
4. 等待采集结束，再点击“导出 ADB 诊断”。也可返回车辆检测“导出记录”，最新ADB结果会放在同一JSON的 `adbDiagnostics` 内；无需单独传两份。
5. 连接失败、取消或断流也可导出，已完成的命令和当前命令收到的部分输出会保留。退出/销毁页面会关闭连接并保存部分结果；后台不持续监听，重进页面不会自动重连。

建议在需要诊断的原生接口错误刚出现后采集，避免环形日志已被后续输出覆盖。日志是近期快照，并非整段行程录制。报告默认只保留最近一次采集，重新采集前有需要可先导出旧记录。

## 采集内容和用途

| 内容 | 用途 |
| --- | --- |
| `id`、SELinux、当前用户 | 确认实际ADB shell身份及系统状态，不把ADB身份当App身份 |
| 权限定义 `dumpsys package permissions` | 查ENTRY_LOCK、CAR_DRIVING、VEHICLE_CONTROL、SEQUOIA等保护级别和定义者 |
| 本App、智行生活、Mega服务/语音车控、工程模式等包详情与AppOps | 对比声明权限、真实授予、UID和安装身份；AppOps查询只读 |
| 实际APK路径、共享库、Binder服务、HOME桌面组件 | 定位SDK依赖、真实桌面/服务包，解决手写路径错误 |
| `ro.android.car.carservice.package` | 自动补充真正CarService实现包详情；包名严格校验，不把输出当任意命令执行 |
| 有限车辆型号和系统版本属性 | 核对具体固件/车型 |
| Mega/Android车辆服务dumpsys | 获取服务允许公开的诊断状态；拒绝或不支持会原样保留 |
| `logcat -d -t 2000`的指定车辆/权限相关标签 | 最近车辆属性、SDK、服务、AndroidRuntime与SELinux/audit日志快照 |

应用自己的原生错误、回调和音效诊断仍由原有检测导出提供。ADB采集不直接订阅车辆、不改变当前音效来源，也不证明shell能够读取Mega车辆属性。先据此确认权限原因，再决定后续授权或接入方案。

## 授权、结果与限制

- 使用App自己生成的2048位RSA密钥完成标准ADB授权，密钥只保存在App私有目录且不随报告导出；不读取或导入电脑/其他应用的ADB私钥。清除应用数据或卸载后会重新生成身份，可能需要重新授权。
- 只执行固定读取命令，无任意shell输入，不执行root、grant、setprop、服务控制调用、车辆写操作，也不清空日志。
- AndroidManifest新增INTERNET供TCP连接使用；诊断Activity不导出，只有页面手动点击才建立连接。读取系统日志走已授权ADB身份，不依赖本应用READ_LOGS；普通日志辅助开关与本功能独立。
- TCP连接等待8秒，普通读取空闲等待最多15秒、每命令目标总时长20秒；收到部分报文但传输中断时保存部分输出。采集可取消。
- 单命令输出上限2MiB，总命令输出上限12MiB；截断写 `truncated=true`。所有命令完成不等于内容完整，应检查截断和输出中的拒绝。
- 传统ADB shell模式不可靠提供退出码，因此明确标注 `commandExitCodesAvailable=false`、`exitCode=null`；`transportCompleted=true`只表示流正常结束，服务可能仍在正文返回Permission denied或Unknown command。
- `complete=true`只说明固定采集流程已结束，不能认定全部命令成功、权限已授予或车辆订阅已恢复。`complete=false`时检查 `error` 及每条命令的部分输出。
- 报告是本地文件，通过系统文档选择器导出，无自动上传；检测合并字段保留本次采集时间，避免把上次ADB结果误当当前状态。

页面提示TLS/配对端口时，使用已知的传统TCP ADB端口；如果固件只提供TLS无线调试，当前内置客户端不能连接。电脑已完成正常配对的情况下仍可用仓库已有 `tools/collect_vehicle_interfaces.py` 读取元数据。本轮没有实现TLS配对流程。

## 实现与本地验证

`AdbClient`处理标准ADB连接、RSA认证、单shell流、ACK/关闭、取消和限量输出。`AdbDiagnosticCommands`固定命令清单并校验动态包名，`AdbDiagnostics`逐命令原子保存报告，`AdbDiagnosticsActivity`提供手动连接与导出。协议和平台密钥兼容性使用模拟传输及Robolectric验证，没有连接真实车机。

本地通过145项核心检查、81项Android/Robolectric测试及资源/Java/DEX编译，覆盖标准认证、多段输出、截断、取消/断流、密钥复用、动态服务/HOME包名发现、合并导出和页面不自动连接。尚未实车连接验证。

源码版本0.3.1 / versionCode 7，正式APK仍由用户使用原0.2.x签名密钥构建。仓库旧APK不含本功能。
