# 离线复查：还有哪些非日志事件路径

> 2026-10-31后续更新：17:48已确认本App及shell对消息中心的SELinux查找拒绝；17:57三包已补齐静态复核，systemserver并未补齐预期车辆provider，Owl是上报接口，车设相关出口受保护或定向本包。此前“systemserver优先研究”是当时待核线索，应以下文链接的新结论为准。[三包复核与后续取舍](system-apps-investigation-20261031-175727.md)。

日期：2026-10-31。用户已离开车辆；本轮仅检查已有APK、旧JSON、历史代码与研究记录，没有连接车机、调用系统组件、修改运行代码或构建新APK。当前交付仍为0.3.6。

## 结论先行

**尚未确认一条能被本App使用、具有新鲜实车数据、并替代日志覆盖门/挡位/中控锁/AutoHold的接口。** 这不等于证明整车没有任何其他接口，而是现有证据不足以交付另一个可靠后端。

- 用户口述0.3.6挂挡后仍为0、变化0次，但未取得对应JSON。不能据此区分注册失败、查询未知值、只有缓存、上游未推送等情况，更不能认定就是签名拒绝。
- 今晚无需上车或重新提取已分析的APK。先把当前材料能排除的路径排除，不再仅凭SDK函数名发布试验包。
- 优先研究缺失的真实车型数据提供者；日志可作为另一条工程路线，但ADB日志桥仍然是日志，不冒充原生订阅。

## 补充：实际发现Mega服务权限调试分支

用户补充ADB来自非公开测试程序，提出车辆服务是否也有类似开关。本轮继续定点检查Redwood原始DEX，**确认存在服务端权限检查调试分支**，不是仅凭字符串猜测。此项更新前文“继续找接口”的优先级，但尚不是可安全启用的只读后端。

- `com.mega.car.CarImpl.assertPermission(Context,String)`读取`persist.mega.car.userdebug`，默认false。该读取经`MegaSystemProperties.getBoolean`直接委托Android系统属性接口。
- DEX显示该值为true时直接返回；否则再进入`checkCallingOrSelfPermission`，失败抛`SecurityException("requires " + permission)`。
- 原始DEX同时确认`CarPropertyService.getProperty`、`registerListener`、`setProperty`都调用同一个`assertPermission`。所以它并非只为本App或只读事件设计的窄授权开关；改变它会影响共用此检查的其他调用方及写接口，不能当成安全的“允许共享车况”设置。
- 它与旧研究的ADB开关`vendor.adbd.disable_tcp`是两个不同机制。打开ADB不会自动改变车辆服务这个分支。
- 现有材料尚未证明：当前属性值、哪一组件有权修改、有无厂商提供的受控入口、运行固件是否与样本完全一致，以及其他过滤/HAL/SELinux检查。已恢复源码中只找到检查端，未找到写入该属性的UI；原工程测试APK完整实现当前不在材料中，不能声称已找出可用操作入口。
- 这是扩大权限检查例外范围的调试机制，不是厂商授权声明。不执行属性修改、不伪装调用方身份、不注入事件或修改系统安全配置。后续离线优先审查厂商是否存在按应用、按只读属性授权的正式机制；无窄范围机制时保留研究结论，不因用户找到ADB入口就盲开全局车辆调试权限。

关键DEX片段补入[证据摘录](apk-research-20261031/offline-alternatives-evidence.txt)。本轮仍未改运行代码或生成新APK。

## 一、标准Android Car：权限有了，数据并没有因此得到证明

00:23报告中的`binder-services`包含`car_service: [android.car.ICar]`，并独立列出标准AIDL VHAL实例`android.hardware.automotive.vehicle.IVehicle/default`。Mega另有`com.mega.car.CarService: [mega.car.ICar]`。

`permission-definitions`与`package-cn.es.soundlab`显示：

| 权限 | 实车保护级别/授权 | 能说明什么 |
| --- | --- | --- |
| `android.car.permission.CAR_POWERTRAIN` | normal；本App granted=true | 不同于已被拒绝的Mega四域权限，但不能证明数据新鲜 |
| `android.car.permission.CONTROL_CAR_DOORS` | signature\|privileged；本App granted=false | 不能把挡位权限扩展为门/锁访问能力 |
| `android.car.permission.CAR_SPEED` | 本App granted=false | 不能顺带宣称车速已可用 |

`car-service-dump`有`GEAR_SELECTION`（`0x11400400`）和`CURRENT_GEAR`（`0x11400401`）的READ/ON_CHANGE配置。但目录、支持枚举和注册客户端数量不是当前挡位值，也不是变化记录。`CarDrivingStateService`显示状态0及旧Boot记录；不足以证明接上实际车况，也不足以断言整个标准VHAL一定是假实现。

**必须纠正“新路径”的表述：** [10月29日实车分析](log-analysis-20261029-224225.md)已经记录“Android固定挡位”。旧`VehicleAccess`也曾包含Android兼容路径，后续纯Mega改造才移除。它不是从未试过的新方案。先前进度中提到其权限已获授，只能作为权限事实，不能升级成读取成功。

优先级：低。未来若做对照，应明确区分两个挡位属性、状态/时间戳/回调与真实变化；没有新鲜变化就停止，不把反复读取固定值当实时成功。当前不为此重新制作一个整套Android后端。

## 二、讯飞：原厂客户端引用不等于实际车型回调已经可用

恢复场景引擎源码并用DEX交叉核查：

- `SystemStatusManager.init()`确实注册系统监听器，但它的`onVehicleStateChanged(String,int)`只有一条`return-void`，没有业务处理。其service-status处理也是空方法。
- `getGearReverse()`是主动调用`getVehicleState("gear")`；只有返回1000时得1，其余所有整数都被折叠成0，manager为空才返回-1。
- 因此“原厂场景引擎也引用这条挡位链”不能证明这台车型的回调曾成功，更不能证明原始0是一个有效挡位。在这段原厂客户端逻辑里，未知0也只会被当成“不是倒车”。

平台服务自身查询/转发并非空壳，详见[平台服务研究](platform-service-investigation-20261031.md)。真正缺口仍是CustomSystem注册的车型数据提供者：谁提供`onGetVehicleState`，谁发送变化，是否覆盖本车型。当前不能把CarControlAdapter或某个包直接认定为它。

本轮另确认场景引擎开机接收路径明确启动`com.iflytek.autofly.systemserver`的`com.iflytek.autofly.systemserver.START`服务。00:23清单也确有此包，路径为`/system/app/RockyIflytekXSystemServer/RockyIflytekXSystemServer.apk`。**这只证明运行关联和安装事实，不证明它就是车辆provider，更不证明有第三方开放接口。** 不发送这个启动Intent做试探。

优先级：后续定点研究的首位，但不是已成功方案。0.3.6本次JSON尚缺，不靠口述0给失败定性；也不靠多加轮询假装补齐上游。

## 三、SystemUI：车况确实进了界面，但没找到普通App可用的出口

### 中控锁缓存是私有数据

`CheryControlLock.onChanged()`读取`VelSignal.ID_DOOR_CENTRAL_LOCK`，调用`SharedPreferencesKt.putSpInt`保存，再用`setActivated(value == 1)`改变图标。

DEX确认缓存调用为`getSharedPreferences("com.android.systemui.sp", 0)`，不是公开Settings或已发现的车辆ContentProvider。不能把SystemUI能读Mega推成普通App能读取其私有缓存。

### Slice线索又遇到签名权限

SystemUI配置确有中控锁、AutoHold等车设Slice入口，实车权限定义又明确列出：

- `com.mega.permission.common.slice`：signature。
- `com.mega.carsettings.export.permission.RedwoodCentralLockSlice`：signature。
- `com.mega.carsettings.export.permission.RedwoodAutoHoldSlice`：signature。

这些权限事实不支持“换成Slice就对普通App开放”。未取得车设提供者完整实现，不能宣称已经审核每个Slice的内部鉴权；也不能把AutoHold开关界面等同于刹停保持/释放事件。`BehindScreenLockSlice`是后排屏锁，不是中控门锁。

### 界面观察只保留为低价值线索

本次图标代码只明确更新activated状态，未由此确认对外可观察的状态文本/无障碍语义，不能宣称无障碍已能识别锁状态。即便后续证明某个可见节点能读，也没有四类覆盖、熄屏和后台持续可用的证据，不适合直接取代原始车辆事件。本轮不申请无障碍权限或自动点击控制界面。

## 四、Lion / Nexus / Owl：不混淆服务名与车辆事件能力

- 场景引擎内旧Lion模块6明确指向`com.lion.appfwk.vehicle/.service.LTCarService`，`VEHICLE_SYSTEM_SERVICE=false`；AsyncBus又指向`com.lion.appfwk.asyncbus`。提供的00:23安装列表没有这两个宿主。不能把SDK里的get/register函数当作可连接的实车服务。
- 安装列表确有`com.lion.os.service`，但不能拿它替代上面的旧宿主。SystemUI中本轮查到的Lion账户SDK实际连接的是`com.lion.usercenter.service`，方法是账户、token、云端设置与快捷项，不是这四类实时事件。这个局部结论不等于已经审完Lion OS服务全部实现。
- `com.mega.NexusService`确有注册；SystemUI自带的`IMegaNexusManager`客户端版本主要暴露屏幕、任务栈、用户、音频焦点/静音等功能，未见四类车辆属性get/register。不能为了“有register方法”误接屏幕监听，也不调用其中控制/白名单方法。
- `com.mega.OwlService: [mega.owl.IOwlService]`只在报告中确认注册。本轮恢复的SystemUI/场景引擎源码没有找到对应车辆客户端实现；宿主存在不是开放API的证据。

## 五、dumpsys与其他旁路

Redwood的`CarPropertyService.dump()`实码只输出listener设置、client数量与属性注册PID，没有逐项读取当前值。旧报告的Mega dump也主要是目录与客户端注册。不能用高频全量dump替代低延迟事件订阅，更不能把“属性注册了”当成“值变化了”。

有限扫描SystemUI/Redwood的广播与Settings/系统属性写入后，没有定位到可交付的四类公开状态投影。这个结果受扫描范围、反编译完整性和动态实现限制，不是证明整车绝对不存在其他广播或Provider。

ADB/Shizuku代执行不会自动解决已经实测失败的Mega四域权限。若选择它们承载logcat，目标是改善日志读取的连接与授权体验，不是获得新的非日志车辆权限；重启/休眠后可用性仍需验证，不能现在保证永久免确认。

外接只读硬件属于独立的长期路线，当前没有车型协议、接入与四类覆盖证据；不推荐先买设备，更不盲发CAN/控制指令。

## 六、后续优先级与停止条件

1. **先厘清真正提供数据的适配层。** 将讯飞systemserver、已知CarControlAdapter旧研究、真实路由属性与0.3.6快照关联；缺哪层才研究哪层，不重复分析已经拿到的平台中转包。当前没有足够材料确认provider，不要求用户今晚回车提包。
2. **有合法厂商接入授权时走正式车辆接口。** 当前Mega拒绝是实测事实，不再循环尝试换包名、反射初始化、shell或相同属性。没有授权不宣称签名问题已解决。
3. **若目标是先恢复实际可用，单独设计日志后端。** 复用已有有效日志协议，评估手动启用的ADB长连接、断开可见、失效状态清除与首样本静默；这需要独立实现和实车验证。当前0.3.6没有因此自动回退日志。
4. 标准Car与界面观察仅作低优先级对照。不再为缺乏新证据的路径盲目发布下一版；任何成功结论都需要原UID访问结果与新鲜实车变化，不能只看注册0、读取0、ready或回调函数存在。

## 证据与复现边界

- 原始报告：`/Users/hongjunlong/Downloads/ES-ADB诊断-20261031-002358.json`；命令名分别见各节。传输完成不等于所有shell业务成功。
- 本轮APK：RockySystemUI、RedwoodCarService、RockySceneEngine，SHA-256及少量DEX证据见[证据摘录](apk-research-20261031/offline-alternatives-evidence.txt)。没有保存完整厂商源码到版本管理。
- 反编译工具JADX 1.5.3：Redwood完成；SceneEngine报告15处错误；SystemUI整包恢复在约91%时发生OutOfMemory，**不是整包审核完成**。本报告中挡位客户端、空监听、SystemUI私有缓存和锁图标更新的关键判断另用原始DEX核查；Nexus等范围判断限已恢复的客户端版本，不外推到缺失宿主。
- 临时分析目录`build/offline-alternatives-20261031/`会被后续构建清理，结论与小段证据保留在docs。未运行App测试，因为本轮不改运行代码；不把静态分析当实车验证。
