package cn.es.soundlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** User-operated authenticated TCP ADB diagnostics; never starts from vehicle events. */
public final class AdbDiagnosticsActivity extends Activity {
  private UiTheme theme;
  private int INK, BLUE, BG;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private EditText host, port, packageNames;
  private TextView status, selectionStatus;
  private Button collect, scanPackages, choosePackages;
  private final Set<String> selectedPackages = new LinkedHashSet<>();
  private List<String> installedPackages = new ArrayList<>();
  private CheckBox includeApks, includeInterfaceApks;
  private String pendingReport, pendingArchive;
  private volatile AdbClient active;
  private volatile boolean destroyed;
  private boolean collecting, exporting;
  private volatile boolean exportCancelled;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    theme = new UiTheme(this);
    INK = theme.text;
    BLUE = theme.accent;
    BG = theme.background;
    theme.systemBars(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int padding = dp(20);
    content.setPadding(padding, padding, padding, padding);
    content.setBackgroundColor(BG);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    setContentView(scroll);
    text(content, "ADB 只读诊断", 24);
    text(
        content,
        "采集车辆调试属性、网络ADB状态、版本、权限与日志；可按包名或扫描多选提取APK。不接入音效、不授权、不写车辆属性。",
        15);
    text(content, "连接本机通常使用127.0.0.1；端口请按实际配置填写。本页暂不支持需要配对码的无线调试TLS端口。", 14);
    android.content.SharedPreferences prefs = getSharedPreferences("adb_diagnostics", 0);
    host = new EditText(this);
    host.setSingleLine(true);
    host.setHint("ADB 地址，如127.0.0.1");
    host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    styleInput(host);
    host.setText(
        saved == null
            ? prefs.getString("host", "127.0.0.1")
            : saved.getString("host", "127.0.0.1"));
    content.addView(host);
    port = new EditText(this);
    port.setSingleLine(true);
    port.setHint("工程模式实际 ADB 端口");
    port.setInputType(InputType.TYPE_CLASS_NUMBER);
    styleInput(port);
    port.setText(saved == null ? prefs.getString("port", "") : saved.getString("port", ""));
    content.addView(port);
    includeInterfaceApks = new CheckBox(this);
    includeInterfaceApks.setText("提取开放接口分析四包（生态中心 / 场景引擎 / 桌面 / 场景模式）");
    includeInterfaceApks.setTextColor(INK);
    includeInterfaceApks.setButtonTintList(ColorStateList.valueOf(BLUE));
    includeInterfaceApks.setChecked(saved != null && saved.getBoolean("includeInterfaceApks", false));
    content.addView(includeInterfaceApks);
    text(content, "com.mega.ecology\ncom.iflytek.autofly.sceneengine\ncom.android.launcher3\ncom.lion.scenemode", 14);
    includeApks = new CheckBox(this);
    includeApks.setText("同时提取车辆服务和 SystemUI APK（完成后另行导出ZIP）");
    includeApks.setTextColor(INK);
    includeApks.setButtonTintList(ColorStateList.valueOf(BLUE));
    includeApks.setChecked(saved != null && saved.getBoolean("includeApks", false));
    content.addView(includeApks);
    text(content, "通用 APK 提取（默认不选包）", 20);
    packageNames = new EditText(this);
    packageNames.setHint("填写实际包名，可换行或用逗号分隔；例如 com.mega.engineeringmode");
    packageNames.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    packageNames.setMinLines(2);
    packageNames.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(32768)});
    styleInput(packageNames);
    packageNames.setText(saved == null ? prefs.getString("packageNames", "")
        : saved.getString("packageNames", ""));
    content.addView(packageNames);
    if (saved != null && saved.getStringArray("selectedPackages") != null)
      java.util.Collections.addAll(selectedPackages, saved.getStringArray("selectedPackages"));
    else if (saved == null) {
      try {
        org.json.JSONArray previous = new org.json.JSONArray(prefs.getString("selectedPackages", "[]"));
        String[] packages = new String[previous.length()];
        for (int index = 0; index < packages.length; index++) packages[index] = previous.getString(index);
        java.util.Collections.addAll(selectedPackages, AdbPackageCatalog.selection("", packages));
      } catch (Exception invalid) {
        selectedPackages.clear();
      }
    }
    selectionStatus = text(content, "", 14);
    updateSelectionStatus();
    scanPackages = button(content, "扫描已安装包（只读，完成后多选）", this::scanPackages);
    choosePackages = button(content, "搜索 / 修改多选包", this::choosePackages);
    choosePackages.setEnabled(false);
    text(content, "扫描包含系统包和普通应用，按包名搜索；只列安装路径符合提取规则的包，不保证shell有权读取。每次最多64包，实际读取失败会保留错误，不绕过权限。手填、多选及预设组会合并去重。", 14);
    text(content, "不选包时只采集诊断；选择后按实际pm path读取基础APK及分包。单文件上限2GiB、合计4GiB，至少保留64MiB空闲；导出ZIP及诊断JSON。一个包失败会终止本批并删除不完整ZIP，建议分批。已移除无效车辆读取探针。扫描需独立ADB连接/授权，取消后可重试。", 14);
    collect = button(content, "连接并采集诊断", this::collect);
    button(
        content,
        "取消采集",
        () -> {
          AdbClient current = active;
          if (current != null) current.close();
          exportCancelled = true;
          show("已请求取消，正在保存已取得的结果…");
        });
    button(content, "导出 ADB 诊断", this::export);
    button(content, "导出系统 APK（ZIP）", this::exportApks);
    button(content, "返回车辆检测", this::finish);
    text(content, "采集只在点击后运行。结果保留在本机，可单独导出，也会附在车辆检测JSON中。连接身份与应用权限分别记录；不会自动授权、改设置、控制车辆或清空日志。", 14);
    status = text(content, "尚未采集", 14);
    status.setTextIsSelectable(true);
    if (saved != null) {
      pendingReport = saved.getString("pendingReport");
      pendingArchive = saved.getString("pendingArchive");
    }
    try {
      JSONObject previous = AdbDiagnostics.latest(this);
      if (previous != null) show(summary(previous));
    } catch (Exception error) {
      show("上次记录读取失败：" + error);
    }
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void styleInput(EditText input) {
    input.setTextColor(INK);
    input.setHintTextColor(theme.secondaryText);
    input.setBackgroundTintList(ColorStateList.valueOf(BLUE));
    input.setMinHeight(dp(48));
  }

  private TextView text(LinearLayout parent, String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(INK);
    view.setPadding(0, dp(12), 0, dp(12));
    parent.addView(view);
    return view;
  }

  private Button button(LinearLayout parent, String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(new ColorStateList(
        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
        new int[] {theme.color(0xff8b9bb0, theme.inactive), BLUE}));
    button.setTextSize(15);
    button.setMinHeight(dp(48));
    button.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable background = new GradientDrawable();
    background.setColor(theme.raised);
    background.setStroke(dp(1), theme.border);
    background.setCornerRadius(dp(12));
    button.setBackground(background);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = dp(8);
    parent.addView(button, params);
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private void show(String value) {
    runOnUiThread(
        () -> {
          if (!destroyed) status.setText(value);
        });
  }

  private int connectionPort() {
    String address = host.getText().toString().trim();
    int number;
    try {
      number = Integer.parseInt(port.getText().toString().trim());
      if (number < 1
          || number > 65535
          || address.isEmpty()
          || address.contains("/")
          || address.contains(" ")) throw new IllegalArgumentException();
    } catch (Exception error) {
      show("请填写实际 ADB 地址和1～65535之间的端口，不能留空。");
      return -1;
    }
    getSharedPreferences("adb_diagnostics", 0)
        .edit()
        .putString("host", address)
        .putString("port", String.valueOf(number))
        .apply();
    return number;
  }

  private void collect() {
    if (collecting || exporting) return;
    String[] targets;
    try {
      targets = AdbPackageCatalog.selection(packageNames.getText().toString(),
          AdbApkExtractor.targets(includeApks.isChecked(), includeInterfaceApks.isChecked()),
          selectedPackages.toArray(new String[0]));
    } catch (IllegalArgumentException error) {
      show(error.getMessage());
      return;
    }
    int number = connectionPort();
    if (number < 0) return;
    String address = host.getText().toString().trim();
    getSharedPreferences("adb_diagnostics", 0).edit()
        .putString("packageNames", packageNames.getText().toString()).apply();
    collecting = true;
    collect.setEnabled(false);
    setOptionsEnabled(false);
    AdbClient client = new AdbClient();
    active = client;
    worker.execute(
        () -> {
          try {
            JSONObject report =
                AdbDiagnostics.collect(
                    getApplicationContext(), client, address, number, targets, false, this::show);
            show(summary(report));
          } catch (Exception error) {
            show("诊断保存失败：" + error);
          } finally {
            client.close();
            active = null;
            runOnUiThread(
                () -> {
                  if (!destroyed) {
                    collecting = false;
                    collect.setEnabled(true);
                    setOptionsEnabled(true);
                  }
                });
          }
        });
  }

  private void updateSelectionStatus() {
    selectionStatus.setText("扫描多选已选" + selectedPackages.size() + "包（不含手填和预设组）"
        + (selectedPackages.isEmpty() ? "" : "\n" + android.text.TextUtils.join("\n", selectedPackages)));
  }

  private void scanPackages() {
    if (collecting || exporting) return;
    int number = connectionPort();
    if (number < 0) return;
    String address = host.getText().toString().trim();
    collecting = true;
    collect.setEnabled(false);
    setOptionsEnabled(false);
    AdbClient client = new AdbClient();
    active = client;
    worker.execute(() -> {
      List<String> scanned = null;
      try {
        client.connect(address, number, AdbDiagnostics.key(getApplicationContext()), this::show);
        show("扫描已安装包及安装路径…");
        scanned = AdbPackageCatalog.scan(client);
        show("扫描得到" + scanned.size() + "个安装包；尚未验证APK读取权限，请多选后采集。");
      } catch (Exception error) {
        show("扫描未完成：" + error.getMessage() + "；没有更改原有选择。");
      } finally {
        client.close();
        active = null;
        List<String> completed = scanned;
        runOnUiThread(() -> {
          if (destroyed) return;
          collecting = false;
          if (completed != null) installedPackages = completed;
          collect.setEnabled(true);
          setOptionsEnabled(true);
          if (completed != null) choosePackages();
        });
      }
    });
  }

  private void choosePackages() {
    if (collecting || exporting || installedPackages.isEmpty()) return;
    Set<String> pending = new LinkedHashSet<>(selectedPackages);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(16), 0, dp(16), 0);
    EditText search = new EditText(this);
    search.setHint("搜索包名，例如 engineering / iflytek");
    search.setSingleLine(true);
    styleInput(search);
    body.addView(search);
    TextView count = text(body, "", 14);
    ListView list = new ListView(this);
    list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    body.addView(list, new LinearLayout.LayoutParams(-1, dp(240)));
    List<String> visible = new ArrayList<>();
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
        android.R.layout.simple_list_item_multiple_choice, visible);
    list.setAdapter(adapter);
    Runnable refresh = () -> {
      String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
      visible.clear();
      for (String pkg : installedPackages)
        if (pkg.toLowerCase(Locale.ROOT).contains(query)) visible.add(pkg);
      adapter.notifyDataSetChanged();
      list.clearChoices();
      for (int index = 0; index < visible.size(); index++)
        list.setItemChecked(index, pending.contains(visible.get(index)));
      count.setText("已选" + pending.size() + "/64 · 当前显示" + visible.size()
          + "项；列表不代表读取权限已获授");
    };
    list.setOnItemClickListener((parent, view, position, itemId) -> {
      String pkg = visible.get(position);
      if (list.isItemChecked(position)) {
        if (pending.size() < AdbPackageCatalog.MAX_TARGETS) pending.add(pkg);
        else android.widget.Toast.makeText(this, "每批最多64个包", android.widget.Toast.LENGTH_SHORT).show();
      } else pending.remove(pkg);
      refresh.run();
    });
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence value, int start, int removed, int after) {}
      @Override public void onTextChanged(CharSequence value, int start, int before, int count) { refresh.run(); }
      @Override public void afterTextChanged(Editable value) {}
    });
    button(body, "清空多选", () -> { pending.clear(); refresh.run(); });
    refresh.run();
    new AlertDialog.Builder(this).setTitle("选择要提取的安装包").setView(body)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存选择", (dialog, which) -> {
          selectedPackages.clear();
          selectedPackages.addAll(pending);
          getSharedPreferences("adb_diagnostics", 0).edit().putString("selectedPackages",
              new org.json.JSONArray(new ArrayList<>(selectedPackages)).toString()).apply();
          updateSelectionStatus();
        }).show();
  }

  static String summary(JSONObject report) {
    String time =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .format(new Date(report.optLong("startedAt")));
    JSONObject probe = report.optJSONObject("nativeReadProbe");
    JSONObject apks = report.optJSONObject("apkExtraction");
    JSONObject platform = report.optJSONObject("platformVehicleProbe");
    JSONObject messageCenter = report.optJSONObject("messageCenterProbe");
    String probeSummary = probeSummary(probe);
    return "最近采集："
        + time
        + "\n"
        + (report.optBoolean("complete") ? "采集流程完成；仍需检查输出中的权限拒绝/缺失" : "部分结果/连接失败")
        + "\n命令记录："
        + (report.optJSONArray("commands") == null ? 0 : report.optJSONArray("commands").length())
        + "\n" + AdbReadOnlyStatus.summary(report.optJSONObject("targetedReadOnlyDiagnostics"))
        + "\nAPK提取：" + (apks != null && apks.optBoolean("complete") ? "完成，可导出ZIP"
            + (apks.optJSONArray("packages") == null ? ""
                : "（" + apks.optJSONArray("packages").length() + "个包）")
            : apks != null && apks.optBoolean("requested") ? "未完成，检查错误" : "未选择")
        + (report.has("error") ? "\n" + report.optString("error") : "")
        + "\n导出ADB诊断，或返回车辆检测导出合并记录。";
  }

  private void setOptionsEnabled(boolean enabled) {
    host.setEnabled(enabled);
    port.setEnabled(enabled);
    packageNames.setEnabled(enabled);
    scanPackages.setEnabled(enabled);
    choosePackages.setEnabled(enabled && !installedPackages.isEmpty());
    includeApks.setEnabled(enabled);
    includeInterfaceApks.setEnabled(enabled);
  }

  static String probeSummary(JSONObject probe) {
    if (probe == null) return "尚无本轮只读验证结果";
    String read = probe.optBoolean("readVerified") ? "取得有效属性值"
        : probe.optBoolean("readAttempted") ? "已尝试，未取得有效值" : "未执行";
    org.json.JSONArray properties = probe.optJSONArray("properties");
    int checked = 0, granted = 0;
    if (properties != null)
      for (int index = 0; index < properties.length(); index++) {
        JSONObject property = properties.optJSONObject(index);
        if (property == null || !property.has("permissionGranted")) continue;
        checked++;
        if (property.optBoolean("permissionGranted")) granted++;
      }
    String result = "\n进程：" + (probe.optBoolean("processVerified") || probe.optInt("uid") == 2000
        ? "shell已启动" : "未确认")
        + " · SDK：" + (probe.optBoolean("sdkLoaded") || !probe.optString("sdk").isEmpty()
            ? "已加载" : "未确认")
        + "\n车辆服务：" + (probe.optBoolean("serviceConnected") ? "已连接" : "未确认连接")
        + " · 属性管理器：" + (probe.optBoolean("managerAvailable") ? "已取得" : "未确认")
        + "\n权限元数据：" + (checked == 0 ? "未记录" : granted + "/" + checked + "项获授")
        + "（与实际读值分开判断）"
        + "\n实际读取：" + read
        + " · 状态变化：" + (probe.optBoolean("changeObserved") ? "已观察到" : "未观察到")
        + "\n订阅：本轮不测试";
    if (probe.has("error"))
      result += "\n失败阶段：" + phaseName(probe.optString("failedPhase"))
          + "\n" + probe.optString("error");
    return result;
  }

  private static String phaseName(String phase) {
    switch (phase) {
      case "process-start": return "进程身份";
      case "context-setup": return "运行上下文";
      case "permission-check": return "权限元数据";
      case "sdk-load": return "SDK加载";
      case "service-connect": return "车辆服务连接";
      case "manager-create": return "属性管理器";
      case "property-read": return "属性读取";
      default: return "未记录（旧版结果或进程未返回）";
    }
  }

  private void export() {
    if (collecting || exporting) {
      show("请先完成或取消采集，再导出。");
      return;
    }
    try {
      JSONObject report = AdbDiagnostics.latest(this);
      if (report == null) {
        show("尚无ADB采集结果");
        return;
      }
      File snapshot = File.createTempFile("adb-export-", ".json", getCacheDir());
      pendingReport = snapshot.getName();
      try (FileOutputStream output = new FileOutputStream(snapshot)) {
        output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
      }
      startActivityForResult(
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("application/json")
              .putExtra(
                  Intent.EXTRA_TITLE,
                  "ES-ADB诊断-"
                      + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date())
                      + ".json"),
          401);
    } catch (Exception error) {
      show("无法导出：" + error);
    }
  }

  private void exportApks() {
    if (collecting || exporting) {
      show("请先完成或取消采集，再导出。");
      return;
    }
    try {
      File archive = AdbApkExtractor.archive(this, AdbDiagnostics.latest(this));
      pendingArchive = archive.getName();
      startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
          .putExtra(Intent.EXTRA_TITLE, "ES-系统APK-"
              + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date()) + ".zip"), 402);
    } catch (Exception error) {
      show("无法导出APK：" + error.getMessage());
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 401 && request != 402) return;
    if (result != RESULT_OK || data == null || data.getData() == null) {
      if (request == 401 && pendingReport != null) new File(getCacheDir(), pendingReport).delete();
      return;
    }
    android.net.Uri uri = data.getData();
    String json = pendingReport, archive = pendingArchive;
    exporting = true;
    exportCancelled = false;
    collect.setEnabled(false);
    setOptionsEnabled(false);
    worker.execute(
        () -> {
          try {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
              if (output == null) throw new IllegalStateException("无法打开导出位置");
              if (request == 401) {
                if (json == null || !json.matches("adb-export-[0-9-]+\\.json"))
                  throw new IllegalStateException("ADB记录已失效");
                copy(new File(getCacheDir(), json), output);
              } else {
                if (archive == null || !archive.matches("adb-apks-[0-9]+\\.zip"))
                  throw new IllegalStateException("APK归档记录已失效");
                copy(new File(getFilesDir(), archive), output);
              }
            }
            show(request == 401 ? "ADB诊断已导出" : "系统APK ZIP已导出；请连同诊断JSON一起发送");
          } catch (Exception error) {
            show("导出失败：" + error);
          } finally {
            if (request == 401 && json != null) new File(getCacheDir(), json).delete();
            runOnUiThread(() -> {
              exporting = false;
              if (!destroyed) {
                collect.setEnabled(true);
                setOptionsEnabled(true);
              }
            });
          }
        });
  }

  private void copy(File source, OutputStream output) throws java.io.IOException {
    try (FileInputStream input = new FileInputStream(source)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (destroyed || exportCancelled || Thread.currentThread().isInterrupted())
          throw new java.io.IOException("导出已取消，目标文件可能不完整");
        output.write(buffer, 0, count);
      }
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("host", host.getText().toString());
    state.putString("port", port.getText().toString());
    state.putString("packageNames", packageNames.getText().toString());
    state.putStringArray("selectedPackages", selectedPackages.toArray(new String[0]));
    state.putBoolean("includeApks", includeApks.isChecked());
    state.putBoolean("includeInterfaceApks", includeInterfaceApks.isChecked());
    state.putString("pendingReport", pendingReport);
    state.putString("pendingArchive", pendingArchive);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    AdbClient current = active;
    if (current != null) current.close();
    worker.shutdownNow();
    super.onDestroy();
  }
}
