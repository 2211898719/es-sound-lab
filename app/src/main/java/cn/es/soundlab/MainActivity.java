package cn.es.soundlab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private UiTheme theme;
  private int BLUE, INK, MUTED, BG;
  private final Handler ui = new Handler();
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private Store store;
  private MonitorService service;
  private boolean bound, resumed, updatingSwitch;
  private int page;
  private LinearLayout root, content;
  private TextView diagStatus, rawLog, calibrationStatus;
  private StateDashboardView stateDashboard;
  private EngineSettingsView engineSettings;
  private Button calibrationButton;
  private AlertDialog activeCalibrationDialog;
  private Switch monitorSwitch;
  private Button captureButton;
  private String pendingImport;
  private File pendingExport;
  private final List<TextView> tabs = new ArrayList<>();
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
          service =
              b instanceof MonitorService.LocalBinder
                  ? ((MonitorService.LocalBinder) b).service()
                  : null;
          update();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
          service = null;
          update();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    theme = new UiTheme(this);
    BLUE = theme.accent;
    INK = theme.text;
    MUTED = theme.muted;
    BG = theme.background;
    store = new Store(this);
    if (state != null) {
      page = state.getInt("page", 0);
      pendingImport = state.getString("import");
      String export = state.getString("export");
      if (export != null) pendingExport = new File(export);
    }
    theme.systemBars(this);
    render();
    bound = bindService(new Intent(this, MonitorService.class), connection, BIND_AUTO_CREATE);
  }

  @Override
  protected void onStart() {
    super.onStart();
    store.prefs.edit().putBoolean("monitor", true).apply();
    startForegroundService(
        new Intent(this, MonitorService.class).setAction(MonitorService.START));
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    ui.removeCallbacks(ticker);
    ui.post(ticker);
  }

  @Override
  protected void onPause() {
    if (engineSettings != null) engineSettings.shutdown();
    resumed = false;
    ui.removeCallbacks(ticker);
    if (service != null) {
      service.calibrationMode(false);
      service.stopMicrophone();
    }
    if (activeCalibrationDialog != null) activeCalibrationDialog.dismiss();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (engineSettings != null) engineSettings.shutdown();
    if (service != null) {
      service.calibrationMode(false);
      service.stopMicrophone();
    }
    if (bound) unbindService(connection);
    ui.removeCallbacksAndMessages(null);
    files.shutdown();
    super.onDestroy();
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    render();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putInt("page", page);
    b.putString("import", pendingImport);
    if (pendingExport != null) b.putString("export", pendingExport.getAbsolutePath());
    super.onSaveInstanceState(b);
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout col() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private TextView text(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    return t;
  }

  private TextView bold(String s, int size, int color) {
    TextView t = text(s, size, color);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  private GradientDrawable shape(int color, int stroke, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private Button button(String label, boolean primary, Runnable click) {
    Button b = new Button(this);
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setTranslationZ(0);
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setTextColor(primary ? Color.WHITE : BLUE);
    b.setPadding(dp(14), dp(10), dp(14), dp(10));
    b.setBackground(shape(primary ? theme.primary : theme.raised, primary ? 0 : theme.border, 12));
    b.setOnClickListener(v -> click.run());
    return b;
  }

  private void add(LinearLayout p, View v, int h) {
    p.addView(v, new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h)));
  }

  private void gap(LinearLayout p, int h) {
    add(p, new View(this), h);
  }

  private void margin(LinearLayout p, View v, int h, int bottom) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h < 0 ? h : dp(h));
    lp.bottomMargin = dp(bottom);
    p.addView(v, lp);
  }

  private void weighted(LinearLayout p, View v, int height, int right) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(height), 1);
    lp.rightMargin = dp(right);
    p.addView(v, lp);
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  private void render() {
    if (engineSettings != null) engineSettings.shutdown();
    engineSettings = null;
    if (page == 2) {
      diagStatus = null;
      stateDashboard = null;
      monitorSwitch = null;
      engineSettings = new EngineSettingsView(this, store,
          () -> { page = 0; render(); }, this::ensureMonitor,
          () -> chooseAudio(EventSpec.find("engine.loop"), engineSettings.audioName),
          () -> importAudio(EventSpec.find("engine.loop")),
          () -> { if (service != null) service.preview("engine.loop"); else toast("服务连接中，请稍后重试"); },
          () -> { if (service != null) service.stopAudio(); }, () -> service);
      setContentView(engineSettings);
      update();
      return;
    }
    root = col();
    root.setBackgroundColor(BG);
    setContentView(root);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    content = col();
    content.setPadding(dp(20), dp(16), dp(20), dp(20));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (page != 1) {
      ArtView hero = new ArtView(this, "hero", BLUE);
      add(content, hero, 154);
      gap(content, 18);
    }
    LinearLayout nav = row();
    tabs.clear();
    String[] names = {"音效设置", "车辆检测"};
    if (page > 1) page = 0;
    for (int i = 0; i < names.length; i++) {
      final int index = i;
      TextView tab = bold(names[i], 16, page == i ? Color.WHITE : MUTED);
      tab.setPadding(dp(20), dp(12), dp(20), dp(12));
      tab.setGravity(Gravity.CENTER);
      tab.setBackground(shape(page == i ? theme.primary : theme.color(0xffeaf0f8, theme.raised), 0, 12));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
      p.rightMargin = dp(i == names.length - 1 ? 0 : 8);
      nav.addView(tab, p);
      tab.setOnClickListener(
          v -> {
            if (page == 1 && index != 1 && service != null) service.calibrationMode(false);
            page = index;
            render();
          });
      tabs.add(tab);
    }
    add(content, nav, 46);
    gap(content, 18);
    diagStatus = null;
    stateDashboard = null;
    calibrationStatus = null;
    calibrationButton = null;
    rawLog = null;
    logSourceButton = null;
    captureButton = null;
    monitorSwitch = null;
    if (page == 0) home();
    else detection();
    update();
  }

  private void home() {
    LinearLayout controls = row();
    LinearLayout left = col();
    left.addView(bold("星纪元 ES", 18, INK));
    gap(left, 5);
    left.addView(text("个性化车内外音效，让驾驶更有趣", 12, MUTED));
    controls.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
    monitorSwitch = new Switch(this);
    monitorSwitch.setText("车辆监听  ");
    monitorSwitch.setTextSize(13);
    monitorSwitch.setTextColor(MUTED);
    monitorSwitch.setThumbTintList(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
            new int[] {BLUE, theme.inactive}));
    monitorSwitch.setChecked(store.prefs.getBoolean("monitor", false));
    monitorSwitch.setOnCheckedChangeListener(
        (b, on) -> {
          if (updatingSwitch) return;
          if (on) ensureMonitor();
          else if (service != null) service.end();
          else {
            store.prefs.edit().putBoolean("monitor", false).apply();
            startService(new Intent(this, MonitorService.class).setAction(MonitorService.STOP));
          }
        });
    controls.addView(monitorSwitch);
    add(content, controls, 58);
    gap(content, 10);
    if (store.logLockSelected()) {
      homeCards("ICC 接口 · 无需日志授权", "gear", "charge");
      homeCards("授权日志 · 用户自行启用", "door", "tailgate", "autohold", "lock");
    } else {
      homeCards("ICC 接口 · 无需日志授权", "gear", "lock", "charge");
      homeCards("授权日志 · 用户自行启用", "door", "tailgate", "autohold");
    }
    homeCards("声浪与手动音效", "engine", "reverse", "pedestrian", "shout");
    TextView note = text("解闭锁默认启用：先有新鲜P挡基线，再响应中控锁变化；自动使用原厂车外通道。充电仍需核对，日志未授权不影响ICC。", 13, MUTED);
    note.setLineSpacing(dp(4), 1);
    add(content, note, -2);
  }

  private void homeCards(String title, String... groupNames) {
    add(content, bold(title, 17, INK), -2);
    gap(content, 12);
    List<String> groups = java.util.Arrays.asList(groupNames);
    int width = getResources().getConfiguration().screenWidthDp,
        columns = width >= 1000 ? 3 : width >= 660 ? 2 : 1;
    for (int at = 0; at < groups.size(); at += columns) {
      LinearLayout r = row();
      r.setGravity(Gravity.TOP);
      for (int j = 0; j < columns; j++) {
        if (at + j < groups.size()) {
          View card = card(groups.get(at + j));
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
          if (j < columns - 1) lp.rightMargin = dp(14);
          r.addView(card, lp);
        } else r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      }
      margin(content, r, -2, 14);
    }
  }

  private String description(String g) {
    switch (g) {
      case "gear":
        return "换挡时播放，优先打断当前提示音";
      case "autohold":
        return "本车已核对：1释放保持 / 2正在保持，不等同P挡";
      case "door":
        return "四个车门，开门与关门分别设置";
      case "lock":
        return "先有P挡基线，再由中控锁变化触发 · 原厂车外通道";
      case "engine":
        return "车速＋估算加速度驱动 · 可替换音频、选择预设与微调曲线";
      case "reverse":
        return "进入 R 挡播放，退出后停止";
      case "shout":
        return "播放语音，或按住按钮实时喊话";
      case "charge":
        return "ICC接口 · 核对后启用充电开始、结束提示";
      case "tailgate":
        return "后尾门开启和关闭的语音提醒";
      case "pedestrian":
        return "低速前进时的循环提示音";
      default:
        return "为你发现的新事件定制声音";
    }
  }

  private int accent(String g) {
    switch (g) {
      case "door":
        return theme.color(0xffdf963e, 0xfff0bd77);
      case "lock":
      case "tailgate":
        return theme.color(0xff35aa91, 0xff7ddac5);
      case "engine":
        return theme.color(0xff9a73dd, 0xffc6adff);
      case "shout":
        return theme.color(0xffcd71bf, 0xffefa8df);
      case "charge":
      case "pedestrian":
      case "autohold":
        return theme.color(0xff36a798, 0xff81d8cb);
      default:
        return BLUE;
    }
  }

  private int pastel(String g) {
    switch (g) {
      case "door":
        return theme.color(0xfffff3e4, 0xff30281e);
      case "lock":
      case "tailgate":
        return theme.color(0xffe7f7f1, 0xff19342f);
      case "engine":
        return theme.color(0xfff0eafb, 0xff2b253d);
      case "shout":
        return theme.color(0xfff9eaf6, 0xff332338);
      case "charge":
      case "pedestrian":
      case "autohold":
        return theme.color(0xffe6f6f2, 0xff193337);
      default:
        return theme.color(0xffe7f2ff, 0xff1c2c43);
    }
  }

  private View card(String group) {
    LinearLayout card = col();
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    GradientDrawable bg =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {pastel(group), theme.color(0xfffbfdff, theme.surface)});
    bg.setCornerRadius(dp(20));
    bg.setStroke(dp(1), theme.border);
    card.setBackground(bg);
    card.setElevation(dp(1));
    LinearLayout header = row();
    ArtView icon = new ArtView(this, group, accent(group));
    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(58), dp(64));
    iconLp.rightMargin = dp(10);
    header.addView(icon, iconLp);
    LinearLayout titles = col();
    TextView title = bold(EventSpec.groupName(group), 18, INK);
    titles.addView(title);
    gap(titles, 6);
    String badge =
        group.equals("engine")
            ? "车速拟合 · 默认关闭"
            : group.equals("autohold")
            ? (store.autoHoldCalibrated() ? "已校准 · 变化时播放" : "待校准 · 已发现 1/2 状态")
            : group.equals("lock")
                ? (store.logLockSelected() ? "授权日志锁事件" : "ICC锁事件") + " · P挡限定 / 原厂车外通道"
                : group.equals("charge")
                    ? (store.iccTriggerConfirmed("charge") ? "ICC已核对 · 变化时播放" : "ICC仅展示 · 待核对后触发")
                : (group.equals("charge") || group.equals("custom"))
                    ? "等待授权日志"
                    : group.equals("shout") ? "手动播放 · 输出待确认" : "已接入 · 待实车验证";
    TextView badgeView = text(badge, 10, accent(group));
    titles.addView(badgeView);
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    if (group.equals("engine")) {
      card.setTag("engine.simulation");
      card.setMinimumHeight(dp(338));
      add(card, header, 70);
      gap(card, 16);
      TextView state = bold(store.enabled("engine") ? "已启用 · 仅D挡行驶" : "尚未启用 · 默认关闭", 19, accent(group));
      add(card, state, 42);
      add(card, text(store.engineSynth() ? "多层合成：" + EngineTuning.NAMES[store.engineTuning().style]
          : "当前曲线：" + store.engineProfile().name, 13, INK), 30);
      TextView explanation = text(description(group), 13, MUTED);
      explanation.setLineSpacing(dp(4), 1);
      add(card, explanation, 52);
      add(card, button("选择预设 / 曲线详情 →", false, () -> { page = 2; render(); }), 48);
      card.setOnClickListener(view -> { page = 2; render(); });
      return card;
    }
    Switch enabled = new Switch(this);
    enabled.setContentDescription(EventSpec.groupName(group) + "开关");
    enabled.setChecked(store.enabled(group));
    enabled.setThumbTintList(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
            new int[] {BLUE, theme.inactive}));
    enabled.setOnCheckedChangeListener(
        (b, on) -> {
          store.enable(group, on);
          if (on && !group.equals("shout")) ensureMonitor();
          if (on && group.equals("lock") && !store.lockTriggerCalibrated()) lockCalibration();
          if (on && group.equals("charge") && !store.iccTriggerConfirmed("charge")) iccStateSettings("charge");
          if (on && group.equals("autohold") && !store.autoHoldCalibrated()) autoHoldCalibration();
        });
    header.addView(enabled, new LinearLayout.LayoutParams(dp(48), dp(42)));
    add(card, header, 70);
    gap(card, 7);
    TextView desc = text(description(group), 12, MUTED);
    desc.setMaxLines(2);
    add(card, desc, 34);
    gap(card, 9);
    final List<EventSpec> events = EventSpec.group(group);
    List<String> labels = new ArrayList<>();
    int selected = 0;
    for (int i = 0; i < events.size(); i++) {
      labels.add(events.get(i).title);
      if (events.get(i).id.equals(store.selected(group))) selected = i;
    }
    Spinner picker = spinner(labels);
    picker.setSelection(selected);
    picker.setBackground(shape(theme.color(0x99ffffff, theme.field), theme.border, 10));
    picker.setContentDescription(EventSpec.groupName(group) + "事件选择");
    add(card, picker, 40);
    gap(card, 9);
    LinearLayout fileRow = row();
    TextView name = text(store.audioName(events.get(selected)), 13, INK);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    name.setPadding(dp(11), 0, dp(5), 0);
    name.setBackground(shape(theme.color(0xbbffffff, theme.field), theme.border, 10));
    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, dp(40), 1);
    nameLp.rightMargin = dp(7);
    name.setGravity(Gravity.CENTER_VERTICAL);
    fileRow.addView(name, nameLp);
    name.setOnClickListener(v -> chooseAudio(events.get(picker.getSelectedItemPosition()), name));
    Button upload =
        button("导入", false, () -> importAudio(events.get(picker.getSelectedItemPosition())));
    fileRow.addView(upload, new LinearLayout.LayoutParams(dp(66), dp(40)));
    add(card, fileRow, 40);
    gap(card, 12);
    LinearLayout volume = row();
    volume.addView(text("音量", 12, MUTED));
    SeekBar seek = new SeekBar(this);
    seek.setMax(100);
    seek.setProgress(store.volume(group));
    seek.setProgressTintList(ColorStateList.valueOf(BLUE));
    seek.setThumbTintList(ColorStateList.valueOf(theme.thumb));
    volume.addView(seek, new LinearLayout.LayoutParams(0, dp(36), 1));
    TextView percent = bold(store.volume(group) + "%", 12, MUTED);
    percent.setGravity(Gravity.END);
    volume.addView(percent, new LinearLayout.LayoutParams(dp(37), -2));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {}

          public void onProgressChanged(SeekBar s, int n, boolean user) {
            percent.setText(n + "%");
          }

          public void onStopTrackingTouch(SeekBar s) {
            store.volume(group, s.getProgress());
          }
        });
    add(card, volume, 36);
    gap(card, 9);
    LinearLayout actions = row();
    weighted(
        actions,
        button(
            "▶ 试听",
            false,
            () -> {
              if (service != null) service.preview(events.get(picker.getSelectedItemPosition()).id);
              else toast("服务连接中，请稍后重试");
            }),
        40,
        8);
    if (group.equals("shout")) {
      Button talk = button("按住说话", true, () -> {});
      talk.setOnTouchListener(
          (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
              if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                  != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1001);
              else if (service != null) {
                service.startMicrophone();
                talk.setText("松开结束");
              }
            } else if (event.getAction() == MotionEvent.ACTION_UP
                || event.getAction() == MotionEvent.ACTION_CANCEL) {
              if (service != null) service.stopMicrophone();
              talk.setText("按住说话");
              v.performClick();
            }
            return true;
          });
      weighted(actions, talk, 40, 0);
    } else
      weighted(
          actions,
          button(
              group.equals("pedestrian")
                  ? "车速上限"
                  : group.equals("lock")
                      ? "锁车设置"
                      : group.equals("autohold") ? "AutoHold 设置" : "更多设置",
              false,
              () -> {
                if (group.equals("pedestrian")) speedThreshold();
                else if (group.equals("lock"))
                  lockSettings(events.get(picker.getSelectedItemPosition()));
                else if (group.equals("autohold"))
                  new AlertDialog.Builder(this)
                      .setTitle("AutoHold 音效")
                      .setItems(
                          new String[] {"校准保持 / 释放状态", "更多音频设置"},
                          (dialog, item) -> {
                            if (item == 0) autoHoldCalibration();
                            else editEvent(events.get(picker.getSelectedItemPosition()));
                          })
                      .setNegativeButton("关闭", null)
                      .show();
                else editEvent(events.get(picker.getSelectedItemPosition()));
              }),
          40,
          0);
    add(card, actions, 40);
    picker.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> p) {}

          public void onItemSelected(android.widget.AdapterView<?> p, View v, int at, long id) {
            EventSpec spec = events.get(at);
            store.select(group, spec.id);
            name.setText(store.audioName(spec));
          }
        });
    return card;
  }

  private Spinner spinner(List<String> labels) {
    Spinner s = new Spinner(this);
    ArrayAdapter<String> a =
        new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
          private View styled(View v, boolean dropdown) {
            TextView t = (TextView) v;
            t.setTextColor(INK);
            t.setTextSize(14);
            t.setPadding(dp(12), dp(10), dp(12), dp(10));
            t.setBackgroundColor(dropdown ? theme.surface : Color.TRANSPARENT);
            if (!dropdown) t.setText(t.getText() + "  ▾");
            return t;
          }

          @Override
          public View getView(int position, View convert, android.view.ViewGroup parent) {
            return styled(super.getView(position, convert, parent), false);
          }

          @Override
          public View getDropDownView(int position, View convert, android.view.ViewGroup parent) {
            return styled(super.getDropDownView(position, convert, parent), true);
          }
        };
    s.setAdapter(a);
    return s;
  }

  private void ensureMonitor() {
    store.prefs.edit().putBoolean("monitor", true).apply();
    startForegroundService(new Intent(this, MonitorService.class).setAction(MonitorService.START));
  }

  private void editEvent(EventSpec spec) {
    LinearLayout body = col();
    body.setPadding(dp(22), dp(12), dp(22), dp(16));
    body.addView(text(spec.title, 18, INK));
    gap(body, 14);
    TextView name = text(store.audioName(spec), 14, MUTED);
    body.addView(name);
    gap(body, 12);
    body.addView(button("选择内置音频", false, () -> chooseAudio(spec, name)));
    gap(body, 8);
    body.addView(button("从文件 / U 盘导入", false, () -> importAudio(spec)));
    gap(body, 16);
    TextView speed = text(String.format(Locale.CHINA, "语速  %.1f×", store.speed(spec.id)), 14, INK);
    body.addView(speed);
    SeekBar s = new SeekBar(this);
    s.setMax(15);
    s.setProgress(Math.round(store.speed(spec.id) * 10) - 5);
    s.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar bar) {}

          public void onProgressChanged(SeekBar bar, int v, boolean user) {
            speed.setText(String.format(Locale.CHINA, "语速  %.1f×", (v + 5) / 10f));
          }

          public void onStopTrackingTouch(SeekBar bar) {
            store.prefs.edit().putInt("speed." + spec.id, (bar.getProgress() + 5) * 10).apply();
          }
        });
    body.addView(s);
    gap(body, 10);
    body.addView(
        button(
            "恢复默认音频",
            false,
            () -> {
              store.asset(spec, "audio/" + spec.asset, spec.title + extension(spec.asset));
              store.prefs.edit().remove("speed." + spec.id).apply();
              name.setText(store.audioName(spec));
              toast("已恢复默认音频");
            }));
    new AlertDialog.Builder(this)
        .setTitle("事件音效")
        .setView(body)
        .setPositiveButton("完成", (d, w) -> render())
        .show();
  }

  private String extension(String filename) {
    int at = filename.lastIndexOf('.');
    return at >= 0 ? filename.substring(at) : "";
  }

  private void chooseAudio(EventSpec spec, TextView label) {
    try {
      String[] names = getAssets().list("audio");
      if (names == null) return;
      List<String> paths = new ArrayList<>(), labels = new ArrayList<>();
      for (String n : names) {
        if (!n.endsWith(".wav") && !n.endsWith(".mp3") && !n.endsWith(".ogg") && !n.endsWith(".flac") && !n.endsWith(".m4a")) continue;
        paths.add(n);
        String display = AssetNames.display(n);
        labels.add(display);
      }
      new AlertDialog.Builder(this)
          .setTitle(spec.title + " · 内置音频")
          .setItems(
              labels.toArray(new String[0]),
              (d, at) -> {
                store.asset(spec, "audio/" + paths.get(at), labels.get(at));
                label.setText(labels.get(at));
                if (service != null) service.preview(spec.id);
              })
          .setNegativeButton("取消", null)
          .show();
    } catch (Exception e) {
      toast("读取内置音频失败");
    }
  }

  private void importAudio(EventSpec spec) {
    pendingImport = spec.id;
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("audio/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    try {
      startActivityForResult(i, 301);
    } catch (Exception e) {
      toast("系统没有可用的文件选择器");
    }
  }

  private void speedThreshold() {
    LinearLayout body = col();
    body.setPadding(dp(22), dp(10), dp(22), dp(10));
    TextView label = text("车速上限 " + store.prefs.getInt("lowSpeed", 20) + " km/h", 16, INK);
    body.addView(label);
    SeekBar s = new SeekBar(this);
    s.setMax(19);
    s.setProgress(store.prefs.getInt("lowSpeed", 20) - 1);
    s.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int n, boolean user) {
            label.setText("车速上限 " + (n + 1) + " km/h");
          }
        });
    body.addView(s);
    new AlertDialog.Builder(this)
        .setTitle("低速行人提醒")
        .setView(body)
        .setPositiveButton(
            "保存", (d, w) -> store.prefs.edit().putInt("lowSpeed", s.getProgress() + 1).apply())
        .setNegativeButton("取消", null)
        .show();
  }

  void engineOutput() {
    AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
    List<AudioDeviceInfo> devices = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    labels.add("系统默认输出（车内 / 车外由车机决定）");
    int selected = 0, id = store.prefs.getInt("output", -1);
    if (manager != null)
      for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
        devices.add(d);
        labels.add(d.getProductName() + " · 设备 " + d.getId() + " · 类型 " + d.getType());
        if (d.getId() == id) selected = labels.size() - 1;
      }
    new AlertDialog.Builder(this)
        .setTitle("普通音效与喊话输出（不含解闭锁）")
        .setSingleChoiceItems(
            labels.toArray(new String[0]),
            selected,
            (dialog, at) -> {
              store
                  .prefs
                  .edit()
                  .putInt("output", at == 0 ? -1 : devices.get(at - 1).getId())
                  .apply();
              dialog.dismiss();
              toast("输出已选择，可点击试听验证");
            })
        .setNeutralButton(
            "停止播放",
            (dialog, which) -> {
              if (service != null) service.stopAudio();
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  private void autoHoldCalibration() {
    LinearLayout body = col();
    body.setPadding(dp(22), dp(10), dp(22), dp(16));
    body.addView(
        text("本车已核对：1=行车/释放保持，2=驻车/正在保持。这里仅表示AutoHold状态，不等同于P挡、手刹或车辆可行驶。可按实车重新校准；日志仍需自行授权，音效开关不会自动开启。", 13, INK));
    if (service != null)
      for (Diagnostics.Candidate candidate : service.diagnostics.candidates())
        if ("autohold".equals(candidate.sample.key))
          body.addView(
              text(
                  "最近原始值：" + candidate.sample.raw + " · 已观测 " + candidate.values + "（仅供核对）",
                  13,
                  MUTED));
    EditText holding = new EditText(this), released = new EditText(this);
    for (EditText input : new EditText[] {holding, released}) {
      input.setSingleLine(true);
      input.setTextColor(INK);
      input.setHintTextColor(MUTED);
      input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    }
    body.addView(text("保持车辆时的原始值", 13, INK));
    holding.setHint("填写 1 或 2");
    holding.setContentDescription("AutoHold 保持原始值");
    holding.setText(store.prefs.getString("autohold.holding", "2"));
    body.addView(holding);
    body.addView(text("释放保持时的原始值", 13, INK));
    released.setHint("填写另一个值");
    released.setContentDescription("AutoHold 释放原始值");
    released.setText(store.prefs.getString("autohold.released", "1"));
    body.addView(released);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("校准 AutoHold 状态")
            .setView(body)
            .setPositiveButton("保存校准", null)
            .setNeutralButton(
                "清除校准",
                (view, which) -> {
                  store.prefs.edit().putString("autohold.holding", "").putString("autohold.released", "").apply();
                  render();
                })
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        view ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    button -> {
                      try {
                        store.calibrateAutoHold(
                            holding.getText().toString().trim(),
                            released.getText().toString().trim());
                        dialog.dismiss();
                        render();
                        toast("已保存；下次有效状态建立基线，后续变化才播音");
                      } catch (IllegalArgumentException error) {
                        released.setError(error.getMessage());
                      }
                    }));
    showCalibrationDialog(dialog);
  }

  private void lockSettings(EventSpec event) {
    new AlertDialog.Builder(this).setTitle("解闭锁 · 原厂车外通道")
        .setItems(new String[] {"车外通道与P挡条件", "试听车外声音", "更多音频设置", "锁事件来源（ICC / 授权日志）", "校准日志锁状态"}, (dialog, item) -> {
          if (item == 0) iccStateSettings("lock");
          else if (item == 1) { if (service != null) service.preview(event.id); }
          else if (item == 2) editEvent(event);
          else if (item == 3) lockTriggerSource();
          else lockCalibration();
        }).setNegativeButton("关闭", null).show();
  }

  private void lockTriggerSource() {
    new AlertDialog.Builder(this).setTitle("锁事件来源 · 不自动补位")
        .setSingleChoiceItems(new String[] {"ICC接口（默认，无需日志授权）", "授权日志（需另行启用、授权及校准）"},
            store.logLockSelected() ? 1 : 0, (dialog, which) -> {
              store.logLockSelected(which == 1);
              dialog.dismiss();
              render();
              toast(which == 1 ? "已选日志锁事件；请核对校准，并在来源设置自行启用和授权日志" : "已选ICC；等待新基线");
            })
        .setNegativeButton("取消", null).show();
  }

  private void lockCalibration() {
    LinearLayout body = col();
    body.setPadding(dp(22), dp(10), dp(22), dp(16));
    body.addView(text("仅校准授权日志的锁原始值，保留以前保存的映射，不套用ICC的1/2。保存不会启用日志或改变车辆。两种来源都要求新鲜ICC P挡，息屏/休眠后不保证可播放。", 13, INK));
    EditText locked = new EditText(this), unlocked = new EditText(this);
    for (EditText input : new EditText[] {locked, unlocked}) {
      input.setSingleLine(true);
      input.setTextColor(INK);
      input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    }
    body.addView(text("闭锁原始值", 13, INK));
    locked.setContentDescription("日志闭锁原始值");
    locked.setTag("lock.log.locked");
    locked.setText(store.prefs.getString("lock.locked", ""));
    body.addView(locked);
    body.addView(text("解锁原始值", 13, INK));
    unlocked.setContentDescription("日志解锁原始值");
    unlocked.setTag("lock.log.unlocked");
    unlocked.setText(store.prefs.getString("lock.unlocked", ""));
    body.addView(unlocked);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("校准日志锁状态").setView(body)
        .setPositiveButton("保存校准", null).setNegativeButton("取消", null).create();
    dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      try {
        store.calibrateLock(locked.getText().toString().trim(), unlocked.getText().toString().trim());
        dialog.dismiss();
        render();
        toast("已保存日志映射；等待新基线，不补播");
      } catch (IllegalArgumentException invalid) { unlocked.setError(invalid.getMessage()); }
    }));
    showCalibrationDialog(dialog);
  }

  private LinearLayout panel() {
    LinearLayout box = col();
    box.setPadding(dp(18), dp(16), dp(18), dp(16));
    box.setBackground(shape(theme.surface, theme.border, 18));
    margin(content, box, -2, 14);
    return box;
  }

  private Button logSourceButton;

  private void detection() {
    LinearLayout top = panel();
    top.addView(bold("车辆事件监听", 22, INK));
    gap(top, 12);
    diagStatus = text("ICC接口与授权日志分别读取…", 13, INK);
    diagStatus.setLineSpacing(dp(5), 1);
    top.addView(diagStatus);
    gap(top, 14);
    LinearLayout controls = row();
    captureButton =
        button(
            "开始监听",
            true,
            () -> {
              if (service == null) {
                toast("服务正在连接");
                return;
              }
              if (service.running()) service.end();
              else ensureMonitor();
              update();
            });
    weighted(controls, captureButton, 44, 8);
    weighted(controls, button("重新连接", false, () -> {
      if (service == null) { toast("服务正在连接"); return; }
      if (service.running()) service.reconnect();
      else ensureMonitor();
      update();
    }), 44, 8);
    weighted(controls, button("导出记录", false, () -> export()), 44, 0);
    top.addView(controls);
    gap(top, 10);
    LinearLayout options = row();
    logSourceButton = button("来源设置", false, () -> logSource());
    weighted(options, logSourceButton, 40, 8);
    weighted(options, button("更多", false, () -> detectionOptions()), 40, 0);
    top.addView(options);
    gap(top, 10);
    top.addView(text("仅在安全驻车时测试。首样本不播音；0次或报错也请导出。", 12, MUTED));
    LinearLayout values = panel();
    values.addView(bold("车辆状态大屏", 22, INK));
    gap(values, 10);
    LinearLayout dashboardActions = row();
    calibrationButton = button("统一校准", true, () -> dashboardCalibration());
    weighted(dashboardActions, calibrationButton, 44, 0);
    values.addView(dashboardActions);
    calibrationStatus = text("ICC六项固定走接口；门、尾门和AutoHold固定走授权日志，点击卡片核对。", 12, MUTED);
    calibrationStatus.setPadding(0, dp(10), 0, 0);
    values.addView(calibrationStatus);
    gap(values, 12);
    stateDashboard = new StateDashboardView(this, key -> calibrateDashboardState(key));
    values.addView(stateDashboard);
    values.addView(text("绿色为近5秒样本；灰色为未收到/上次状态。车速拟合声浪需单独启用，仅D挡使用近2.5秒数据；倒车、行人循环不自动启用。", 11, MUTED));
    stateDashboard.update(service == null ? new Diagnostics() : service.diagnostics, store,
        service != null && service.running(), System.currentTimeMillis());
    LinearLayout logs = panel();
    logs.addView(bold("近期日志", 17, INK));
    gap(logs, 10);
    rawLog = text("等待日志…", 11, theme.secondaryText);
    rawLog.setTypeface(Typeface.MONOSPACE);
    rawLog.setTextIsSelectable(true);
    rawLog.setLineSpacing(dp(3), 1);
    logs.addView(rawLog);
  }

  @Override public void onBackPressed() {
    if (page == 2) { page = 0; render(); return; }
    if (service != null) service.calibrationMode(false);
    super.onBackPressed();
  }

  private void dashboardCalibration() {
    if (service == null) { toast("服务正在连接"); return; }
    if (service.diagnostics.calibrating) {
      service.calibrationMode(false);
      update();
      return;
    }
    service.calibrationMode(true);
    update();
    List<VehicleStateSpec> available = new ArrayList<>();
    for (VehicleStateSpec spec : VehicleStateSpec.ALL)
      if (!spec.key.equals("handbrake") && !spec.key.equals("power")
          ) available.add(spec);
    String[] names = new String[available.size()];
    for (int index = 0; index < names.length; index++) names[index] = available.get(index).title;
    AlertDialog menu = new AlertDialog.Builder(this).setTitle("统一校准 · 自动音效已暂停")
        .setItems(names, (dialog, which) -> calibrateDashboardState(available.get(which).key))
        .setNeutralButton("ES已知预设", (dialog, which) -> esStatePresetDialog())
        .setNegativeButton("返回大屏", null).create();
    showCalibrationDialog(menu);
  }

  private void esStatePresetDialog() {
    if (service == null) { toast("服务正在连接"); return; }
    service.calibrationMode(true);
    LinearLayout body = col();
    body.setPadding(dp(20), dp(12), dp(20), dp(12));
    body.addView(text("可选应用已知ES车门/尾门日志映射，不代表本车已验证。仅覆盖勾选项，不修改车辆、不启用音效、不生成样本。ICC接口不套用日志预设；AutoHold需单独校准。", 13, INK));
    body.addView(text("未勾选项的手动校准保留；原有ES预设取消勾选则恢复默认解析。", 12, MUTED));
    boolean hasPreset = false;
    for (VehicleStateSpec spec : VehicleStateSpec.ALL) if (store.esStatePreset(spec.key)) hasPreset = true;
    List<android.widget.CheckBox> choices = new ArrayList<>();
    for (VehicleStateSpec spec : VehicleStateSpec.ALL) {
      if (!spec.customMapping() || !VehicleStateSpec.logKey(spec.key)) continue;
      android.widget.CheckBox choice = new android.widget.CheckBox(this);
      choice.setText(spec.title + "\n" + spec.esPresetDescription());
      choice.setTextColor(INK);
      choice.setTag(spec.key);
      choice.setContentDescription("ES预设 " + spec.title);
      choice.setChecked(!hasPreset || store.esStatePreset(spec.key));
      body.addView(choice);
      choices.add(choice);
    }
    TextView error = text("应用后继续留在校准模式；完成时请点击完成校准。", 12, MUTED);
    body.addView(error);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("ES预设 · 已知日志映射")
        .setView(scroll).setPositiveButton("应用所选预设", null)
        .setNeutralButton("移除ES预设", (view, which) -> {
          store.clearEsStatePreset();
          render();
          toast("已移除ES预设，恢复默认解析；手动校准保留");
        }).setNegativeButton("取消", null).create();
    dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      List<String> selected = new ArrayList<>();
      for (android.widget.CheckBox choice : choices)
        if (choice.isChecked()) selected.add((String) choice.getTag());
      try {
        store.applyEsStatePreset(selected.toArray(new String[0]));
        dialog.dismiss();
        render();
        toast("已应用ES预设；等待新日志，请继续对照实车核对");
      } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
    }));
    showCalibrationDialog(dialog);
  }

  private void calibrateDashboardState(String key) {
    if (service == null) { toast("服务正在连接"); return; }
    VehicleStateSpec spec = VehicleStateSpec.find(key);
    if (spec == null) return;
    if (key.equals("lock")) { lockSettings(EventSpec.find("lock.lock")); return; }
    if (key.equals("charge")) {
      iccStateSettings(key);
      return;
    }
    if ((key.equals("handbrake") || key.equals("power") || key.equals("speed"))) {
      new AlertDialog.Builder(this).setTitle("ICC" + spec.title)
          .setMessage(key.equals("speed") ? "本车已实测：ICC原值就是km/h，不再换算。成功回复、挡位有效且车速字段省略时按本车静止0显示，原字段仍记录为MISSING；无回复、失败、过期或挡位未知不会补0。不参与行人/声浪自动循环音。"
              : key.equals("handbrake") ? "原厂协议1=手刹释放、2=手刹拉起，0/其他/缺失不猜测。此字段不是AutoHold，不复用其音效或校准。"
              : "原厂协议1=OFF、2=ACC、3=ON、4=START，0/其他/缺失显示未知。仅展示，不据此判断可行驶、启动状态或执行任何车控。")
          .setPositiveButton("知道了", null).show();
      return;
    }
    if (key.equals("gear")) {
      new AlertDialog.Builder(this).setTitle("ICC协议挡位")
          .setMessage("挡位固定从ICC接口读取，1=P、2=N、3=D、4=R；不套用日志校准，不从日志补位。接口异常立即使状态失效，按1/2/4/8/10秒退避持续自动重试；恢复首样本静默。停止监听或切换/替换接口来源后取消旧请求和重试。")
          .setPositiveButton("知道了", null).show();
      return;
    }
    service.calibrationMode(true);
    update();
    if (key.equals("lock")) { lockCalibration(); return; }
    if (key.equals("autohold")) { autoHoldCalibration(); return; }
    Diagnostics.StateReading reading = service.diagnostics.stateReadings().get(key);
    if (reading != null && IccVehicleWire.gear(reading.sample)) {
      new AlertDialog.Builder(this).setTitle("ICC协议挡位")
          .setMessage("ICC原始值1=P、2=N、3=D、4=R，独立转换为本App音效状态，不套用ES日志预设。已收到实车P/D变化；N/R仍需对照。这里只读，不修改车辆挡位；完成后点击完成校准，恢复首样本静默。")
          .setPositiveButton("返回大屏", null).show();
      return;
    }
    if (reading == null || !Store.knownLogIdentity(reading.sample) || reading.sample.status != 0) {
      new AlertDialog.Builder(this).setTitle("等待" + spec.title + "日志")
          .setMessage("请先开始日志监听，在安全驻车条件下操作并收到本项有效原始值，再校准。不会把未收到当成0，也不会修改车辆状态。")
          .setPositiveButton("返回大屏", null).show();
      return;
    }
    stateCalibrationDialog(spec, reading.sample);
  }

  private void stateCalibrationDialog(VehicleStateSpec spec, Protocol.Sample subject) {
    LinearLayout body = col();
    body.setPadding(dp(20), dp(12), dp(20), dp(12));
    body.addView(text("自动音效已暂停；仅修改本App映射，不控制车辆。每项按实车状态填写，保存不会播放，完成后仍须新样本建立基线。", 13, INK));
    body.addView(text("绑定来源：" + subject.topic + " / 0x" + Long.toHexString(subject.propertyId)
        + " / " + subject.area + "\n开窗时原始值：" + subject.raw + "（可能已旧，不作为实时保证）", 12, MUTED));
    if (store.esStatePreset(spec.key))
      body.addView(text("当前为ES预设；保存本表将替换为仅绑定上述来源的手动校准，预填值仍须实车确认。", 12, MUTED));
    JSONObject configuration = store.stateCalibration(spec.key);
    boolean sameIdentity = subject.topic.equals(configuration.optString("topic"))
        && subject.propertyId == configuration.optLong("propertyId")
        && subject.area.equals(configuration.optString("area"));
    JSONObject mapping = sameIdentity ? configuration.optJSONObject("mapping") : null;
    List<EditText> inputs = new ArrayList<>();
    Spinner units = spinner(java.util.Arrays.asList("原始值为 km/h", "原始值为 m/s（×3.6）"));
    if (spec.key.equals("speed")) {
      units.setSelection(sameIdentity && configuration.optBoolean("metersPerSecond") ? 1 : 0);
      body.addView(text("请选择原始车速单位，不猜单位、不校准偏移。", 13, INK));
      body.addView(units);
    } else for (int index = 0; index < spec.values.length; index++) {
      final int target = index;
      body.addView(text(spec.labels[index] + (spec.key.equals("gear") && index == 4 ? "（可留空）" : "对应原始值"), 13, INK));
      EditText input = new EditText(this);
      input.setTextColor(INK);
      input.setSingleLine(true);
      input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
      input.setContentDescription(spec.title + " " + spec.labels[index] + "原始值");
      String preset = store.esStatePreset(spec.key) ? spec.esPresetRawValues()[index] : "";
      if (mapping != null) {
        java.util.Iterator<String> keys = mapping.keys();
        while (keys.hasNext()) {
          String raw = keys.next();
          if (spec.values[index].equals(mapping.optString(raw))) preset = raw;
        }
      }
      input.setText(preset);
      inputs.add(input);
      LinearLayout field = row();
      weighted(field, input, 48, 8);
      field.addView(button("取当前", false, () -> {
        if (service == null) return;
        Diagnostics.StateReading current = service.diagnostics.stateReadings().get(spec.key);
        long now = System.currentTimeMillis();
        if (current == null || !current.sample.identity().equals(subject.identity())
            || current.sample.status != 0 || !Protocol.lockValue(current.sample.raw)
            || now - current.receivedAt > 5000 || now - current.sample.time > 5000
            || current.sample.time > now + 500 || !service.running()) {
          toast("没有同来源的近5秒有效样本，请等待新日志或手动填写已确认值");
          return;
        }
        inputs.get(target).setText(current.sample.raw);
      }), new LinearLayout.LayoutParams(dp(88), dp(44)));
      body.addView(field);
    }
    android.widget.CheckBox confirmed = new android.widget.CheckBox(this);
    confirmed.setText("已对照实车确认上述含义（不是修改实车状态）");
    confirmed.setTextColor(INK);
    body.addView(confirmed);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("校准" + spec.title).setView(scroll)
        .setPositiveButton("保存校准", null)
        .setNeutralButton("恢复默认", (view, which) -> {
          store.clearStateCalibration(spec.key);
          render();
          toast("已恢复默认；等待新样本建立基线");
        }).setNegativeButton("取消", null).create();
    dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      if (!confirmed.isChecked()) { confirmed.setError("请先对照实车确认"); return; }
      String[] values = new String[inputs.size()];
      for (int index = 0; index < values.length; index++) values[index] = inputs.get(index).getText().toString().trim();
      try {
        store.calibrateState(subject, values, units.getSelectedItemPosition() == 1);
        dialog.dismiss();
        render();
        toast("已保存；可继续校准其他项，完成后点击完成校准");
      } catch (IllegalArgumentException error) {
        confirmed.setError(error.getMessage());
      }
    }));
    showCalibrationDialog(dialog);
  }

  private void showCalibrationDialog(AlertDialog dialog) {
    if (page == 1 && service != null && service.diagnostics.calibrating) {
      activeCalibrationDialog = dialog;
      dialog.setOnDismissListener(view -> {
        if (activeCalibrationDialog == dialog) activeCalibrationDialog = null;
      });
    }
    dialog.show();
  }

  private void detectionOptions() {
    if (service == null) { toast("服务正在连接"); return; }
    String[] items = {"读取详情", "标记当前操作", "日志授权说明", "ADB 诊断 / APK 提取", "清空记录"};
    new AlertDialog.Builder(this).setTitle("更多")
        .setItems(items, (dialog, which) -> {
          if (service == null) return;
          if (which == 0) {
            String details = "接口：挡位、车速(km/h)、手刹、电源、充电" + (store.logLockSelected() ? "\n" : "、中控锁\n")
                + service.diagnostics.iccVehicleSourceSummary()
                + "\n\n日志：四门、尾门、AutoHold" + (store.logLockSelected() ? "、中控锁\n" : "\n") + service.diagnostics.logStatus
                + "\n\n" + service.diagnostics.recordingStatus()
                + "\n两类独立运行、不自动补位。车速仅在本车成功回复且挡位有效时，省略字段按静止0显示；未知、失效不补0。";
            TextView detailView = text(details, 13, INK);
            detailView.setTextIsSelectable(true);
            detailView.setPadding(dp(20), dp(12), dp(20), dp(12));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(detailView);
            new AlertDialog.Builder(this).setTitle("读取详情").setView(scroll)
                .setPositiveButton("关闭", null).show();
          } else if (which == 1) marker();
          else if (which == 2) permissions();
          else if (which == 3) startActivity(new Intent(this, AdbDiagnosticsActivity.class));
          else new AlertDialog.Builder(this).setTitle("清空检测记录？")
              .setMessage("清空日志、候选和操作标记，不删除音源或设置。需要的记录请先导出。")
              .setNegativeButton("取消", null)
              .setPositiveButton("清空", (confirm, selected) -> {
                if (service != null) service.diagnostics.clear();
                update();
              }).show();
        }).setNegativeButton("关闭", null).show();
  }

  private void logSource() {
    if (service == null) { toast("服务正在连接"); return; }
    new AlertDialog.Builder(this).setTitle("接口与授权日志")
        .setItems(new String[] {"ICC接口：查询间隔", service.logsEnabled() ? "授权日志：已启用（点击关闭）" : "授权日志：未启用（点击授权）", "日志授权说明"},
            (dialog, which) -> {
              if (which == 0) iccSourceSettings();
              else if (which == 2) permissions();
              else if (service != null && service.logsEnabled()) {
                service.logsEnabled(false);
                update();
              } else new AlertDialog.Builder(this).setTitle("启用授权日志补充？")
                  .setMessage("为四门、尾门、AutoHold及手动选中的日志锁事件读取系统实时日志；其余状态走ICC，不自动切换。\n\n请自行在应用信息中授予读取系统日志权限，并同意可能出现的系统日志确认。没有数据不当成关闭，授权不代表本车一定输出这些事件。\n\n启用后，开始监听会发起日志请求；断开不反复申请，需手动重新连接。可随时关闭，ICC不受影响。")
                  .setNegativeButton("取消", null)
                  .setNeutralButton("授权说明", (confirm, selected) -> permissions())
                  .setPositiveButton("我同意，启用日志", (confirm, selected) -> {
                    if (service != null) service.logsEnabled(true);
                    update();
                  }).show();
            }).setNegativeButton("关闭", null).show();
  }

  private void iccSourceSettings() {
    int[] intervals = {250, 500, 1000};
    int[] selected = {store.iccIntervalMillis()};
    LinearLayout body = col();
    body.setPadding(dp(20), dp(12), dp(20), dp(12));
    body.addView(text("ICC接口默认启用，只查询、不控制车辆。打开应用自动监听六项车况；中控锁默认启用P挡车外提示，充电仍需核对后开启，首样本/重连/未知恢复不播音。", 13, INK));
    android.widget.RadioGroup choices = new android.widget.RadioGroup(this);
    String[] labels = {"较快：完成后等250 ms（默认）", "适中：完成后等500 ms", "保守：完成后等1000 ms"};
    for (int index = 0; index < intervals.length; index++) {
      android.widget.RadioButton choice = new android.widget.RadioButton(this);
      choice.setId(intervals[index]);
      choice.setTag("icc.interval." + intervals[index]);
      choice.setText(labels[index]);
      choices.addView(choice);
    }
    choices.check(selected[0]);
    choices.setOnCheckedChangeListener((group, id) -> selected[0] = id);
    body.addView(choices);
    body.addView(text("正常间隔=请求耗时+所选等待，串行不重叠。部分查询约1.2秒，缩短等待不能消除上游延迟。超时、网络/HTTP/报文失败后按1/2/4/8/10秒退避持续重试，不再永久停查；恢复后回到所选间隔，首样本静默，不补播。停止监听或替换ICC来源取消旧重试。\n\n保持服务运行不等于阻止整机休眠；系统挂起时不能执行请求，恢复调度后继续。无需日志/存储授权，不自动重新申请日志。手刹不是AutoHold；本车车速原值即km/h，持续循环音不自动开启。", 12, MUTED));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    new AlertDialog.Builder(this).setTitle("ICC查询间隔").setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (dialog, which) -> {
          store.iccIntervalMillis(selected[0]);
          update();
        }).show();
  }

  private void iccStateSettings(String key) {
    if (!key.equals("lock") && !key.equals("charge")) return;
    boolean lock = key.equals("lock");
    if (lock) {
      new AlertDialog.Builder(this).setTitle("中控锁 · P挡车外提示")
          .setMessage((store.logLockSelected() ? "当前使用授权日志锁事件，原始值按日志锁校准解释；请另行启用并授权日志。" : "当前使用ICC锁事件，协议1=解锁、2=闭锁，无需二次确认。")
              + "自动锁声默认启用，可在首页关闭。锁设置中可手动切换ICC/授权日志，不自动补位。\n\n两种来源都先建立新鲜ICC P挡与锁状态基线，之后中控锁变化才播放；D/R/N、未知或过期挡位均不播放，也不在回到P挡后补播。离开P挡立即取消待播与正在播放的自动锁声。\n\n默认匹配原厂BUS2007_VENDOR_CONTEXT2车外总线，播放和焦点使用usage2007；播放器默认100%增益，沿用该组系统音量，不擅自拉满全车音量。通道或usage不可用就停止，不回退车内，不切换全局outside_mode。息屏后是否仍有P挡、锁事件及车外输出，需导出检测确认。")
          .setPositiveButton("关闭", null).show();
      return;
    }
    LinearLayout body = col();
    body.setPadding(dp(20), dp(12), dp(20), dp(12));
    body.addView(text("ICC充电协议：1=未充电、2=待机、3=慢充、4=快充。仅3/4算充电中，1/2算未充电；快慢切换不重复播开始。先对照真实充电过程核对，再允许触发；仍需开启充电提示音。", 13, INK));
    Diagnostics.StateReading reading = service == null ? null : service.diagnostics.stateReadings().get(key);
    body.addView(text(reading != null && IccVehicleWire.state(reading.sample)
        ? "最近回复：" + IccVehicleWire.stateLabel(reading.sample) + " · raw=" + reading.sample.raw + "（历史回复，不保证当前）"
        : "尚无本来源样本，不能把缺失/0当作真实状态。", 12, MUTED));
    android.widget.CheckBox confirmed = new android.widget.CheckBox(this);
    confirmed.setTag("icc.confirm." + key);
    confirmed.setText("我已对照实车核对ICC充电状态，允许本来源音效触发");
    confirmed.setChecked(store.iccTriggerConfirmed(key));
    body.addView(confirmed);
    body.addView(text("默认仅展示。保存/撤销确认会取消旧查询及待播提示，恢复首样本静默；校准模式中仍不自动播放。不修改车辆状态或原厂配置。", 12, MUTED));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    new AlertDialog.Builder(this).setTitle("ICC充电状态核对").setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", (dialog, which) -> {
          store.iccTriggerConfirmed(key, confirmed.isChecked());
          update();
        }).show();
  }

  private void permissions() {
    new AlertDialog.Builder(this)
        .setTitle("日志授权说明")
        .setMessage("ICC接口不需要日志或存储授权，负责挡位、车速、手刹、电源、充电及默认的中控锁。锁可在解闭锁设置中手动改为授权日志。\n\n四门、尾门、AutoHold及显式选中的日志锁事件需要您在来源设置中自行启用日志，并在系统应用信息中授予本应用读取系统日志权限。Android可能另行弹出日志访问确认，请自行同意；应用不会自动授予或绕过授权。\n\n授权不保证本车输出所有事件。日志断开后不会自动反复请求；关闭日志不影响ICC。已移除文件、Carota和无效车辆探针。")
        .setPositiveButton(
            "打开应用信息",
            (d, w) -> {
              try {
                startActivity(
                    new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
              } catch (Exception e) {
                toast("请从应用管家打开本应用");
              }
            })
        .setNeutralButton(
            "复制包名",
            (d, w) -> {
              ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
              cm.setPrimaryClip(ClipData.newPlainText("package", getPackageName()));
              toast("已复制包名");
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  private void marker() {
    if (service == null) return;
    String[] labels = {
      "主驾开 / 关门",
      "副驾开 / 关门",
      "后排开 / 关门",
      "切换挡位",
      "实车挡位 P（驻车）",
      "实车挡位 R（倒车）",
      "实车挡位 N（空挡）",
      "实车挡位 D（前进）",
      "尾门开 / 关",
      "车辆解锁",
      "车辆闭锁",
      "插入充电枪",
      "开始充电",
      "停止充电 / 拔枪",
      "车窗变化",
      "灯光变化",
      "安全带变化"
    };
    new AlertDialog.Builder(this)
        .setTitle("记录刚才或即将执行的操作")
        .setItems(
            labels,
            (d, at) -> {
              service.diagnostics.marker(labels[at]);
              toast("已标记：" + labels[at]);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void update() {
    if (service == null) return;
    Diagnostics d = service.diagnostics;
    if (engineSettings != null) engineSettings.update(d);
    if (monitorSwitch != null) {
      updatingSwitch = true;
      monitorSwitch.setChecked(d.monitoring);
      updatingSwitch = false;
    }
    if (page != 1 || diagStatus == null) return;
    diagStatus.setText("ICC接口\n" + d.iccVehicleSourceSummary() + "\n\n授权日志\n" + d.logStatus);
    if (logSourceButton != null) logSourceButton.setText(service.logsEnabled() ? "接口 + 授权日志" : "接口 + 日志未启用");
    captureButton.setText(service.running() ? "停止监听" : "开始监听");
    if (stateDashboard != null) stateDashboard.update(d, store, service.running(), System.currentTimeMillis());
    if (calibrationButton != null) calibrationButton.setText(d.calibrating ? "完成校准" : "统一校准");
    if (calibrationStatus != null) {
      calibrationStatus.setText(d.calibrating
          ? "校准中：自动音效暂停；点击各卡片依次校准，完成后重新建立基线。"
          : "接口六项与日志补充分开；日志仅负责门、尾门和AutoHold，ES预设仅用于日志。");
      calibrationStatus.setTextColor(d.calibrating ? theme.warning : MUTED);
    }
    if (rawLog == null) return;
    String all = d.lines("");
    String[] logLines = all.split("\n");
    StringBuilder tail = new StringBuilder();
    for (int i = Math.max(0, logLines.length - 12); i < logLines.length; i++)
      tail.append(logLines[i]).append('\n');
    rawLog.setText(tail.length() > 1 ? tail.toString() : "暂无日志");
  }

  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          update();
          ui.postDelayed(this, 1000);
        }
      };

  private void export() {
    if (service == null) return;
    try {
      String data = service.diagnostics.export(service.readLogsGranted(), store);
      pendingExport = new File(getCacheDir(), "vehicle-detection-export.json");
      try (FileOutputStream out = new FileOutputStream(pendingExport)) {
        out.write(data.getBytes(StandardCharsets.UTF_8));
      }
      String name =
          "ES-检测-"
              + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date())
              + ".json";
      startActivityForResult(
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("application/json")
              .putExtra(Intent.EXTRA_TITLE, name),
          302);
    } catch (Exception e) {
      toast("无法导出记录：" + e.getMessage());
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 301) {
      EventSpec spec = EventSpec.find(pendingImport);
      pendingImport = null;
      if (spec == null) {
        toast("没有找到待导入的事件，请重新选择");
        return;
      }
      toast("正在导入音频…");
      files.execute(() -> copyAudio(uri, spec));
    } else if (request == 302) {
      File source = pendingExport;
      if (source == null || !source.isFile()) {
        toast("导出数据已失效，请重新导出");
        return;
      }
      files.execute(
          () -> {
            try (InputStream in = new java.io.FileInputStream(source);
                OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IllegalStateException("无法打开导出位置");
              byte[] buffer = new byte[16384];
              int n;
              while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
              runOnUiThread(() -> toast("检测记录已导出"));
            } catch (Exception e) {
              runOnUiThread(() -> toast("导出失败：" + e.getMessage()));
            }
          });
    }
  }

  private void copyAudio(Uri uri, EventSpec spec) {
    File file = null;
    try {
      String name = "自选音频";
      try (android.database.Cursor cursor =
          getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
      }
      File dir = new File(getFilesDir(), "audio");
      if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法建立音频目录");
      file = File.createTempFile("sound-", ".audio", dir);
      try (InputStream in = getContentResolver().openInputStream(uri);
          OutputStream out = new FileOutputStream(file)) {
        if (in == null) throw new IllegalStateException("无法读取选中的音频");
        byte[] buffer = new byte[16384];
        int n;
        long total = 0;
        while ((n = in.read(buffer)) != -1) {
          total += n;
          if (total > 20L * 1024 * 1024) throw new IllegalArgumentException("音频不能超过 20 MiB");
          out.write(buffer, 0, n);
        }
        if (total == 0) throw new IllegalArgumentException("音频文件为空");
      }
      MediaMetadataRetriever meta = new MediaMetadataRetriever();
      try {
        meta.setDataSource(file.getAbsolutePath());
        String duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (duration == null || Long.parseLong(duration) <= 0)
          throw new IllegalArgumentException("系统无法识别该音频");
        if (spec.group.equals("engine") && Long.parseLong(duration) > 30000)
          throw new IllegalArgumentException("请为声浪选择30秒以内的循环素材");
      } finally {
        meta.release();
      }
      if (spec.group.equals("engine")) EngineDecoder.decode(this, "file:" + file.getAbsolutePath(), () -> isDestroyed());
      store.file(spec, file, name);
      runOnUiThread(
          () -> {
            if (!isDestroyed()) {
              render();
              toast("音频已保存，可点击试听");
            }
          });
    } catch (Exception e) {
      if (file != null) file.delete();
      runOnUiThread(
          () -> {
            if (!isDestroyed())
              new AlertDialog.Builder(this)
                  .setTitle("音频导入失败")
                  .setMessage(e.getMessage())
                  .setPositiveButton("关闭", null)
                  .show();
          });
    }
  }
}
