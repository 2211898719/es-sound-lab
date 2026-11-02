package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
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
public final class MessageCenterProbeTest {
  private final Handler main = new Handler(Looper.getMainLooper());
  private Application app;
  private Diagnostics diagnostics;
  private MessageCenterProbe probe;
  private FakeTransport transport;
  private TestExecutor executor;

  private static final class TestExecutor extends AbstractExecutorService {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean queued;
    @Override public void execute(Runnable task) { if (queued) tasks.add(task); else task.run(); }
    void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    @Override public void shutdown() {}
    @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
  }

  private static final class FakeTransport implements MessageCenterProbe.Transport {
    boolean found = true;
    int discoveries, verifications, subscriptions, unsubscriptions, result;
    MessageCenterProbe.Sink sink;
    ArrayList<String> subscribed, unsubscribed;
    RuntimeException discoverError, subscribeError, cleanupError;
    Error linkage;
    Runnable duringSubscribe;
    @Override public boolean discover() {
      discoveries++;
      if (discoverError != null) throw discoverError;
      if (linkage != null) throw linkage;
      return found;
    }
    @Override public void verify() { verifications++; }
    @Override public int subscribe(ArrayList<String> topics, MessageCenterProbe.Sink receiver) {
      subscriptions++;
      sink = receiver;
      subscribed = topics;
      if (duringSubscribe != null) duringSubscribe.run();
      if (subscribeError != null) throw subscribeError;
      return result;
    }
    @Override public int unsubscribe(ArrayList<String> topics) {
      unsubscriptions++;
      unsubscribed = topics;
      if (cleanupError != null) throw cleanupError;
      return 0;
    }
    void emit(String topic, String value) { sink.receive(topic, value.getBytes(StandardCharsets.UTF_8)); }
  }

  @Before public void setup() {
    app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("message_center_probe", 0).edit().clear().commit();
    diagnostics = new Diagnostics();
    diagnostics.attach(app);
    executor = new TestExecutor();
    transport = new FakeTransport();
    probe = new MessageCenterProbe(main, diagnostics, executor, transport);
  }

  @After public void cleanup() {
    probe.stop("test-finished", "test cleanup");
    executor.drain();
    shadowOf(Looper.getMainLooper()).idle();
  }

  private void start() { probe.start(); shadowOf(Looper.getMainLooper()).idle(); }
  private JSONObject snapshot() { return probe.snapshot(); }
  private void emit(String value) {
    transport.emit("DrivingInfo/Gear", value);
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test public void registersOnlyFixedReadOnlyTopicsButDoesNotClaimData() {
    start();
    assertTrue(probe.active());
    assertEquals("listening", snapshot().optString("phase"));
    assertEquals(5, transport.subscribed.size());
    assertEquals(MessageCenterProbe.topics(), transport.subscribed);
    for (String topic : transport.subscribed) {
      assertFalse(topic.endsWith("/Set"));
      assertFalse(topic.endsWith("/req"));
    }
    assertEquals(0, snapshot().optInt("validCallbackCount"));
    assertFalse(snapshot().optBoolean("audioConnected"));
    assertFalse(snapshot().optBoolean("sourceTimestampAvailable"));
    assertEquals(android.os.Process.myUid(), snapshot().optInt("uid"));
    probe.stop("stopped", "finished");
    assertEquals(1, transport.unsubscriptions);
    assertEquals(transport.subscribed, transport.unsubscribed);
  }

  @Test public void discoveryOnlyVerifiesButNeverSubscribesOrUnsubscribes() {
    probe.startDiscoveryOnly();
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(snapshot().optBoolean("discoveryOnly"));
    assertEquals("discovery-complete", snapshot().optString("phase"));
    assertTrue(snapshot().optBoolean("interfaceVerified"));
    assertEquals(1, transport.discoveries);
    assertEquals(1, transport.verifications);
    assertEquals(0, transport.subscriptions);
    assertEquals(0, transport.unsubscriptions);
  }

  @Test public void noSuchElementIsUnavailableNotProofOfPermissionAndHasBoundedStack() {
    transport.discoverError = new java.util.NoSuchElementException();
    probe.startDiscoveryOnly();
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals("service-unavailable", snapshot().optString("phase"));
    assertEquals("discovering", snapshot().optString("failedPhase"));
    assertEquals("java.util.NoSuchElementException", snapshot().optString("errorType"));
    assertTrue(snapshot().optString("errorStack").contains("NoSuchElementException"));
    assertTrue(snapshot().optString("errorStack").length() <= 4096);
    assertEquals(0, transport.subscriptions);
  }

  @Test public void missingServiceEndsWithoutSubscriptionOrRetry() {
    transport.found = false;
    start();
    assertFalse(probe.active());
    assertEquals("service-unavailable", snapshot().optString("phase"));
    assertEquals(1, transport.discoveries);
    assertEquals(0, transport.subscriptions);
    assertEquals(0, transport.unsubscriptions);
  }

  @Test public void permissionFailureIsReportedAtDiscoveryWithoutFallback() {
    transport.discoverError = new SecurityException("SELinux denied");
    start();
    assertEquals("permission-denied", snapshot().optString("phase"));
    assertEquals("discovering", snapshot().optString("failedPhase"));
    assertTrue(snapshot().optString("error").contains("SecurityException"));
    assertEquals(0, transport.subscriptions);
  }

  @Test public void hiddenApiLinkageFailureIsContained() {
    transport.linkage = new NoSuchMethodError("hidden API blocked");
    start();
    assertEquals("hidden-api-unavailable", snapshot().optString("phase"));
    assertFalse(probe.active());
  }

  @Test public void failedAndAmbiguousRegistrationAttemptIsCleanedOnce() {
    transport.subscribeError = new SecurityException("subscribe denied");
    start();
    probe.stop("stopped", "again");
    assertEquals("subscribing", snapshot().optString("failedPhase"));
    assertEquals(1, transport.unsubscriptions);
  }

  @Test public void nonzeroRegistrationStopsAndRecordsCleanupFailure() {
    transport.result = -1;
    transport.cleanupError = new IllegalStateException("remote dead");
    start();
    assertEquals("subscribe-rejected", snapshot().optString("phase"));
    assertTrue(snapshot().optString("unsubscribeError").contains("remote dead"));
    assertFalse(snapshot().optBoolean("cleanupPending"));
  }

  @Test public void zeroIsPreservedAndFirstSampleDoesNotCountAsChange() throws Exception {
    start();
    emit("{\"valid\":true,\"value\":0}");
    assertEquals(1, snapshot().optInt("validCallbackCount"));
    assertEquals(0, snapshot().optInt("changes"));
    assertEquals("0", snapshot().getJSONObject("samples").getJSONObject("DrivingInfo/Gear").getString("value"));
    emit("{\"valid\":true,\"value\":0}");
    emit("{\"valid\":true,\"value\":2}");
    assertEquals(1, snapshot().optInt("changes"));
  }

  @Test public void invalidAndMalformedSamplesResetBaselineNotBecomeZero() throws Exception {
    start();
    emit("{\"valid\":true,\"value\":1}");
    emit("{\"valid\":false,\"value\":0}");
    assertFalse(snapshot().getJSONObject("samples").getJSONObject("DrivingInfo/Gear").has("value"));
    emit("{\"valid\":true,\"value\":2}");
    emit("not json");
    emit("{\"valid\":true,\"value\":3}");
    assertEquals(0, snapshot().optInt("changes"));
    assertEquals(1, snapshot().optInt("malformedCallbackCount"));
    assertEquals(1, snapshot().optInt("invalidCallbackCount"));
  }

  @Test public void stringValidMissingValueAndNullAreNotValidData() {
    start();
    emit("{\"valid\":\"true\",\"value\":1}");
    emit("{\"valid\":true}");
    emit("{\"valid\":true,\"value\":null}");
    assertEquals(0, snapshot().optInt("validCallbackCount"));
    assertEquals(3, snapshot().optInt("invalidCallbackCount"));
  }

  @Test public void unexpectedTopicsAreIgnoredAndPayloadQueueIsBounded() {
    start();
    transport.emit("Identity/VIN", "{\"valid\":true,\"value\":123}");
    for (int index = 0; index < 100; index++)
      transport.emit("DrivingInfo/Gear", "{\"valid\":true,\"value\":0}");
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(64, snapshot().optInt("callbackCount"));
    assertEquals(36, snapshot().optInt("droppedCallbacks"));
    assertFalse(snapshot().optJSONObject("samples").has("Identity/VIN"));
  }

  @Test public void oversizedAndBadUtf8PayloadsAreMalformedWithoutKeepingOldValue() throws Exception {
    start();
    emit("{\"valid\":true,\"value\":1}");
    transport.sink.receive("DrivingInfo/Gear", new byte[8193]);
    shadowOf(Looper.getMainLooper()).idle();
    JSONObject sample = snapshot().getJSONObject("samples").getJSONObject("DrivingInfo/Gear");
    assertFalse(sample.has("raw"));
    assertFalse(sample.has("value"));
    transport.sink.receive("DrivingInfo/Gear", new byte[] {(byte) 0xff});
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(2, snapshot().optInt("malformedCallbackCount"));
  }

  @Test public void sixtySecondDeadlineUnsubscribesAndLateCallbacksAreIgnored() {
    start();
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));
    assertEquals("timeout", snapshot().optString("phase"));
    assertEquals(1, transport.unsubscriptions);
    emit("{\"valid\":true,\"value\":1}");
    assertEquals(0, snapshot().optInt("callbackCount"));
  }

  @Test public void stoppedQueuedDiscoveryDoesNotConnect() {
    executor.queued = true;
    probe.start();
    probe.stop("stopped", "before worker");
    executor.drain();
    assertEquals(0, transport.discoveries);
    assertFalse(snapshot().optBoolean("cleanupPending"));
  }

  @Test public void stopDuringSubscriptionStillUnsubscribesAndDoesNotResurrect() {
    transport.duringSubscribe = () -> probe.stop("stopped", "during subscribe");
    start();
    assertFalse(probe.active());
    assertFalse(snapshot().optBoolean("registered"));
    assertEquals("stopped", snapshot().optString("phase"));
    assertEquals(1, transport.unsubscriptions);
  }

  @Test public void operationTimeoutBlocksNewSessionsUntilQueuedCleanupReturns() {
    executor.queued = true;
    probe.start();
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
    assertEquals("timeout", snapshot().optString("phase"));
    MessageCenterProbe next = new MessageCenterProbe(main, diagnostics, executor, new FakeTransport());
    next.start();
    assertEquals("busy", next.snapshot().optString("phase"));
    executor.drain();
    assertFalse(snapshot().optBoolean("cleanupPending"));
    next.stop("test-finished", "done");
  }

  @Test public void persistenceExportsAndProcessInterruptionNeverFeedAudio() throws Exception {
    start();
    emit("{\"valid\":true,\"value\":1}");
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    assertEquals(1, Diagnostics.latestMessageCenterProbe(app).optInt("callbackCount"));
    JSONObject exported = new JSONObject(diagnostics.export(false, new Store(app)));
    assertEquals(1, exported.getJSONObject("messageCenterProbe").optInt("callbackCount"));
    assertEquals(0, exported.getJSONArray("candidates").length());
    Diagnostics reloaded = new Diagnostics();
    reloaded.attach(app);
    assertEquals("previous-session-interrupted", reloaded.messageCenterProbeSnapshot().optString("phase"));
    assertTrue(reloaded.messageCenterProbeSnapshot().optBoolean("cleanupUnknown"));
  }

  @Test public void manyEventsAreCappedAndSnapshotsAreIndependent() throws Exception {
    start();
    for (int index = 0; index < 150; index++) emit("{\"valid\":true,\"value\":" + index + "}");
    assertEquals(96, snapshot().getJSONArray("events").length());
    assertTrue(snapshot().optLong("eventsDropped") > 0);
    JSONObject copy = snapshot();
    copy.put("phase", "modified");
    assertEquals("listening", snapshot().getString("phase"));
  }
}
