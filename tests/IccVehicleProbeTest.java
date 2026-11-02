package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
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
public final class IccVehicleProbeTest {
  static final class Worker extends AbstractExecutorService {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean shutdown;
    void runNext() { tasks.remove().run(); }
    @Override public void execute(Runnable task) { tasks.add(task); }
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() {
      shutdown = true; List<Runnable> result = new ArrayList<>(tasks); tasks.clear(); return result;
    }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long time, TimeUnit unit) { return shutdown; }
  }

  static final class Transport implements IccVehicleProbe.Transport {
    final ArrayDeque<IccVehicleProbe.Reply> replies = new ArrayDeque<>();
    boolean closed;
    int calls;
    Exception error;
    @Override public IccVehicleProbe.Reply query() throws Exception {
      calls++;
      if (error != null) throw error;
      return replies.isEmpty() ? gear(1) : replies.remove();
    }
    @Override public void close() { closed = true; }
  }

  private final AtomicLong clock = new AtomicLong(100000);
  private Diagnostics diagnostics;
  private Transport transport;
  private Worker worker;
  private IccVehicleProbe probe;

  static IccVehicleProbe.Reply gear(int raw) { return new IccVehicleProbe.Reply(200, new byte[] {72, (byte) raw}); }

  @Before public void setup() {
    RuntimeEnvironment.getApplication().getSharedPreferences("icc_vehicle_probe", 0).edit().clear().commit();
    diagnostics = new Diagnostics(); diagnostics.attach(RuntimeEnvironment.getApplication());
    transport = new Transport(); worker = new Worker();
    probe = new IccVehicleProbe(new Handler(Looper.getMainLooper()), diagnostics,
        transport, worker, clock::get, clock::get);
  }

  @After public void cleanup() { probe.stop("stopped", "test cleanup"); }
  private JSONObject report() { return diagnostics.iccVehicleProbeSnapshot(); }
  private void deliver() { worker.runNext(); shadowOf(Looper.getMainLooper()).idle(); }
  private void advance(long millis) {
    clock.addAndGet(millis); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
  }

  @Test public void manualStartOnlyAndRawSnapshotsStayOutOfPlaybackAndCalibrations() throws Exception {
    assertEquals(0, transport.calls); assertFalse(report().has("sessionId"));
    probe.start(); assertEquals(0, transport.calls); deliver();
    assertEquals("P", report().getJSONObject("lastSample").getString("gear"));
    assertEquals(0, report().getInt("gearChanges")); assertTrue(diagnostics.stateReadings().isEmpty());
    JSONObject export = new JSONObject(diagnostics.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(0, export.getLong("acceptedLogSamples"));
    assertFalse(export.getJSONObject("iccVehicleProbe").getBoolean("audioConnected"));
    assertFalse(export.getJSONObject("iccVehicleProbe").getBoolean("upstreamTimestampAvailable"));
  }

  @Test public void firstSampleIsBaselineAndUnknownClearsChangeBaseline() throws Exception {
    for (int raw : new int[] {1, 1, 4, 3, 0, 1}) transport.replies.add(gear(raw));
    probe.start(); deliver();
    for (int index = 1; index < 6; index++) { advance(1000); deliver(); }
    assertEquals(2, report().getInt("gearChanges")); assertEquals(5, report().getInt("knownGearSamples"));
    assertEquals(6, report().getJSONArray("samples").length());
  }

  @Test public void noOverlappingRequestsAndThirtyAttemptsIsHardBound() throws Exception {
    probe.start(); advance(10000);
    assertEquals(1, worker.tasks.size()); assertEquals(1, report().getInt("attempts")); deliver();
    for (int index = 1; index < 30; index++) { advance(1000); deliver(); }
    assertFalse(probe.active()); assertTrue(transport.closed); assertTrue(worker.shutdown);
    assertEquals(30, transport.calls); assertEquals(30, report().getJSONArray("samples").length());
    advance(10000); assertEquals(30, transport.calls);
  }

  @Test public void sixtySecondDeadlineCancelsEvenWhenWorkerHasNotReturned() throws Exception {
    probe.start(); advance(60000);
    assertFalse(probe.active()); assertTrue(transport.closed); assertTrue(worker.shutdown);
    assertEquals("complete", report().getString("phase")); assertEquals(0, transport.calls);
  }

  @Test public void missingGearIsNotParkingAndMalformedBodyStopsWithoutGuessing() throws Exception {
    transport.replies.add(new IccVehicleProbe.Reply(200, new byte[] {16, 0}));
    transport.replies.add(new IccVehicleProbe.Reply(200, new byte[] {72}));
    probe.start(); deliver();
    assertEquals("缺失", report().getJSONObject("lastSample").getString("gear"));
    assertEquals(0, report().getInt("knownGearSamples"));
    advance(1000); deliver(); assertEquals("invalid-response", report().getString("phase"));
    assertFalse(probe.active()); assertEquals(0, report().getInt("gearChanges"));
  }

  @Test public void deniedHttpAndRedirectsStopRatherThanTryOtherRoutes() throws Exception {
    for (int status : new int[] {401, 403, 301, 404, 500}) {
      setup(); transport.replies.add(new IccVehicleProbe.Reply(status, null));
      probe.start(); deliver(); advance(10000);
      assertEquals("http-" + status, report().getString("phase"));
      assertFalse(probe.active()); assertEquals(1, transport.calls); assertTrue(transport.closed);
    }
  }

  @Test public void timeoutDoesNotRetryAndDoesNotExportExceptionPayload() throws Exception {
    transport.error = new SocketTimeoutException("account-secret-response");
    probe.start(); deliver(); advance(10000);
    assertEquals("failed", report().getString("phase")); assertEquals(1, transport.calls);
    assertFalse(report().toString().contains("account-secret"));
  }

  @Test public void stoppedSessionIgnoresAlreadyQueuedResult() throws Exception {
    probe.start(); worker.runNext(); probe.stop("stopped", "cancelled");
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(report().has("lastSample")); assertEquals(0, report().getInt("http200"));
    assertEquals("stopped", report().getString("phase"));
  }

  @Test public void queuedOldReplyCannotBecomeCurrentSnapshot() throws Exception {
    probe.start(); worker.runNext(); clock.addAndGet(6001);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, report().getInt("queueExpired")); assertFalse(report().has("lastSample"));
    assertEquals(0, report().getInt("knownGearSamples"));
  }

  @Test public void persistedInterruptedProbeIsHistoricalAndNeverAutoRestarts() throws Exception {
    probe.start(); deliver();
    Diagnostics restored = new Diagnostics(); restored.attach(RuntimeEnvironment.getApplication());
    JSONObject snapshot = restored.iccVehicleProbeSnapshot();
    assertFalse(snapshot.getBoolean("active")); assertFalse(snapshot.getBoolean("inFlight"));
    assertEquals("previous-session-interrupted", snapshot.getString("phase"));
    assertEquals(1, transport.calls); assertTrue(restored.iccVehicleProbeSummary().contains("历史回复"));
    probe.stop("stopped", "clear"); restored.clear();
    Diagnostics empty = new Diagnostics(); empty.attach(RuntimeEnvironment.getApplication());
    assertFalse(empty.iccVehicleProbeSnapshot().has("sessionId"));
  }
}
