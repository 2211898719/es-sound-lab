package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    qualifiers = "w1280dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UiTest {
  private View find(View v, String text) {
    if (v instanceof TextView && text.equals(((TextView) v).getText().toString())) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View match = find(((ViewGroup) v).getChildAt(i), text);
        if (match != null) return match;
      }
    return null;
  }

  private int count(View v, Class<?> type) {
    int n = type.isInstance(v) ? 1 : 0;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
        n += count(((ViewGroup) v).getChildAt(i), type);
    return n;
  }

  private void image(android.app.Activity a, String name, int width, int height) throws Exception {
    View v = ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
    v.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    v.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    v.draw(new Canvas(bitmap));
    File dir = new File("dist/screenshots");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  private int assertFlatButtons(View view) {
    int buttons = 0;
    if (view instanceof android.widget.Button) {
      buttons++;
      assertNull(view.getStateListAnimator());
      for (boolean pressed : new boolean[] {false, true, false}) {
        view.setPressed(pressed);
        view.requestFocus();
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));
        assertEquals(0f, view.getElevation(), 0f);
        assertEquals(0f, view.getTranslationZ(), 0f);
        assertEquals(0f, view.getZ(), 0f);
      }
      view.clearFocus();
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++)
        buttons += assertFlatButtons(group.getChildAt(index));
    }
    return buttons;
  }

  @Test
  public void mainPageButtonsStayFlatWhenIdlePressedAndFocused() {
    try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
      controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
      controller.create().start().resume().visible();
      shadowOf(Looper.getMainLooper()).idle();
      View root = controller.get().findViewById(android.R.id.content);
      assertTrue(assertFlatButtons(root) > 0);
      find(root, "车辆检测").performClick();
      assertEquals(6, assertFlatButtons(root));
      assertNull(find(root, "规则管理"));
    }
  }

  @Test public void engineCardOpensSimulationSettingsAndKeepsSavedAudioSettings() {
    try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
      controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
      controller.create().start().resume().visible();
      MainActivity activity = controller.get();
      Store store = new Store(activity);
      store.enable("engine", true);
      store.volume("engine", 37);
      String source = store.source(EventSpec.find("engine.loop"));
      View card = activity.findViewById(android.R.id.content).findViewWithTag("engine.simulation");
      assertNotNull(card);
      assertNotNull(find(card, "模拟声浪"));
      assertNotNull(find(card, "选择预设 / 曲线详情 →"));
      assertEquals(0, count(card, Switch.class));
      assertEquals(0, count(card, android.widget.Spinner.class));
      assertEquals(0, count(card, android.widget.SeekBar.class));
      assertEquals(1, count(card, android.widget.Button.class));
      find(card, "选择预设 / 曲线详情 →").performClick();
      View settings = activity.findViewById(android.R.id.content).findViewWithTag("engine.settings");
      assertNotNull(settings);
      assertNotNull(find(settings, "从文件 / U盘替换"));
      assertNotNull(settings.findViewWithTag("engine.curve-graph"));
      activity.onBackPressed();
      assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("engine.simulation"));
      Store reopened = new Store(activity);
      assertTrue(reopened.enabled("engine"));
      assertEquals(37, reopened.volume("engine"));
      assertEquals(source, reopened.source(EventSpec.find("engine.loop")));
    }
  }

  @Test public void reopeningAppRestartsMonitoringAndKeepsNormalDashboardWithoutProbeButtons() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().putBoolean("monitor", false).commit();
    while (shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService() != null) {}
    try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
      controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
      controller.create().start().resume().visible();
      MainActivity activity = controller.get();
      shadowOf(Looper.getMainLooper()).idle();
      android.content.Intent started = null, candidate;
      while ((candidate = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null)
        if (MonitorService.START.equals(candidate.getAction())) started = candidate;
      assertNotNull(started);
      assertEquals(MonitorService.START, started.getAction());
      assertEquals(MonitorService.class.getName(), started.getComponent().getClassName());
      assertTrue(store.prefs.getBoolean("monitor", false));
      assertEquals(0, activity.getWindow().getAttributes().flags
          & android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
      View root = activity.findViewById(android.R.id.content);
      assertNull(find(root, "分屏探测"));
      image(activity, "home-landscape", 1280, 1080);
      find(root, "车辆检测").performClick();
      assertNull(find(root, "全屏显示"));
      assertNull(find(root, "退出全屏"));
      assertNotNull(find(root, "音效设置"));
      image(activity, "detection-landscape", 1280, 1400);
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(activity, "iccStateSettings",
          org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class, "lock"));
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(dialog.isShowing());
      assertEquals(0, count(dialog.getWindow().getDecorView(), android.widget.CheckBox.class));
      assertTrue(containsText(dialog.getWindow().getDecorView(), "无需二次确认"));
      assertFalse(store.iccTriggerConfirmed("charge"));
      JSONObject lock = new JSONObject(new Diagnostics().export(false, store)).getJSONObject("lockConfiguration");
      assertEquals(2007, lock.getInt("targetUsage"));
      assertEquals(ExteriorAudio.ADDRESS, lock.getString("targetAddress"));
      assertFalse(lock.getBoolean("requiresOutputConfirmation"));
      assertTrue(lock.getBoolean("requiresPriorParkBaseline"));
      dialog.dismiss();
      store.prefs.edit().putBoolean("monitor", false).commit();
      controller.pause().stop();
      while (shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService() != null) {}
      controller.start().resume();
      started = null;
      while ((candidate = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null)
        if (MonitorService.START.equals(candidate.getAction())) started = candidate;
      assertNotNull(started);
      assertTrue(store.prefs.getBoolean("monitor", false));
    }
  }

  @Test
  public void adbPackagePickerPageRendersWithoutStartingConnection() throws Exception {
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create().start().resume().visible()) {
      AdbDiagnosticsActivity activity = controller.get();
      shadowOf(Looper.getMainLooper()).idle();
      View root = activity.findViewById(android.R.id.content);
      assertNotNull(find(root, "通用 APK 提取（默认不选包）"));
      assertNotNull(find(root, "扫描已安装包（只读，完成后多选）"));
      assertNull(find(root, "消息总线定点定位（不提取APK）"));
      image(activity, "adb-package-extraction", 1280, 900);
      java.lang.reflect.Field client = AdbDiagnosticsActivity.class.getDeclaredField("active");
      client.setAccessible(true);
      assertNull(client.get(activity));
    }
  }

  @Test
  public void importedSourcesAndRulesPersistIndependently() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    Store store = new Store(c);
    store.prefs.edit().clear().commit();
    EventSpec e = EventSpec.find("door.front_left.open");
    assertFalse(store.enabled("door"));
    store.enable("door", true);
    store.volume("door", 500);
    store.asset(e, "audio/option_princess.mp3", "公主请上车.mp3");
    Store reopened = new Store(c);
    assertTrue(reopened.enabled("door"));
    assertEquals(100, reopened.volume("door"));
    assertEquals("asset:audio/option_princess.mp3", reopened.source(e));
    assertEquals(
        "asset:audio/door_front_left_closed.mp3",
        reopened.source(EventSpec.find("door.front_left.close")));
    JSONObject rule =
        new JSONObject()
            .put("id", "lock1")
            .put("event", "lock.unlock")
            .put("topic", "observed/topic")
            .put("source", "日志")
            .put("value", "2");
    reopened.addRule(rule);
    assertTrue(reopened.mapped("lock.unlock"));
    assertTrue(reopened.mapped("lock.lock"));
    reopened.deleteRule(0);
    assertTrue(reopened.mapped("lock.unlock"));
    assertEquals(0, reopened.rules().length());
  }

  private boolean containsText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++)
        if (containsText(group.getChildAt(index), text)) return true;
    }
    return false;
  }

  @Test public void detectionStartUsesStartedForegroundServiceNotJustBoundService() throws Exception {
    org.robolectric.android.controller.ServiceController<MonitorService> serviceController =
        Robolectric.buildService(MonitorService.class).create();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
      MainActivity activity = controller.get();
      shadowOf(Looper.getMainLooper()).idle();
      java.lang.reflect.Field serviceField = MainActivity.class.getDeclaredField("service");
      serviceField.setAccessible(true); serviceField.set(activity, serviceController.get());
      find(activity.findViewById(android.R.id.content), "车辆检测").performClick();
      while (shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService() != null) {}
      find(activity.findViewById(android.R.id.content), "开始监听").performClick();
      android.content.Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
      assertNotNull(intent); assertEquals(MonitorService.START, intent.getAction());
      assertTrue(new Store(activity).prefs.getBoolean("monitor", false));
    } finally { serviceController.destroy(); }
  }

  @Test
  public void diagnosticsAreBoundedAndExportable() throws Exception {
    Diagnostics d = new Diagnostics();
    for (int i = 0; i < 900; i++) d.line("test", "line-" + i);
    assertFalse(d.lines("").contains("line-0\n"));
    assertTrue(d.lines("").contains("line-899"));
    Protocol.Sample s =
        Protocol.decode(
            "1760000000.125 345 789 I MessageProperty: receive VehicleDoor/Door/FrontLeft, value:"
                + " CarPropertyValue{id=0x600002c, area=0x0, status=0, value=1}");
    d.sample(s);
    d.marker("主驾开门");
    JSONObject exported =
        new JSONObject(d.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(600, exported.getJSONArray("lines").length());
    assertEquals(1, exported.getJSONArray("candidates").length());
    assertFalse(exported.getBoolean("readLogsGranted"));
    assertEquals(
        "VehicleDoor/Door/FrontLeft",
        exported.getJSONArray("candidates").getJSONObject(0).getString("topic"));
  }

  @Test
  public void signalHistorySurvivesRawNoiseAndKeepsStatusChanges() throws Exception {
    Diagnostics diagnostics = new Diagnostics();
    Protocol.Sample locked = new Protocol.Sample("VehicleDoor/CentralLock", Protocol.CENTRAL_LOCK,
        "0x0", 0, "1", 100, "日志", "lock", "1");
    Protocol.Sample unlocked = new Protocol.Sample("VehicleDoor/CentralLock", Protocol.CENTRAL_LOCK,
        "0x0", 0, "0", 200, "日志", "lock", "0");
    diagnostics.sample(locked);
    diagnostics.sample(locked);
    diagnostics.sample(unlocked);
    diagnostics.sample(new Protocol.Sample("VehicleDoor/CentralLock", Protocol.CENTRAL_LOCK,
        "0x0", 1, "0", 300, "日志", "lock", null));
    for (int index = 0; index < 900; index++) diagnostics.line("test", "noise");
    diagnostics.filteredLog();
    diagnostics.droppedLog();
    diagnostics.property("Mega", 10, new int[] {0, 1}, "Integer");
    diagnostics.property("Mega", 10, new int[] {0, 1}, "Integer");
    JSONObject exported = new JSONObject(diagnostics.export(true, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(2, exported.getInt("schemaVersion"));
    assertEquals(3, exported.getJSONArray("signalHistory").length());
    assertTrue(exported.getJSONArray("signalHistory").getJSONObject(0).getBoolean("baseline"));
    JSONObject change = exported.getJSONArray("signalHistory").getJSONObject(1);
    assertEquals(200, change.getLong("time"));
    assertEquals("1", change.getString("previousRaw"));
    assertEquals("0", change.getString("raw"));
    assertEquals(1, exported.getJSONArray("signalHistory").getJSONObject(2).getInt("status"));
    assertEquals(1, exported.getInt("filteredLogs"));
    assertEquals(1, exported.getInt("logQueueDropped"));
    assertEquals(1, exported.getJSONArray("propertyCatalog").length());
    diagnostics.clear();
    exported = new JSONObject(diagnostics.export(true, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(0, exported.getJSONArray("signalHistory").length());
    assertEquals(0, exported.getInt("filteredLogs"));
    assertEquals(0, exported.getInt("logQueueDropped"));
  }

  @Test
  public void historyIsBoundedAndNoiseRulesRemainDiscoverable() throws Exception {
    Diagnostics diagnostics = new Diagnostics();
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    diagnostics.sample(new Protocol.Sample("VehicleDoor/CentralLock", Protocol.CENTRAL_LOCK,
        "0x0", 0, "1", 0, "日志", "lock", "1"));
    for (int index = 0; index < 1400; index++)
      diagnostics.sample(new Protocol.Sample("unknown", 1, "0x0", 0, "" + index,
          index, "日志", null, null));
    JSONObject exported = new JSONObject(diagnostics.export(true, store));
    assertEquals(1200, exported.getJSONArray("signalHistory").length());
    assertEquals(201, exported.getInt("historyDropped"));
    assertEquals(1, exported.getJSONArray("eventHistory").length());
    assertEquals("VehicleDoor/CentralLock", exported.getJSONArray("eventHistory").getJSONObject(0).getString("topic"));
    for (int index = 0; index < 450; index++)
      diagnostics.sample(new Protocol.Sample("VehicleDoor/CentralLock", Protocol.CENTRAL_LOCK,
          "0x0", 0, "" + (index % 2), index, "日志", "lock", "" + (index % 2)));
    exported = new JSONObject(diagnostics.export(true, store));
    assertEquals(400, exported.getJSONArray("eventHistory").length());
    assertEquals(51, exported.getInt("eventHistoryDropped"));
    Protocol.Sample noisy = new Protocol.Sample("EcologyMedia/LRC", 5, "0x0", 0, "1", 1,
        "日志", null, null);
    assertFalse(store.watches(noisy));
    store.addRule(new JSONObject().put("id", "noise-rule").put("source", "日志")
        .put("topic", noisy.topic).put("propertyId", 5).put("area", "0x0"));
    assertTrue(store.watches(noisy));
    assertFalse(store.watches(new Protocol.Sample(noisy.topic, 5, "0x1", 0, "1", 1,
        "日志", null, null)));
  }

  @Test
  public void lockCalibrationPersistsWithoutGuessingEnums() {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    assertFalse(store.lockCalibrated());
    assertNull(store.lockState("1"));
    store.calibrateLock("2", "1");
    Store reopened = new Store(RuntimeEnvironment.getApplication());
    assertTrue(reopened.lockCalibrated());
    assertEquals("locked", reopened.lockState("2"));
    assertEquals("unlocked", reopened.lockState("1"));
    assertTrue(reopened.mapped("lock.lock"));
    assertTrue(reopened.mapped("lock.unlock"));
    assertFalse(reopened.prefs.getBoolean("lock.output.confirmed", false));
  }

  @Test
  public void autoHoldCalibrationPersistsAndHistoryIsKeptSeparateFromContinuousSignals()
      throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    assertTrue(store.autoHoldCalibrated());
    assertTrue(store.mapped("autohold.holding"));
    for (String[] bad : new String[][] {{"", ""}, {"1", "1"}, {"0", "1"}, {"1", "3"}}) {
      try {
        store.calibrateAutoHold(bad[0], bad[1]);
        fail("invalid calibration accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
    store.calibrateAutoHold("2", "1");
    Store reopened = new Store(RuntimeEnvironment.getApplication());
    assertTrue(reopened.autoHoldCalibrated());
    assertEquals("holding", reopened.autoHoldState("2"));
    assertEquals("released", reopened.autoHoldState("1"));
    assertTrue(reopened.mapped("autohold.holding"));
    assertFalse(reopened.enabled("autohold"));
    Diagnostics diag = new Diagnostics();
    Protocol.Sample sample =
        Protocol.decode(
            "1760000000.125 345 789 I MessageProperty: receive T1CF1/AutoHold,"
                + " value: CarPropertyValue{id=0x50000022, area=0x0, status=0, value=2}");
    diag.sample(sample);
    for (int i = 0; i < 1300; i++)
      diag.sample(new Protocol.Sample("continuous", 1, "0x0", 0, "" + i, i, "日志", null, null));
    org.json.JSONObject exported = new org.json.JSONObject(diag.export(true, reopened));
    assertEquals(1, exported.getJSONArray("eventHistory").length());
    assertEquals(
        "autohold", exported.getJSONArray("eventHistory").getJSONObject(0).getString("mappedKey"));
    assertTrue(exported.getJSONObject("autoHoldConfiguration").getBoolean("calibrated"));
    assertEquals("2", exported.getJSONObject("autoHoldConfiguration").getString("holdingValue"));
    assertTrue(exported.has("autoHoldTriggerStatus"));
  }

  @Test
  public void autoHoldSettingsAllowCalibrationAndClearingFromTheScreen() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()) {
      MainActivity activity = controller.get();
      View root = activity.findViewById(android.R.id.content);
      find(root, "AutoHold 设置").performClick();
      AlertDialog menu = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      org.robolectric.Shadows.shadowOf(menu).clickOnItem(0);
      AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      shadowOf(Looper.getMainLooper()).idle();
      View dialogRoot = dialog.getWindow().getDecorView();
      EditText holding = (EditText) findDescription(dialogRoot, "AutoHold 保持原始值");
      EditText released = (EditText) findDescription(dialogRoot, "AutoHold 释放原始值");
      assertNotNull(holding);
      assertNotNull(released);
      holding.setText("1");
      released.setText("1");
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertTrue(dialog.isShowing());
      assertTrue(store.autoHoldCalibrated());
      assertEquals("holding", store.autoHoldState("2"));
      released.setText("2");
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertTrue(store.autoHoldCalibrated());
      assertEquals("holding", store.autoHoldState("1"));
      find(root, "AutoHold 设置").performClick();
      menu = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      org.robolectric.Shadows.shadowOf(menu).clickOnItem(0);
      dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      shadowOf(Looper.getMainLooper()).idle();
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
      shadowOf(Looper.getMainLooper()).idle();
      assertFalse(store.autoHoldCalibrated());
    }
  }

  private View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup)
      for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
        View match = findDescription(((ViewGroup) view).getChildAt(index), description);
        if (match != null) return match;
      }
    return null;
  }

  @Test
  public void sessionJournalRetainsBoundedEvidenceAcrossServiceRecreation() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("session_journal", 0).edit().clear().commit();
    Diagnostics first = new Diagnostics();
    first.attach(context);
    first.lockDecision("原始值=0 · 未校准，不触发");
    first.line("车外播放", "手动试听 · lock.lock");
    JSONObject before = new JSONObject(first.export(true, new Store(context)));
    Diagnostics reopened = new Diagnostics();
    reopened.attach(context);
    JSONObject after = new JSONObject(reopened.export(true, new Store(context)));
    assertEquals(0, after.getJSONArray("signalHistory").length());
    assertEquals(4, after.getJSONArray("sessionHistory").length());
    assertTrue(after.getJSONArray("sessionHistory").toString().contains("未校准"));
    assertEquals(before.getJSONObject("runtime").getString("processId"),
        after.getJSONObject("runtime").getString("processId"));
    assertNotEquals(before.getJSONObject("runtime").getString("serviceSessionId"),
        after.getJSONObject("runtime").getString("serviceSessionId"));
    assertTrue(after.getJSONObject("runtime").has("bootCount"));
    assertTrue(after.getJSONObject("runtime").has("elapsedRealtimeMs"));
    assertTrue(after.getJSONObject("runtime").has("uptimeMs"));
    first.line("日志通道", "旧请求延迟关闭");
    reopened.line("日志通道", "新请求已启动");
    after = new JSONObject(reopened.export(true, new Store(context)));
    assertEquals(6, after.getJSONArray("sessionHistory").length());
    assertTrue(after.getJSONArray("sessionHistory").toString().contains("未校准"));
    assertTrue(after.getJSONArray("sessionHistory").toString().contains("旧请求延迟关闭"));
    for (int index = 0; index < 200; index++) reopened.line("生命周期", "test " + index);
    after = new JSONObject(reopened.export(true, new Store(context)));
    assertEquals(160, after.getJSONArray("sessionHistory").length());
    reopened.clear();
    after = new JSONObject(reopened.export(true, new Store(context)));
    assertEquals(1, after.getJSONArray("sessionHistory").length());
    assertFalse(after.getJSONArray("sessionHistory").toString().contains("未校准"));
  }

  @Test
  public void lockReadinessExplainsMissingConfigurationAndDisabledGroup() {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    assertTrue(store.iccTriggerConfirmed("lock"));
    assertTrue(store.lockReadiness().contains("不可用"));
    store.enable("lock", false);
    assertTrue(store.lockReadiness().contains("开关已关闭"));
    store.enable("lock", true);
    assertTrue(store.lockReadiness().contains("不可用"));
  }
}
