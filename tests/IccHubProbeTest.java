package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.time.Duration;
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
public final class IccHubProbeTest {
  static final class Transport implements IccHubProbe.Transport {
    IccVehicleProbe.Reply reply;
    Exception error;
    int calls;
    boolean closed;
    Transport() throws Exception {
      reply = new IccVehicleProbe.Reply(200, IccHubWireTest.join(
          IccHubWireTest.route("ota_icc_vsi", "192.168.55.40:51020"),
          IccHubWireTest.route("ota_android_vsi", ""), IccHubWireTest.string(2, "VIN-private-Seed-account")));
    }
    @Override public IccVehicleProbe.Reply query() throws Exception {
      calls++;
      if (error != null) throw error;
      return reply;
    }
    @Override public void close() { closed = true; }
  }

  private final AtomicLong clock = new AtomicLong(100000);
  private Diagnostics diagnostics;
  private Transport transport;
  private IccVehicleProbeTest.Worker worker;
  private IccHubProbe probe;

  @Before public void setup() throws Exception {
    RuntimeEnvironment.getApplication().getSharedPreferences("icc_hub_probe", 0).edit().clear().commit();
    diagnostics = new Diagnostics(); diagnostics.attach(RuntimeEnvironment.getApplication());
    transport = new Transport(); worker = new IccVehicleProbeTest.Worker();
    probe = new IccHubProbe(new Handler(Looper.getMainLooper()), diagnostics,
        transport, worker, clock::get, clock::get);
  }

  @After public void cleanup() { probe.stop("stopped", "test cleanup"); }
  private JSONObject report() { return diagnostics.iccHubProbeSnapshot(); }
  private void deliver() { worker.runNext(); shadowOf(Looper.getMainLooper()).idle(); }
  private void advance(long millis) {
    clock.addAndGet(millis); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
  }

  @Test public void manualSingleShotExportsOnlySelectedRoutesWithoutLiveStateOrAudio() throws Exception {
    assertEquals(0, transport.calls); assertEquals(0, report().length());
    probe.start(); assertEquals(0, transport.calls); deliver(); advance(60000); probe.start();
    assertEquals(1, transport.calls); assertFalse(probe.active()); assertTrue(transport.closed); assertTrue(worker.shutdown);
    assertEquals("complete", report().getString("phase"));
    assertEquals("GET", report().getString("method")); assertEquals("/info", report().getString("path"));
    assertEquals("ota_proxy", report().getString("logicalService"));
    assertEquals(1, report().getInt("attempts")); assertEquals(1, report().getInt("iccRouteCount"));
    assertEquals(1, report().getInt("androidRouteCount"));
    assertFalse(report().getBoolean("audioConnected")); assertTrue(report().getBoolean("readOnly"));
    assertTrue(diagnostics.stateReadings().isEmpty());
    JSONObject export = new JSONObject(diagnostics.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertEquals("complete", export.getJSONObject("iccHubProbe").getString("phase"));
    assertEquals(0, export.getJSONArray("candidates").length());
    assertEquals(0, export.getLong("acceptedLogSamples"));
    for (String privateText : new String[] {"VIN-private", "Seed-account", "rawBody", "FORMAT_KEY"})
      assertFalse(export.toString().contains(privateText));
  }

  @Test public void startsAndSnapshotsAreDefensiveAndDuplicateStartDoesNotQueueMoreWork() throws Exception {
    probe.start(); probe.start(); assertEquals(1, worker.tasks.size());
    JSONObject snapshot = report(); snapshot.put("phase", "mutated");
    assertEquals("querying", report().getString("phase"));
    deliver(); assertEquals(1, transport.calls);
  }

  @Test public void emptyResponseOrMissingIccRouteMeansNotListedNotNonexistent() throws Exception {
    transport.reply = new IccVehicleProbe.Reply(200, IccHubWireTest.route("ota_android_vsi", ""));
    probe.start(); deliver();
    assertEquals("target-not-listed", report().getString("phase"));
    assertEquals(0, report().getInt("iccRouteCount")); assertEquals(1, report().getInt("androidRouteCount"));
    assertTrue(diagnostics.iccHubProbeSummary().contains("不代表服务不存在"));
    setup(); transport.reply = new IccVehicleProbe.Reply(200, new byte[0]); probe.start(); deliver();
    assertEquals("target-not-listed", report().getString("phase")); assertEquals(0, report().getJSONArray("routes").length());
  }

  @Test public void errorsAndPrivateExceptionTextAreNotRetriedOrExported() throws Exception {
    transport.error = new IOException("VIN=secret Seed=private-account");
    probe.start(); deliver(); advance(60000);
    assertEquals("failed", report().getString("phase")); assertEquals(1, transport.calls);
    assertFalse(report().toString().contains("secret")); assertFalse(report().toString().contains("private-account"));
    assertTrue(worker.shutdown); assertTrue(transport.closed);
  }

  @Test public void httpDeniedAndRedirectNeverProduceRoutes() throws Exception {
    for (int status : new int[] {301, 403, 404, 500}) {
      setup(); transport.reply = new IccVehicleProbe.Reply(status, transport.reply.body);
      probe.start(); deliver(); advance(10000);
      assertEquals("http-" + status, report().getString("phase"));
      assertEquals(0, report().getJSONArray("routes").length()); assertEquals(1, transport.calls);
    }
  }

  @Test public void malformedResponseOrNullReplyCannotBecomeComplete() throws Exception {
    transport.reply = new IccVehicleProbe.Reply(200, new byte[] {10, 127});
    probe.start(); deliver(); assertEquals("invalid-response", report().getString("phase"));
    setup(); transport.reply = null; probe.start(); deliver(); assertEquals("failed", report().getString("phase"));
  }

  @Test public void totalDeadlineClosesPendingWorkAndDoesNotRepeatRequest() throws Exception {
    probe.start(); advance(5000);
    assertFalse(probe.active()); assertTrue(transport.closed); assertTrue(worker.shutdown);
    assertTrue(worker.tasks.isEmpty()); assertEquals("timeout", report().getString("phase"));
    assertFalse(report().getBoolean("inFlight")); assertEquals(0, transport.calls);
  }

  @Test public void cancelledLateReplyCannotOverwriteCancelledReport() throws Exception {
    probe.start(); worker.runNext(); probe.stop("stopped", "test user cancellation");
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals("stopped", report().getString("phase")); assertEquals(0, report().getJSONArray("routes").length());
    assertTrue(transport.closed); assertFalse(report().getBoolean("active"));
  }

  @Test public void mainThreadDelayedReplyBeyondDeadlineIsDiscarded() throws Exception {
    probe.start(); worker.runNext(); clock.addAndGet(5001); shadowOf(Looper.getMainLooper()).idle();
    assertEquals("timeout", report().getString("phase")); assertEquals(0, report().getJSONArray("routes").length());
  }

  @Test public void completedReportPersistsWithoutNetworkAndCanBeCleared() throws Exception {
    probe.start(); deliver();
    Diagnostics restored = new Diagnostics(); restored.attach(RuntimeEnvironment.getApplication());
    assertEquals("complete", restored.iccHubProbeSnapshot().getString("phase"));
    assertTrue(restored.iccHubProbeSummary().contains("192.168.55.40:51020"));
    assertEquals(1, transport.calls); assertTrue(restored.stateReadings().isEmpty());
    restored.clear(); Diagnostics cleared = new Diagnostics(); cleared.attach(RuntimeEnvironment.getApplication());
    assertEquals(0, cleared.iccHubProbeSnapshot().length());
  }

  @Test public void interruptedHistoryNeverResumesOrClaimsActive() throws Exception {
    probe.start();
    Diagnostics restored = new Diagnostics(); restored.attach(RuntimeEnvironment.getApplication());
    JSONObject previous = restored.iccHubProbeSnapshot();
    assertFalse(previous.getBoolean("active")); assertFalse(previous.getBoolean("inFlight"));
    assertEquals("previous-session-interrupted", previous.getString("phase")); assertEquals(0, transport.calls);
  }

  @Test public void transportErrorExportsItsStageWithoutLosingTheAlreadyReceivedHttpStatus() throws Exception {
    JSONObject observations = new JSONObject().put("httpStatus", 200).put("declaredBodyBytes", 48)
        .put("receivedBodyBytes", 8).put("responseType", "cs/pb-a").put("responseSeedPresent", true);
    transport.error = new IccHubFailure("response-body", new IOException("Connection reset Seed=private-value"), observations);
    probe.start(); deliver(); advance(10000);
    assertEquals("failed", report().getString("phase")); assertEquals(200, report().getInt("httpStatus"));
    assertEquals("response-body", report().getJSONObject("failure").getString("stage"));
    assertEquals("connection-reset", report().getJSONObject("failure").getString("reason"));
    assertTrue(diagnostics.iccHubProbeSummary().contains("已接收 8 字节 / 声明 48 字节"));
    assertTrue(diagnostics.iccHubProbeSummary().contains("读取响应体失败"));
    JSONObject export = new JSONObject(diagnostics.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(200, export.getJSONObject("iccHubProbe").getJSONObject("failure").getInt("httpStatus"));
    assertFalse(export.toString().contains("private-value")); assertEquals(1, transport.calls);
    assertTrue(diagnostics.stateReadings().isEmpty());
  }

  @Test public void invalidProtobufExportsADistinctDecodeStageAndSafeParserCode() throws Exception {
    transport.reply = new IccVehicleProbe.Reply(200, new byte[] {10, 127});
    probe.start(); deliver();
    assertEquals("invalid-response", report().getString("phase")); assertEquals(200, report().getInt("httpStatus"));
    assertEquals("decode-routes", report().getJSONObject("failure").getString("stage"));
    assertEquals("truncated-field", report().getJSONObject("failure").getString("reason"));
    assertEquals(0, report().getJSONArray("routes").length());
  }
}
