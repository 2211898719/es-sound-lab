package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class StandardCarProbeTest {
  static final class QueueExecutor extends AbstractExecutorService {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    @Override public void execute(Runnable task) { tasks.add(task); }
    void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    @Override public void shutdown() {}
    @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
  }

  static final class FakeTransport implements StandardCarProbe.Transport {
    StandardCarProbe.Sink sink;
    final List<String> operations = new ArrayList<>();
    boolean ready = true, disconnectError, registerError, readError, unregisterError;
    boolean missingClass, missingMethod;
    boolean registerResult = true;
    boolean securityReadDenied, missingReadMethod;
    Object selectionRaw = 0, currentRaw = 0;
    int readStatus;
    long fixedTimestamp = -1;
    Runnable duringRegister, duringRead;
    @Override public void connect(StandardCarProbe.Sink receiver) throws Exception {
      operations.add("connect"); sink = receiver;
      if (missingClass) throw new ClassNotFoundException("android.car.Car");
      if (ready) receiver.ready(true);
    }
    @Override public void prepare(StandardCarProbe.Sink receiver) throws Exception {
      operations.add("prepare");
      if (missingMethod) throw new NoSuchMethodException("getCarManager");
    }
    @Override public boolean register(int property) {
      operations.add("register:" + property);
      if (duringRegister != null) duringRegister.run();
      if (registerError && property == StandardCarProbe.SELECTION) throw new SecurityException("read-only denied");
      return registerResult;
    }
    @Override public StandardCarProbe.Value read(int property) throws Exception {
      operations.add("read:" + property);
      if (duringRead != null) duringRead.run();
      if (securityReadDenied && property == StandardCarProbe.SELECTION) throw new SecurityException("read denied");
      if (missingReadMethod && property == StandardCarProbe.SELECTION) throw new NoSuchMethodException("getProperty");
      if (readError && property == StandardCarProbe.SELECTION) throw new IllegalStateException("unavailable");
      return new StandardCarProbe.Value(property, 0, readStatus,
          fixedTimestamp < 0 ? SystemClock.elapsedRealtimeNanos() : fixedTimestamp,
          property == StandardCarProbe.SELECTION ? selectionRaw : currentRaw);
    }
    @Override public void unregister(int property) {
      operations.add("unregister:" + property);
      if (unregisterError) throw new IllegalStateException("unregister failed");
    }
    @Override public void disconnect() {
      operations.add("disconnect");
      if (disconnectError) throw new IllegalStateException("disconnect failed");
    }
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private Application app;
  private Diagnostics diagnostics;
  private QueueExecutor executor;
  private FakeTransport transport;
  private StandardCarProbe probe;

  @Before public void setup() {
    app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("standard_car_probe", 0).edit().clear().commit();
    diagnostics = new Diagnostics(); diagnostics.attach(app);
    executor = new QueueExecutor(); transport = new FakeTransport();
    probe = new StandardCarProbe(main, diagnostics, executor, transport);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
  }
  @After public void cleanup() {
    probe.stop("test-finished", "cleanup"); executor.drain(); shadowOf(Looper.getMainLooper()).idle();
  }
  private static StandardCarProbe.Value value(int property, int status, Object raw) {
    return new StandardCarProbe.Value(property, 0, status, SystemClock.elapsedRealtimeNanos(), raw);
  }
  private void start() { probe.start(); executor.drain(); shadowOf(Looper.getMainLooper()).idle(); }
  private JSONObject item(int property) throws Exception {
    return probe.snapshot().getJSONObject("properties").getJSONObject(Integer.toString(property));
  }
  private void emit(int status, Object raw) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
    transport.sink.value(value(StandardCarProbe.SELECTION, status, raw));
    shadowOf(Looper.getMainLooper()).idle();
  }
  private void pollOnce() {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StandardCarProbe.POLL_INTERVAL));
    executor.drain(); shadowOf(Looper.getMainLooper()).idle();
  }

  @Test public void manualStartOnlyRegistersAndReadsTwoPropertiesWithNoAudio() throws Exception {
    assertTrue(executor.tasks.isEmpty());
    assertFalse(probe.active()); start();
    assertEquals(List.of("connect", "prepare", "register:" + StandardCarProbe.SELECTION,
        "read:" + StandardCarProbe.SELECTION, "register:" + StandardCarProbe.CURRENT,
        "read:" + StandardCarProbe.CURRENT), transport.operations);
    assertTrue(probe.active());
    assertEquals("listening", probe.snapshot().getString("phase"));
    assertFalse(probe.snapshot().getBoolean("audioConnected"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("reads"));
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("callbacks"));
    assertEquals(0, item(StandardCarProbe.SELECTION).getJSONObject("lastRead").getInt("raw"));
    assertFalse(item(StandardCarProbe.SELECTION).getJSONObject("lastRead").getBoolean("valid"));
  }

  @Test public void selectionAndCurrentAreIndependentAndReadDoesNotEstablishCallbackBaseline() throws Exception {
    start(); emit(0, 4); emit(0, 2); emit(0, 1); emit(0, 8);
    assertEquals(3, item(StandardCarProbe.SELECTION).getInt("changes"));
    assertEquals(0, item(StandardCarProbe.CURRENT).optInt("changes"));
    assertEquals(4, item(StandardCarProbe.SELECTION).getInt("callbacks"));
    assertEquals("D", item(StandardCarProbe.SELECTION).getJSONObject("lastCallback").getString("label"));
  }

  @Test public void zeroUnavailableMalformedAndErrorsDoNotBecomeGearChanges() throws Exception {
    start(); emit(0, 0); emit(1, 4); emit(2, 2); emit(0, "4"); emit(0, null); emit(0, 4);
    transport.sink.error(StandardCarProbe.SELECTION, "area=0 code=5");
    shadowOf(Looper.getMainLooper()).idle(); emit(0, 2);
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("changes"));
    assertEquals(5, item(StandardCarProbe.SELECTION).getInt("invalidOrUnorderedCallbacks"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("errorCallbacks"));
  }

  @Test public void duplicateAndOutOfOrderTimestampsDoNotCountChanges() throws Exception {
    start(); emit(0, 4);
    long timestamp = item(StandardCarProbe.SELECTION).getJSONObject("lastCallback").getLong("sourceTimestampNanos");
    transport.sink.value(new StandardCarProbe.Value(StandardCarProbe.SELECTION, 0, 0, timestamp, 2));
    transport.sink.value(new StandardCarProbe.Value(StandardCarProbe.SELECTION, 0, 0, timestamp - 1, 8));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("changes"));
    assertEquals(2, item(StandardCarProbe.SELECTION).getInt("invalidOrUnorderedCallbacks"));
  }

  @Test public void zeroFutureTimestampAndNonGlobalAreaAreNotValidChanges() throws Exception {
    start(); emit(0, 4);
    transport.sink.value(new StandardCarProbe.Value(StandardCarProbe.SELECTION, 0, 0, 0, 2));
    transport.sink.value(new StandardCarProbe.Value(StandardCarProbe.SELECTION, 0, 0,
        SystemClock.elapsedRealtimeNanos() + 1000000000L, 8));
    transport.sink.value(new StandardCarProbe.Value(StandardCarProbe.SELECTION, 1, 0,
        SystemClock.elapsedRealtimeNanos(), 1));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("changes"));
  }

  @Test public void individualRegistrationAndReadErrorsKeepOtherPropertyEvidence() throws Exception {
    transport.registerError = true; transport.readError = true; start();
    assertTrue(item(StandardCarProbe.SELECTION).getString("registerError").contains("SecurityException"));
    assertTrue(item(StandardCarProbe.SELECTION).has("readError"));
    assertTrue(item(StandardCarProbe.CURRENT).getBoolean("registered"));
    assertEquals(1, item(StandardCarProbe.CURRENT).getInt("reads"));
    probe.stop("stopped", "done"); executor.drain();
    assertTrue(transport.operations.contains("unregister:" + StandardCarProbe.SELECTION));
  }

  @Test public void falseRegistrationDoesNotMeanSubscribedButStillReadsAndCleansUp() throws Exception {
    transport.registerResult = false; start();
    assertFalse(item(StandardCarProbe.SELECTION).getBoolean("registered"));
    assertFalse(item(StandardCarProbe.SELECTION).getBoolean("registerResult"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("reads"));
    probe.stop("stopped", "done"); executor.drain();
    assertTrue(probe.snapshot().getBoolean("cleanupConfirmed"));
  }

  @Test public void stopUnregistersBothExactlyOnceAndIgnoresLateCallbacks() throws Exception {
    start(); emit(0, 4); probe.stop("stopped", "export"); probe.stop("stopped", "again");
    transport.sink.value(value(StandardCarProbe.SELECTION, 0, 2));
    executor.drain(); shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("callbacks"));
    assertEquals(1, Collections.frequency(transport.operations, "disconnect"));
    assertEquals(1, Collections.frequency(transport.operations, "unregister:" + StandardCarProbe.CURRENT));
    assertFalse(item(StandardCarProbe.SELECTION).getBoolean("registered"));
    assertTrue(probe.snapshot().getBoolean("cleanupConfirmed"));
  }

  @Test public void sixtySecondDeadlineStopsAndCleansUp() throws Exception {
    start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60)); executor.drain();
    assertFalse(probe.active()); assertEquals("completed", probe.snapshot().getString("phase"));
    assertFalse(probe.snapshot().has("failedPhase"));
    assertTrue(probe.snapshot().getBoolean("cleanupConfirmed"));
  }

  @Test public void missingCarClassFailsPreciselyWithoutInventedZeroOrRetry() throws Exception {
    transport.missingClass = true; start();
    assertEquals("failed", probe.snapshot().getString("phase"));
    assertEquals("connecting", probe.snapshot().getString("failedPhase"));
    assertTrue(probe.snapshot().getString("error").contains("ClassNotFoundException: android.car.Car"));
    assertFalse(item(StandardCarProbe.SELECTION).has("lastRead"));
    assertEquals(List.of("connect", "disconnect"), transport.operations);
  }

  @Test public void missingManagerMethodDisconnectsWithoutRegistering() throws Exception {
    transport.missingMethod = true; start();
    assertEquals("property-manager", probe.snapshot().getString("failedPhase"));
    assertTrue(probe.snapshot().getString("error").contains("NoSuchMethodException"));
    assertEquals(List.of("connect", "prepare", "disconnect"), transport.operations);
  }

  @Test public void disconnectNotificationStopsAndLateReadyDoesNotReconnect() throws Exception {
    start(); transport.sink.ready(false); transport.sink.ready(true); executor.drain();
    assertEquals("disconnected", probe.snapshot().getString("phase"));
    assertEquals(1, Collections.frequency(transport.operations, "connect"));
    assertEquals(1, Collections.frequency(transport.operations, "prepare"));
  }

  @Test public void oldSessionCleanupCannotOverwriteNewReport() throws Exception {
    start(); probe.stop("stopped", "old session");
    JSONObject latest = new JSONObject().put("sessionId", Long.MAX_VALUE).put("phase", "new-session");
    diagnostics.beginStandardCarProbe(latest); executor.drain();
    assertEquals("new-session", diagnostics.standardCarProbeSnapshot().getString("phase"));
    assertEquals(Long.MAX_VALUE, Diagnostics.latestStandardCarProbe(app).getLong("sessionId"));
  }

  @Test public void connectionTimeoutReleasesOnlyAfterCleanupAndBlocksStackedCalls() throws Exception {
    transport.ready = false; start();
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
    assertTrue(probe.snapshot().getBoolean("cleanupPending"));
    StandardCarProbe next = new StandardCarProbe(main, diagnostics, executor, new FakeTransport());
    next.start(); assertEquals("busy", next.snapshot().getString("phase"));
    executor.drain();
    assertFalse(probe.snapshot().getBoolean("cleanupPending"));
    assertFalse(transport.operations.contains("prepare"));
  }

  @Test public void timeoutDuringRegisterDoesNotReadOrRegisterNextAndStillUnregisters() throws Exception {
    transport.duringRegister = () -> shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
    start();
    assertEquals("timeout", probe.snapshot().getString("phase"));
    assertFalse(transport.operations.contains("read:" + StandardCarProbe.SELECTION));
    assertFalse(transport.operations.contains("register:" + StandardCarProbe.CURRENT));
    assertTrue(transport.operations.contains("unregister:" + StandardCarProbe.SELECTION));
    assertFalse(item(StandardCarProbe.SELECTION).getBoolean("registered"));
  }

  @Test public void cleanupFailuresRemainExplicitAndStillTryDisconnect() throws Exception {
    transport.unregisterError = true; transport.disconnectError = true; start();
    probe.stop("stopped", "done"); executor.drain();
    assertFalse(probe.snapshot().getBoolean("cleanupConfirmed"));
    assertTrue(probe.snapshot().has("disconnectError"));
    assertTrue(item(StandardCarProbe.CURRENT).has("unregisterError"));
  }

  @Test public void persistenceExportAndInterruptedSessionAreIndependentOfAudio() throws Exception {
    start(); emit(0, 4); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    assertEquals(probe.snapshot().getLong("sessionId"), Diagnostics.latestStandardCarProbe(app).getLong("sessionId"));
    JSONObject exported = new JSONObject(diagnostics.export(false, new Store(app)));
    assertEquals(1, exported.getJSONObject("standardCarProbe").getJSONObject("properties")
        .getJSONObject(Integer.toString(StandardCarProbe.SELECTION)).getInt("callbacks"));
    assertEquals(0, exported.getJSONArray("candidates").length());
    Diagnostics restored = new Diagnostics(); restored.attach(app);
    assertEquals("previous-session-interrupted", restored.standardCarProbeSnapshot().getString("phase"));
    assertTrue(restored.standardCarProbeSnapshot().getBoolean("cleanupUnknown"));
    assertFalse(restored.standardCarProbeSnapshot().getJSONObject("properties")
        .getJSONObject(Integer.toString(StandardCarProbe.SELECTION)).getBoolean("registered"));
  }

  @Test public void boundedQueueAndSampleHistoryAndDeepCopies() throws Exception {
    start();
    for (int index = 0; index < 100; index++) transport.sink.value(value(StandardCarProbe.SELECTION, 0, 4));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(36, probe.snapshot().getInt("droppedCallbacks"));
    for (int index = 0; index < 350; index++) emit(0, index % 2 == 0 ? 4 : 2);
    assertEquals(StandardCarProbe.MAX_SAMPLES, probe.snapshot().getJSONArray("samples").length());
    assertEquals(120, probe.snapshot().getJSONArray("events").length());
    assertTrue(probe.snapshot().getInt("samplesDropped") > 0);
    JSONObject copy = probe.snapshot(); copy.put("phase", "changed");
    assertEquals("listening", probe.snapshot().getString("phase"));
  }

  @Test public void standardEnumsAreNotMegaEnums() {
    assertEquals("P", StandardCarProbe.label(4)); assertEquals("R", StandardCarProbe.label(2));
    assertEquals("N", StandardCarProbe.label(1)); assertEquals("D", StandardCarProbe.label(8));
    assertEquals("前进1挡", StandardCarProbe.label(16));
    assertEquals("UNKNOWN/未识别", StandardCarProbe.label(0));
    assertEquals("UNKNOWN/未识别", StandardCarProbe.label(3));
  }

  @Test public void everySecondReadsBothButRegistersOnlyOnceAndKeepsCallbackIndependent() throws Exception {
    transport.selectionRaw = 4; transport.currentRaw = 4; start();
    emit(0, 4); transport.selectionRaw = 2;
    pollOnce(); transport.selectionRaw = 8; pollOnce();
    assertEquals(3, item(StandardCarProbe.SELECTION).getInt("reads"));
    assertEquals(2, item(StandardCarProbe.SELECTION).getInt("pollReads"));
    assertEquals(2, item(StandardCarProbe.SELECTION).getInt("readChanges"));
    assertEquals(0, item(StandardCarProbe.CURRENT).optInt("readChanges"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("callbacks"));
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("changes"));
    assertEquals(4, item(StandardCarProbe.SELECTION).getJSONObject("lastCallback").getInt("raw"));
    assertEquals(8, item(StandardCarProbe.SELECTION).getJSONObject("lastRead").getInt("raw"));
    assertEquals("poll", item(StandardCarProbe.SELECTION).getJSONObject("lastRead").getString("readKind"));
    assertEquals(1, Collections.frequency(transport.operations, "register:" + StandardCarProbe.SELECTION));
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("读取 D（原值8） / 回调 P（原值4）"));
    JSONObject exported = new JSONObject(diagnostics.export(false, new Store(app)))
        .getJSONObject("standardCarProbe");
    assertEquals(1000, exported.getInt("pollIntervalMillis"));
    assertEquals(2, exported.getJSONObject("properties").getJSONObject(Integer.toString(StandardCarProbe.SELECTION))
        .getInt("readChanges"));
    assertEquals(0, new JSONObject(diagnostics.export(false, new Store(app))).getJSONArray("candidates").length());
  }

  @Test public void unchangedCachedTimestampIsNotReportedAsFreshEvenIfReadValueChanges() throws Exception {
    transport.fixedTimestamp = 1; transport.selectionRaw = 4; start();
    pollOnce(); assertEquals(0, item(StandardCarProbe.SELECTION).optInt("readChanges"));
    transport.selectionRaw = 2; pollOnce();
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("readChanges"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("readChangesWithoutTimestampAdvance"));
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("readTimestampAdvances"));
    assertFalse(item(StandardCarProbe.SELECTION).getJSONObject("lastRead").getBoolean("sourceTimestampAdvanced"));
  }

  @Test public void validPollReadWithAdvancingTimestampHasSeparateAdvanceCount() throws Exception {
    transport.selectionRaw = 4; start(); pollOnce(); transport.selectionRaw = 2; pollOnce();
    assertEquals(2, item(StandardCarProbe.SELECTION).getInt("readTimestampAdvances"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("readChanges"));
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("readChangesWithoutTimestampAdvance"));
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("changes"));
  }

  @Test public void readErrorsClearReadBaselineAndDoNotFreezeOtherProperty() throws Exception {
    transport.selectionRaw = 4; start(); transport.readError = true; pollOnce();
    assertFalse(item(StandardCarProbe.SELECTION).getBoolean("lastReadSucceeded"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("readFailures"));
    assertEquals(4, item(StandardCarProbe.SELECTION).getJSONObject("lastRead").getInt("raw"));
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("最新读取失败，显示上次值"));
    transport.readError = false; transport.selectionRaw = 2; pollOnce();
    assertTrue(item(StandardCarProbe.SELECTION).getBoolean("lastReadSucceeded"));
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("readChanges"));
    assertEquals(3, item(StandardCarProbe.CURRENT).getInt("reads"));
  }

  @Test public void zeroUnavailableAndNonIntegerPollReadsDoNotInventChanges() throws Exception {
    transport.selectionRaw = 4; start(); transport.selectionRaw = 0; pollOnce();
    transport.selectionRaw = "2"; pollOnce(); transport.selectionRaw = 2; transport.readStatus = 1; pollOnce();
    transport.readStatus = 0; transport.selectionRaw = 8; pollOnce();
    assertEquals(0, item(StandardCarProbe.SELECTION).optInt("readChanges"));
  }

  @Test public void knownDeniedAndMissingReadMethodAreNotRepeated() throws Exception {
    transport.securityReadDenied = true; start(); pollOnce(); pollOnce();
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("readAttempts"));
    assertTrue(item(StandardCarProbe.SELECTION).getBoolean("readDisabled"));
    assertEquals(3, item(StandardCarProbe.CURRENT).getInt("reads"));
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("轮询已暂停"));
    probe.stop("stopped", "done"); executor.drain();
    transport = new FakeTransport(); transport.missingReadMethod = true;
    probe = new StandardCarProbe(main, diagnostics, executor, transport); start(); pollOnce();
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("readAttempts"));
    assertTrue(item(StandardCarProbe.SELECTION).getString("readError").contains("NoSuchMethodException"));
  }

  @Test public void onlyOnePollMayBeQueuedAndNextIntervalStartsAfterPreviousFinishes() throws Exception {
    start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
    assertEquals(1, executor.tasks.size()); assertTrue(probe.snapshot().getBoolean("pollInFlight"));
    executor.drain(); assertFalse(probe.snapshot().getBoolean("pollInFlight"));
    assertEquals(2, item(StandardCarProbe.SELECTION).getInt("reads"));
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999)); assertTrue(executor.tasks.isEmpty());
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1)); assertEquals(1, executor.tasks.size());
    executor.drain();
  }

  @Test public void stoppedQueuedPollNeverReadsOrReschedules() throws Exception {
    start(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    probe.stop("stopped", "export"); executor.drain();
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); executor.drain();
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("reads"));
    assertEquals(1, item(StandardCarProbe.CURRENT).getInt("reads"));
    assertTrue(executor.tasks.isEmpty()); assertTrue(probe.snapshot().getBoolean("cleanupConfirmed"));
  }

  @Test public void pollOperationTimeoutStopsNextReadAndDiscardsLateReturn() throws Exception {
    start(); transport.duringRead = () -> shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
    pollOnce();
    assertFalse(probe.active()); assertEquals("timeout", probe.snapshot().getString("phase"));
    assertEquals("poll-GEAR_SELECTION", probe.snapshot().getString("failedPhase"));
    assertEquals(1, item(StandardCarProbe.SELECTION).getInt("reads"));
    assertEquals(1, item(StandardCarProbe.CURRENT).getInt("reads"));
    assertTrue(probe.snapshot().getBoolean("cleanupConfirmed"));
  }

  @Test public void pollsDoNotExtendSixtySecondsAndHistoryKeepsWholeReadSession() throws Exception {
    start();
    for (int index = 0; index < 59; index++) pollOnce();
    assertTrue(probe.active()); assertEquals(60, item(StandardCarProbe.SELECTION).getInt("reads"));
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); executor.drain();
    assertEquals("completed", probe.snapshot().getString("phase"));
    assertEquals(60000, probe.snapshot().getLong("durationMillis"));
    assertEquals(120, probe.snapshot().getJSONArray("samples").length());
    assertEquals(0, probe.snapshot().optInt("samplesDropped"));
    assertTrue(executor.tasks.isEmpty());
  }

  @Test public void liveSummaryShowsCountdownCountsReceiveTimesAndDoesNotDependOnRevision() throws Exception {
    transport.selectionRaw = 4; start();
    long revision = diagnostics.revision;
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("剩余 60秒"));
    transport.selectionRaw = 2; pollOnce(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    assertEquals(revision, diagnostics.revision);
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("读取 R（原值2） / 回调 等待数据"));
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("读取原值变化 1"));
    assertTrue(diagnostics.standardCarProbeSummary().contains("收到于"));
    assertTrue(diagnostics.standardCarProbeSummary().contains("定时读取=1"));
    assertTrue(diagnostics.standardCarProbeLiveSummary().contains("剩余 59秒"));
    probe.stop("stopped", "test");
    assertTrue(diagnostics.standardCarProbeLiveSummary().startsWith("已停止"));
  }

  @Test public void retiredCarProbeIsNotOwnedByMonitorService() {
    try {
      MonitorService.class.getDeclaredField("standardCarProbe");
      fail("retired reader remains attached to runtime service");
    } catch (NoSuchFieldException expected) {
      assertFalse(probe.active());
    }
  }

  @Test public void retiredCarProbeHasNoVisibleStartButton() throws Exception {
    try (org.robolectric.android.controller.ActivityController<MainActivity> activityController =
             org.robolectric.Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
      MainActivity activity = activityController.get();
      find(activity.findViewById(android.R.id.content), "车辆检测").performClick();
      assertNull(find(activity.findViewById(android.R.id.content), "开始标准Car挡位检测"));
      assertNotNull(find(activity.findViewById(android.R.id.content), "更多"));
      assertFalse(Diagnostics.latestStandardCarProbe(app).has("sessionId"));
      activityController.pause().stop().destroy();
    }
  }

  private static android.view.View find(android.view.View view, String text) {
    if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return view;
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        android.view.View match = find(group.getChildAt(index), text);
        if (match != null) return match;
      }
    }
    return null;
  }
}
