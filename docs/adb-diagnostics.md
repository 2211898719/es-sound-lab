# App 内 ADB 只读诊断与 APK 提取（0.3.7）

当前0.3.9 / versionCode15沿用原签名，新增“消息总线定点定位（不提取APK）”：只复现App原UID服务发现并核对接口，紧接着读取相关系统日志、HIDL注册/声明清单与VINTF。此入口忽略所有APK选择、不订阅、不重跑旧原生读取探针；完成后导出ADB诊断JSON，失败也保留部分结果。具体边界见[消息总线定点定位](message-center-probe.md)。

0.3.7新增定点诊断和通用提取。默认不选任何APK，也不重跑已知拒绝的原生探针。手填包名和扫描多选均已实现；扫描包列表不会启动目标App或修改车辆服务。

0.3.6新增独立App UID的[讯飞挡位检测](platform-vehicle-probe.md)。先在车辆检测页手动完成读取/注册/回调验证，随后ADB报告会附带最后一次`platformVehicleProbe`快照（独立会话时间，不代表shell执行）；也补充平台服务路由、包元数据和运行状态的只读命令。无需重复提取已有APK或重跑已拒绝的Mega探针，最简反馈直接用车辆检测页“导出记录”。

用户在车机开启ADB后，在App里直接连接并采集；不需要另外安装Shizuku或把电脑常驻车内。当前只支持工程模式常见的传统TCP ADB及RSA授权；不支持配对码/TLS无线调试，不自动开启ADB或寻找端口。

## 在车上怎么用

1. 在原有工程模式中开启ADB，确认实际连接端口。
2. 进入“车辆检测 → ADB 只读诊断”。地址先用 `127.0.0.1`；端口填写工程模式实际端口。默认留空，不能把5555当已确认值。
3. 如需提取，在“通用APK提取”手填实际包名（换行/空格/逗号分隔），或点击“扫描已安装包”，等待授权后按包名搜索多选，点击“保存选择”。扫描只执行`pm list packages -f`，列出路径符合规则的已安装包，不代表shell已获文件读取权限。扫描失败或取消不覆盖原选择或旧诊断；旋转后保留已选包，但完整扫描列表需重新扫描。保存的多选在本机保留，再次打开请检查并按需清空。
4. 不需要提取则保持包名输入为空、多选清空、预设组不勾选。手填、多选与预设组会合并去重，最多64包，包名严格验证，不能输入命令或路径；未知但合法的包名可尝试，包不存在将明确失败。
5. 点击“连接并采集诊断”。如系统询问，允许此次 `ES Sound Lab` 的ADB连接；首次认证最多等待约60秒。扫描和采集使用独立连接，可能再次询问。不弹窗不一定失败，以页面结果为准。
6. 完成后分别点击“导出 ADB 诊断”和“导出系统 APK（ZIP）”，将两份一起提供分析。仅诊断时没有本次ZIP。已知拒绝的shell原生探针默认跳过；若手动启用，请停车P挡，不要求切挡。
5. 连接失败、取消或断流也可导出，已完成的命令和当前命令收到的部分输出会保留。退出/销毁页面会关闭连接并保存部分结果；后台不持续监听，重进页面不会自动重连。

建议在需要诊断的原生接口错误刚出现后采集，避免环形日志已被后续输出覆盖。日志是近期快照，并非整段行程录制。报告默认只保留最近一次采集，重新采集前有需要可先导出旧记录。

报告及ZIP内的`extraction-info.json`包含所选包名、逐包完成情况、逐文件真实路径/字节数/SHA-256、采集ID；目标同时采集包组件和权限元数据。通用包归档为`packages/<包名>.apk`及`packages/<包名>/<序号>-<分包名>`，原六个预设保留历史文件名，命名空间隔离避免碰撞。包不存在、路径缺失、无权读取或传输失败会记录失败包名并删除本批不完整ZIP，不自动跳过错误、伪报完整成功或导出旧批次ZIP。仍受原只读安装路径规则、单文件2GiB/合计4GiB及64MiB空闲保留限制；应用私有数据不可提取。每批建议少量选包，既有诊断总输出仍限12MiB。提取成功不等于存在普通应用可用的事件接口。

## 新增定点只读状态

JSON新增`targetedReadOnlyDiagnostics`：

- `vehicleDebug`记录`persist.mega.car.userdebug`原值及`true/false/empty/unrecognized/truncated/transport-error/not-collected`状态。空值、拒绝和传输失败不折算为false；页面只显示观测结果，不自动改变任何调试配置或触发探针。
- `properties`包含车辆调试属性、网络ADB相关属性、构建指纹和安全补丁字段；它们是本次采集快照，不证明车型适配或权限放行。
- `network`保留`ip addr show`、`ip route show`、`ss -ltn`的原始输出，命令缺失或权限拒绝仍供判断；`lanReachabilityVerified=false`，必须由另一台设备实际连接才能确认局域网可达。导出前注意IP/MAC等网络信息。
- `commands`中按验证后的实际安装路径对`com.mega.car`、`com.mega.app.engineeringmode`、`com.mega.engineeringmode`追加`sha256sum`；未知/缺失路径或路径输出截断时不拼接命令。哈希命令的输出和失败原样记录，不把shell流完成等同于哈希核验成功；本机实际提取的文件另有SHA-256。

不枚举或连接局域网其他设备，不扫描端口，不调用`setprop`、`grant`、车辆写入、调试权限修改或root。

## 23:33 实车结果：连接成功，四项读取被拒

报告`ES-ADB诊断-20261030-233324.json`确认0.3.4 / versionCode 10生效，51条命令全部完成传输且无截断/传输错误；本次未选APK提取。探针UID2000，SDK两参数初始化及属性管理器取得成功，未请求服务绑定。旧调用方绑定错误本次已解决。

主驾门、挡位、中控锁、AutoHold各实际读取一次，分别明确抛出缺少ENTRY_LOCK、CAR_DRIVING、VEHICLE_CONTROL、SEQUOIA的SecurityException；各项readDenied=true，outcome=permissions-denied。权限定义仍为signature|privileged，本App和本次shell元数据均未获授四项权限。readAttempted=true但readVerified/changeObserved=false；订阅本轮没有测试。探针约37ms后按全部拒绝提前结束，不能误判为超时、没有开门或采样时间不足。

此结果只针对本次shell身份、固件和四类属性，不扩大成所有ADB能力均不可用；但它已排除将当前同权限shell接口当作这四类事件来源的依据。后续不重复改初始化、延长采样或接同权限常驻桥，先确认厂商支持的授权/开放接口，不改系统权限或调用身份。

## 22:58 实车结果与系统 APK

0.3.3的51条命令传输完成且无截断，两个APK提取成功。用户提供的ZIP经实际读取、大小/SHA-256及采集ID核对一致；SystemUI为257.4318MiB，确认了旧256MiB上限的触发原因。shell探针虽启动成功、SDK已加载，但绑定MegaCarService时出现`Unable to find app for caller`，四项样本均为空，不是已完成四项读取后全部被拒。当前仍未验证读取、变化或订阅。SDK及SystemUI实现结论见[本批分析](system-apks-analysis-20261030.md)，不将本次连接失败改成后台持续重试。

## 0.3.2：本次验证什么

> 0.3.4更新：shell探针不再调用四参数生命周期绑定入口，改为本批SDK已有的`MegaCar.createCar(Context, Handler)`查询现有服务。该实现的构造器只保存服务引用与连接状态，不调用bindService；不回退到绑定、不启动服务、不换调用方、不改权限。接口缺失、查询被拒或`isConnected=false`立即记录连接阶段失败。23:33报告已确认本次初始化和管理器取得成功，但四类实际读取均被权限拒绝。以下四轮读取规则仅在成功取得管理器后执行，真正被拒的属性不再重试。

0.3.4探针结果的`schemaVersion=2`；新增`phase`/`failedPhase`、`processVerified`、`contextReady`、`permissionsChecked`、`sdkLoaded`、`sdkInitializer`、`serviceBindingRequested`、`serviceConnected`、`managerAvailable`和由样本重算的`readAttempted`。检测页直接展示分阶段结果、四项权限元数据和原始错误，区分“没执行读取”与“已尝试但没有有效值”，不再只显示笼统未验证成功。旧版探针报告仍可读取；缺失的阶段标为未确认，不推定成功。订阅始终标为本轮不测试。

- 元数据补充`com.android.shell`真实授权；SystemUI查询修正为`com.android.systemui`，不再请求不存在的`com.mega.systemui`包。权限名及Provider authority不随包名改变。
- 只读探针通过本应用已安装APK的固定入口运行在ADB shell进程，不上传额外二进制、不复制私钥、不请求root、不修改授权。仅允许UID2000；11秒看门狗退出，SDK依赖或SELinux阻断均写入诊断，不能把进程启动成功当读取成功。
- 使用已有SDK反射签名，读取主驾门`0x0600002c`、挡位`0x0400000a`、中控锁`0x67000096`、AutoHold`0x50000022`，区域0、Integer，各最多4轮、间隔2秒。包权限检查和真实读取分开：前者不获授仍允许实际只读检验服务端例外，真正抛SecurityException的属性后续轮次跳过。
- `nativeReadProbe`包含UID、SDK/管理器、逐项权限和样本、status/原值/时间戳/异常。只有ID/区域/类型匹配且status=0才计有效；`readVerified`表示至少一项有有效值，`changeObserved`表示有效样本值发生变化。没有操作或没有变化不等于接口不可用，需结合逐项结果。
- 此轮不注册回调，`subscriptionVerified=false`；没有后台常驻shell车辆桥，不触发音效，不写车控属性。能短时读值也不等于实时订阅、后台存活或音效接入已经可用。
- 取消或离开页面关闭ADB连接；远端只读探针不保证同一瞬间被终止，最迟按自身11秒看门狗退出。四类读取都实际被SecurityException拒绝时提前结束，不空等或重复刷失败。
- 已确认本App未授予的签名/特权权限不再反复直接读取/订阅；若四域全部被阻断则不再反复加载SDK。不是删除音效配置，其他获授域和未知权限定义仍保留原生检测。

## APK提取与导出

选择后通过`pm path <实际包名>`取得真实安装路径，使用ADB sync的RECV/DATA/DONE二进制协议，只读传输到本App私有目录中的ZIP。不是`cat`文本转码或JSON内嵌大文件，也不向系统目录写临时文件。旧两包在ZIP内保留`RedwoodCarService.apk`、`RockySystemUI.apk`命名，通用包使用上文的`packages/`命名空间，若有分包也一并保留，附`extraction-info.json`以及各文件路径、大小、SHA-256。

每个目标最多16个APK。0.3.3将单文件上限从256MiB提高到2GiB、合计从512MiB提高到4GiB、每文件期限从180秒提高到30分钟，读取空闲仍最多15秒；全程流式处理，不将整个APK放进内存。开始及每读取约1MiB检查App目录存储空间，至少保留64MiB空闲，不足则停止。真实路径必须符合系统/应用APK只读白名单；文件为空、服务拒绝、传输断开、取消或超过上限均失败并删除半成品。保留最近成功归档，下一次成功替换时清理旧归档；只有与当前报告采集ID一致且完整的ZIP能导出，不把上次成功冒充本次成功。异常退出遗留`.part`在下次提取前清理。

0.3.2的“APK超过单文件256MiB上限”来自App自设限额，不是ADB授权错误；同一提示也曾用于合计预算不足，不能仅凭这句提示确定哪个APK的实际大小。0.3.3错误包含具体文件名；报告记录`fileLimitBytes`、`totalLimitBytes`、`minFreeSpaceBytes`以及逐文件`limitBytes`、`limitKind`、`bytes`和`error`，区分单文件、合计限额及存储不足。

JSON的`apkExtraction`保存提取元数据和错误，不包含APK二进制。未勾选、部分失败或归档已被清理时不会提供成功ZIP。JSON和ZIP均使用系统文件选择器导出，无需存储全盘权限；JSON使用缓存快照，Activity保存状态只记录短文件名。ZIP导出也可能被取消，失败时用户选择的目标文件可能不完整，不要作为成功材料发送。

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

## 2026-10-30 实车报告：第二条命令流ID不匹配

21:30导出的报告确认：连接本机传统TCP ADB后，第一条`id`完整返回`uid=2000(shell)`及`shell` SELinux上下文；第二条`getenforce`尚无输出即报`ADB stream ID 不匹配`。因此本次连接及首条shell命令已成功，不是仅凭此错误就能认定ADB未授权或车辆权限不足；SELinux是否Enforcing、Mega权限定义和应用实际授权仍未采集到。

旧客户端要求下一条命令收到的每个报文都属于新流，未隔离已关闭流的迟到报文。模拟上一条流关闭后又收到旧`CLSE`可复现相同错误；原报告没有报文类型和ID，不能据此确认实车具体收到的是哪种报文。

源码现保存最近64个已关闭流的本地/远端ID，只跳过匹配旧流的`CLSE`/`OKAY`；旧`WRTE`不混入新命令输出，而是回复关闭。截断后的关闭等待也使用同一隔离逻辑，并受报文数量和命令期限限制。未知流或错误远端ID仍中止采集，错误附报文类型及期望/实际ID；shell服务拒绝打开不再记作成功空输出。

本轮通过145项核心检查、89项Android/Robolectric回归及资源/Java/DEX编译；新增8项测试覆盖连续命令迟到报文、截断关闭、错误ID、拒绝打开和旧包上限。系统默认Java 25不兼容当前Robolectric的ASM，验证时使用本机Java 21及`ES_SOUNDLAB_JAVA_HOME`，未变更测试依赖。未重新签名或替换`dist/`里的APK，现有0.3.1安装包不自动包含本次源码修复。

在原签名环境重建并覆盖安装后，按相同地址/端口重试，检查`getenforce`之后是否继续采集权限与服务信息。即使仍失败也请导出新报告；新增ID详情用于确认剩余协议差异。此轮修复尚未实车复测。

## 实现与本地验证

0.3.4本地通过145项核心检查、121项Android/Robolectric回归及资源/Java/DEX编译。新增11项覆盖两参数SDK调用及原Context/Handler保持、无绑定或启动、接口缺失不回退、无服务不重试、空返回、查询拒绝原始异常、各阶段失败、连接与读值分离、权限元数据、旧报告和页面错误摘要。均为本地模拟，未连接车机，不能当作两参数入口已实车通过或已获车辆权限。

0.3.3本地通过145项核心检查、110项Android/Robolectric回归及资源/Java/DEX编译。新增回归实际流式读取257MiB模拟二进制数据并比对CRC，不在内存中构造整个文件；同时覆盖长整型文件/合计预算、真实限额提示、低存储预检及传输中断后的字节记录和半成品清理。仍需实车重新提取验证。

0.3.2本地通过145项核心检查、106项Android/Robolectric回归及资源/Java/DEX编译；新增覆盖sync二进制分片、拒绝/限量/空文件/取消、ZIP内容和采集ID、只读样本判定、无权限不反复访问、检测合并及大JSON状态保存。尚未实车验证APK提取或shell探针启动/读值。

`AdbClient`处理标准ADB连接、RSA认证、单shell流、ACK/关闭、取消和限量输出。`AdbDiagnosticCommands`固定命令清单并校验动态包名，`AdbDiagnostics`逐命令原子保存报告，`AdbDiagnosticsActivity`提供手动连接与导出。协议和平台密钥兼容性使用模拟传输及Robolectric验证，没有连接真实车机。

本地通过145项核心检查、81项Android/Robolectric测试及资源/Java/DEX编译，覆盖标准认证、多段输出、截断、取消/断流、密钥复用、动态服务/HOME包名发现、合并导出和页面不自动连接。尚未实车连接验证。

上述81项为0.3.1历史验证记录。当前版本0.3.4 / versionCode 10，使用已核对的原0.2.x密钥构建；0.3.3提取与0.3.4初始化已分别实车确认，四类属性实际读取均被权限拒绝，旧安装包不会自动包含本轮功能。
# 0.3.8消息总线检测补充

车辆检测页新增“消息总线 · 只读验证（新路线）”，使用App原UID订阅5个候选状态topic，最多60秒，不发车辆控制消息。必须手动启动；0次/权限拒绝/隐藏API错误也请导出，不需要再次提取APK。车辆检测JSON和新一轮ADB诊断均包含独立`messageCenterProbe`，不要把导出时间当作探针时间。详见[操作、协议与限制](message-center-probe.md)。
