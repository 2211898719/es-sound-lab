package cn.es.soundlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.Supplier;
import org.json.JSONObject;

final class EngineSettingsView extends LinearLayout {
  private final Store store;
  private final UiTheme theme;
  private final LinearLayout body, points;
  private final TextView profileName, live;
  final TextView audioName;
  private final CurveView graph;
  private EngineProfile profile;
  private int curve;
  private final Button[] curveTabs = new Button[3];
  private final Supplier<MonitorService> demoService;
  private final Handler demoHandler = new Handler(Looper.getMainLooper());
  private TextView demoReading;
  private MotionView motionView;
  private int horsepower, zeroHundredTenths, throttle;
  private Button previewButton;
  private final Runnable demoTicker = new Runnable() {
    @Override public void run() {
      MonitorService service = demoService.get();
      EngineDemo demo = service == null ? null : service.engineDemo();
      if (demo != null && demo.active) service.engineDemoThrottle(throttle / 100f);
      updateDemo();
      if (demo != null && demo.active) demoHandler.postDelayed(this, 100);
    }
  };

  EngineSettingsView(Context context, Store store, Runnable back, Runnable enableMonitor,
      Runnable chooseAudio, Runnable importAudio, Runnable preview, Runnable stop,
      Supplier<MonitorService> demoService) {
    super(context);
    this.store = store;
    this.demoService = demoService;
    theme = new UiTheme(context);
    profile = store.engineProfile();
    setTag("engine.settings");
    setOrientation(VERTICAL);
    setBackgroundColor(theme.background);
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    body = column();
    body.setPadding(dp(20), dp(20), dp(20), dp(24));
    scroll.addView(body);
    addView(scroll, new LayoutParams(-1, -1));
    body.addView(button("← 返回音效设置", back));
    body.addView(label("模拟声浪", 24, theme.text));
    body.addView(label("七层合成 / 自定义循环音频；只生成虚拟声浪，不修改原厂ASD或行人提示。请在安全驻车时设置，行车由乘员观察。", 13, theme.muted));
    Switch enabled = new Switch(context);
    enabled.setText("启用声浪（合成模式含上电怠速，D挡随车速变化）");
    enabled.setTextColor(theme.text);
    enabled.setChecked(store.enabled("engine"));
    enabled.setContentDescription("车速拟合声浪开关");
    enabled.setOnCheckedChangeListener((view, checked) -> {
      store.enable("engine", checked);
      if (checked) enableMonitor.run();
    });
    body.addView(enabled, new LayoutParams(-1, dp(56)));
    live = label("等待车辆监听服务", 13, theme.text);
    live.setTag("engine.live");
    live.setBackground(background(theme.surface));
    body.addView(live, new LayoutParams(-1, -2));
    buildSynthesis();
    body.addView(label("选择响应预设", 18, theme.text));
    Spinner presets = spinner(EngineProfile.NAMES);
    presets.setTag("engine.response.presets");
    presets.setContentDescription("声浪响应预设");
    int selected = 0;
    for (int index = 0; index < EngineProfile.NAMES.length; index++)
      if (profile.name.equals(EngineProfile.NAMES[index])) selected = index;
    presets.setSelection(selected);
    body.addView(presets, new LayoutParams(-1, dp(48)));
    profileName = label("当前曲线：" + profile.name, 14, theme.accent);
    body.addView(profileName);
    body.addView(button("应用预设（覆盖当前曲线）", () -> {
      profile = EngineProfile.preset(presets.getSelectedItemPosition());
      save();
      rebuildPoints();
      SeekBar response = (SeekBar) findViewWithTag("engine.response");
      if (response != null) response.setProgress(profile.responseMillis - 200);
    }));
    body.addView(label("预设只改变响应曲线，不替换音频；下方微调自动保存。", 12, theme.muted));
    body.addView(label("三条旧曲线保留：音量和加速增强仍参与两种模式；车速→音高只用于循环音频模式。合成模式由虚拟变速箱生成转速，音色微调用上方声调设置。", 12, theme.muted));
    body.addView(label("循环模式音源（不替换合成器）", 18, theme.text));
    audioName = label(store.audioName(EventSpec.find("engine.loop")), 14, theme.text);
    audioName.setTag("engine.audio-name");
    body.addView(audioName);
    LinearLayout audioButtons = row();
    weighted(audioButtons, button("选择内置音频", chooseAudio));
    weighted(audioButtons, button("从文件 / U盘替换", importAudio));
    body.addView(audioButtons);
    LinearLayout playbackButtons = row();
    previewButton = button(store.engineSynth() ? "合成音色试听（最多5秒）" : "原音试听（最多5秒）", preview);
    weighted(playbackButtons, previewButton);
    weighted(playbackButtons, button("停止播放", stop));
    body.addView(playbackButtons);
    body.addView(label("内置：domasx2的CC0赛车循环素材；不是原厂发动机实录转速库。自选素材≤20 MiB、≤30秒、单/双声道；加载为PCM后连续播放，不在每圈重启解码器。", 12, theme.muted));
    body.addView(label("两种模式均使用48kHz连续PCM与参数平滑。合成音色有完整发动机周期、七层混音与周期对齐的纹理交叉淡化；试听有起落包络及静音尾巴。安全失效仍立即停止，不延后补播。", 12, theme.muted));
    slider(body, "声浪总音量", 0, 100, store.volume("engine"), value -> value + "%",
        value -> store.volume("engine", value), "engine.master-volume");
    slider(body, "合成怠速 / 基础音量", 0, 40, store.engineIdleVolume(), value -> value + "%",
        value -> store.prefs.edit().putInt("engine.idle.volume", value).apply(), "engine.idle-volume");
    body.addView(label("合成模式：ICC上电且P/N/D静止时播放怠速；行驶曲线音量不低于此基础值，最终乘总音量。设为0可关闭基础声。不改变自定义循环模式。", 12, theme.muted));
    body.addView(button("音频输出设置", () -> {
      shutdown();
      Context owner = getContext();
      if (owner instanceof MainActivity) ((MainActivity) owner).engineOutput();
    }));
    body.addView(label("遵循系统媒体音量与音频焦点；不自动走原厂锁车BUS，也不调整系统总音量。", 12, theme.muted));
    buildDemo();
    body.addView(label("曲线详情 / 微调", 20, theme.text));
    LinearLayout curveButtons = row();
    String[] titles = {"车速→音高", "车速→音量", "加速→增强"};
    for (int index = 0; index < titles.length; index++) {
      final int selectedCurve = index;
      curveTabs[index] = button(titles[index], () -> {
        curve = selectedCurve;
        rebuildPoints();
      });
      weighted(curveButtons, curveTabs[index]);
    }
    body.addView(curveButtons);
    graph = new CurveView(context);
    graph.setTag("engine.curve-graph");
    body.addView(graph, new LayoutParams(-1, dp(220)));
    points = column();
    body.addView(points);
    body.addView(label("音高0.50–2.00倍；音量/增强0–100%。固定横轴锚点间线性插值，超出末点保持末值。增强只使用正加速度；减速不会被当作加速。", 12, theme.muted));
    slider(body, "响应平滑时间", 200, 2000, profile.responseMillis, value -> value + " ms",
        value -> { profile = profile.response(value); save(); }, "engine.response");
    body.addView(label("平滑时间越长，响应越柔和；车速拟合窗口约1.5秒。合成模式P/N/D静止保留怠速；R挡、断连、旧数据、未知挡位或电源关闭立即静音，行驶调制需重新积累样本。", 12, theme.muted));
    rebuildPoints();
  }

  private void buildSynthesis() {
    body.addView(label("播放模式 · 多层合成", 20, theme.text));
    Switch mode = new Switch(getContext());
    mode.setText("使用多层合成（关闭则使用原循环音频）");
    mode.setContentDescription("多层合成模式");
    mode.setTag("engine.synth.mode");
    mode.setTextColor(theme.text);
    mode.setChecked(store.engineSynth());
    mode.setOnCheckedChangeListener((view, checked) -> {
      shutdown();
      store.prefs.edit().putBoolean("engine.synth.enabled", checked).apply();
      if (previewButton != null) previewButton.setText(checked ? "合成音色试听（最多5秒）" : "原音试听（最多5秒）");
    });
    body.addView(mode, new LayoutParams(-1, dp(64)));
    body.addView(label("升级保留原模式。打开此开关仅切换声浪引擎，不打开自动声浪。七层独立合成：怠速＋低/中/高转的驱动与收油；不包含网站代码、录音或车辆控制。", 12, theme.muted));
    EngineTuning tuning = store.engineTuning();
    Spinner styles = spinner(EngineTuning.NAMES);
    styles.setContentDescription("合成声浪角色");
    styles.setTag("engine.synth.styles");
    styles.setSelection(tuning.style);
    styles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onNothingSelected(AdapterView<?> parent) {}
      @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position != store.engineTuning().style) {
          shutdown();
          store.prefs.edit().putInt("engine.synth.style", position).apply();
        }
      }
    });
    body.addView(styles, new LayoutParams(-1, dp(48)));
    slider(body, "合成声调", 75, 125, tuning.tone, value -> value + "%",
        value -> { shutdown(); store.prefs.edit().putInt("engine.synth.tone", value).apply(); }, "engine.synth.tone");
    slider(body, "音色力度", 0, 100, tuning.intensity, value -> value + "%",
        value -> { shutdown(); store.prefs.edit().putInt("engine.synth.intensity", value).apply(); }, "engine.synth.intensity");
    slider(body, "换挡运动倾向", 0, 100, tuning.shifting, value -> value + "%",
        value -> { shutdown(); store.prefs.edit().putInt("engine.synth.shifting", value).apply(); }, "engine.synth.shifting");
    body.addView(label("V8/V10含虚拟六挡、升挡卸载与降挡补油；未来音色连续变调、不模拟换挡。收油负载可为负数，不代表真实油门。修改这些设置会先停止视听；原文件及曲线不删除。", 12, theme.muted));
  }

  private void buildDemo() {
    LinearLayout section = column();
    section.setTag("engine.demo.panel");
    section.setBackgroundColor(theme.background);
    body.addView(section);
    horsepower = Math.max(80, Math.min(800, store.prefs.getInt("engine.demo.horsepower", 280)));
    zeroHundredTenths = Math.max(30, Math.min(180, store.prefs.getInt("engine.demo.zeroHundredTenths", 70)));
    section.addView(label("动态视听 · 虚拟油门", 22, theme.text));
    section.addView(label("仅用于驻车调音，不读取真实油门、不控制车辆。马力/零百控制虚拟加速，也作为合成模式的负载估计参考；这是近似模型，并非真实动力测量。", 12, theme.muted));
    Spinner cars = spinner(EngineDemo.NAMES);
    cars.setTag("engine.demo.presets");
    cars.setContentDescription("视听车型预设");
    int selected = 1;
    for (int index = 0; index < EngineDemo.HORSEPOWER.length; index++)
      if (horsepower == EngineDemo.HORSEPOWER[index]
          && zeroHundredTenths == Math.round(EngineDemo.ZERO_HUNDRED[index] * 10)) selected = index;
    cars.setSelection(selected);
    section.addView(cars, new LayoutParams(-1, dp(48)));
    section.addView(button("应用视听车型（不改声浪曲线）", () -> {
      int index = cars.getSelectedItemPosition();
      shutdown();
      horsepower = EngineDemo.HORSEPOWER[index];
      zeroHundredTenths = Math.round(EngineDemo.ZERO_HUNDRED[index] * 10);
      ((SeekBar) findViewWithTag("engine.demo.horsepower")).setProgress(horsepower - 80);
      ((SeekBar) findViewWithTag("engine.demo.zero-hundred")).setProgress(zeroHundredTenths - 30);
      saveCar();
    }));
    slider(section, "模拟马力", 80, 800, horsepower, value -> value + " 匹",
        value -> { shutdown(); horsepower = value; saveCar(); }, "engine.demo.horsepower");
    slider(section, "0–100 km/h", 30, 180, zeroHundredTenths,
        value -> String.format(Locale.CHINA, "%.1f 秒", value / 10f),
        value -> { shutdown(); zeroHundredTenths = value; saveCar(); }, "engine.demo.zero-hundred");
    motionView = new MotionView(getContext());
    motionView.setTag("engine.demo.gauge");
    section.addView(motionView, new LayoutParams(-1, dp(220)));
    demoReading = label("点击开始后拖动虚拟油门，松油门可滑行减速。车型参数会保留，不改变实车播放。", 14, theme.text);
    demoReading.setTag("engine.demo.reading");
    section.addView(demoReading);
    slider(section, "虚拟油门（不是实车油门）", 0, 100, 0, value -> value + "%", value -> {
      throttle = value;
      MonitorService service = demoService.get();
      if (service != null) service.engineDemoThrottle(value / 100f);
      motionView.invalidate();
    }, "engine.demo.throttle");
    LinearLayout buttons = row();
    Button start = button("开始 / 重置视听", () -> {
      MonitorService service = demoService.get();
      if (service == null) { demoReading.setText("服务连接中，请稍后重试"); return; }
      if (!service.startEngineDemo(horsepower, zeroHundredTenths / 10f)) {
        demoReading.setText("当前不可演示：请安全驻车，退出校准或喊话后重试");
        return;
      }
      demoHandler.removeCallbacks(demoTicker);
      demoHandler.post(demoTicker);
    });
    start.setTag("engine.demo.start");
    weighted(buttons, start);
    weighted(buttons, button("停止视听", this::shutdown));
    section.addView(buttons);
    section.addView(label("使用当前模式、音量曲线及总音量；合成模式同时展示虚拟挡位与转速。显示值为参数目标，非扬声器测量。单次最多60秒 / 200 km/h；离页、后台、焦点丢失或收到实车非P挡/行驶信号即停止。视听不打开自动声浪。", 12, theme.muted));
  }

  private void saveCar() {
    store.prefs.edit().putInt("engine.demo.horsepower", horsepower)
        .putInt("engine.demo.zeroHundredTenths", zeroHundredTenths).apply();
  }

  private EngineDemo demo() {
    MonitorService service = demoService.get();
    return service == null ? null : service.engineDemo();
  }

  private void updateDemo() {
    EngineDemo demo = demo();
    if (demo == null || demoReading == null) return;
    motionView.speed = demo.speed;
    motionView.load = demo.load;
    motionView.distance = demo.distance;
    demoReading.setText(String.format(Locale.CHINA,
        "%s · %.1f / 60 秒\n虚拟车速 %.1f km/h · 加速度 %+.2f m/s²\n模拟负载 %.0f%% · 音高 %.2f× · 曲线音量 %.0f%% × 总音量 %d%% = %.0f%%\n%s",
        demo.status, demo.elapsed, demo.speed, demo.acceleration, demo.load * 100, demo.pitch,
        demo.gain * 100, store.volume("engine"), demo.active ? demo.gain * store.volume("engine") : 0,
        demoService.get().diagnostics.engineDemoReading.optString("audio", "实际扬声器输出仍需确认")));
    if (demo.powertrain() != null) demoReading.append(String.format(Locale.CHINA,
        "\n虚拟挡位 %d · 虚拟转速 %.0f RPM · %s", demo.powertrain().gear(), demo.powertrain().rpm,
        demo.powertrain().shiftDirection > 0 ? "升挡卸载" : demo.powertrain().shiftDirection < 0 ? "降挡补油" : demo.load < 0 ? "收油滑行" : "驱动"));
    motionView.setContentDescription(String.format(Locale.CHINA, "虚拟车速%.1f千米每小时，虚拟油门%d%%", demo.speed, throttle));
    motionView.invalidate();
  }

  void shutdown() {
    demoHandler.removeCallbacks(demoTicker);
    MonitorService service = demoService.get();
    if (service != null) service.stopEngineDemo();
    updateDemo();
  }

  @Override protected void onDetachedFromWindow() {
    shutdown();
    super.onDetachedFromWindow();
  }

  private final class MotionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float speed, load, distance;
    MotionView(Context context) { super(context); }
    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      paint.setColor(theme.surface);
      canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), paint);
      paint.setTypeface(Typeface.DEFAULT_BOLD);
      paint.setColor(theme.accent);
      paint.setTextSize(dp(44));
      canvas.drawText(String.format(Locale.CHINA, "%03.0f", speed), dp(20), dp(62), paint);
      paint.setTextSize(dp(13));
      paint.setTypeface(Typeface.DEFAULT);
      paint.setColor(theme.muted);
      canvas.drawText("km/h · 虚拟演示", dp(20), dp(86), paint);
      float left = getWidth() * .55f, right = getWidth() - dp(22);
      paint.setColor(theme.border);
      canvas.drawLine(left, dp(16), left, dp(100), paint);
      canvas.drawLine(right, dp(16), right, dp(100), paint);
      float offset = distance * 4 % 24;
      paint.setColor(theme.accent);
      for (int index = 0; index < 4; index++) {
        float top = dp(16 + (index * 24 + offset) % 84);
        canvas.drawRect((left + right) / 2 - dp(2), top, (left + right) / 2 + dp(2), top + dp(9), paint);
      }
      meter(canvas, "虚拟油门", throttle / 100f, 123);
      meter(canvas, "模拟负载", load, 174);
    }

    private void meter(Canvas canvas, String title, float value, float top) {
      paint.setTextSize(dp(12));
      paint.setColor(theme.text);
      canvas.drawText(title + String.format(Locale.CHINA, "  %.0f%%", value * 100), dp(20), dp(top), paint);
      paint.setColor(theme.border);
      canvas.drawRoundRect(dp(20), dp(top + 10), getWidth() - dp(20), dp(top + 21), dp(4), dp(4), paint);
      paint.setColor(theme.accent);
      canvas.drawRoundRect(dp(20), dp(top + 10), dp(20) + (getWidth() - dp(40)) * Math.min(1, Math.abs(value)),
          dp(top + 21), dp(4), dp(4), paint);
    }
  }

  private interface ValueLabel { String text(int value); }
  private interface Changed { void accept(int value); }

  private void slider(LinearLayout parent, String title, int min, int max, int initial,
      ValueLabel format, Changed changed, String tag) {
    TextView valueLabel = label(title + "  " + format.text(initial), 14, theme.text);
    parent.addView(valueLabel);
    SeekBar bar = new SeekBar(getContext());
    bar.setTag(tag);
    bar.setContentDescription(title);
    bar.setMax(max - min);
    bar.setProgress(initial - min);
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent));
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(theme.thumb));
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onStartTrackingTouch(SeekBar seek) {}
      @Override public void onProgressChanged(SeekBar seek, int progress, boolean user) {
        valueLabel.setText(title + "  " + format.text(progress + min));
        if (user) changed.accept(progress + min);
      }
      @Override public void onStopTrackingTouch(SeekBar seek) { changed.accept(seek.getProgress() + min); }
    });
    parent.addView(bar, new LayoutParams(-1, dp(48)));
  }

  private void save() {
    store.engineProfile(profile);
    profileName.setText("当前曲线：" + profile.name);
    if (graph != null) graph.invalidate();
  }

  private void rebuildPoints() {
    for (int index = 0; index < curveTabs.length; index++) {
      curveTabs[index].setBackground(background(index == curve ? theme.primary : theme.raised));
      curveTabs[index].setTextColor(index == curve ? android.graphics.Color.WHITE : theme.accent);
      curveTabs[index].setSelected(index == curve);
    }
    points.removeAllViews();
    float[] anchors = curve == 2 ? EngineProfile.ACCEL_POINTS : EngineProfile.SPEED_POINTS;
    float[] values = curve == 0 ? profile.pitch : curve == 1 ? profile.volume : profile.acceleration;
    for (int index = 0; index < anchors.length; index++) {
      final int point = index;
      String title = String.format(Locale.CHINA, curve == 2 ? "%.1f m/s²" : "%.0f km/h", anchors[index]);
      slider(points, title, curve == 0 ? 50 : 0, curve == 0 ? 200 : 100,
          Math.round(values[index] * 100),
          value -> curve == 0 ? String.format(Locale.CHINA, "%.2f×", value / 100f) : value + "%",
          value -> { profile = profile.edit(curve, point, value / 100f); save(); }, "engine.point." + index);
    }
    graph.invalidate();
    graph.setContentDescription(curve == 0 ? "车速音高曲线" : curve == 1 ? "车速音量曲线" : "加速度增强曲线");
  }

  void update(Diagnostics diagnostics) {
    JSONObject reading = diagnostics.engineReading();
    long age = System.currentTimeMillis() - reading.optLong("updatedAt", 0);
    if (!diagnostics.monitoring || age < 0 || age > EngineMotion.MAX_AGE) {
      live.setText("车辆监听未运行或数据已过期；不会播放行车声浪");
      return;
    }
    String measurements = reading.isNull("rawKmh") ? "车速 / 加速度 / 负载：—（无可用拟合样本）"
        : String.format(Locale.CHINA, "原始车速 %.0f km/h  ·  平滑车速 %.1f km/h\n估算加速度 %+.2f m/s²  ·  模拟负载 %.0f%%",
            reading.optDouble("rawKmh"), reading.optDouble("smoothedKmh"),
            reading.optDouble("accelerationMps2"), reading.optDouble("simulatedLoad") * 100);
    live.setText(String.format(Locale.CHINA,
        "%s\n%s\n目标音高 %.2f×  ·  目标音量 %.0f%%（总音量前）\n%s",
        reading.optString("status"), measurements,
        reading.optDouble("pitch", 1), reading.optDouble("gain") * 100,
        reading.optString("audio", "声浪未播放") + " · 目标参数不代表扬声器已出声"));
    if (store.engineSynth() && "idle".equals(reading.optString("mode"))) live.append(String.format(Locale.CHINA,
        "\n虚拟怠速 %.0f RPM（不是车辆实测）", diagnostics.enginePowertrain.optDouble("rpm")));
    else if (store.engineSynth() && reading.optBoolean("estimating")) live.append(String.format(Locale.CHINA,
        "\n虚拟挡位 %d · 虚拟转速 %.0f RPM（不是车辆实测）",
        diagnostics.enginePowertrain.optInt("gear"), diagnostics.enginePowertrain.optDouble("rpm")));
  }

  private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
  private LinearLayout column() { LinearLayout result = new LinearLayout(getContext()); result.setOrientation(VERTICAL); return result; }
  private LinearLayout row() { LinearLayout result = new LinearLayout(getContext()); result.setGravity(Gravity.CENTER_VERTICAL); return result; }
  private void weighted(LinearLayout parent, View view) { LayoutParams params = new LayoutParams(0, dp(52), 1); params.setMargins(0, dp(3), dp(6), dp(3)); parent.addView(view, params); }
  private GradientDrawable background(int color) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(10)); result.setStroke(dp(1), theme.border); return result; }
  private TextView label(String text, int size, int color) { TextView result = new TextView(getContext()); result.setText(text); result.setTextSize(size); result.setTextColor(color); result.setPadding(dp(8), dp(10), dp(8), dp(10)); return result; }
  private Button button(String title, Runnable action) {
    Button result = new Button(getContext());
    result.setText(title);
    result.setTextColor(theme.accent);
    result.setTextSize(14);
    result.setAllCaps(false);
    result.setStateListAnimator(null);
    result.setElevation(0);
    result.setTranslationZ(0);
    result.setBackground(background(theme.raised));
    result.setOnClickListener(view -> action.run());
    return result;
  }

  private Spinner spinner(String[] labels) {
    Spinner result = new Spinner(getContext());
    result.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, labels) {
      @Override public View getView(int position, View convert, ViewGroup parent) { return label(getItem(position), 15, theme.text); }
      @Override public View getDropDownView(int position, View convert, ViewGroup parent) { TextView view = label(getItem(position), 15, theme.text); view.setBackgroundColor(theme.surface); return view; }
    });
    result.setPopupBackgroundDrawable(background(theme.surface));
    return result;
  }

  private final class CurveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    CurveView(Context context) { super(context); }
    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float left = dp(48), top = dp(22), right = getWidth() - dp(22), bottom = getHeight() - dp(36);
      if (right <= left || bottom <= top) return;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(theme.surface);
      canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), paint);
      float min = curve == 0 ? .5f : 0, max = curve == 0 ? 2 : 1;
      float[] anchors = curve == 2 ? EngineProfile.ACCEL_POINTS : EngineProfile.SPEED_POINTS;
      float[] values = curve == 0 ? profile.pitch : curve == 1 ? profile.volume : profile.acceleration;
      paint.setTextSize(dp(11));
      paint.setTypeface(Typeface.DEFAULT);
      for (int index = 0; index <= 3; index++) {
        float height = bottom - (bottom - top) * index / 3;
        paint.setColor(theme.border);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(left, height, right, height, paint);
        paint.setColor(theme.muted);
        float value = min + (max - min) * index / 3;
        canvas.drawText(curve == 0 ? String.format(Locale.CHINA, "%.1f×", value) : Math.round(value * 100) + "%", dp(4), height + dp(4), paint);
      }
      Path line = new Path();
      for (int index = 0; index < anchors.length; index++) {
        float horizontal = left + (right - left) * anchors[index] / anchors[anchors.length - 1];
        float vertical = bottom - (bottom - top) * (values[index] - min) / (max - min);
        if (index == 0) line.moveTo(horizontal, vertical); else line.lineTo(horizontal, vertical);
        paint.setColor(theme.accent);
        canvas.drawCircle(horizontal, vertical, dp(4), paint);
        paint.setColor(theme.muted);
        canvas.drawText(String.format(Locale.CHINA, curve == 2 ? "%.1f" : "%.0f", anchors[index]), horizontal - dp(7), bottom + dp(18), paint);
      }
      paint.setColor(theme.accent);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(3));
      canvas.drawPath(line, paint);
      paint.setStyle(Paint.Style.FILL);
    }
  }
}
