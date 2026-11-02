# MegaEngineeringMode：ADB与车辆权限开关对照

日期：2026-10-31。用户确认此APK就是实际用于开启ADB的工程程序。本轮仅电脑端静态分析，不安装/启动APK，不连接车辆，不发送Intent或消息，不修改系统属性，不生成新版本。

## 样本身份

- 文件：`/Users/hongjunlong/Downloads/MegaEngineeringMode_13.apk`。
- 字节数15,650,869；SHA-256 `ad47bcc7be6ece6c538efec46a2851501acc5d06c71c635cd8b4131e834b1847`，与10月30日[原清单](apk-research-20261030/apk-inventory.json)完全相同，不是更新版。
- 包名`com.mega.app.engineeringmode`，versionName13/versionCode33，min/target SDK33；Manifest声明`android.uid.system`。
- apksigner验证通过；证书SHA-256 `83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d`。身份声明不代替运行UID核验。

## 直接结论

**确认有ADB开关；本次未找到用于启用车辆服务`persist.mega.car.userdebug`的设置入口，也未确认只对本App授予只读车况权限的入口。** 车辆服务自身存在该调试例外分支的[上一轮结论](offline-event-alternatives-20261031.md)仍成立，但不能把“服务会检查它”推成“这个工程APK能够修改它”。

### 用户指出的“关闭鉴权”：确认存在，但不是同一个属性

用户补充界面有“关闭鉴权”后重新核对资源及classes6.dex，确认此前遗漏了这个实际按钮，应纠正“未发现授权入口”可能造成的过宽理解：本APK确有鉴权状态开关，但没有证据说明它能关闭Mega车辆属性权限。

- 资源`close_auth`为“关闭鉴权”，`open_auth`为“打开鉴权”，按钮ID为`switch_auth`，属于`info.InfoActivity`主机产品信息页。
- `InfoActivity.B()`读取`persist.mega.need_auth`，默认true；`H()`用它更新鉴权开/关状态和“关闭/打开鉴权”按钮文字。
- 点击处理`InfoActivity$b$a.onClick`取得当前布尔值后取反，经`h.c.c(String,String)`调用`SystemProperties.set`，修改的键是`persist.mega.need_auth`。它不是假按钮，但静态实现不能代替写入成功或生效结果。
- Mega车辆属性服务`CarImpl.assertPermission`读取的却是`persist.mega.car.userdebug`（默认false），随后调用Android权限检查。这两个键不同，已核对的检查路径没有读取need_auth。
- 对现有平台服务、Redwood、SystemUI、生态中心、场景引擎、桌面及场景模式APK的DEX完整键扫描未命中`persist.mega.need_auth`；此项仅定位直接引用，不排除系统framework、native或其他未取得模块读取此键，不能断言它具体只控制联网、证书、应用或工程页面鉴权，更不能宣称两键绝对不存在间接联动。

结论：不能把该按钮认定为本项目需要的车辆只读授权开关；其实际受影响模块仍缺消费者实现。本轮不点击或修改，不建议为了排查盲目关闭。当前0.3.7只采集车辆userdebug属性，未采集need_auth，不把已交付包描述成包含本次新增发现。

### ADB：精确匹配既有实码

`AdvancedOptionsActivity.onClick()`中`enable_tcp_adbd`分支写`vendor.adbd.disable_tcp=false`。已核对classes6.dex的字符串、SystemProperties.set调用及相应分支，与用户实际启用ADB的陈述相符。这个按钮不写车辆userdebug属性。

### 最像车辆开关的选项，实际不是权限开关

资源包含：`CarService缓存第一次无效值(重启后生效)`、状态`缓存/不缓存`及按钮`切换`。

但本版本原始DEX确认：

- 状态读取方法`DebugActivity.d():boolean`只执行`const/4 0; return`，固定返回false。
- `carservice_cacheinvalid_switch`对应监听器`DebugActivity$a.onClick`只经合成方法调用`o()`。
- `o()`读取上述固定false并更新TextView，因此只刷新“不缓存”显示；这条点击链没有系统属性写入或服务调用。

所以既不能把这项名称当成授权开关，也不能依据“重启后生效”的文案就建议用户重启尝试。仅对这份字节一致的版本作此结论，不推广到其他固件工程工具。

### 通用工具函数不等于外部授权入口

`Util.setProperty(String,String)`是经反射调用SystemProperties的内部工具。已检查相关App业务调用点，没有定位到外部参数可直接选择车辆userdebug键的路径。拥有内部工具方法不等于普通App可以借用其系统身份，也不证明能修改每个受保护属性。

Manifest有导出的`EngineerModeCommandService`，但`onBind()`返回null，已恢复的`onStartCommand()`处理的是清除DDR数据命令，并非车辆属性读取/授权服务。**不启动这个服务试探**，也不把其清理操作转用于本项目。

菜单配置包含带访问限制标记的页面；本轮不分析口令、验证密钥或规避页面访问检查。代码声明加载原生`cert_pwd_jni`库，APK未携带相应实现，实际加载来源尚未确认；不能把Java恢复结果说成覆盖外部代码。

## 检查范围与下一步

- 检查六个DEX、Manifest、四份工程菜单配置、资源与关键属性调用点。ZIP全部条目解压内容未出现完整ASCII键`persist.mega.car.userdebug`，ADB键只在classes6.dex出现；无直接字符串不单独作为不存在动态路径的证明。
- `userdebug`在恢复Java中命中的是通用SetupDesign对构建类型的判断，不能与Mega车辆服务属性混淆。
- JADX1.5.3全量完成，最终报告20处恢复错误；核心ADB及缓存按钮结论使用原始DEX交叉核验。未完整审计外部native库、远端消息处理端或全部固件。
- 后续如用户回车采集，只需先做现有车辆调试属性及包版本的**只读状态核验**，不预置属性写入，不全局关闭权限检查。真正有价值的下一层是厂商按App/只读范围的正式授权配置或真实车辆provider，不是反复尝试这个已无对应入口的工程包。
- 本轮不改App运行代码，没有新APK；静态分析不等于实车权限通过。证据见[小段DEX摘录](apk-research-20261031/engineering-mode-switch-evidence.txt)。

临时完整分析在忽略目录`build/engineeringmode-20261031/`，后续构建会清除。文件哈希、ZIP CRC、签名与证据检查均通过。
