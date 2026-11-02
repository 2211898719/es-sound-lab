# 0.3.33：连续PCM声浪与音源许可

## 问题与范围

用户反馈内置声浪在循环边界及单独试听结束时均出现“波”的杂音。旧实现是2秒合成WAV、MediaPlayer循环及100ms重复设置变速参数；单次播放没有收尾包络。默认旧素材未发现明显削顶/首尾异常阶跃，不能仅据此认定杂音一定来自文件拼接，也不能仅凭软件测试证明车机功放停止时不会爆音。

本次只重做模拟声浪播放，不修改解闭锁usage2007、ICC读取、日志授权、P挡保护或原厂ASD。不是任何厂商认证或通用强制“行业标准”，而是借鉴交互车辆音频中连续渲染、转速/负载控制、循环混合和参数平滑的方法，仍需要目标车机验收。

## 已采用的公开音源

- 作者：domasx2；作品：Racing car engine sound loops；选用`loop_0.wav`。
- 原始页面：https://opengameart.org/content/racing-car-engine-sound-loops
- 网页镜像：https://lpc.opengameart.org/content/racing-car-engine-sound-loops
- 下载：https://opengameart.org/sites/default/files/loop_0.wav
- 页面许可：CC0 1.0 Universal，https://creativecommons.org/publicdomain/zero/1.0/legalcode
- 原始SHA-256：`69d74b106509037ce547429e4c3f9ae906b0fffe88d0bb8b3b5a8b0cfc239048`。
- 作者明示包内其他版本只改变音高。因此本次只取一个基础循环，不把这些文件宣传成不同RPM/油门负载的实录。收油音色是代码低通派生，不是另一组录音。
- `tools/prepare_engine_audio.py`固定校验原文件、移除DC、峰值归一到0.55并转换为48kHz单声道PCM16；生成的`engine.wav`哈希为`5df28fd4a3b3bd9d25d4c4f1a40f8ec2c63c3adf2bdd1cf8361e106d9e421a12`。
- APK随附`audio/CREDITS-engine.txt`；不依赖联网播放，没有使用未经许可的原厂音源、FMOD或GameSynth程序/素材。CC0不要求署名，但仍记录来源。

## 渲染实现

`EngineVoice`是播放器边界；`EngineDecoder`处理PCM16 WAV快路径和Android MediaCodec压缩格式解码，解码与文件IO在工作线程、AudioTrack启动前完成。声浪≤20MiB/≤30秒/8–96kHz/1–2声道；拒绝无效浮点、损坏块、过长或不支持格式，不悄悄换回旧MediaPlayer或重试失败源。Codec等待有20秒截止和取消检查；系统支持的压缩格式仍需实机验证。

`EnginePcm.Render`在48kHz输出上连续推进采样位置，线性插值；不是每圈seek、重新解码或每100ms调用系统变速。循环尾与头重叠，采用相关素材的恒增益raised-cosine权重；重叠为最多30ms且不超过素材1/4，交叉后的周期为原长度减重叠。不会在每圈插静音或对整圈淡出。当前不是高阶带限重采样器，极端变速、特殊素材仍需听感评估。

音高倍率、最终增益与负载在每个采样以35ms时间常数平滑；负载在原始音色与1kHz低通派生音色之间混合，原始比例为`0.25+0.75×load`。它是音效控制，不是气缸/扭矩建模。DC移除、保守峰值限幅和输出夹紧保留余量，不做硬件功放音量写入。

原音试听最长5秒，25ms起音、末80ms收尾、100ms静音尾巴，等待播放头消耗已写入内容再释放。连续循环只在起声时使用起音包络，不每圈收尾。手动停止、安全失效、焦点丢失、来源替换等立即取消并pause/flush；不会为渐出延长旧车辆状态或延后补播。

`PcmEnginePlayer`使用独立音频优先级线程、预分配10ms PCM块、至少50ms且不小于系统最小值两倍的输出缓冲，先填充一部分再play。循环热路径不读文件、不解码；状态字符串最多每半秒更新。界面报告`AudioTrack.getUnderrunCount()`，不能把0欠载当作已验证物理扬声器。来源/生命周期的旧回调按实例隔离，暂停或失败后不自行复活。

## 预设与兼容

仍保留车速音高/音量与加速度增强三条用户曲线，以及用户主音量。新预设采用虚拟RPM和dB增益锚点生成，倍率＝虚拟RPM/3000，线性增益＝`10^(dB/20)`；随后保持原有曲线插值与负载增强公式，不暗改已保存曲线。

| 预设 | 0/20/40/60/90/120 km/h的虚拟RPM | 基础增益dB | 响应 |
| --- | --- | --- | --- |
| 舒适巡航 | 1500/2100/2850/3600/4500/5400 | -32/-23/-19/-16/-13/-11 | 800ms |
| 运动响应 | 1500/2550/3450/4350/5400/5850 | -30/-20/-16/-13/-10/-8 | 450ms |
| 电感平顺 | 1800/2400/3000/3450/3900/4500 | -34/-25/-22/-19/-16/-14 | 1000ms |

这些数值是本App的初始调音值，不是网络资料提供的OEM标定或实车转速。既有自定义音源、曲线、音量、车型参数与默认关闭开关均保留；只有内置默认`engine.wav`换为上述CC0素材。想用新版曲线须主动应用预设。280匹/7秒等仅决定虚拟驾驶过程，不能还原实车油门/扭矩。检测JSON增加渲染类型、源许可、包络/拼接/平滑参数及虚拟RPM。

## 参考与验证

- Android低延迟音频建议：https://developer.android.com/games/sdk/oboe/low-latency-audio
- AudioTrack缓冲欠载诊断：https://developer.android.com/reference/android/media/AudioTrack
- RPM校准、带负载/收油音源组与FMOD集成示例：https://tsugi-studio.com/blog/2022/05/19/from-engine-plug-in-to-fmod-studio/

自动测试覆盖循环多次跨界/0.5–2倍变速、试听零端点/收尾/静音尾巴、5秒上限、立体声、DC/限幅、平滑和负载音色、WAV异常、线程取消/写入失败，以及实车模式门控和旧回调隔离。测试生成`dist/audio-check/engine-continuous.wav`供桌面听感复核；它不代表来自真实车辆。实际车机扬声器、AudioTrack路由、Codec、硬件功放起停和欠载仍需实车复测。
