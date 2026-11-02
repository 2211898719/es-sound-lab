# Mega SystemUI 与分屏知识笔记

更新日期：2026-10-30。适用于本项目现有的星纪元 ES / E0X 8295 分析材料，不代表所有年款、车机版本都相同。

## 目前掌握到哪一步

已收到并静态分析目标车机的 `RockySystemUI.apk`，实际包名为 `com.android.systemui`。本批普通分屏入口有默认三包白名单、分屏开关及导航状态条件；Provider 需 signature 权限。没有收到本应用成功进入分屏的实车记录。[本批完整性、代码证据及限制](system-apks-analysis-20261030.md)。

早期地图和畅听调用方中的参数形态仍可参考，但不代表本批固件允许任意侧载应用进入分屏。下文探测组合为历史实现，不建议继续重复已失败组合。详细原始来源见[车机分屏协议](split-screen-protocol.md)。

## 几个名称分别是什么

| 名称 | 类型 | 含义 |
| --- | --- | --- |
| `com.android.systemui` | 系统应用包名 | 本车 `RockySystemUI.apk` 的真实包名；`com.mega.systemui` 不是本次已安装包 |
| `com.mega.systemui.chery.Split` | ContentProvider authority | 退出分屏调用的目标，不是另一个 APK 的文件名 |
| `com.mega.systemui.provider` | Android 权限名 | 本批定义为 signature；声明不等于侧载应用已获授权 |
| `system_split_pkg` | `Settings.Global` 的键 | 记录分屏配对的系统设置，不是 APK、应用包名或启动命令 |
| `cn.es.soundlab` | 本应用包名 | ES 音效实验室 |
| `com.lion.appfwk.navi` | 地图包名 | 探测页默认分屏对手 |
| `com.lion.media` | 畅听包名 | 仅作分屏对照 |

Android 的 SystemUI 通常承担状态栏、导航栏、通知等系统界面；本车机增加了厂商定制功能。不能据此把桌面、空调控制和所有车辆服务都归到同一个进程，具体归属仍需查看对应 APK 与服务。

## `system_split_pkg` 怎么理解

读取位置：

```java
Settings.Global.getString(context.getContentResolver(), "system_split_pkg");
```

典型格式是两个包名用冒号连接，左边表示左侧应用。例如：

```text
cn.es.soundlab:com.lion.appfwk.navi
```

这表示设置中记录的是“音效实验室在左，地图在右”的配对。它是观测状态的依据，仍需核对窗口宽度，不能仅凭字符串断言实际画面已经分屏。

- `null`、空字符串：没有读到有效配对值，不能单独证明整台车机完全没有其他形式的多窗口。
- 读取抛异常：状态未知，应保留异常；不等于已经确认退出分屏。
- 配对没有本应用：不能认为本应用已进入该配对。
- 格式异常、重复包名或只有包名前缀相同：不算匹配。本项目按两个完整包名判断。

本项目只读此设置。手动写入一个配对字符串不能代替系统创建和排列窗口，也没有证据表明它是进入分屏的控制入口。

若车机确实可用 ADB，可辅助只读查询：

```sh
adb shell settings get global system_split_pkg
```

ADB 不是探测页的运行前提。10月30日用户已确认通过工程模式开启ADB并用于提取APK；其shell权限和重启后可用性仍需核对，见[新APK分析](vehicle-apk-research-20261030.md)。

## 已确认的请求形式

### 进入

应用发出显式 `startActivity` 请求；进入参数如下：

| 字段 | 内容 |
| --- | --- |
| Intent component | 本次启动的一侧 Activity |
| flag | 仅 `Intent.FLAG_ACTIVITY_NEW_TASK` |
| `mega.intent.extra.split.METHOD` | 字符串 `enter_split` |
| `mega.intent.extra.split.IS_HANDLE_LEFT` | boolean，把手左 / 右 |
| `mega.intent.extra.split.COMPONENT` | 原地图调用方传入的 `ComponentName`；本批处理分支不据此接受任意第三方配对 |

调用形态来自地图 `BaseSystemImpl.openNoa()`。原调用切换到文远智行；本项目只借用已确认的参数形态，目标使用地图或自身，不启动文远智行。

地图原方法会在没有分屏配对时提前返回，这是调用方策略。它既不能证明系统也有同样限制，也不能证明系统必然支持从全屏接入第三方应用。探测页允许从全屏手动测试，以补充这部分证据。

本项目提供“启动地图 / 启动自己”与“把手左 / 右”四种组合。把手参数不能直接当作本应用左右位置的保证；实际位置以配对顺序和画面为准。畅听只作额外对照。

### 退出

两条路径独立测试：

| 路径 | 已确认的调用 |
| --- | --- |
| Provider | `call(Uri.parse("content://com.mega.systemui.chery.Split"), "exit_spit", pkg, null)` |
| Intent | 显式启动地图入口，带 `METHOD="exit_split"` 与 `FLAG_ACTIVITY_NEW_TASK` |

`exit_spit` 的拼写来自调用方代码，不要改成 `exit_split`。Provider 的 `pkg` 是要退出的一侧包名，探测页可选择自己、地图或当前配对另一侧。

Provider 访问受 `com.mega.systemui.provider` 的 signature 权限保护，普通声明不能获授。`SecurityException` 表明这次 Provider 调用被拒绝，不能据此认定所有 Intent 路径的执行结果；后者也有上述包名与状态条件。

探测页也提供启动自身的 `exit_split`，以及退出时带 / 不带 `split_invalid_intent` category 的对照。它们是待车上验证的组合，不是第三方兼容性保证。该 category 不加入进入请求。

## 怎么判断真的分屏

必须同时观察到：

1. `system_split_pkg` 是本应用与所选对手的精确双包配对。
2. 本 Activity 的实际窗口宽度接近 870px 或 1682px，容差为 ±100px。

参考尺寸来自目标地图代码：

| 模式 | 像素宽度参考 | 畅听的 dp 判断 |
| --- | --- | --- |
| 1/3 | 870px | 400–600dp |
| 2/3 | 1682px | 1000–1200dp |
| 全屏 | 2560px | 上述区间外按畅听规则归为全屏 |

dp = px / density。密度约 1.5 时，870px 约 580dp，1682px 约 1121dp。探测页同时展示两套分类，但成功判定使用配对及像素宽度；未知像素宽度不会被强行当成分屏成功。

`startActivity` 无异常只说明请求已发出；系统可能忽略 extra，普通打开地图。`isInMultiWindowMode()` 仅辅助观察，不能替代上述条件。本地窄栏截图和 Robolectric 测试也不能证明车机 SystemUI 已接受本包。

## 历史实车验证步骤与结果解释

以下保留探测页的原验证流程，不是针对当前已知限制继续重试的要求。本批默认列表不含本应用，Provider 权限也已确认，不再重复已失败组合。

1. 从首页进入“分屏探测”，刷新并记录初始配对、窗口宽度和密度。
2. 逐一点击四种地图进入组合。若只打开地图，返回探测页查看状态；记录操作名、配对、宽度、异常及实际画面。
3. 若配对和宽度同时符合条件，记录是哪一种组合成功，以及本应用实际在左还是右。
4. 测试退出并核对配对与画面。Provider 被拒绝时继续测试 Intent 路径；异常不等于已经退出。
5. 从车辆检测页导出 JSON，在 `sessionHistory` 查看 `分屏探测` 记录；系统日志标记为 `SplitProbe`。无需为了分屏开启车辆监听。

原先仅凭四种组合失败，不能区分接入限制。现已从本批 APK 确认默认列表、导航状态条件和 Provider 签名权限；不能再将它们列为完全未知，也不能仅靠改变 extra 消除。运行时资源是否被覆盖及实际状态仍需单独证据。

诊断沿用最多160条的会话记录，长异常分段会占多条，建议测试后及时导出。未保留的旧记录不能解释为操作没有发生。

## 仍然未知的部分

- 运行时资源覆盖是否改变 APK 的默认三包列表，以及实际导航/分屏开关状态。
- 受支持应用和身份在真实窗口中的最终行为；当前没有本应用成功记录。
- 是否存在公开受支持的第三方接入协议；不将系统内部接口视为通用入口。
- 系统策略在不同年款和车机软件版本间是否一致。

本批 `com.android.systemui` 的 Manifest、分屏代码和 Provider 权限已分析，详见[本批结论](system-apks-analysis-20261030.md)。后续以受支持的接入方式及运行时事实为依据，不提高 targetSdk 来试错，不修改签名 / shared UID、系统列表或系统设置，不增加未知 Mega 调用。

## 与空调控制的边界

分屏属于系统窗口管理；空调调温、风量等属于车辆控制。两者不是同一项能力。能发送分屏请求、读车辆状态或取得 `READ_LOGS`，都不代表获准写入空调属性。

第三方能否控制空调，需要另外分析原厂桌面 / 空调 APK 调用的车辆服务与权限检查。目前本应用没有实现空调写入，也没有证据证明普通侧载安装就能控制。

## 项目入口

- [完整分屏协议](split-screen-protocol.md)：调用参数、来源与实现约束。
- [SplitController](../app/src/main/java/cn/es/soundlab/SplitController.java)：构造请求、读取设置、测量宽度。
- [SplitProbeActivity](../app/src/main/java/cn/es/soundlab/SplitProbeActivity.java)：手动按钮、状态与异常日志。
- [SplitProbeTest](../tests/SplitProbeTest.java)：本地协议与界面回归；不连接真实 Mega SystemUI。
