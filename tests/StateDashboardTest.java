package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class, qualifiers = "w1280dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StateDashboardTest {
  private ActivityController<MainActivity> activityController;
  private ServiceController<MonitorService> serviceController;
  private MainActivity activity;
  private MonitorService service;
  private Store store;
  private IccVehicleProbeTest.Worker worker;
  private IccVehicleProbeTest.Transport transport;

  @Before public void setup() {
    store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    shadowOf(RuntimeEnvironment.getApplication()).denyPermissions("android.permission.READ_LOGS");
    serviceController = Robolectric.buildService(MonitorService.class).create();
    service = serviceController.get();
    installSource();
    activityController = Robolectric.buildActivity(MainActivity.class);
    activityController.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
    activityController.create().start().resume().visible();
    activity = activityController.get();
    shadowOf(Looper.getMainLooper()).idle();
    ReflectionHelpers.setField(activity, "service", service);
    find(root(), "车辆检测").performClick();
  }

  @After public void cleanup() {
    activityController.pause().stop().destroy();
    serviceController.destroy();
  }

  private void installSource() {
    ReflectionHelpers.setField(service, "iccSourceFactory", (MonitorService.IccSourceFactory) listener -> {
      worker = new IccVehicleProbeTest.Worker();
      transport = new IccVehicleProbeTest.Transport();
      return new IccVehicleSource(new android.os.Handler(Looper.getMainLooper()), service.diagnostics,
          listener, store.iccIntervalMillis(), transport, worker,
          android.os.SystemClock::elapsedRealtime, System::currentTimeMillis);
    });
  }

  private View root() { return activity.findViewById(android.R.id.content); }
  private AlertDialog dialog() { return ShadowAlertDialog.getLatestAlertDialog(); }
  private void refresh() { shadowOf(Looper.getMainLooper()).idle(); ReflectionHelpers.callInstanceMethod(activity, "update"); }
  private void start() { service.begin(); refresh(); }
  private void deliver() { worker.runNext(); refresh(); }
  private TextView tagged(String tag) { return root().findViewWithTag(tag); }
  private View find(View view, String label) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) return view;
    if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
      View result = find(((ViewGroup) view).getChildAt(index), label);
      if (result != null) return result;
    }
    return null;
  }

  private void sourceMenu() { ReflectionHelpers.callInstanceMethod(activity, "logSource"); }
  private LogReader.Listener logListener() {
    store.logsEnabled(true);
    refresh();
    return service.logListener(ReflectionHelpers.getField(service, "logGeneration"));
  }
  private void door(LogReader.Listener listener, String raw, long at, long start) {
    listener.line(String.format(Locale.ROOT,
        "%d.%03d 123 456 I MessageProperty: receive VehicleDoor/Door/FrontLeft, value: CarPropertyValue{id=0x600002c, area=0x0, status=0, value=%s}",
        at / 1000, at % 1000, raw), start);
  }

  @Test public void defaultIsIccAndNoLogOrStorageRequest() {
    start();
    assertEquals(1, worker.tasks.size());
    assertFalse(service.logsEnabled());
    assertNull(ReflectionHelpers.getField(service, "logs"));
    assertNull(shadowOf(activity).getLastRequestedPermission());
    assertNotNull(find(root(), "ICC 接口 · 无需日志授权"));
    assertNotNull(find(root(), "授权日志 · 用户自行启用"));
    assertEquals("日志未启用", tagged("state.value.door.front_left").getText().toString());
    assertEquals("未收到", tagged("state.value.charge").getText().toString());
  }

  @Test public void oldFileAndCarotaChoicesCannotStartRemovedReaders() {
    for (String old : new String[] {"file", "carota", "icc"}) {
      store.prefs.edit().putString("log.backend", old).commit();
      assertFalse(store.logsEnabled());
    }
    start();
    assertEquals(1, worker.tasks.size());
    assertNull(ReflectionHelpers.getField(service, "logs"));
  }

  @Test public void logsNeedConsentAndCancellingDoesNotAffectIcc() {
    start();
    IccVehicleProbeTest.Transport retained = transport;
    sourceMenu();
    shadowOf(dialog()).clickOnItem(1);
    dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
    refresh();
    assertFalse(store.logsEnabled());
    assertSame(retained, transport);
    assertFalse(retained.closed);
    assertEquals(1, worker.tasks.size());
  }

  @Test public void enablingAndDisablingLogsPreservesIccConnectionAndBaselines() {
    start(); deliver();
    IccVehicleProbeTest.Transport retained = transport;
    sourceMenu(); shadowOf(dialog()).clickOnItem(1);
    dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); refresh();
    assertTrue(store.logsEnabled());
    assertNotNull(ReflectionHelpers.getField(service, "logs"));
    assertSame(retained, transport);
    assertEquals("ICC", service.diagnostics.live().get("gear").source);
    sourceMenu(); shadowOf(dialog()).clickOnItem(1); refresh();
    assertFalse(store.logsEnabled());
    assertNull(ReflectionHelpers.getField(service, "logs"));
    assertFalse(retained.closed);
    assertEquals("1", service.diagnostics.live().get("gear").value);
  }

  @Test public void sixInterfaceFieldsHaveIndependentMeaningsAndNoLogCalibration() throws Exception {
    start();
    transport.replies.add(IccVehicleStateTest.reply(9, 3, 2, 123, 6, 2, 7, 3, 8, 4, 13, 1));
    deliver();
    assertEquals("D 挡", tagged("state.value.gear").getText().toString());
    assertEquals("123 km/h", tagged("state.value.speed").getText().toString());
    assertEquals("已拉起", tagged("state.value.handbrake").getText().toString());
    assertEquals("ON", tagged("state.value.power").getText().toString());
    assertEquals("快充中", tagged("state.value.charge").getText().toString());
    assertEquals("已解锁", tagged("state.value.lock").getText().toString());
    assertFalse(service.diagnostics.live().containsKey("autohold"));
    ReflectionHelpers.callInstanceMethod(activity, "calibrateDashboardState", ReflectionHelpers.ClassParameter.from(String.class, "gear"));
    assertNotNull(find(dialog().findViewById(android.R.id.content), "ICC协议挡位"));
    assertFalse(service.diagnostics.calibrating);
  }

  @Test public void logGearCannotOverwriteIccEvenAfterIccFailure() {
    start(); deliver();
    LogReader.Listener listener = logListener();
    long now = System.currentTimeMillis();
    listener.line(String.format(Locale.ROOT,
        "%d.%03d 1 1 I MessageProperty: receive DrivingInfo/Gear, value: CarPropertyValue{id=0x400000a, area=0x0, status=0, value=2}", now / 1000, now % 1000), now - 1000);
    refresh();
    assertEquals("1", service.diagnostics.live().get("gear").value);
    ReflectionHelpers.callInstanceMethod(service, "stopIccSource");
    listener.line(String.format(Locale.ROOT,
        "%d.%03d 1 1 I MessageProperty: receive DrivingInfo/Gear, value: CarPropertyValue{id=0x400000a, area=0x0, status=0, value=4}", now / 1000, now % 1000), now - 1000);
    refresh();
    assertFalse(service.diagnostics.live().containsKey("gear"));
  }

  @Test public void logDoorsAndIccGearCoexistAndDisablingLogsOnlyClearsLogState() {
    start(); deliver();
    LogReader.Listener listener = logListener();
    long now = System.currentTimeMillis();
    door(listener, "0", now - 10, now - 1000); refresh();
    door(listener, "1", now, now - 1000); refresh();
    assertEquals("1", service.diagnostics.live().get("door.front_left").value);
    assertEquals("1", service.diagnostics.live().get("gear").value);
    store.logsEnabled(false); refresh();
    assertFalse(service.diagnostics.live().containsKey("door.front_left"));
    assertEquals("1", service.diagnostics.live().get("gear").value);
    door(listener, "0", now + 1, now - 1000); refresh();
    assertFalse(service.diagnostics.live().containsKey("door.front_left"));
  }

  @Test public void staleFutureAndDuplicateDoorLogsCannotChangeState() {
    start();
    LogReader.Listener listener = logListener();
    long now = System.currentTimeMillis(), start = now - 1000;
    door(listener, "0", now - 10, start); refresh();
    door(listener, "1", now - 10, start); refresh();
    assertEquals("0", service.diagnostics.live().get("door.front_left").value);
    door(listener, "1", now - 10000, start); refresh();
    door(listener, "1", now + 10000, start); refresh();
    assertEquals("0", service.diagnostics.live().get("door.front_left").value);
  }

  @Test public void mainThreadExpiredDoorLogIsDroppedAndBaselineCleared() {
    start();
    LogReader.Listener listener = logListener();
    long now = System.currentTimeMillis();
    AtomicLong clock = new AtomicLong(now);
    ReflectionHelpers.setField(service, "logClock", (java.util.function.LongSupplier) clock::get);
    door(listener, "0", now - 10, now - 1000); refresh();
    door(listener, "1", now, now - 1000);
    clock.addAndGet(6000); refresh();
    assertFalse(service.diagnostics.live().containsKey("door.front_left"));
  }

  @Test public void logResetDoesNotClearInterfaceAndLateGenerationIsRejected() {
    start(); deliver();
    LogReader.Listener listener = logListener();
    long now = System.currentTimeMillis();
    door(listener, "0", now - 10, now - 1000); refresh();
    listener.reset(); refresh();
    assertFalse(service.diagnostics.live().containsKey("door.front_left"));
    assertEquals("1", service.diagnostics.live().get("gear").value);
    store.logsEnabled(false); store.logsEnabled(true); refresh();
    door(listener, "1", now, now - 1000); refresh();
    assertFalse(service.diagnostics.live().containsKey("door.front_left"));
  }

  @Test public void interfaceIntervalChangesDoNotRestartAuthorizedLogRequest() {
    start(); logListener();
    Object retained = ReflectionHelpers.getField(service, "logs");
    IccVehicleProbeTest.Transport previous = transport;
    store.iccIntervalMillis(500); refresh();
    assertSame(retained, ReflectionHelpers.getField(service, "logs"));
    assertTrue(previous.closed);
    assertEquals(1, worker.tasks.size());
  }

  @Test public void retryWaitShowsUnavailableStateAndRecoveryWithoutManualReconnect() throws Exception {
    start(); deliver();
    transport.error = new java.net.SocketTimeoutException();
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(store.iccIntervalMillis()));
    deliver();
    assertTrue(service.running());
    assertFalse(service.diagnostics.live().containsKey("gear"));
    assertEquals("待新样本", tagged("state.value.gear").getText().toString());
    assertTrue(service.diagnostics.iccVehicleSourceSummary().contains("retry-wait"));
    assertTrue(service.diagnostics.iccVehicleSourceSummary().contains("自动重试"));
    JSONObject report = new JSONObject(service.diagnostics.export(false, store));
    assertEquals("0.3.35", report.getString("version"));
    assertTrue(report.getJSONObject("iccVehicleSource").getBoolean("retryScheduled"));
    assertTrue(report.getJSONObject("iccVehicleSource").getBoolean("active"));
    transport.error = null;
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
    deliver();
    assertEquals("P 挡", tagged("state.value.gear").getText().toString());
    assertEquals(1, service.diagnostics.iccVehicleSourceSnapshot().getLong("recoveries"));
  }

  @Test public void logsStayOffWhenStoppedUntilUserStartsMonitoring() {
    sourceMenu(); shadowOf(dialog()).clickOnItem(1);
    dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); refresh();
    assertTrue(store.logsEnabled());
    assertFalse(service.running());
    assertNull(ReflectionHelpers.getField(service, "logs"));
    assertNull(worker);
    assertTrue(new Store(activity).logsEnabled());
  }

  @Test public void rulesAndFailedReadingRoutesAreNotInNavigationOrOptions() {
    assertNull(find(root(), "规则管理"));
    find(root(), "更多").performClick();
    assertEquals(5, dialog().getListView().getAdapter().getCount());
    sourceMenu();
    assertEquals(3, dialog().getListView().getAdapter().getCount());
    assertEquals("ICC接口：查询间隔", dialog().getListView().getAdapter().getItem(0));
  }

  @Test public void exportingDeclaresFixedPolicyAndExcludesOldRules() throws Exception {
    store.addRule(new JSONObject().put("id", "old").put("event", "gear.r"));
    start(); deliver();
    JSONObject report = new JSONObject(service.diagnostics.export(false, store));
    assertEquals("icc", report.getString("eventBackend"));
    assertFalse(report.has("rules"));
    assertFalse(report.getJSONObject("sourcePolicy").getBoolean("automaticFallback"));
    store.logsEnabled(true); refresh();
    report = new JSONObject(service.diagnostics.export(false, store));
    assertEquals("icc+authorized-log", report.getString("eventBackend"));
    assertTrue(report.getBoolean("authorizedLogEnabled"));
    assertFalse(transport.closed);
  }

  @Test public void leavingPageAndBackgroundingDoNotStopFormalSources() {
    start(); deliver(); logListener();
    IccVehicleProbeTest.Transport retained = transport;
    find(root(), "音效设置").performClick();
    activityController.pause();
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    assertFalse(retained.closed);
    assertTrue(service.running());
    assertNotNull(ReflectionHelpers.getField(service, "logs"));
  }

  @Test public void allStatesBelongToExactlyOneSource() {
    for (VehicleStateSpec spec : VehicleStateSpec.ALL)
      assertTrue(VehicleStateSpec.interfaceKey(spec.key) ^ VehicleStateSpec.logKey(spec.key));
    assertFalse(VehicleStateSpec.logKey(null));
    assertFalse(VehicleStateSpec.interfaceKey("unknown"));
  }

  @Test public void lockSourceCanBeSelectedFromDashboardWithoutEnablingOrAuthorizingLogs() throws Exception {
    start(); deliver();
    ReflectionHelpers.callInstanceMethod(activity, "calibrateDashboardState",
        ReflectionHelpers.ClassParameter.from(String.class, "lock"));
    assertEquals("锁事件来源（ICC / 授权日志）", dialog().getListView().getAdapter().getItem(3));
    shadowOf(dialog()).clickOnItem(3);
    shadowOf(dialog()).clickOnItem(1);
    refresh();
    assertTrue(store.logLockSelected());
    assertFalse(store.logsEnabled());
    assertNull(ReflectionHelpers.getField(service, "logs"));
    assertNull(shadowOf(activity).getLastRequestedPermission());
    assertEquals("日志未启用", tagged("state.value.lock").getText().toString());
    assertFalse(service.diagnostics.calibrating);
    JSONObject policy = new JSONObject(service.diagnostics.export(false, store)).getJSONObject("sourcePolicy");
    assertTrue(policy.getJSONArray("authorizedLog").toString().contains("lock"));
    assertFalse(policy.getJSONArray("interface").toString().contains("lock"));
  }

  @Test public void logLockCalibrationReusesOldValuesAndSavingDoesNotSelectOrEnableLogs() {
    store.calibrateLock("1", "0");
    ReflectionHelpers.callInstanceMethod(activity, "lockCalibration");
    shadowOf(Looper.getMainLooper()).idle();
    View body = dialog().getWindow().getDecorView();
    android.widget.EditText locked = body.findViewWithTag("lock.log.locked");
    android.widget.EditText unlocked = body.findViewWithTag("lock.log.unlocked");
    assertEquals("1", locked.getText().toString());
    assertEquals("0", unlocked.getText().toString());
    locked.setText("0");
    dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    assertTrue(dialog().isShowing());
    assertEquals("locked", store.lockState("1"));
    unlocked.setText("1");
    dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    refresh();
    assertEquals("locked", store.lockState("0"));
    assertFalse(store.logLockSelected());
    assertFalse(store.logsEnabled());
  }

  @Test public void confirmedSpeedAndOmissionAreShownWithoutHistoricalValuesBecomingLive() throws Exception {
    start();
    transport.replies.add(IccVehicleStateTest.reply(9, 3, 2, 123));
    deliver();
    TextView candidates = tagged("state.speed.candidates");
    assertEquals("123 km/h", tagged("state.value.speed").getText().toString());
    assertTrue(tagged("state.raw.speed").getText().toString().contains("123"));
    assertTrue(candidates.getText().toString().contains("原值直读"));
    assertFalse(candidates.getText().toString().contains("÷10"));
    assertTrue(new JSONObject(service.diagnostics.export(false, store)).getJSONObject("iccConfiguration")
        .getBoolean("speedUnitConfirmed"));
    transport.replies.add(IccVehicleStateTest.reply(9, 3));
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(store.iccIntervalMillis()));
    deliver();
    assertEquals("0 km/h", tagged("state.value.speed").getText().toString());
    assertTrue(candidates.getText().toString().contains("省略字段"));
    assertFalse(candidates.getText().toString().contains("A ×1"));
    transport.replies.add(IccVehicleStateTest.reply(9, 3, 2, 0));
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(store.iccIntervalMillis()));
    deliver();
    assertTrue(candidates.getText().toString().contains("0 km/h"));
    StateDashboardView dashboard = ReflectionHelpers.getField(activity, "stateDashboard");
    dashboard.update(service.diagnostics, store, true, System.currentTimeMillis() + 6000);
    assertFalse(candidates.getText().toString().contains("A ×1"));
    service.end();
    refresh();
    assertFalse(candidates.getText().toString().contains("A ×1"));
    assertFalse(new JSONObject(service.diagnostics.export(false, store)).getJSONObject("iccSpeedComparison")
        .getBoolean("freshReply"));
    SoundEngine audio = ReflectionHelpers.getField(service, "audio");
    assertNull(ReflectionHelpers.getField(audio, "loop"));
  }

  @Test public void splitSourceDashboardRenders() throws Exception {
    start();
    transport.replies.add(IccVehicleStateTest.reply(9, 3, 2, 123, 6, 2, 7, 3, 8, 4, 13, 1));
    deliver();
    View view = root();
    view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, 1280, 1400);
    Bitmap bitmap = Bitmap.createBitmap(1280, 1400, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File directory = new File("dist/screenshots"); directory.mkdirs();
    try (FileOutputStream output = new FileOutputStream(new File(directory, "interface-and-log-dashboard.png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
    } finally { bitmap.recycle(); }
  }
}
