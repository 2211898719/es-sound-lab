package cn.es.soundlab;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** User-operated probe. No split call is made by lifecycle or vehicle events. */
public final class SplitProbeActivity extends Activity {
  private static final int INK = 0xff203553, BLUE = 0xff2475f5, BG = 0xfff3f7fc;
  private SplitController controller;
  private SessionJournal journal;
  private TextView stateView, resultView;
  private String lastAction = "尚未操作", lastError = "无", partner = SplitController.NAVI_PKG;
  private String lastObservation;
  private LinearLayout content;
  private final Runnable refreshAfterRequest = () -> refreshState(true);

  interface Action {
    void run();
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = new SplitController(this);
    journal = new SessionJournal(this, "分屏探测页");
    if (saved != null) {
      lastAction = saved.getString("action", lastAction);
      lastError = saved.getString("error", lastError);
      partner = saved.getString("partner", partner);
    }
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    buildUi();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextColor(INK);
    view.setTextSize(size);
    view.setTextIsSelectable(true);
    view.setPadding(0, dp(8), 0, dp(8));
    return view;
  }

  private void button(String label, Action action) {
    Button button = new Button(this);
    button.setText(label);
    button.setTextSize(15);
    button.setTextColor(BLUE);
    button.setAllCaps(false);
    button.setMinHeight(dp(48));
    button.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xfffafdff);
    background.setStroke(dp(1), 0xffdce7f5);
    background.setCornerRadius(dp(12));
    button.setBackground(background);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(8);
    content.addView(button, params);
    button.setOnClickListener(view -> perform(label, action));
  }

  private void buildUi() {
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(16), dp(8), dp(16), dp(8));
    page.setBackgroundColor(BG);
    setContentView(page);
    TextView title = text("分屏探测", 24);
    title.setTypeface(null, Typeface.BOLD);
    page.addView(title);
    ScrollView statusScroll = new ScrollView(this);
    LinearLayout status = new LinearLayout(this);
    status.setOrientation(LinearLayout.VERTICAL);
    statusScroll.addView(status, new ScrollView.LayoutParams(-1, -2));
    page.addView(statusScroll, new LinearLayout.LayoutParams(-1, dp(320)));
    stateView = text("正在读取状态…", 14);
    stateView.setTypeface(Typeface.MONOSPACE);
    status.addView(stateView);
    resultView = text("", 14);
    status.addView(resultView);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(0, dp(8), 0, dp(16));
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content.addView(text("手动测试与地图分屏；进入请求无异常只代表已发出，仍需配对值与窗口宽度共同确认。", 14));
    button("刷新状态", () -> {});
    content.addView(text("进入 · 默认地图", 18));
    button("启动地图 · 自己在 extra · 把手左", () -> enterNavi(true, true));
    button("启动地图 · 自己在 extra · 把手右", () -> enterNavi(true, false));
    button("启动自己 · 地图在 extra · 把手左", () -> enterNavi(false, true));
    button("启动自己 · 地图在 extra · 把手右", () -> enterNavi(false, false));
    button(
        "对照：启动畅听 · 自己在 extra",
        () -> {
          partner = SplitController.MEDIA_PKG;
          controller.enterWithMedia();
        });
    content.addView(text("退出 · Provider", 18));
    button("Provider · 退出自己", () -> controller.exitByProvider(getPackageName()));
    button("Provider · 退出地图", () -> controller.exitByProvider(SplitController.NAVI_PKG));
    button(
        "Provider · 退出当前配对另一侧",
        () -> {
          String other =
              SplitController.otherPackage(controller.readSplitPackages(), getPackageName());
          if (other == null) throw new IllegalStateException("当前没有包含本应用的有效双包配对");
          controller.exitByProvider(other);
        });
    content.addView(text("退出 · Intent", 18));
    button("Intent · 启动地图退出", () -> controller.exitByIntent(SplitController.NAVI, false));
    button("Intent · 启动自己退出", () -> controller.exitByIntent(controller.self(), false));
    button("对照：地图退出 + invalid category", () -> controller.exitByIntent(SplitController.NAVI, true));
    button("对照：自己退出 + invalid category", () -> controller.exitByIntent(controller.self(), true));
    content.addView(
        text("Provider 权限可能被系统拒绝，可继续尝试 Intent 退出。操作与状态写入 SplitProbe 日志和现有会话诊断，可从车辆检测页导出。", 13));
    button("返回音效实验室", this::finish);
    page.addOnLayoutChangeListener(
        (view, l, t, r, b, ol, ot, or, ob) -> {
          int panelHeight = Math.min(dp(360), Math.max(dp(100), (b - t) * 45 / 100));
          if (statusScroll.getLayoutParams().height != panelHeight) {
            statusScroll.getLayoutParams().height = panelHeight;
            statusScroll.requestLayout();
          }
          if (r - l != or - ol) refreshState(true);
        });
    refreshState(false);
  }

  private void enterNavi(boolean launchNavi, boolean handleOnLeft) {
    partner = SplitController.NAVI_PKG;
    controller.enterWithNavi(launchNavi, handleOnLeft);
  }

  private void perform(String name, Action action) {
    lastAction = name;
    lastError = "无（请求已发出，成功与否见状态）";
    try {
      action.run();
    } catch (Exception error) {
      lastError = Log.getStackTraceString(error);
    }
    record("操作：" + lastAction + "\n异常：" + lastError);
    refreshState(true);
    stateView.removeCallbacks(refreshAfterRequest);
    stateView.postDelayed(refreshAfterRequest, 600);
  }

  private void refreshState(boolean recordChange) {
    SplitController.Measurement measure = SplitController.measure(this);
    String raw, readError = "";
    try {
      raw = controller.readSplitPackages();
    } catch (Exception error) {
      raw = null;
      readError = "\n读取异常：\n" + Log.getStackTraceString(error);
    }
    boolean pair = SplitController.hasPair(raw, getPackageName(), partner);
    String assessment =
        pair && measure.isSplitWidth() ? "已观测到配对与分屏宽度匹配" : "尚未确认进入：需配对同时包含本包与对手，且窗口接近 870 / 1682px";
    String state =
        "system_split_pkg："
            + (raw == null ? "<null>" : "\"" + raw + "\"")
            + readError
            + "\nisInMultiWindowMode："
            + isInMultiWindowMode()
            + "\n"
            + measure
            + "\n当前核对对手："
            + partner
            + "\n"
            + assessment;
    stateView.setText(state);
    resultView.setText("最近操作：" + lastAction + "\n异常：" + lastError);
    if (recordChange && !state.equals(lastObservation)) record("状态：\n" + state);
    lastObservation = state;
  }

  private void record(String value) {
    Log.i("SplitProbe", value);
    // SessionJournal bounds each message to 500 chars; chunk to retain complete exceptions.
    for (int at = 0; at < value.length(); at += 400)
      journal.record(
          "分屏探测",
          "[" + (at / 400 + 1) + "] " + value.substring(at, Math.min(at + 400, value.length())));
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshState(true);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    record("收到 Intent，METHOD=" + intent.getStringExtra(SplitController.METHOD));
    refreshState(true);
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    refreshState(true);
    stateView.post(refreshAfterRequest);
  }

  @Override
  public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration config) {
    super.onMultiWindowModeChanged(inMultiWindow, config);
    refreshState(true);
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("action", lastAction);
    state.putString("error", lastError);
    state.putString("partner", partner);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onDestroy() {
    stateView.removeCallbacks(refreshAfterRequest);
    super.onDestroy();
  }
}
