# 车机分屏协议与第三方接入说明

给实现用。协议来自实车 APK 反编译，不是公开文档。只实现本文写明的调用。不要猜测其它 Mega / SystemUI 接口，不要改音效、车辆读取、签名和版本号。

目标工程：`es-sound-lab`，包名 `cn.es.soundlab`，Java，无 Compose。minSdk 26，targetSdk 28，compileSdk 35。车机是 Android 13，但不要为了这个接口提高 targetSdk。

## 结论

2026-10-30补充：已拿到目标固件的 `RockySystemUI.apk`，真实包名为 `com.android.systemui`。本批普通启动拦截仅接受默认分屏列表中的包，且要求分屏开关开启和导航状态成立；列表为地图、畅听和文远智行，不含本应用。Provider 受 signature 权限保护。详见[新系统APK分析](system-apks-analysis-20261030.md)。

下文记录早期从地图/畅听调用方得到的参数与历史探测实现，不能再理解为“第三方任意发一次 Intent 即可分屏”的接入保证。不要继续重复已失败组合，不在启动、开机或车辆事件里自动分屏，不改系统列表或签名。

## 已确认的事实

来源：

- 地图 `com.lion.appfwk.navi`，版本 `010_260115_e87c0`。`android:sharedUserId="android.uid.system"`。`MainActivity` 有 `android:resizeableActivity="true"`。`isSupportSplitScreen()` 固定返回 true。
- 畅听 `com.lion.media`，版本 `230821_V1.1.8`，渠道 meta `CHANNEL=E0X 8295`。不是 system uid。

全屏宽 2560px。分屏只有两档，地图按像素判断，容差 100px：

| 模式 | 正常宽度 | 地图内部的 fault 宽度 |
| --- | ---: | ---: |
| 1/3 | 870 | 920 |
| 2/3 | 1682 | 1730 |
| 全屏 | 2560 | 2610 |

870 + 1682 = 2552，中间约 8px 是分割条。畅听不看像素，看 dp：`widthPixels / density` 在 400–600 算 1/3，1000–1200 算 2/3，其它算全屏。密度约 1.5 时，870px 约 580dp，1682px 约 1121dp，两套判断一致。

当前配对写在：

```text
Settings.Global  system_split_pkg
```

值是冒号分隔的两个包名，例如 `com.lion.appfwk.navi:com.lion.media`。左边是左侧应用。空值表示未读到有效配对；读取失败只能说明状态未知，不能认定已经退出。读这个值不需要写安全设置。不要写这个值。名称含义和证据边界另见[分屏知识笔记](mega-systemui-split-notes.md)。

地图代码里没有 `com.lion.media`。畅听没有 `enter_split`。两个包都不会拉起对方进入分屏。自动并排的策略在本批 `com.android.systemui`，不在这两个 APK 里。

## 进入分屏

唯一确认的进入调用在地图 `BaseSystemImpl.openNoa()`。它启动文远智行，并把地图放在另一侧。字符串和 flag 是确认的，对手包名不要照抄。

```java
Intent intent = new Intent();
intent.setComponent(new ComponentName(
        "ai.weride.compass.voyah",
        "ai.weride.core.ui.MainLionActivity"));
intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 反编译里写成 VehicleAreaDoor.DOOR_HOOD，值就是 0x10000000
intent.putExtra("mega.intent.extra.split.METHOD", "enter_split");
intent.putExtra("mega.intent.extra.split.IS_HANDLE_LEFT", handleOnLeft);
intent.putExtra("mega.intent.extra.split.COMPONENT",
        new ComponentName("com.lion.appfwk.navi",
                "com.lion.appfwk.navi.activity.StartupActivity"));
context.startActivity(intent);
```

含义：

- `setComponent` 是这一次启动的那一侧。
- `mega.intent.extra.split.COMPONENT` 在原地图调用方中表示另一侧，类型是 `ComponentName`；本批服务端该分支仅记录它，不据此接受任意第三方配对。
- `mega.intent.extra.split.METHOD` 必须是 `enter_split`。
- `mega.intent.extra.split.IS_HANDLE_LEFT` 是 boolean，表示分割条把手在左侧。地图在已分屏且 `system_split_pkg` 以 `com.lion.appfwk.navi` 开头时传 false，否则传 true。这是它切换对手时的把手策略，不是进入分屏的前置条件。
- flag 只有 `FLAG_ACTIVITY_NEW_TASK`。不要加 `FLAG_ACTIVITY_LAUNCH_ADJACENT`，不要加 `split_invalid_intent`。那个 category 只出现在畅听的退出路径。
- 不要申请 `android.uid.system`。畅听不是 system uid，仍然能发分屏 Intent。

`openNoa()` 在 `system_split_pkg` 为空时直接返回，是地图自身切换到文远智行的策略。历史探测页允许从全屏手动请求；现在已从本批 SystemUI 确认默认列表、开关和导航状态条件，不能再将从全屏发送请求视为通用第三方接入方案。

第三方默认和地图分屏。提供两种启动方向，因为 SystemUI 把哪一个当主任务还没在车上证实：

1. 启动地图，extra 里放自己。
2. 启动自己，extra 里放地图。

```java
private static final String NAVI_PKG = "com.lion.appfwk.navi";
private static final String NAVI_ACTIVITY = "com.lion.appfwk.navi.activity.StartupActivity";

void enterSplit(boolean launchNavi, boolean handleOnLeft) {
    String self = context.getPackageName();
    String selfActivity = SplitProbeActivity.class.getName();
    ComponentName launched = launchNavi
            ? new ComponentName(NAVI_PKG, NAVI_ACTIVITY)
            : new ComponentName(self, selfActivity);
    ComponentName other = launchNavi
            ? new ComponentName(self, selfActivity)
            : new ComponentName(NAVI_PKG, NAVI_ACTIVITY);
    Intent intent = new Intent();
    intent.setComponent(launched);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.putExtra("mega.intent.extra.split.METHOD", "enter_split");
    intent.putExtra("mega.intent.extra.split.IS_HANDLE_LEFT", handleOnLeft);
    intent.putExtra("mega.intent.extra.split.COMPONENT", other);
    context.startActivity(intent);
}
```

可选第二个对手是畅听，仅作对照按钮，不是默认：

```text
com.lion.media / com.lion.business.home.ui.HomeActivity
```

捕获 `ActivityNotFoundException` 和 `SecurityException`，把异常类名和 message 显示出来。不要吞掉。

## 退出分屏

确认有两条，都要做。权限失败时另一条仍可能有效。

### Provider

畅听 `WindowUtils`：

```java
context.getContentResolver().call(
        "com.mega.systemui.chery.Split",
        "exit_spit",   // 拼写就是 spit，不是 split
        packageName,   // 要退出的那一侧包名
        null);
```

`call(String authority, ...)` 是 API 29。本工程 minSdk 26，使用 API 11 就有的 Uri 重载，车上 Android 13 两种都能跑：

```java
contentResolver.call(
        Uri.parse("content://com.mega.systemui.chery.Split"),
        "exit_spit",
        pkg,
        null);
```

第三个参数是要退出的包名。畅听分别传自己的包名、地图包名，或 `system_split_pkg` 里除自己以外的那个包名。探测页三个都做按钮：退出自己、退出地图、退出当前配对里的另一侧。

这个调用需要 `com.mega.systemui.provider`。本批定义已确认是 signature；声明权限不代表本项目调试签名可获授。必须捕获访问拒绝并显示，不能混为进入请求的执行结果。

```xml
<uses-permission android:name="com.mega.systemui.provider" />
```

不要为此改 `sharedUserId`。不要请求 `WRITE_SECURE_SETTINGS`。

### Intent

地图 `notifyExitSplitScreen()` 不查任务栈，直接启动地图入口并带退出 extra：

```java
Intent intent = new Intent();
intent.setComponent(new ComponentName(NAVI_PKG, NAVI_ACTIVITY));
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
intent.putExtra("mega.intent.extra.split.METHOD", "exit_split");
context.startActivity(intent);
```

畅听还有一条更绕的退出：用 `getRunningTasks()` 找到对手当前 Activity，再启动它，并加上 category `split_invalid_intent`。第三方没有 `GET_TASKS` / `REAL_GET_TASKS`，不要实现这条。探测页只保留上面这条，以及同一条 Intent 但 component 改成自己的 `SplitProbeActivity`。category 不要加到进入请求上。退出按钮可以各做一个带和不带 `split_invalid_intent` 的，车上对比。

## 界面要求

新增 `SplitProbeActivity`，不要把按钮塞进现有音效首页。首页只加一个入口按钮「分屏探测」，打开该 Activity。现有音频、检测、校准逻辑不要改。

`SplitProbeActivity`：

- `exported=true`。SystemUI 会按组件名启动它，不依赖 launcher intent-filter。不要给它 MAIN/LAUNCHER。
- `resizeableActivity=true`。
- 不要 `screenOrientation`。
- `launchMode=singleTask`。地图主界面也是 singleTask。这是为了重复触发时复用任务，不是协议字段。
- `configChanges` 至少包含 `screenSize|smallestScreenSize|screenLayout|orientation|density`。宽度变化时不要重建到看不清状态。
- 布局用代码创建，沿用 `MainActivity.button()` 的做法即可，不必新建 XML。横屏、可滚动。1/3 宽约 580dp，按钮和日志必须在这个宽度里能点、能看。不要假设 2560px 全屏。

屏幕上始终显示，并在每次操作后刷新：

- `system_split_pkg` 原始字符串
- `isInMultiWindowMode()`
- `widthPixels`、`density`、算出的 dp
- 按上面两套规则分别得出的模式：像素档和 dp 档
- 最近一次操作名、是否抛异常、异常全文

按钮：

- 进入：启动地图，自己在 extra，把手左 / 把手右，两个按钮
- 进入：启动自己，地图在 extra，把手左 / 把手右，两个按钮
- 进入：启动畅听，自己在 extra，一个按钮，仅对照
- 退出：Provider 退出自己、退出地图、退出配对另一侧
- 退出：Intent `exit_split` 启动地图；同样一条启动自己
- 刷新状态

操作结果同时 `Log.i("SplitProbe", ...)`，并写入现有 `SessionJournal`，方便和音效检测日志一起导出。不要新建日志框架。

进入成功的判定只有一个：操作后 `system_split_pkg` 同时包含 `cn.es.soundlab` 和对手包名，且本 Activity 宽度落到 870 或 1682 附近。`startActivity` 没抛异常不算成功。SystemUI 可能把 extra 忽略，只是普通打开了地图。

## 明确不要做

- 不要在 `Application`、开机广播、前台服务或车辆信号里自动调用进入。
- 不要写 `Settings.Global`。
- 不要调用文远智行 `ai.weride.compass.voyah`。那是地图切 NOA 的对手，不是本应用的目标。
- 不要实现 Android 标准分屏：`FLAG_ACTIVITY_LAUNCH_ADJACENT`、`ActivityOptions.setLaunchWindowingMode`、自由窗口。这台车机不走那条路。
- 不要把 `checkNavigationSplit` 当成分屏。畅听里这个方法只是 2 秒内的语音切页。
- 不要提高 targetSdk，不要加 `android:sharedUserId`，不要改签名、versionCode、音效资源。
- 不要为了编译去依赖 `android.car.VehicleAreaDoor`。flag 直接用 `Intent.FLAG_ACTIVITY_NEW_TASK`。

## 历史实现验证流程

以下是原探测流程的留档，不要求在已知默认列表和权限条件不满足时继续重试。本批最新结论以本文顶部和系统APK分析为准。

本机可做的：编译通过；探测页在普通手机或模拟器上打开不崩；点进入时异常被显示而不是闪退。普通设备没有这个 SystemUI，进入失败是预期。

车上才算验证：

1. 安装后从音效实验室进入探测页。
2. 先点刷新，记录 `system_split_pkg`。
3. 分别点四种「和地图进入」。看哪个组合让设置值变成包含 `cn.es.soundlab` 和 `com.lion.appfwk.navi`，并且本页宽度变为约 870 或 1682。
4. 立刻点退出。确认设置值清空或不再包含本包，地图回到全屏。若 Provider 报 `SecurityException`，再试点 Intent 退出。
5. 若四种进入都只是普通打开地图、设置值不变，本次接入未成功；尚不能区分侧载限制、系统前置条件或版本差异。停止改 extra，不要再猜新接口，优先分析目标SystemUI及日志。

## 文件

| 文件 | 做什么 |
| --- | --- |
| `app/src/main/java/cn/es/soundlab/SplitController.java` | 组 Intent、调 Provider、读 `system_split_pkg`、算宽度档。不含界面。 |
| `app/src/main/java/cn/es/soundlab/SplitProbeActivity.java` | 按钮和状态 |
| `AndroidManifest.xml` | 权限、新 Activity。首页 Activity 的 configChanges 可补 `smallestScreenSize\|screenLayout`，但不要改它的方向和 launchMode |
| `MainActivity.java` | 只加一个打开探测页的按钮 |

控制器公开方法建议就这些：`enterWithNavi(boolean launchNavi, boolean handleOnLeft)`、`enterWithMedia()`、`exitByProvider(String pkg)`、`exitByIntent(ComponentName target, boolean invalidCategory)`、`readSplitPackages()`、`measure(Activity activity)`。异常不要在控制器里吞掉，返回或抛给界面显示。
