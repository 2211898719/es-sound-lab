package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

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

  private void image(MainActivity a, String name, int width, int height) throws Exception {
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

  @Test
  public void homeDetectionRulesAndPortraitRender() throws Exception {
    try (ActivityController<MainActivity> c =
        Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()) {
      MainActivity a = c.get();
      shadowOf(Looper.getMainLooper()).idle();
      View root = a.findViewById(android.R.id.content);
      assertNotNull(find(root, "挂挡音效"));
      assertNotNull(find(root, "解闭锁提示音"));
      assertNotNull(find(root, "充电提示音"));
      assertEquals(10, count(root, Switch.class));
      image(a, "home-landscape", 1280, 1080);
      find(root, "车辆检测").performClick();
      assertNotNull(find(root, "开始扩展检测"));
      assertEquals(1, count(root, EditText.class));
      image(a, "detection-landscape", 1280, 1080);
      find(root, "规则管理").performClick();
      assertNotNull(find(root, "让新事件拥有自己的声音"));
      image(a, "rules-landscape", 1280, 900);
      RuntimeEnvironment.setQualifiers("w400dp-h840dp-port-mdpi");
      find(root, "音效设置").performClick();
      image(a, "home-portrait", 400, 840);
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
    assertFalse(reopened.mapped("lock.lock"));
    reopened.deleteRule(0);
    assertFalse(reopened.mapped("lock.unlock"));
    assertEquals(0, reopened.rules().length());
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
    assertEquals(1, exported.getJSONArray("propertyCatalog").length());
    diagnostics.clear();
    exported = new JSONObject(diagnostics.export(true, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(0, exported.getJSONArray("signalHistory").length());
    assertEquals(0, exported.getInt("filteredLogs"));
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
    assertTrue(store.lockReadiness().contains("未校准"));
    store.calibrateLock("0", "1");
    assertTrue(store.lockReadiness().contains("开关已关闭"));
    store.enable("lock", true);
    assertTrue(store.lockReadiness().contains("未人工确认"));
    store.prefs.edit().putBoolean("lock.output.confirmed", true).commit();
    assertTrue(store.lockReadiness().contains("不可用"));
  }
}
