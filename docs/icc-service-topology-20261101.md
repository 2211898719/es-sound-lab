# ICC车况接口：本机路由器、上游服务与启动来源

分析日期：2026-11-01。仅分析用户提供的APK、Carota原始日志和ADB诊断；没有连接车辆、发送网络请求、启动/停止原厂服务、改变权限或生成新APK。

## 结论先行

- 当前App访问的第一跳明确是车机Android本机：`192.168.55.31:51020`。这不是仅凭私网地址推断；新ADB诊断明确显示该地址配置在本机`eth0.55`，同端口正在监听。
- 静态代码与Carota原始日志共同指向：`com.carota.chery.v4`（系统升级App）启动本地Carota RPC/Hub路由器。端口与配置均为51020。
- `ota_icc_vsi`不是已确认的Android包名，也不是我们直连的云端域名，而是由Hub解析的逻辑模块名。Hub按注册表把请求转发到提供者地址。
- 当前材料没有给出`ota_icc_vsi`的实际注册地址、端口及对应进程。因此仍不能确定最终提供者是在同一Android系统、车内另一运行环境，还是其他节点；更不能凭ICC名称指定某个APK或QNX进程。
- 已证明本机入口，尚未完成整条转发链的离线独立性验证。不宜宣称“完全不依赖任何外网”，也没有证据把现有车况查询描述成云端查询。Carota还包含独立的OTA云端业务，不能与车况路径混为一谈。

## 1. 实车网络证据

来源：`/Users/hongjunlong/Downloads/ES-ADB诊断-20261101-023905.json`。App版本0.3.21；以下命令输出均标记传输完成、未截断。旧shell记录不提供业务退出码，不把传输完成等同于每个命令全部执行成功。

`commands[name=network-addresses]`：

```text
eth0.55@eth0
inet 192.168.55.31/24 ... scope global eth0.55
```

`commands[name=network-routes]`：

```text
192.168.55.0/24 dev eth0.55 proto kernel scope link src 192.168.55.31
```

`commands[name=network-tcp-listeners]`：

```text
LISTEN ... [::ffff:192.168.55.31]:51020 ...
```

这份监听输出没有PID/进程名，故运行时socket归属尚待定点核验。不能拿同一UID或服务绑定关系代替socket归属证据。

App侧`IccVehicleTransport.java`固定使用该HTTP代理，逻辑目标为`ota_icc_vsi`的`/cdt`；不是让Android DNS直接解析该逻辑名称后访问互联网。

## 2. 谁启动本地入口

原厂样本：`/Users/hongjunlong/Downloads/系统升级_1.7.0.2609r.apk`，包名`com.carota.chery.v4`，版本1.7.0.2609r，SHA-256：`0397699703f9218ba3e8a183cd329d4572df53bb2db2058d6af133665133b2b8`。此处启动链是该样本的静态实现；新诊断尚未核对车端Carota APK哈希，不把它直接等同于当前车端的逐字节实现。

- Manifest声明`com.carota.MainService`、`com.carota.DaemonService`和继承MainService的`com.carota.fota_build.ExtendService`。它们是原厂应用内部服务，不是当前App新增或启动的服务。
- `f.a`（CarotaClient）的初始化方法`g(...)`向本包发送`ota.intent.action.DAEMON`服务Intent；根据条件调用startForegroundService或startService。
- DaemonService执行CarotaClient初始化。MainService的Handler消息1把CoreServer初始化任务交给单一执行器。该任务结构化反编译有恢复缺口，已用JADX 1.5.3单类、禁匿名内联、fallback模式重新核验`f.c.run()`：读取本地地址/端口，构造`j2.h`，保存至CoreServer的f1651d，再调用HTTP服务器启动方法。原始日志中的端口来自实际ServerSocket.getLocalPort()，不是仅打印配置值。临时指令摘录位于`/tmp/es-icc-router-core-fallback.java`；不能把先前JADX占位异常当成原厂实际行为。
- `f.f`（CoreServer）根据配置装配VehicleInformationService、HubService等处理器；`j2.h`（RouterHttp）提供HTTP监听与分发。
- BootBroadcastReceiver收到BOOT_COMPLETED后发布内部`boot_completed`事件。这不是“该接收器直接启动了ICC后端”的证据，后端启动者尚未查明。

原始日志：`/Users/hongjunlong/Downloads/com.carota.chery.v4[2618T2026-1031-1559-55].log`：

| 行号 | 时间（2026-10-31，日志本地时钟） | 关键记录 |
| --- | --- | --- |
| 14–15 | 15:59:55.578 | 配置地址192.168.55.31、端口51020 |
| 226、229 | 15:59:55.913 | CoreServer初始化VSI、Hub |
| 230–231 | 15:59:55.913 | 注册本地ota_android_vsi的信息及车况路径 |
| 300、302 | 15:59:55.927–.937 | RPC开始，端口51020 |
| 303–304 | 15:59:55.938 | 列出本地Hub模块，随后READY |

第303行本地模块包含`ota_android_vsi`、`ota_icc`等，但没有`ota_icc_vsi`。`ota_icc`与`ota_icc_vsi`不是同一个名称，不能因前者本地存在就断言后者也在同一Android进程。该行只代表当时本地模块列表，并不等于完整动态路由表。

## 3. 逻辑名如何转发

静态来源目录：`/tmp/es-carota-apk-20261101/sources/`。

```text
ES App：逻辑目标ota_icc_vsi，路径/cdt
  → 显式HTTP代理192.168.55.31:51020（Android本机）
  → Carota RouterHttp / HubService
  → RouteMap查找模块ota_icc_vsi
  → 提供者注册的IP:端口（尚未从实车取得）
  → 车况响应
```

- `j2.a`（HttpDispatcher）结合逻辑Host和路径查找处理器。
- `u0.a`（HubService）注册通配转发处理器，同时提供信息查询与模块注册处理器。
- `u0.c`（ProxyHandler）从路径提取模块名，读取`t0.a`（RouteMap），再转发至已登记地址及原路径；不是在这里读取Mega车门属性。
- `u0.d`（RegisterHandler）接收模块列表和端口，用连接来源地址建立注册关系。这是注册写入入口，本轮没有调用，也不应拿它试探或注入模块。
- `u1.e`（ActionVSI）证明Carota原有客户端查询逻辑模块的`/cdt`并解析VehicleConditionRsp。

配置同时列出OTA公网服务地址，供云端升级业务使用。存在云端配置本身，不能证明本次车况请求经过云端；同样，本机代理的存在也不能独立证明全部后续流量都不依赖外部网络。

## 4. 已找到下一项只读核验点

`u0.b`（InfoHandler）的GET处理函数只遍历RouteMap并构造`ServiceHub.InfoRsp`，返回每条路由的`module`和`addr`；`u0.a`将它注册在配置Hub名称`ota_proxy`的`/info`路径。没有在这个读取方法内发现注册、车辆控制或升级动作。

这是针对已知Hub的信息查询，不是ICC VSI自己的`/info`，二者不能混用。0.3.22已按用户请求加入[独立单次只读定位](icc-hub-probe.md)，尚待用户实车执行。当前正式来源仍只允许原有`/cdt`，没有扩大持续监听白名单。

建议顺序：

1. 手动、单次读取已确认的Hub信息路径，重点只保留`ota_icc_vsi`与`ota_android_vsi`的模块/地址；限制超时和响应大小，禁止重定向及自动重复，不保存原始响应或信封材料。
2. 由返回地址区分本机其他端口与车内其他节点；空地址、缺失或拒绝原样报告，不将缺失解释成服务不存在。
3. 只读采集Carota当前服务/PID及已知51020端口的进程归属；系统不允许查看时标记未确认，不改权限、不提权。
4. 若上游是本机进程，再针对实际程序分析初始化和只读车况实现；若是其他运行环境，需要对应组件/合法诊断材料，不能只靠Android APK猜测。

不扫描额外端口，不调用注册、事件、系统操作、复位、升级或车辆写入路径，不停止系统升级服务来验证依赖，也不通过切断车载以太网或拔保险验证。车辆断网独立性可留待后续安全、明确区分互联网与车内链路的对照，不以本轮静态分析代替实测。
