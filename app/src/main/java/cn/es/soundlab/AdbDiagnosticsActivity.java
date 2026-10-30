package cn.es.soundlab;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** User-operated authenticated TCP ADB diagnostics; never starts from vehicle events. */
public final class AdbDiagnosticsActivity extends Activity {
  private static final int INK = 0xff203553, BLUE = 0xff2475f5, BG = 0xfff3f7fc;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private EditText host, port;
  private TextView status;
  private Button collect;
  private volatile AdbClient active;
  private volatile boolean destroyed;
  private boolean collecting;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
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
        "通过车机已开启的传统 TCP ADB 采集车辆权限、应用授权、服务/SDK信息和近期相关日志。先开启工程模式ADB，填写实际地址和端口；连接时在系统弹窗允许 ES Sound"
            + " Lab。",
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
    collect = button(content, "连接并采集诊断", this::collect);
    button(
        content,
        "取消采集",
        () -> {
          AdbClient current = active;
          if (current != null) current.close();
          show("已请求取消，正在保存已取得的结果…");
        });
    button(content, "导出 ADB 诊断", this::export);
    button(content, "返回车辆检测", this::finish);
    text(content, "采集只在点击后运行。结果保留在本机，可单独导出，也会附在车辆检测JSON中。连接身份与应用权限分别记录；不会自动授权、改设置、控制车辆或清空日志。", 14);
    status = text(content, "尚未采集", 14);
    status.setTextIsSelectable(true);
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
    input.setHintTextColor(0xff60738c);
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
    button.setTextColor(BLUE);
    button.setTextSize(15);
    button.setMinHeight(dp(48));
    button.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xfffafdff);
    background.setStroke(dp(1), 0xffdce7f5);
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

  private void collect() {
    if (collecting) return;
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
      return;
    }
    getSharedPreferences("adb_diagnostics", 0)
        .edit()
        .putString("host", address)
        .putString("port", String.valueOf(number))
        .apply();
    collecting = true;
    collect.setEnabled(false);
    AdbClient client = new AdbClient();
    active = client;
    worker.execute(
        () -> {
          try {
            JSONObject report =
                AdbDiagnostics.collect(
                    getApplicationContext(), client, address, number, this::show);
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
                  }
                });
          }
        });
  }

  private static String summary(JSONObject report) {
    String time =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .format(new Date(report.optLong("startedAt")));
    return "最近采集："
        + time
        + "\n"
        + (report.optBoolean("complete") ? "采集流程完成；仍需检查输出中的权限拒绝/缺失" : "部分结果/连接失败")
        + "\n命令记录："
        + (report.optJSONArray("commands") == null ? 0 : report.optJSONArray("commands").length())
        + (report.has("error") ? "\n" + report.optString("error") : "")
        + "\n导出ADB诊断，或返回车辆检测导出合并记录。";
  }

  private void export() {
    if (collecting) {
      show("请先完成或取消采集，再导出。");
      return;
    }
    try {
      if (AdbDiagnostics.latest(this) == null) {
        show("尚无ADB采集结果");
        return;
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

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 401 || result != RESULT_OK || data == null || data.getData() == null) return;
    android.net.Uri uri = data.getData();
    worker.execute(
        () -> {
          try {
            JSONObject report = AdbDiagnostics.latest(this);
            if (report == null) throw new IllegalStateException("ADB记录已失效");
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
              if (output == null) throw new IllegalStateException("无法打开导出位置");
              output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            show("ADB诊断已导出");
          } catch (Exception error) {
            show("导出失败：" + error);
          }
        });
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("host", host.getText().toString());
    state.putString("port", port.getText().toString());
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
