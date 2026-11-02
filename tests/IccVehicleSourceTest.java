package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
public final class IccVehicleSourceTest {
  private final AtomicLong clock = new AtomicLong(100000);
  private final List<Protocol.Sample> readings = new ArrayList<>();
  private Diagnostics diagnostics;
  private IccVehicleProbeTest.Transport transport;
  private IccVehicleProbeTest.Worker worker;
  private IccVehicleSource source;
  private int resets;

  @Before public void setup() {
    RuntimeEnvironment.getApplication().getSharedPreferences("icc_vehicle_source", 0).edit().clear().commit();
    diagnostics = new Diagnostics(); diagnostics.attach(RuntimeEnvironment.getApplication());
    transport = new IccVehicleProbeTest.Transport(); worker = new IccVehicleProbeTest.Worker();
    source = create(250);
  }

  private IccVehicleSource create(int interval) {
    return new IccVehicleSource(new Handler(Looper.getMainLooper()), diagnostics, new IccVehicleSource.Listener() {
      public void sample(Protocol.Sample sample) { readings.add(sample); }
      public void reset() { resets++; }
    }, interval, transport, worker, clock::get, clock::get);
  }

  @After public void cleanup() { source.stop("stopped", "test cleanup"); }
  private JSONObject report() { return diagnostics.iccVehicleSourceSnapshot(); }
  private void deliver() { worker.runNext(); shadowOf(Looper.getMainLooper()).idle(); }
  private void advance(long millis) {
    clock.addAndGet(millis); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
  }

  @Test public void manualStartOnlyAndDefaultDelayIs250MillisAfterCompletion() throws Exception {
    assertFalse(source.active()); assertEquals(0, worker.tasks.size());
    source.start(); assertEquals(1, worker.tasks.size()); assertEquals(0, transport.calls);
    advance(1100); assertEquals(1, worker.tasks.size());
    deliver(); assertEquals(6, readings.size()); assertEquals(1100, report().getInt("lastDurationMillis"));
    advance(249); assertEquals(0, worker.tasks.size());
    advance(1); assertEquals(1, worker.tasks.size()); deliver();
    assertEquals(250, report().getInt("minIntervalMillis"));
    assertTrue(report().getBoolean("audioConnected"));
    assertFalse(report().getBoolean("upstreamTimestampAvailable"));
  }

  @Test public void allGearEnumsMapIndependentlyAndMissingOrUnknownCannotBecomeP() throws Exception {
    String[] canonical = {null, "1", "3", "4", "2", null};
    for (int index = 0; index < canonical.length; index++) {
      Protocol.Sample sample = IccVehicleWire.decode(new byte[] {72, (byte) index}).gearSample(1);
      assertEquals(canonical[index], sample.value); assertEquals(Integer.toString(index), sample.raw);
      assertTrue(IccVehicleWire.gear(sample));
    }
    Protocol.Sample missing = IccVehicleWire.decode(new byte[] {48, 2}).gearSample(1);
    assertNull(missing.value); assertEquals("MISSING", missing.raw);
  }

  @Test public void continuousModeHasNoThirtyAttemptCapButHistoryIsBounded() throws Exception {
    source.start(); deliver();
    for (int index = 0; index < 270; index++) { advance(250); deliver(); }
    assertTrue(source.active()); assertEquals(271, report().getInt("attempts"));
    assertEquals(271, report().getInt("http200")); assertEquals(30, report().getJSONArray("samples").length());
    assertEquals(271 * 6, readings.size());
  }

  @Test public void intervalPresetsAreValidatedAndCorruptPreferencesUseDefault() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication()); store.prefs.edit().clear().commit();
    assertEquals(250, store.iccIntervalMillis());
    for (int interval : new int[] {250, 500, 1000}) {
      store.iccIntervalMillis(interval); assertEquals(interval, store.iccIntervalMillis());
    }
    store.iccIntervalMillis(0); assertEquals(1000, store.iccIntervalMillis());
    store.prefs.edit().putInt("icc.intervalMillis", -1).commit(); assertEquals(250, store.iccIntervalMillis());
    source = create(500); source.start(); deliver(); advance(499); assertTrue(worker.tasks.isEmpty());
    advance(1); assertEquals(1, worker.tasks.size());
  }

  @Test public void queueExpiryDiscardsSnapshotAndResetsBaseline() throws Exception {
    source.start(); deliver(); advance(250);
    worker.runNext(); advance(5001);
    assertEquals(6, readings.size()); assertTrue(resets >= 2);
    assertEquals(1, report().getInt("expired"));
  }

  @Test public void longRequestCannotBeTreatedAsFreshJustBecauseItsCallbackIsNew() throws Exception {
    source.start(); clock.addAndGet(5001); deliver();
    assertEquals(0, readings.size()); assertEquals(1, report().getInt("expired"));
  }

  @Test public void invalidationDropsAnInFlightQueryBeforeReestablishingBaseline() throws Exception {
    source.start(); source.invalidate("calibration changed"); deliver();
    assertEquals(0, readings.size()); assertEquals(1, report().getInt("epochDropped"));
    advance(250); deliver(); assertEquals(6, readings.size()); assertEquals(0, report().getInt("gearChanges"));
  }

  @Test public void unknownGearClearsItsBaselineWhileOtherMissingFieldsStayUnknown() throws Exception {
    for (int raw : new int[] {1, 3, 0, 4, 1}) transport.replies.add(IccVehicleProbeTest.gear(raw));
    source.start(); deliver();
    for (int index = 1; index < 5; index++) { advance(250); deliver(); }
    assertNull(readings.get(12).value); assertEquals(2, report().getInt("gearChanges"));
    assertEquals(30, readings.size());
    for (Protocol.Sample sample : readings) if (!sample.key.equals("gear")) {
      assertEquals("MISSING", sample.raw);
      if (sample.key.equals("speed") && readings.indexOf(sample) / 6 != 2) assertEquals("0", sample.value);
      else assertNull(sample.value);
    }
    assertTrue(diagnostics.stateReadings().isEmpty());
  }

  @Test public void httpFailureKeepsMonitoringAndRetriesWithoutChangingEndpointOrReadingBody() throws Exception {
    transport.replies.add(new IccVehicleProbe.Reply(403, new byte[] {72, 4}));
    source.start(); deliver();
    assertTrue(source.active()); assertFalse(transport.closed); assertFalse(worker.shutdown);
    assertEquals(1, transport.calls); assertTrue(readings.isEmpty()); assertTrue(resets >= 2);
    assertEquals("retry-wait", report().getString("phase"));
    assertEquals("http-403", report().getString("lastErrorCode"));
    advance(999); assertTrue(worker.tasks.isEmpty());
    advance(1); deliver();
    assertEquals(2, transport.calls); assertEquals(6, readings.size());
    assertEquals(1, report().getLong("recoveries"));
  }

  @Test public void malformedReplyAndPrivateTransportErrorNeverReachStatesOrExport() throws Exception {
    transport.replies.add(new IccVehicleProbe.Reply(200, new byte[] {72}));
    source.start(); deliver(); assertTrue(source.active()); assertTrue(readings.isEmpty());
    assertEquals("invalid-response", report().getString("lastErrorCode"));
    source.stop("stopped", "replace test source");
    transport = new IccVehicleProbeTest.Transport(); worker = new IccVehicleProbeTest.Worker();
    transport.error = new java.io.IOException("private-seed=secret VIN=example");
    source = create(250); source.start(); deliver();
    assertFalse(report().toString().contains("secret")); assertFalse(report().toString().contains("example"));
  }

  @Test public void timeoutsBackOffToTenSecondsWithoutPermanentStopOrOverlappingQueries() throws Exception {
    transport.error = new java.net.SocketTimeoutException("private-timeout-details");
    source.start();
    int[] delays = {1000, 2000, 4000, 8000, 10000, 10000, 10000};
    for (int index = 0; index < delays.length; index++) {
      deliver();
      assertTrue(source.active());
      assertEquals(index + 1, report().getLong("consecutiveFailures"));
      assertEquals(delays[index], report().getLong("retryDelayMillis"));
      assertTrue(report().getBoolean("retryScheduled"));
      assertEquals(clock.get() + delays[index], report().getLong("nextRequestAt"));
      assertFalse(report().getBoolean("inFlight"));
      assertTrue(worker.tasks.isEmpty());
      advance(delays[index] - 1); assertTrue(worker.tasks.isEmpty());
      advance(1); assertEquals(1, worker.tasks.size());
      org.robolectric.util.ReflectionHelpers.callInstanceMethod(source, "poll");
      assertEquals(1, worker.tasks.size());
    }
    assertFalse(transport.closed); assertFalse(worker.shutdown);
    assertFalse(report().toString().contains("private-timeout-details"));
  }

  @Test public void successfulRecoveryResetsBackoffAndDoesNotCountChangesAcrossOutage() throws Exception {
    source.start(); deliver();
    transport.error = new java.io.IOException("unavailable");
    advance(250); deliver();
    advance(1000); deliver();
    assertEquals(2000, report().getLong("retryDelayMillis"));
    transport.error = null;
    transport.replies.add(IccVehicleProbeTest.gear(3));
    advance(2000); deliver();
    assertEquals(0, report().getLong("consecutiveFailures"));
    assertEquals(1, report().getLong("recoveries"));
    assertEquals(0, report().getLong("gearChanges"));
    assertEquals(clock.get(), report().getLong("lastSuccessAt"));
    advance(249); assertTrue(worker.tasks.isEmpty());
    advance(1); assertEquals(1, worker.tasks.size());
    transport.error = new java.net.SocketTimeoutException();
    deliver(); assertEquals(1000, report().getLong("retryDelayMillis"));
    assertEquals(3, report().getLong("failures"));
    assertTrue(diagnostics.lines("").contains("只读查询恢复"));
    assertTrue(new JSONObject(diagnostics.export(false, new Store(RuntimeEnvironment.getApplication())))
        .getJSONArray("sessionHistory").toString().contains("ICC重连"));
  }

  @Test public void stoppingDuringRetryWaitCancelsAllFutureRequestsAndCannotRestartOldSource() throws Exception {
    transport.error = new java.net.SocketTimeoutException();
    source.start(); deliver();
    source.stop("stopped", "manual stop");
    advance(60000); source.start();
    assertFalse(source.active()); assertEquals(1, transport.calls); assertTrue(worker.tasks.isEmpty());
    assertTrue(transport.closed); assertTrue(worker.shutdown);
    assertFalse(report().getBoolean("retryScheduled"));
    assertTrue(report().isNull("nextRequestAt"));
    assertEquals("manual stop", report().getString("detail"));
  }

  @Test public void stoppingWithFailureQueuedNeverSchedulesRetryFromLateCallback() throws Exception {
    transport.error = new java.net.SocketTimeoutException();
    source.start(); worker.runNext(); source.stop("stopped", "source replaced");
    shadowOf(Looper.getMainLooper()).idle(); advance(60000);
    assertEquals(1, transport.calls); assertTrue(worker.tasks.isEmpty());
    assertEquals(0, report().getLong("failures"));
    assertEquals("source replaced", report().getString("detail"));
  }

  @Test public void mixedHttpAndMalformedFailuresKeepOneRetryTimerAndRecover() throws Exception {
    transport.replies.add(new IccVehicleProbe.Reply(503, null));
    transport.replies.add(new IccVehicleProbe.Reply(200, new byte[] {72}));
    source.start(); deliver();
    advance(1000); deliver();
    transport.replies.add(new IccVehicleProbe.Reply(200, new byte[] {72, 1}));
    advance(2000); deliver();
    assertEquals(3, transport.calls); assertEquals(6, readings.size());
    assertEquals(2, report().getLong("failures"));
    assertEquals(1, report().getLong("recoveries"));
    assertEquals(0, report().getLong("consecutiveFailures"));
    advance(250); assertEquals(1, worker.tasks.size());
  }

  @Test public void emptyReplyIsRetriedInsteadOfDisablingMonitoring() throws Exception {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    source = new IccVehicleSource(new Handler(Looper.getMainLooper()), diagnostics, new IccVehicleSource.Listener() {
      public void sample(Protocol.Sample sample) { readings.add(sample); }
      public void reset() { resets++; }
    }, 250, new IccVehicleProbe.Transport() {
      public IccVehicleProbe.Reply query() { return calls.incrementAndGet() == 1 ? null : IccVehicleProbeTest.gear(1); }
      public void close() {}
    }, worker, clock::get, clock::get);
    source.start(); deliver();
    assertTrue(source.active()); assertEquals("empty-reply", report().getString("lastErrorCode"));
    advance(1000); deliver();
    assertEquals(2, calls.get()); assertEquals(6, readings.size());
    assertEquals(1, report().getLong("recoveries"));
  }

  @Test public void longSuspensionDoesNotReplayOldSnapshotAndTimeoutStillRecovers() throws Exception {
    source.start(); deliver(); advance(250);
    clock.addAndGet(2 * 60 * 60 * 1000L);
    deliver();
    assertEquals(6, readings.size()); assertEquals(1, report().getLong("expired"));
    transport.error = new java.net.SocketTimeoutException();
    advance(250); deliver();
    assertTrue(source.active()); assertEquals("retry-wait", report().getString("phase"));
    transport.error = null;
    advance(1000); deliver();
    assertEquals(12, readings.size()); assertEquals(0, report().getLong("gearChanges"));
    assertEquals(1, report().getLong("recoveries"));
  }

  @Test public void repeatedFailuresHaveNoAttemptLimitAndDoNotRetainUnboundedSamples() throws Exception {
    transport.error = new java.io.IOException("unavailable");
    source.start();
    for (int index = 0; index < 300; index++) {
      deliver();
      if (index != 299) advance(report().getLong("retryDelayMillis"));
    }
    assertTrue(source.active()); assertEquals(300, transport.calls);
    assertEquals(300, report().getLong("failures"));
    assertEquals(0, report().getJSONArray("samples").length());
    assertTrue(worker.tasks.isEmpty()); assertFalse(transport.closed);
    assertTrue(new JSONObject(diagnostics.export(false, new Store(RuntimeEnvironment.getApplication())))
        .getJSONArray("sessionHistory").length() <= 160);
  }

  @Test public void stopClosesTransportAndLateReplyCannotRestoreState() throws Exception {
    source.start(); worker.runNext(); source.stop("stopped", "test");
    shadowOf(Looper.getMainLooper()).idle(); advance(2000);
    assertTrue(readings.isEmpty()); assertTrue(worker.tasks.isEmpty()); assertTrue(transport.closed);
    assertEquals(0, report().getInt("http200")); assertFalse(report().getBoolean("active"));
  }

  @Test public void historicalReportCannotRestoreLiveValuesOrStartNetwork() throws Exception {
    source.start(); deliver();
    Diagnostics restored = new Diagnostics(); restored.attach(RuntimeEnvironment.getApplication());
    assertFalse(restored.iccVehicleSourceSnapshot().getBoolean("active"));
    assertEquals("previous-session-interrupted", restored.iccVehicleSourceSnapshot().getString("phase"));
    assertTrue(restored.stateReadings().isEmpty()); assertEquals(1, transport.calls);
    JSONObject exported = new JSONObject(restored.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertTrue(exported.has("iccVehicleSource"));
    restored.clear(); assertEquals(0, restored.iccVehicleSourceSnapshot().length());
    assertEquals(0, RuntimeEnvironment.getApplication().getSharedPreferences("icc_vehicle_source", 0).getAll().size());
  }

  @Test public void stoppingDuringStateDeliveryCannotForwardRemainingFieldsOrOverwriteStopReason() throws Exception {
    List<Protocol.Sample> delivered = new ArrayList<>();
    source = new IccVehicleSource(new Handler(Looper.getMainLooper()), diagnostics, new IccVehicleSource.Listener() {
      public void sample(Protocol.Sample sample) { delivered.add(sample); source.stop("stopped", "stop during callback"); }
      public void reset() { resets++; }
    }, 250, transport, worker, clock::get, clock::get);
    source.start(); deliver(); advance(1000);
    assertEquals(1, delivered.size()); assertEquals("stopped", report().getString("phase"));
    assertFalse(source.active()); assertTrue(worker.tasks.isEmpty()); assertTrue(transport.closed);
  }
}
