# 17:57三包复核与后续路线

分析日期：2026-10-31。用户目录`/Users/hongjunlong/Downloads/ES-系统APK-20261031-175727`。仅离线静态分析，没有连接车机、启动/绑定厂商组件、调用车辆接口、改变权限或构建新APK；当前交付仍为0.3.9。

## 结论

本批没有确认可供普通App使用的门、挡位、中控锁、AutoHold实时接口。不是全系统不存在开放接口的证明，但不足以据此再制作一版厂商接口盲测包。

- 讯飞systemserver主要是通知与连接组件，本批未补齐此前缺失的CustomSystem车型数据提供者。
- Owl的公开Binder方向是应用向系统上报事件，不是让应用订阅车辆事件。
- 车设确有车辆状态消费链，但已检查的锁、AutoHold入口受保护，锁刷新广播只发给本包；内部缓存不是共享车况API。
- 不再按“像车辆服务”的包名扩大提取名单；先验证已有、权限条件不同的标准Car挡位路径，或在用户接受日志方案时做事件日志覆盖验证。

## 样本核验

三包大小与SHA-256均匹配extraction-info.json，合计486,554,269字节；签名核验通过，证书SHA-256均为`83c557731f16f11ad8fb1c83024771e176d6902acbd9e0fc4c0ab97fecb78c3d`。均声明android.uid.system，声明不自动等同每个包的实测运行身份；17:48包管理器另确认carsettings为UID1000。

| 包名 | 字节 | versionCode | SHA-256 |
| --- | ---: | ---: | --- |
| com.iflytek.autofly.systemserver | 13,770,814 | 25102221 | 388dc7017c57fe15379d91ecefcfedddb4b45cb7923e4a1086a720c83c554e46 |
| com.mega.owl | 9,426,139 | 10000001 | a7538e9bd65b9ee4c5954aa72694c1ba49d3a3551266f329fbb9ce0f15fc069f |
| com.mega.carsettings | 463,357,316 | 10000010 | 56b9474d9834083412b9cf35c2baf73e6add2651326351275ccad52fb34cd1e4 |

清单、版本、Manifest组件、权限、DEX哈希与定向字符串计数保存于[核验清单](apk-research-20261031/system-apps-175727-inventory.json)，关键代码保存于[证据摘录](apk-research-20261031/system-apps-175727-evidence.txt)。完整反编译与选定类Smali在`build/system-apps-20261031-175727/`，后续构建会清理。

JADX 1.5.3分别报告13、0、99项方法恢复错误；不把有错误的方法当作真实可用逻辑。XSystemService返回null、Owl Binder方法集合、Slice授权对象、AutoHold布尔折叠、锁缓存与定向刷新广播已用所选类Smali交叉核对。未穷尽原生库、反射、固件framework或其他进程的实现。

## 讯飞systemserver

Application初始化NoticeSendManager、LinkManager并注册消息接收；XSystemService.onBind直接返回null，onCreate主要创建前台通知。另有NoticeReceive/Send的Binder回调，但语义是通知接收、处理结果及通知生命周期，不是车辆状态变化。

全包两个DEX定向字符串扫描没有onGetVehicleState、notifyVehicleStateChanged、CustomSystem、onGetCarProperty或mega/car/引用，已恢复代码亦未找到对应车辆provider注册链。原生库只有各架构libsign.so；没有对其所有原生逻辑作完整证明。因此结论是“这份包未提供我们正在寻找的实现证据”，不是整个讯飞上游不存在车辆提供者。

此前场景引擎会启动它，只能证明启动关联；目前应降低把它当作车辆提供者的研究优先级。不调用通知、透传或启动入口作车况试探，不把消息输入接口用作状态注入。

## Owl

OwlApplication注册com.mega.OwlService，与实车Binder清单对应。IOwlService只定义sendEvent(EventWrapper)，事务1；实现把收到的事件存入OwlDatabase，后续涉及日志/诊断上报，没有getVehicleProperty或注册车辆listener的方法。

内部CarMonitor通过MegaCarProperty注册车辆回调，供规则、异常诊断等内部处理。服务能消费车况不等于它向第三方提供这些数据。包里getVehicleState字符串来自遥测protobuf Vehicle类，不是此前讯飞getVehicleState("gear")协议。不能将同名方法当作新的服务入口。

不调用sendEvent、日志上传或其他诊断命令；它们不是只读车况订阅。

## 车辆设置

### 权限要核对实际组件，不能只看同名permission定义

共54个Provider，52个显式exported=true，其中47个实际入口权限在本包定义为signature；其余需逐个查定义，不能一概说“无权限”或“全部同一种签名权限”。

- RedwoodAutoHoldSlice实际入口要求com.mega.carsettings.export.permission.RedwoodAutoHoldSlice，signature。
- **RedwoodCentralLockSlice实际入口要求android.permission.BIND_QUICK_SETTINGS_TILE**。17:48车端权限定义为signature|recents。虽然本包还定义了同名RedwoodCentralLockSlice签名权限，但不能把这个未绑定到该组件的定义冒充实际入口权限。此前仅据同名权限归因的描述应以此核对为准；普通App仍不能把这个Provider当开放接口。
- BaseSliceProvider自动grantSlicePermission的对象固定为com.android.systemui，不是任意第三方。
- 语音Provider实际要求MEGA_VOICE_LIB_PERMISSION，和本包定义的mega.mega.permission.MEGA_VOICE_LIB_PERMISSION拼写不同。不能根据相似名字假定已授权，也不把未在当前清单找到定义理解成可调用。已检查的语音查询主要是电量、续航、胎压、里程和空气质量；同入口还混有车辆控制，不进行通用call试探。

### 数据与通知方向

AreaLiveDataStore/CarAreaPropertyLiveData仍落到MegaCarPropHelper读值和回调。锁变化在CommonService写入默认MMKV，再调用sendWidgetBroadcast；广播显式setPackage(getPackageName())，只面向本包。MMKV.initialize(context)默认路径是context.getFilesDir()/mmkv，不是共享Settings或公开车况数据库。

AutoHold Slice观察ID_AUTO_HOLD，但REEV分支把原值1和2都映射为同一个开关true。即使获得Slice也不能拿这个布尔值区分保持/释放，或把UI启用状态当成真实制动介入事件。

已检查的其他直接广播多为界面、蓝牙/Wi-Fi、语音、widget或定向通知，没有确认四类原始车况的第三方发布链。不是对全部动态/封装广播的不存在证明。

### 暂不据以扩展的线索

包内带VDBus SDK，但默认车辆/车况宿主未在17:48安装列表中出现；仅看到VR宿主。SDK前缀又可经系统属性重定向，不能凭SDK类存在就认定本车有可连接的车辆服务。

Manifest声明com.mega.platform.PlatformService，但四个DEX未找到该类描述符，未取得实现。这个孤立声明及其尚未确认定义的入口权限不构成可用车况出口证据，不盲绑或向其发送请求。

## 下一步：不再盲目提包

### 非日志：标准Android Car挡位对照

这不是新发现，而是此前保留的低优先级路线。17:48新报告再次确认：

- car_service的完整dump列出GEAR_SELECTION/0x11400400和CURRENT_GEAR/0x11400401，均为READ/ON_CHANGE配置。
- 本App的android.car.permission.CAR_POWERTRAIN仍为granted=true，权限定义normal；与Mega四域拒绝及HIDL的hwservice查找拒绝不是同一个权限路径。
- 配置/授权不能证明读值可达或数据源新鲜，更不能排除标准VHAL未接入车型真实数据。已有材料没有把这两个属性的原App UID读取、status、原始时间戳、回调和实车对照完整关联起来。

若用户要求继续实现，可做单独、有界、原UID的只读诊断：两项属性、先注册再取初值、原始状态/时间戳/枚举分别记录、停止注销、不接音效、不使用车辆控制或shell冒充App。只可能先验证挡位，不承诺车门、锁或AutoHold。若失败、不可用或没有新鲜变化则保留结果并停止，不把固定值或注册0当成功，也不要求为测试行驶。

### 实用路线：验证日志覆盖后再考虑事件桥

如果用户接受日志来源，可在自然、安全操作时给日志加人工时间标记，核对门/挡位/锁/AutoHold的实际覆盖、重复、延迟和缺失，再决定是否实现有界只读采集与事件解析。静态日志打印语句不等于实车持续日志；不能承诺四类齐全、后台常驻或重启后ADB自动恢复。

如果实现，应区分诊断与音效开关，使用首样本静默基线、断连/超时清空状态、过期不触发、自动重连不重播旧状态。ADB桥仍是日志，不冒充原生回调。

### 材料取舍

优先复用已取得的CarControlAdapter旧研究和原样本；没有新宿主/接口证据，不要求用户重复上传。com.mega.sceneblock的SceneModeReceiver与SERVICE权限在17:48定义中均为signature，不能因名字像场景引擎就优先追加盲测；RockyCarController仅凭包名也不构成新方案。普通App直连HIDL和普通shell直连HIDL均已有明确SELinux查找拒绝，不重复换topic/延时测试。厂商正式SDK/按应用只读授权若能取得，才是有价值的新授权材料；不靠全局工程开关或伪装身份解决。
