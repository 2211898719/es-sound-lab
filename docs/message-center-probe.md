# 消息总线检测与定点定位（0.3.9）

## 2026-10-31 17:48实车：确认SELinux查找拒绝

材料：`/Users/hongjunlong/Downloads/ES-ADB诊断-20261031-174857.json`。安装版本0.3.9 / versionCode15；本次采集17:48:33.182至17:48:39.354（Asia/Shanghai），complete=true，HIDL采集流程完成，未请求APK提取或旧原生读取探针。

- 本轮应看`hidlDiagnostics.appLookup`，不是顶层旧独立订阅记录。会话4、UID10091、PID30007，仅发现模式；17:48:33.265开始，.266在`HwBinder.getService`抛`NoSuchElementException`，failedPhase=discovering，无订阅事务。
- 同一毫秒17:48:33.266，hwservicemanager PID445记录`avc: denied { find }`，interface=`vendor.mega.message_center::IMessageCenter`，请求者pid=30007、scontext=`u:r:untrusted_app_27:s0:c91,c256,c512,c768`，目标tcontext=`u:object_r:message_center_hwservice:s0`，tclass=`hwservice_manager`、permissive=0。SELinux与auditd各一行是同一次拒绝的两条记录，不是两次调用。与本轮App PID、时间、接口全部吻合，足以确认当前App服务查找被SELinux拦截。
- 随后普通近期日志还记录17:48:33.532，同接口、同目标类型的`find`拒绝，请求者为`u:r:shell:s0`、pid4804。shell身份命令确认为UID2000，系统SELinux为Enforcing；不能假设改由普通ADB shell调用即可解决。
- `/vendor/etc/vintf/manifest.xml`完整读取13118字节，明确声明`vendor.mega.message_center`、1.0、hwbinder及`IMessageCenter/default`；实例与代码一致。声明清单退出0。注册枚举退出200，目标有`cannot be fetched from service manager (null)`警告和`hwbinder N/A`行，不能把该命令当作完整成功，也不能由此证明已取得服务句柄或服务进程健康。
- 同期pid4845/UID1000对`com.mega.UserService`的拒绝与本App/目标不同，不用于本轮归因。vendor/system文件数上限均触发，其他分区存在覆盖限制；不将此次快照称为全系统完整清单。

结论更新：17:08单独异常尚不知原因；17:48新证据已经确认当前直连路径的SELinux查找障碍，不再盲改topic、延长等待或换实例。尚未到接口核对/注册/回调阶段，不能判断放行后服务内部是否另有授权、topic适配或新鲜度问题，也不能说只改签名就能解决。后续优先离线核查已提取系统应用是否有明确对普通应用开放的只读事件/转发接口；目前没有验证可用出口，不自动打开工程开关、不提权或改变策略。本轮仅分析与记录，不改App、不生成新APK。

## 0.3.9：本次只运行定点定位

1. 使用原签名0.3.9覆盖升级，不卸载；车辆安全停稳后进入“车辆检测 → ADB只读诊断”。
2. 填原来可用的车机本地ADB地址、端口，点击“消息总线定点定位（不提取APK）”并确认。即使其他APK选项有勾选，此入口也忽略它们，不重跑旧原生读取探针。
3. 自动先记录车端时钟，以App原UID只发现服务并核对接口；紧接着采集相关系统日志，再采集注册/声明清单与分区VINTF XML。不订阅、不写车辆属性，不自动打开工程组件；服务查找本身可能触发系统已有的惰性初始化。
4. 等待流程结束，导出本次ADB诊断JSON；显示异常、不可见或部分完成也请导出。不需要另跑60秒订阅、切挡或重新提取APK。

报告中的`hidlDiagnostics.appLookup`是本次仅发现会话；顶层`messageCenterProbe`仍保留先前独立检测，不会被临时探针覆盖。检测JSON中的路径为`adbDiagnostics.hidlDiagnostics`。

- 同期采集不等于自动判定根因。日志摘要分别标记时间窗口、App UID/PID和目标服务匹配，不能把其他进程/旧时段的拒绝套到当前失败。记录车端时钟偏差；轮转、过滤和权限可能使证据缺失。
- 注册清单与日志由ADB shell读取，服务发现由本App身份执行。回环地址也不能证明没有转发，所以`sameDeviceVerified=false`；非回环ADB跳过本地App复现，避免把两台设备的证据混合。
- `lshal`注册/声明清单与VINTF中的接口、实例和传输类型保留原文及解析结果。看不到不等于不存在，shell列得出也不代表App获准访问。
- 每条定点命令记录退出码与传输、截断、工具不支持、拒绝和超时等状态；命令成功只表示采集成功。清单命令6秒上限，VINTF每分区最多8文件、每文件64KiB，单命令512KiB、此模块合计4MiB，受整份报告12MiB上限约束。不完整XML/读取失败不会被解读为没有声明。
- `NoSuchElementException`在发现阶段归为`service-unavailable`，保留错误类型、失败阶段及有界堆栈，不直接归为签名或SELinux拒绝。仅发现流程不发送订阅/注销事务，不改隐藏API策略或权限。

## 2026-10-31 17:08实车结果

用户提供`/Users/hongjunlong/Downloads/ES-检测-20261031-170823.json`，应用0.3.8、Android13/API33。本次messageCenterProbe会话3，原App UID10091，记录开始17:08:04.653，结束17:08:04.654，导出17:08:23.185（Asia/Shanghai）。

- `failedPhase=discovering`、`phase=operation-error`，完整错误为`java.util.NoSuchElementException: null`。按本版代码，异常位于`HwBinder.getService`发现路径；没有取得该服务句柄的证据，不是等待回调60秒后无变化。
- `serviceFound`、`interfaceVerified`、`subscriptionAttempted`、`remoteSubscriptionAttempted`及`subscribeResult`字段均未产生，samples为空。不要把字段缺失冒充显式false、注册返回非0，或把异常后的null当作空挡位值。
- `unsubscribeSkipped=no subscription transaction dispatched`、`cleanupPending=false`、`cleanupConfirmed=true`：本轮没有发送订阅事务，无需远端注销；不是成功注册并注销的证据。sessionId=3只表示当前进程内的会话编号，文件只保留最新一轮，不能推出前两轮的结果。
- 该异常不能单独区分服务未注册/未运行、实例或声明不同、系统访问策略导致不可获得；没有SecurityException也不能排除SELinux。尚不能说总线在整机上不存在、一定是签名问题，或工程模式页面已实测可用。
- JSON内ADB快照起于16:12:14.419，讯飞探针起于16:04:30.090，均是旧记录；文件内没有lshal/hwservicemanager/hwservice_manager信息，应用lines只有生命周期及Mega未授权提示，没有此次17:08同时间的系统层拒绝日志。READ_LOGS已授权不等于HIDL访问已授权。

后续应针对同一实车补采HIDL服务注册/传输类型与实例、相关VINTF声明及复现时段的hwservicemanager/SELinux日志，分别保留命令拒绝、截断与无法观测的情况。现有`service list`普通Binder清单不能替代HIDL硬件服务清单；即使新枚举看不到，也不自动认定整机没有服务。不再靠延长会话、切挡或盲换topic重复测试，也不改权限或调用身份。本次仅分析与记录，不改App、不构建新版。

新增独立的`MessageCenterProbe`，用于验证另一套工程模式中的CAN页面消息总线路线。仅本App UID调用，无shell代理、root、系统身份、属性修改或权限绕过；只订阅/注销，不发控制、查询触发或注入消息，不接音效。

## 实车怎么测

1. 用原签名的0.3.9覆盖升级，不卸载旧版；车辆安全停稳后进入“车辆检测”。本节是独立订阅检测，不是上方定位异常所需的步骤。
2. 找到“消息总线 · 只读验证（新路线）”，点击“开始消息总线检测”并确认。无需开日志辅助、ADB或扩展检测，也不用再次提取APK。
3. 最多60秒自动结束；服务发现、接口核对、订阅结果、总回调数、有效标记数、变化数、逐topic最近原始值、失败阶段及注销结果分别显示。无效/解析失败不会显示为有效0。
4. 等结束后导出“车辆检测 → 导出记录”JSON；0次、拒绝或隐藏API错误也请导出。普通回调约250ms合并保存，停止会立即保存；若清理还在进行，导出可能带`cleanupPending=true`，不伪称已注销。等待清理后也可进入ADB诊断重新采集并导出；两种JSON都带`messageCenterProbe`及独立起止时间。
5. 离开检测页、切到其他页签、导出、停止监听或服务销毁均会停止探针并尝试注销。回到页面不会自动重启。不要为测试专门切挡或行驶。

开始新探针会停止旧讯飞挡位探针，反向同理，避免混淆两种来源。旧功能仍保留，但不会自动运行。

## 只读协议与候选topic

从用户提供的`com.mega.engineeringmode.apk`核对的客户端协议：

- `HwBinder.getService("vendor.mega.message_center@1.0::IMessageCenter", "default", false)`，非重试发现，不显式启动工程组件；底层服务发现仍可能触发厂商进程懒加载，不能保证远端无初始化副作用，开始前对话框有提示。
- 先核对IBase interfaceChain包含目标描述符，再用transaction 4批量订阅，5批量取消。请求使用同一个回调Binder及完全相同的topic列表。
- **未实现厂商发送transaction 1**。回调接口`IMsgRxCallback`的transaction 1是相反方向的单向数据接收，不是向车辆发消息；只有描述符、单向标记与topic白名单符合才处理。

固定5个候选，不扫描/通配订阅总线，也不订阅VIN或账户标识：

| topic | 证据及限制 |
| --- | --- |
| `DrivingInfo/Gear` | 同车哈希匹配的RedwoodCarService DEX内直接常量；不预先解码挡位 |
| `VehicleDoor/CurrentPosition` | 同上车辆服务topic常量，原始值可能是结构化数据，不预断门位置 |
| `VehicleDoor/CentralLock` | 同上车辆服务topic常量，不预断0/1方向 |
| `VehicleDoor/reevLHFdoors` | 同上车辆服务中REEV主驾门候选，不声称已验证实车含义 |
| `T1CF1/AutoHold` | 另一工程模式CAN映射`ABS_2C0/AVHSts`对应topic；T1CF1不等于E0X车型适配成功 |

回调按UTF-8 JSON解析，必须`valid`为布尔true且`value`非缺失/null才计入有效标记数。保留原始JSON摘要，不把任意0当成P挡、闭锁或AutoHold关闭。首次有效值仅建基线；重复值不计变化，无效或解析失败清除变化基线。回调无可信源时间戳，`valid=true`也不证明新鲜度、来源身份或语义正确。

## 生命周期与边界

- 后台单线程HIDL调用；发现/核对/订阅各阶段最多8秒等待，会话最多60秒。发现不可见、权限/SELinux拒绝、接口不匹配、非0返回、隐藏API/链接错误立即结束，不改身份或重试。
- 同步native/HIDL调用不能强制取消。主线程超时会先关闭接收、记录失败阶段及清理待定，调用返回后注销；未返回前拒绝新探针并报告busy，不累积工作线程。
- 尝试过订阅即用同一回调尝试一次取消（包括远端可能部分注册后抛错）。明确记录`unsubscribeResult`、异常及`cleanupConfirmed`；停止接收不等于远端已成功注销。
- 只接收白名单，最多64条待处理回调，8KiB载荷（native反序列化后检查）、96条事件摘要、每topic最后一条样本；JSON原文和值摘要分别限2048字符，记录截断和丢弃计数。
- 探针独立持久化；重启后若原记录仍活动/清理待定，标记`previous-session-interrupted`与`cleanupUnknown`，不冒充已完成注销。不同会话不能用旧回调覆盖新报告。
- 本地模拟测试不能证明车机hidden API、SELinux或服务端允许普通App访问；真正结论必须看实车导出的阶段与原始错误。

## 构建与测试

HIDL系统类没有包含在公开SDK中。`tools/hidl-stubs`仅用于Java编译和D8链接；Gradle为compileOnly项目，bootstrap构建放在独立输出目录，**不打进APK、不实现替代系统类、不豁免隐藏API策略**。实际调用受车机系统策略约束，失败会记录，不导致自动提权。

协议测试用模拟HwParcel/HwBinder检查接口token、只读4/5事务、回调方向/白名单/标记、IBase响应及资源释放；状态测试覆盖有效/无效/坏编码/过载、超时/停止/注销、持久化与无音效副作用；UI和ADB测试检查手动入口与两类导出。
