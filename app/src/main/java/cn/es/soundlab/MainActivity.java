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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int BLUE = 0xff2475f5, INK = 0xff203553, MUTED = 0xff72839a, BG = 0xfff3f7fc;
  private final Handler ui = new Handler();
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private Store store;
  private MonitorService service;
  private boolean bound, resumed, updatingSwitch;
  private int page;
  private LinearLayout root, content, candidateList;
  private TextView status, diagStatus, signals, rawLog;
  private Switch monitorSwitch;
  private Button captureButton;
  private EditText filter;
  private String pendingImport;
  private File pendingExport;
  private long lastRevision = -1;
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
    store = new Store(this);
    if (state != null) {
      page = state.getInt("page", 0);
      pendingImport = state.getString("import");
      String export = state.getString("export");
      if (export != null) pendingExport = new File(export);
    }
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    render();
    bound = bindService(new Intent(this, MonitorService.class), connection, BIND_AUTO_CREATE);
    if (store.prefs.getBoolean("monitor", false))
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
    resumed = false;
    ui.removeCallbacks(ticker);
    if (service != null) service.stopMicrophone();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (service != null) service.stopMicrophone();
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
    b.setText(label);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setTextColor(primary ? Color.WHITE : BLUE);
    b.setPadding(dp(14), dp(10), dp(14), dp(10));
    b.setBackground(shape(primary ? BLUE : 0xfffafdff, primary ? 0 : 0xffdce7f5, 12));
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
    lastRevision = -1;
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
    ArtView hero = new ArtView(this, "hero", BLUE);
    add(content, hero, 154);
    gap(content, 18);
    LinearLayout nav = row();
    tabs.clear();
    String[] names = {"音效设置", "车辆检测", "规则管理"};
    for (int i = 0; i < 3; i++) {
      final int index = i;
      TextView tab = bold(names[i], 16, page == i ? Color.WHITE : MUTED);
      tab.setPadding(dp(20), dp(12), dp(20), dp(12));
      tab.setGravity(Gravity.CENTER);
      tab.setBackground(shape(page == i ? BLUE : 0xffeaf0f8, 0, 12));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
      p.rightMargin = dp(i == 2 ? 0 : 8);
      nav.addView(tab, p);
      tab.setOnClickListener(
          v -> {
            page = index;
            render();
          });
      tabs.add(tab);
    }
    add(content, nav, 46);
    gap(content, 18);
    diagStatus = null;
    signals = null;
    rawLog = null;
    filter = null;
    candidateList = null;
    captureButton = null;
    monitorSwitch = null;
    if (page == 0) home();
    else if (page == 1) detection();
    else rules();
    LinearLayout footer = row();
    footer.setPadding(dp(20), dp(9), dp(20), dp(9));
    footer.setBackgroundColor(Color.WHITE);
    status = text("正在连接本地服务…", 12, MUTED);
    status.setMaxLines(2);
    footer.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
    Button stop =
        button(
            "■ 停止播放",
            false,
            () -> {
              if (service != null) service.stopAudio();
            });
    footer.addView(stop, new LinearLayout.LayoutParams(dp(124), dp(40)));
    add(root, footer, 62);
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
            new int[] {BLUE, 0xffb4c3d8}));
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
    LinearLayout links = row();
    Button output = button("♬ 音频输出", false, () -> output());
    weighted(links, output, 42, 8);
    weighted(
        links,
        button(
            "查看实车信号 →",
            false,
            () -> {
              page = 1;
              render();
            }),
        42,
        0);
    add(content, links, 42);
    gap(content, 16);
    List<String> groups = new ArrayList<>();
    Collections.addAll(
        groups,
        "gear",
        "door",
        "lock",
        "engine",
        "reverse",
        "shout",
        "charge",
        "tailgate",
        "pedestrian");
    if (!EventSpec.group("custom").isEmpty()) groups.add("custom");
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
    TextView note = text("解闭锁、充电及更多事件，可在“车辆检测”中确认信号后绑定音效。车外喊话的输出通道需要在实车上选择。", 13, MUTED);
    note.setLineSpacing(dp(4), 1);
    add(content, note, -2);
  }

  private String description(String g) {
    switch (g) {
      case "gear":
        return "换挡时播放，优先打断当前提示音";
      case "door":
        return "四个车门，开门与关门分别设置";
      case "lock":
        return "检测并绑定实车解锁、闭锁事件";
      case "engine":
        return "随已读取的车速变化播放声浪";
      case "reverse":
        return "进入 R 挡播放，退出后停止";
      case "shout":
        return "播放语音，或按住按钮实时喊话";
      case "charge":
        return "绑定实车充电开始、结束信号";
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
        return 0xffdf963e;
      case "lock":
      case "tailgate":
        return 0xff35aa91;
      case "engine":
        return 0xff9a73dd;
      case "shout":
        return 0xffcd71bf;
      case "charge":
      case "pedestrian":
        return 0xff36a798;
      default:
        return BLUE;
    }
  }

  private int pastel(String g) {
    switch (g) {
      case "door":
        return 0xfffff3e4;
      case "lock":
      case "tailgate":
        return 0xffe7f7f1;
      case "engine":
        return 0xfff0eafb;
      case "shout":
        return 0xfff9eaf6;
      case "charge":
      case "pedestrian":
        return 0xffe6f6f2;
      default:
        return 0xffe7f2ff;
    }
  }

  private View card(String group) {
    LinearLayout card = col();
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    GradientDrawable bg =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {pastel(group), 0xfffbfdff});
    bg.setCornerRadius(dp(20));
    bg.setStroke(dp(1), 0xffe5edf8);
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
        (group.equals("lock") || group.equals("charge") || group.equals("custom"))
            ? "待检测 / 可绑定规则"
            : group.equals("shout") ? "手动播放 · 输出待确认" : "已接入 · 待实车验证";
    TextView badgeView = text(badge, 10, accent(group));
    titles.addView(badgeView);
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    Switch enabled = new Switch(this);
    enabled.setContentDescription(EventSpec.groupName(group) + "开关");
    enabled.setChecked(store.enabled(group));
    enabled.setThumbTintList(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
            new int[] {BLUE, 0xffbbc9db}));
    enabled.setOnCheckedChangeListener(
        (b, on) -> {
          store.enable(group, on);
          if (on && !group.equals("shout")) ensureMonitor();
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
    picker.setBackground(shape(0x99ffffff, 0xffdbe7f6, 10));
    picker.setContentDescription(EventSpec.groupName(group) + "事件选择");
    add(card, picker, 40);
    gap(card, 9);
    LinearLayout fileRow = row();
    TextView name = text(store.audioName(events.get(selected)), 13, INK);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    name.setPadding(dp(11), 0, dp(5), 0);
    name.setBackground(shape(0xbbffffff, 0xffdbe7f6, 10));
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
    seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
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
              group.equals("pedestrian") ? "车速上限" : "更多设置",
              false,
              () -> {
                if (group.equals("pedestrian")) speedThreshold();
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
            t.setBackgroundColor(dropdown ? Color.WHITE : Color.TRANSPARENT);
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

  private void output() {
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
        .setTitle("音效与喊话输出通道")
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
        .setNegativeButton("关闭", null)
        .show();
  }

  private LinearLayout panel() {
    LinearLayout box = col();
    box.setPadding(dp(18), dp(16), dp(18), dp(16));
    box.setBackground(shape(Color.WHITE, 0xffe0e9f5, 18));
    margin(content, box, -2, 14);
    return box;
  }

  private void detection() {
    LinearLayout top = panel();
    top.addView(bold("发现你的 ES 能提供哪些事件", 22, INK));
    gap(top, 9);
    TextView intro = text("开始检测后，依次操作车门、挡位、尾门、解闭锁和充电。用操作标记记录时间，点击候选信号可绑定新的音效。", 13, MUTED);
    intro.setLineSpacing(dp(5), 1);
    top.addView(intro);
    gap(top, 15);
    diagStatus = text("正在读取权限与接口状态…", 13, INK);
    diagStatus.setLineSpacing(dp(5), 1);
    top.addView(diagStatus);
    gap(top, 14);
    LinearLayout controls = row();
    captureButton =
        button(
            "开始扩展检测",
            true,
            () -> {
              if (service == null) {
                toast("服务正在连接");
                return;
              }
              service.discovery(!service.diagnostics.scanning);
              update();
            });
    weighted(controls, captureButton, 44, 8);
    weighted(controls, button("标记当前操作", false, () -> marker()), 44, 8);
    weighted(controls, button("导出记录", false, () -> export()), 44, 0);
    top.addView(controls);
    gap(top, 10);
    LinearLayout extra = row();
    weighted(extra, button("权限说明", false, () -> permissions()), 40, 8);
    weighted(
        extra,
        button(
            "重新连接",
            false,
            () -> {
              if (service != null) service.reconnect();
            }),
        40,
        8);
    weighted(
        extra,
        button(
            "清空记录",
            false,
            () -> {
              if (service != null) {
                service.diagnostics.clear();
                update();
              }
            }),
        40,
        0);
    top.addView(extra);
    LinearLayout values = panel();
    values.addView(bold("已知车辆状态", 17, INK));
    gap(values, 12);
    signals = text("暂无实车数据。开启检测后，首次状态仅作为基线。", 14, MUTED);
    signals.setLineSpacing(dp(6), 1);
    values.addView(signals);
    LinearLayout candidates = panel();
    candidates.addView(bold("候选信号与属性", 17, INK));
    gap(candidates, 6);
    candidates.addView(text("显示最近的 40 项；完整记录随导出文件保存。点击条目查看或绑定。", 11, MUTED));
    gap(candidates, 12);
    candidateList = col();
    candidates.addView(candidateList);
    LinearLayout logs = panel();
    logs.addView(bold("实时日志", 17, INK));
    gap(logs, 10);
    filter = new EditText(this);
    filter.setTextColor(INK);
    filter.setHintTextColor(MUTED);
    filter.setSingleLine(true);
    filter.setTextSize(13);
    filter.setHint("筛选，例如 Lock / Charge / VehicleDoor");
    filter.setPadding(dp(12), dp(8), dp(12), dp(8));
    filter.setBackground(shape(0xfff4f7fb, 0xffe0e9f5, 10));
    add(logs, filter, 42);
    gap(logs, 12);
    rawLog = text("等待日志…", 11, 0xff60718c);
    rawLog.setTypeface(Typeface.MONOSPACE);
    rawLog.setTextIsSelectable(true);
    rawLog.setLineSpacing(dp(3), 1);
    logs.addView(rawLog);
  }

  private void permissions() {
    new AlertDialog.Builder(this)
        .setTitle("给独立 App 授予日志权限")
        .setMessage(
            "请在应用管家中，为“ES 音效实验室”开启读取系统日志权限。\n\n"
                + "新应用包名：cn.es.soundlab\n\n"
                + "原雨涵应用已有的授权不会自动授予本应用。授权后返回并点击“重新连接”。车辆属性接口若被系统限制，检测页会显示实际结果。")
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

  private void rules() {
    LinearLayout intro = panel();
    intro.addView(bold("让新事件拥有自己的声音", 22, INK));
    gap(intro, 10);
    intro.addView(text("在车辆检测页观察状态变化，选中对应信号，将实际出现的值绑定到音效。规则只响应绑定后的变化，首次读取不会播放。", 14, MUTED));
    gap(intro, 15);
    intro.addView(
        button(
            "前往车辆检测 →",
            true,
            () -> {
              page = 1;
              render();
            }));
    JSONArray a = store.rules();
    if (a.length() == 0) {
      LinearLayout empty = panel();
      empty.addView(text("还没有扩展规则。\n解闭锁、充电及自定义音效可从实车检测结果中添加。", 14, MUTED));
      return;
    }
    for (int at = 0; at < a.length(); at++) {
      final int index = at;
      JSONObject r = a.optJSONObject(at);
      if (r == null) continue;
      LinearLayout box = panel();
      EventSpec event = EventSpec.find(r.optString("event"));
      box.addView(bold(event == null ? r.optString("event") : event.title, 18, INK));
      gap(box, 8);
      TextView detail =
          text(
              r.optString("source")
                  + " · "
                  + r.optString("topic")
                  + "\n0x"
                  + Long.toHexString(r.optLong("propertyId"))
                  + " · area "
                  + r.optString("area")
                  + "\n变化后等于 "
                  + r.optString("value")
                  + " 时播放",
              12,
              MUTED);
      detail.setLineSpacing(dp(4), 1);
      box.addView(detail);
      gap(box, 12);
      LinearLayout actions = row();
      if (event != null) weighted(actions, button("设置音频", false, () -> editEvent(event)), 40, 8);
      weighted(
          actions,
          button(
              "删除规则",
              false,
              () ->
                  new AlertDialog.Builder(this)
                      .setMessage("删除这条扩展规则？")
                      .setPositiveButton(
                          "删除",
                          (d, w) -> {
                            store.deleteRule(index);
                            render();
                          })
                      .setNegativeButton("取消", null)
                      .show()),
          40,
          0);
      box.addView(actions);
    }
  }

  private void candidate(Diagnostics.Candidate c) {
    Protocol.Sample s = c.sample;
    String details =
        "来源："
            + s.source
            + "\n主题："
            + s.topic
            + "\n属性：0x"
            + Long.toHexString(s.propertyId)
            + "\n区域："
            + s.area
            + "\n状态："
            + s.status
            + "\n当前值："
            + s.raw
            + "\n已见值："
            + c.values
            + "\n上报 "
            + c.count
            + " 次，变化 "
            + c.changes
            + " 次\n\n"
            + (s.key == null ? "尚未预设事件含义，请结合实车操作确认。" : "已知映射：" + signalName(s.key));
    new AlertDialog.Builder(this)
        .setTitle("信号详情")
        .setMessage(details)
        .setPositiveButton("绑定音效", (d, w) -> bindRule(c))
        .setNegativeButton("关闭", null)
        .show();
  }

  private void bindRule(Diagnostics.Candidate candidate) {
    Protocol.Sample sample = candidate.sample;
    if (sample.status != 0) {
      toast("该信号当前状态无效，请等待有效值后再绑定");
      return;
    }
    LinearLayout body = col();
    body.setPadding(dp(22), dp(8), dp(22), dp(16));
    body.addView(text(sample.topic + "\n当前值：" + sample.raw, 12, MUTED));
    gap(body, 12);
    EditText match = new EditText(this);
    match.setTextColor(INK);
    match.setHintTextColor(MUTED);
    match.setSingleLine(true);
    match.setText(sample.raw);
    match.setHint("状态变化后等于这个值时播放");
    body.addView(match);
    gap(body, 10);
    List<EventSpec> events = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (EventSpec e : EventSpec.ALL)
      if (!e.id.endsWith(".loop") && !e.group.equals("shout")) {
        events.add(e);
        labels.add(e.title);
      }
    Spinner target = spinner(labels);
    for (int i = 0; i < events.size(); i++)
      if (events.get(i).id.equals("lock.unlock")) {
        target.setSelection(i);
        break;
      }
    body.addView(text("绑定已有音效", 13, INK));
    body.addView(target);
    gap(body, 12);
    EditText custom = new EditText(this);
    custom.setTextColor(INK);
    custom.setHintTextColor(MUTED);
    custom.setSingleLine(true);
    custom.setHint("或新建音效，例如：迎宾灯亮起（可选）");
    custom.setTextSize(13);
    body.addView(custom);
    TextView note = text("以检测到的主题、属性和区域精确匹配。保存后会启用该组音效；仍需再次操作车辆来验证。", 12, MUTED);
    note.setPadding(0, dp(12), 0, 0);
    body.addView(note);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("将实车信号绑定到音效")
            .setView(body)
            .setPositiveButton("绑定并启用", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String value = match.getText().toString().trim(),
                          name = custom.getText().toString().trim();
                      if (value.isEmpty() || value.length() > 128) {
                        match.setError("请输入 1–128 个字符的实际值");
                        return;
                      }
                      if (name.length() > 30) {
                        custom.setError("名称最多 30 个字");
                        return;
                      }
                      try {
                        EventSpec e =
                            name.isEmpty()
                                ? events.get(target.getSelectedItemPosition())
                                : store.custom(name);
                        JSONObject r =
                            new JSONObject()
                                .put("id", UUID.randomUUID().toString())
                                .put("source", sample.source)
                                .put("topic", sample.topic)
                                .put("propertyId", sample.propertyId)
                                .put("area", sample.area)
                                .put("value", value)
                                .put("event", e.id)
                                .put("name", e.title)
                                .put("createdAt", System.currentTimeMillis())
                                .put("confirmation", "user-observed");
                        store.addRule(r);
                        store.enable(e.group, true);
                        ensureMonitor();
                        dialog.dismiss();
                        toast("规则已保存，等待下一次真实变化");
                        editEvent(e);
                      } catch (Exception ex) {
                        toast("保存规则失败：" + ex.getMessage());
                      }
                    }));
    dialog.show();
  }

  private String signalName(String key) {
    if (key.equals("gear")) return "挡位";
    if (key.equals("speed")) return "车速";
    if (key.equals("tailgate")) return "后尾门";
    for (int i = 0; i < 4; i++)
      if (key.equals("door." + Protocol.DOORS[i]))
        return new String[] {"主驾车门", "副驾车门", "左后车门", "右后车门"}[i];
    return key;
  }

  private String signalValue(String key, String value) {
    if (key.equals("gear")) {
      int n = (int) Protocol.number(value);
      return n >= 1 && n <= 5 ? new String[] {"P 挡", "R 挡", "N 挡", "D 挡", "M 挡"}[n - 1] : value;
    }
    if (key.equals("speed"))
      return String.format(Locale.CHINA, "%.1f km/h", Protocol.number(value));
    if (value.equals("0")) return "关闭";
    if (value.equals("1")) return "开启";
    return value;
  }

  private void update() {
    if (status == null) return;
    if (service == null) {
      status.setText("本地服务连接中 · 星纪元 ES");
      return;
    }
    Diagnostics d = service.diagnostics;
    status.setText(
        (d.monitoring ? "● 监听中" : "○ 监听已停止")
            + "  ·  "
            + d.playing
            + (d.scanning ? "  ·  扩展检测中" : ""));
    if (monitorSwitch != null) {
      updatingSwitch = true;
      monitorSwitch.setChecked(d.monitoring);
      updatingSwitch = false;
    }
    if (page != 1 || diagStatus == null) return;
    diagStatus.setText(
        (service.readLogsGranted() ? "● 日志权限已授予" : "○ 日志权限未授予")
            + "\n"
            + d.nativeStatus
            + "\n"
            + d.logStatus);
    captureButton.setText(d.scanning ? "结束扩展检测" : "开始扩展检测");
    StringBuilder live = new StringBuilder();
    for (Map.Entry<String, Diagnostics.Live> e : d.live().entrySet()) {
      Diagnostics.Live v = e.getValue();
      live.append(signalName(v.key))
          .append("   ")
          .append(signalValue(v.key, v.value))
          .append("    · ")
          .append(v.source)
          .append("\n");
    }
    signals.setText(live.length() == 0 ? "暂无实车数据。首次状态只建立基线，不触发音效。" : live.toString().trim());
    String f = filter == null ? "" : filter.getText().toString();
    String all = d.lines(f);
    String[] logLines = all.split("\n");
    StringBuilder tail = new StringBuilder();
    for (int i = Math.max(0, logLines.length - 35); i < logLines.length; i++)
      tail.append(logLines[i]).append('\n');
    rawLog.setText(tail.length() > 1 ? tail.toString() : "暂无匹配日志");
    if (lastRevision == d.revision) return;
    lastRevision = d.revision;
    candidateList.removeAllViews();
    List<Diagnostics.Candidate> list = d.candidates();
    Collections.sort(list, (a, b) -> Long.compare(b.sample.time, a.sample.time));
    if (list.isEmpty()) {
      candidateList.addView(text("尚未发现信号。日志权限已授予后，开始检测并操作车辆。", 13, MUTED));
      return;
    }
    for (int i = 0; i < Math.min(40, list.size()); i++) {
      Diagnostics.Candidate c = list.get(i);
      LinearLayout line = col();
      line.setPadding(dp(12), dp(11), dp(12), dp(11));
      line.setBackground(shape(c.sample.key == null ? 0xfff6f8fc : 0xffedf6ff, 0, 10));
      TextView title =
          bold(c.sample.key == null ? c.sample.topic : signalName(c.sample.key), 14, INK);
      title.setMaxLines(2);
      line.addView(title);
      gap(line, 5);
      TextView detail =
          text(
              c.sample.raw
                  + "  ·  "
                  + c.sample.source
                  + "  ·  变化 "
                  + c.changes
                  + " 次"
                  + (c.sample.key == null ? "  ·  未映射" : "  ·  已知事件"),
              11,
              MUTED);
      detail.setMaxLines(2);
      line.addView(detail);
      line.setOnClickListener(v -> candidate(c));
      margin(candidateList, line, -2, 7);
    }
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
      } finally {
        meta.release();
      }
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
