# 系统升级APK：Carota日志写入与时效分析

分析日期：2026-11-01。仅静态分析用户提供的APK，没有连接车机、调用OTA服务、修改原厂包、签名、鉴权或工程开关。

## 样本身份与证据范围

- 文件：`/Users/hongjunlong/Downloads/系统升级_1.7.0.2609r.apk`。
- 包名：`com.carota.chery.v4`；versionName：`1.7.0.2609r`；versionCode：`29534928`。
- 文件大小：32,127,342字节；SHA-256：`0397699703f9218ba3e8a183cd329d4572df53bb2db2058d6af133665133b2b8`。
- Manifest声明`android.uid.system`共享UID。这是原厂包的声明，不表示本App可以获取同一身份。
- 使用本地aapt2核对Manifest和二进制XML配置，JADX 1.5.3读取两个DEX。整体反编译报告40处错误，不能把全部输出当成可重新编译的源码；关键Logger写入路径另外以fallback指令输出核对。LogUtil初始化的结构化反编译失败，以下只采用simple输出中明确的调用、常量和字段赋值。
- 不复制整个反编译目录或原始日志到仓库，不记录用户VIN、账号、手机号、令牌或内部协议密钥。

## 1. 找到了与原日志一致的写入器

`classes2.dex`中`d4.j`的原文件名是`Logger.java`。`e(String, Object...)`先调用`g("INFO", 4, ...)`，后者构造与样本一致的行：

```text
[INFO] yyyy-MM-dd HH:mm:ss.SSS in ConditionHandlerImpl.kt(行号) >Vehicle condition data: ...
```

`g(...)`在同一方法中依次执行：

```java
PrintStream printStream = f1215a;
if (printStream != null) {
    printStream.println(strK);
    f1215a.flush();
}
```

`f(int, File, String)`直接用`new PrintStream(new File(...))`创建流，文件名为`进程名[序号Tyyyy-MMdd-HHmm-ss].log`。fallback指令输出也包含连续的`println`和`flush`调用，并非仅凭类名推测。

**因此，这条路径不是常见的“应用先攒几秒/几KB，再统一调用flush”的设计。** 没有在这条日志写入链路发现独立异步日志队列或定时flush逻辑；不能据此排除运行时阻塞、存储层或其他进程的影响，也不能把flush等同于传感器数据实时、或底层介质已完成持久化。静态代码不能量出实车延迟。

`classes.dex`中`p2.j`（`LogUtil.java`）的初始化选择共享存储或App外部缓存，再追加`carota`目录；用进程名作为文件名前缀。文件头中的`@目录/进程名`是目录与前缀的拼接，**不能单凭这一行认定存在包名子目录**。例如`@/storage/emulated/0/carota/com.carota.chery.v4`不等于证明有同名目录；当前App同时兼容根目录与精确包名子目录，无需因此修改扫描范围。

初始化有进程内只执行一次的标志，会选新序号建立日志文件并清理部分旧文件。该路径没有按文件大小持续轮转的检查；用户观察到的多文件仍需结合进程重启等运行情况判断，不把它直接解释成固定容量分片。

## 2. 真正车况行由查询完成后产生，不是挡位变化回调

`classes.dex`中`c0.a`原文件为`ConditionHandlerImpl.kt`：

1. `getPowerState()`启动协程查询。
2. 协程经`r2.b`（`VehicleServiceManager.java`）取`vsm.subde`指定的车况来源，再同步调用其`a()`查询。
3. 查询结果包装为`n.f`（`VehicleCondition.java`），赋给字段`f472a`。
4. **此时才调用`d4.j.e("Vehicle condition data: " + f472a, ...)`记录整组状态。**
5. `getPowerState()`的等待循环为最多100次`Thread.sleep(200L)`，即约20秒的循环等待预算；这不是固定20秒采样周期，也不是整条HTTP请求已证实的超时上限。
6. 挡位getter `c()`直接返回`f472a.c()`，没有在这个getter内重新取挡位。`VehicleService`依次查询1～24号字段时，先以1号读取电源、触发上述刷新，再以4号读取缓存的挡位。

这说明所观察到的行是OTA查询链中的状态快照。**并不是每次挂挡都会立刻产生一条该日志。** 查询调用频率、上游应答时长，以及上游自身是否缓存状态，都会影响我们最终能读到的变化。本轮未证明实际是哪一段占用最多时间，也未证明当前运行服务一定与该提取版本完全一致。

## 3. 找到上游来源，但不是已确认开放的第三方接口

Manifest的`carota.configure`指向资源`0x7f120001`（`xml/cfg_rpc`，APK内`res/Ci.xml`）。二进制XML的映射为：

```text
vsm.condition -> vsi_condition    -> ota_android_vsi
vsm.subde     -> vsi_de_condition -> ota_icc_vsi
```

`ConditionHandlerImpl`使用第二条。`u1.e`（`ActionVSI.java`）通过Carota内部请求助手发起`/cdt`查询，响应按`VehicleConditionRsp`解析；逻辑服务名经内部路由/代理处理。不能将逻辑主机名当作已知的、任意App可直接访问的局域网服务地址。本轮没有发送此请求或使用内部鉴权材料。

另两个容易误解的入口：

- `com.carota.fota_build.vsi.VehicleServiceImpl`在Manifest中`exported=false`，不能因为知道包名/接口名就从本App直接绑定。
- `com.carota.vsi.VehicleEventServerService`虽导出但受`ota.permission.VEHICLE`保护；其Binder业务交易是`queryScheduleFire`和`queryUpgradeTime`，不是通用挡位回调注册接口。

因此目前只能把ICC状态来源记为进一步调查线索，不能宣布已经找到免授权实时接口，也不修改OTA流程来强迫增加日志。

## 4. 对本App延迟观测和后续处理的影响

至少需要分开四段：

```text
实车变化 -> 上游状态可见 -> OTA发起/完成查询并生成日志
         -> 文件新增内容被本App读到 -> 主线程处理/界面展示
```

- 日志时间是在Logger中`new Date()`生成的，位于查询返回之后，**不是车辆信号产生时间**。
- 本App可测的“收到时年龄”是`本App读取时间 - 日志时间`。若它很大，可调查日志生成之后的阻塞/存储可见性/读取调度；若它很小但挡位仍慢，不能排除之前的查询空档或旧快照。
- 主线程排队时间是另一项指标，不能与日志年龄或实车操作延迟混为一谈。
- 单次新增字节、相关车况行数和两次非空读取的间隔只是**读取批次**统计，不是写入器flush批次的直接证据。
- 250ms文件轮询和约1秒界面刷新是当前设置的调度间隔，不是端到端时延上限。更密集轮询或换成文件变化通知，均不能让尚未被OTA采样的状态提前出现。
- 保留现有5秒时效检查、EOF基线及不补播历史保护；把过期行也接受为实时状态只能掩盖问题，不能减少真实延迟。

前一轮已加入但尚未完成回归/发布的`CarotaTiming`和读取批次观测改动继续保留；本轮没有打包新版本，不将这些改动说成已交付或已经完成实车验证。

下一步先用观测版在安全驻车、持续制动、周围无人的条件下对照测试并导出诊断。若日志年龄小但实车响应晚，优先调查OTA查询触发条件和上游响应；若日志年龄本身持续偏大，再调查文件读取/写入后的路径。无须行驶，也不要求启用root、替换原厂升级包或关闭鉴权。

## 后续实现

此静态分析完成后，用户要求加入ICC调用检测；0.3.18已实现手动限时的只读车况查询，并合并Carota观测回归。见[ICC检测说明](icc-vehicle-probe.md)及项目交接的发布记录。此实现不是开放接口或实车实时性已验证的证明，原厂APK未修改。
