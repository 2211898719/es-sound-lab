# 原厂模拟声浪调查（2026-11-01）

## 结论

车辆设置中的声浪不是只有一个名称或占位开关。已还原两条不同链路：选择时播放APK内的短音频，以及通过Mega车辆属性向底层发送ASD设置。用户提供的18:49检测日志确实记录过一次ASD设置发送，但没有提供切换不同声浪时的连续对照，不能据此宣布原厂行车声浪、第三方调用或油门数据接入成功。

这次只做离线调查及文档记录，没有修改App行为、构建APK、连接车辆、写系统设置或调用车辆控制接口。

## 样本与交叉证据

- 车辆设置：`/Users/hongjunlong/Downloads/ES-系统APK-20261031-175727/packages/com.mega.carsettings.apk`。
- 包名`com.mega.carsettings`；versionCode为10000010；versionName为`1.0.10.20260304233723-release/rocky_hqx.4.5.5_20251024`；清单声明`android.uid.system`共享UID。
- SHA-256：`56b9474d9834083412b9cf35c2baf73e6add2651326351275ccad52fb34cd1e4`。
- 配套服务旧样本：`/Users/hongjunlong/Downloads/ES-系统APK-20261030-225845/RedwoodCarService.apk`；SHA-256：`38913715358e7556ef9a750d20d693349b3863eec5dc5f632965acb1e727ec32`。
- 实车记录：`/Users/hongjunlong/Downloads/ES-检测-20261101-184922.json`；权限对照：`/Users/hongjunlong/Downloads/ES-ADB诊断-20261101-023905.json`。两份记录均早于此次调查，不当作本次新测试。
- 用aapt2检查资源和清单，JADX逐类反编译，并以DEX反汇编核对`SequoiaSettings$51.read/write`。反编译中的`VolumeFragment.adjustSeekBarValue`恢复失败，不以该方法推断功能。
- 临时反编译及音频元数据位于`/private/tmp/es-simulated-sound-20261101/`；不将原厂完整源码或音频复制进仓库。

## 1. 本版页面实际使用的选项

`res/xml/pref_volume_settings`实际文件为`res/FUe.xml`。`VolumeFragment`加载这个页面并添加`com.mega.settings.volume.SimulateController`。

| UI值 | 当前页面选项 | 点击试听资源 | APK内文件 |
| --- | --- | --- | --- |
| 0 | 关 | 停止试听 | 无 |
| 1 | 油车声浪 | `raw/fuel_mode`，`0x7f100004` | `res/jtE.wav` |
| 2 | 电车引擎 | `raw/electric_mode`，`0x7f100003` | `res/gq3.wav` |

Preference key为`simulate_sound_waves_value`。页面标题默认“主动声浪”，国际产品分支替换为“模拟音浪”。以上是UI值及试听对应关系，不是对所有车型底层ASD枚举的运行时确认。

`RwConfigUtil.isShowSimulateSound()`还要求配置项`ro.ecu.config.SIMULATE_SOUND`等于1，且满足Rocky/国际产品分支条件。不能因为控制器的`isAvailable()`返回true就说所有车型都启用了这项功能。

APK另含“超跑/运动/科技”及9个子音色资源，`SimulateItemDialog`按1—9写入同一个声浪设置并试听。当前音量页面XML实际绑定的仍是上述0/1/2，不能把共包代码和资源存在当作ES当前可选9种声浪的证明。

## 2. 试听不是完整行车声浪引擎

`SimulateController.onPreferenceChange()`调用`playWav()`或停止播放，随后仍让Preference持久化选择；它不是唯一的控制逻辑。

`playWav()`使用`MediaPlayer`，静音后延迟50ms停止/重置旧播放器，再打开raw资源，以`setAudioStreamType(3)`即音乐流、播放器音量1.0播放。控制器脱离页面时释放播放器。

核对WAV头，两段主要试听均为4.0秒、48kHz、16bit、单声道；超跑示例`supercarf`为3秒。已检查的两个声浪试听类没有循环播放、油门/转速订阅、动态变调或车速驱动合成逻辑，也没有指定本App锁车音效使用的usage2007和车外BUS。音乐流本身不能证明物理扬声器位置。

因此不能直接复制这段试听代码就声称获得原厂实时声浪；也不能反过来断言整车只有4秒试听、没有实际声浪实现。

## 3. 真正的开关下发链路

已还原的调用关系：

```text
RwSegmentPreference.onCheckedChanged / persistInt
  -> CarPreferenceStore.putInt
  -> RedwoodSettingsProvider（key转大写查SequoiaSettings）
  -> SequoiaSettings.SIMULATE_SOUND_WAVES_VALUE.write
       -> super.write -> AreaLiveDataStore / CarAreaPropertyLiveData
          -> MegaCarPropHelper.setRawProp
       -> Settings.System.putInt("com.mega.settings.simulate.value", value)
  -> Mega CarPropertyService（属性权限检查）
  -> MessageProperty.setProperty
  -> MessageClient.setValue -> Message Center
  -> BodyInfo/ASDInfo/Set
```

同一APK的`mega.car.config.SequoiaCarAudio`提供以下定义：

| 项目 | 定义 |
| --- | --- |
| 属性名 | `ID_VOLUME_ASD` |
| 属性ID | `1761607714` / `0x69000022` |
| 类型 | Integer |
| 发布主题 | `BodyInfo/ASDInfo/Set` |
| 订阅主题 | `BodyInfo/ASDInfo` |
| SDK权限域 | `mega.car.permission.SEQUOIA` |

`SIMULATE_SOUND_WAVES_VALUE`构造时使用的就是这个ID。其覆写的`write()`先调用父类下发车辆属性，再写Settings缓存；覆写的`read()`却读取Settings缓存，不是读取ASD底层确认。缓存不存在时还会按产品分支默认返回0或2，因此页面显示某个选项不等于已取得实时底层状态。

Settings的ContentObserver主要用于刷新选择。仅往这个键写整数并不等价于执行完整的原厂设置流程；本次没有确认普通App能用的“写缓存即可使底层生效”入口。

ASD属性不是开度数据，也不是可上传自定义音频的接口。已取得的链路终点是Message Center主题；后续究竟由QNX、DSP、功放还是其他进程持续合成，现有证据尚不能确定。

## 4. 旧实车日志中已有一条发送记录

`ES-检测-20261101-184922.json`第2037行保存了一条被当时通用事件解析器忽略的原厂日志：源时间为2026-11-01 18:49:04.805，`MessageProperty.setProperty`记录发送`BodyInfo/ASDInfo/Set`，属性`0x69000022`、area为0、value为0。

这确认它不只是APK内的静态常量，实车服务至少走过一次发送路径。但这是一条**发送日志**：没有对应的底层确认、切换1/2的对照或用户动作标记，不能把它当成声浪已播放、车辆已执行或第三方拥有调用权限的证明，也不能确定该次发送的原因。

## 5. 对本App可用性的限制

- 原厂SDK把ASD划到SEQUOIA域，配套服务对读、写和注册分别执行属性权限检查。02:39诊断中该权限定义为`signature|privileged`，`package-cn.es.soundlab`记录`SEQUOIA: granted=false`；不能因为原厂设置可用，就推断普通App可直接调同一属性。
- SDK确有油门定义：`Driving.ID_ACC_PEDAL_POS = 67108954 / 0x0400005a`，Float，订阅主题`DrivingInfo/AccPedal`，权限域`CAR_DRIVING`。这是早期报告已列出的定义，不是本次新打通的通道。相同诊断中本App的`CAR_DRIVING: granted=false`。
- 上述18:49检测、01:54检测及02:39ADB诊断中未检出`AccPedal`或该属性ID的日志样本。这是有限样本未命中，不代表整车没有油门信号或日志永远不会打印。
- 本次没有发现可直接替代ICC的免授权油门流，也没有证明原厂ASD会把它使用的底层油门数据回传到Android App。继续保持本App模拟声浪“敬请期待”，不以仅车速驱动冒充油门联动。
- 低速行人提示使用另外的`LOW_SPEED_*`设置与试听资源，不能把ASD开关当作行人提示开关，也不更改原厂安全提示行为。

## 6. 下一步：只观察原厂设置切换

无需重新提取车辆设置APK。车辆安全停放时，在已授权系统日志的前提下，保持现有日志采集；由用户在原厂设置中依次选择“关 → 油车声浪 → 电车引擎 → 关”，每次间隔约5秒，记下时间并导出。无需踩油门或行驶，也不让本App代写车辆属性。

重点对照`SIMULATE_SOUND_WAVES_TYPE`、`SequoiaSettings`、`MessageProperty`、`ASDInfo`的选择、发送、回传和错误，区分选择时4秒试听与退出页面后是否仍有原厂效果。即使收齐0/1/2发送，也不能代替下游确认或实车效果验证；若没有订阅回传，就保留“仅发送已确认”的结论，再依据实际接收端线索决定是否需要下一份底层材料。
