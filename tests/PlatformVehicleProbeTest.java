package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
public final class PlatformVehicleProbeTest {
  private Application app;
  private Handler main;
  private Diagnostics diagnostics;
  private ProbeContext context;
  private FakeRemote remote;
  private PlatformVehicleProbe probe;
  private ServiceInfo serviceInfo;
  private TestExecutor executor;

  private static final class TestExecutor extends AbstractExecutorService {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean queued;

    @Override public void execute(Runnable task) {
      if (queued) tasks.add(task);
      else task.run();
    }
    void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    @Override public void shutdown() {}
    @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long time, TimeUnit unit) { return true; }
  }

  private final class ProbeContext extends ContextWrapper {
    ServiceConnection connection;
    Intent intent;
    int binds, unbinds;
    boolean accepted = true, connect = true;
    RuntimeException failure;

    ProbeContext() { super(app); }

    @Override public boolean bindService(Intent request, ServiceConnection listener, int flags) {
      binds++;
      intent = request;
      connection = listener;
      assertEquals(Context.BIND_AUTO_CREATE, flags);
      if (failure != null) throw failure;
      if (connect && accepted) main.post(() -> listener.onServiceConnected(request.getComponent(), remote));
      return accepted;
    }

    @Override public void unbindService(ServiceConnection listener) {
      assertSame(connection, listener);
      unbinds++;
    }
  }

  private static final class FakeRemote extends Binder {
    IBinder listener;
    final List<Integer> transactions = new ArrayList<>();
    int registerResult, unregisterResult, gear = 1002;
    boolean denyRegister, denyRead, unsupportedRead;
    Runnable duringRegister;

    FakeRemote() { attachInterface(null, PlatformVehicleProbe.DESCRIPTOR); }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      data.enforceInterface(PlatformVehicleProbe.DESCRIPTOR);
      transactions.add(code);
      if (code == 8 || code == 9) {
        IBinder supplied = data.readStrongBinder();
        if (code == 8) {
          listener = supplied;
          if (denyRegister) throw new SecurityException("registration denied");
          if (duringRegister != null) duringRegister.run();
        } else assertSame(listener, supplied);
        reply.writeNoException();
        reply.writeInt(code == 8 ? registerResult : unregisterResult);
        return true;
      }
      if (code == 12) {
        assertEquals("gear", data.readString());
        if (denyRead) throw new SecurityException("read denied");
        if (unsupportedRead) return false;
        reply.writeNoException();
        reply.writeInt(gear);
        return true;
      }
      throw new AssertionError("Unexpected vehicle transaction " + code);
    }

    void callback(int code, String key, int value) throws Exception {
      Parcel data = Parcel.obtain(), reply = Parcel.obtain();
      try {
        data.writeInterfaceToken(PlatformVehicleProbe.CALLBACK);
        if (code == 3 || code == 5) {
          data.writeString(key);
          data.writeInt(value);
        } else if (code == 1) data.writeInt(value);
        else if (code == 2) {
          data.writeInt(value);
          data.writeInt(100);
        }
        assertTrue(listener.transact(code, data, reply, 0));
        reply.readException();
      } finally {
        data.recycle();
        reply.recycle();
      }
    }
  }

  @Before public void setup() {
    app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("platform_vehicle_probe", 0).edit().clear().commit();
    main = new Handler(Looper.getMainLooper());
    diagnostics = new Diagnostics();
    diagnostics.attach(app);
    remote = new FakeRemote();
    executor = new TestExecutor();
    context = new ProbeContext();
    install("com.iflytek.autofly.platformservice");
    probe = create(() -> "");
  }

  private void install(String host) {
    ApplicationInfo application = new ApplicationInfo();
    application.packageName = host;
    application.enabled = true;
    application.flags = ApplicationInfo.FLAG_SYSTEM;
    application.uid = android.os.Process.myUid();
    PackageInfo info = new PackageInfo();
    info.packageName = host;
    info.applicationInfo = application;
    info.versionName = "test-1";
    shadowOf(app.getPackageManager()).installPackage(info);
    serviceInfo = new ServiceInfo();
    serviceInfo.applicationInfo = application;
    serviceInfo.packageName = host;
    serviceInfo.name = host + ".SystemService";
    serviceInfo.exported = true;
    serviceInfo.enabled = true;
    ResolveInfo resolved = new ResolveInfo();
    resolved.serviceInfo = serviceInfo;
    shadowOf(app.getPackageManager()).addResolveInfoForIntent(
        new Intent(PlatformVehicleProbe.ACTION).setPackage(host), resolved);
  }

  private PlatformVehicleProbe create(PlatformVehicleProbe.RouteReader route) {
    return new PlatformVehicleProbe(context, main, diagnostics, executor, route);
  }

  private void idle() { shadowOf(Looper.getMainLooper()).idle(); }
  private JSONObject snapshot() { return diagnostics.platformProbeSnapshot(); }
  private void start() { probe.start(); idle(); }

  @After public void cleanup() {
    probe.stop("test-stop", "test cleanup");
    executor.drain();
    idle();
  }

  @Test public void registersReadsAndUnregistersOnlyAllowedCalls() {
    start();
    assertTrue(probe.active());
    assertEquals(Arrays.asList(8, 12), remote.transactions);
    assertEquals(0, snapshot().optInt("registerResult", -1));
    assertEquals(1002, snapshot().optInt("readRaw"));
    assertTrue(snapshot().optBoolean("readMayBeCached"));
    assertFalse(snapshot().optBoolean("audioConnected"));
    probe.stop("stopped", "manual");
    idle();
    assertEquals(Arrays.asList(8, 12, 9), remote.transactions);
    assertEquals(1, context.unbinds);
    assertEquals(0, snapshot().optInt("unregisterResult", -1));
    assertFalse(snapshot().optBoolean("cleanupPending"));
  }

  @Test public void callbacksSeparateReadinessBaselineAndChanges() throws Exception {
    start();
    remote.callback(6, "", 0);
    remote.callback(4, "", 0);
    remote.callback(3, "gear", 1000);
    remote.callback(3, "gear", 1000);
    remote.callback(3, "gear", 1003);
    remote.callback(3, "power", 1);
    idle();
    assertEquals(1, snapshot().optInt("readyCount"));
    assertEquals(1, snapshot().optInt("uniqueCodeReadyCount"));
    assertEquals(4, snapshot().optInt("vehicleCallbackCount"));
    assertEquals(3, snapshot().optInt("gearCallbackCount"));
    assertEquals(1, snapshot().optInt("callbackChanges"));
    assertEquals(1003, snapshot().optInt("callbackRaw"));
    assertEquals(1002, snapshot().optInt("readRaw"));
    assertTrue(diagnostics.live().isEmpty());
    assertTrue(diagnostics.candidates().isEmpty());
  }

  @Test public void invalidGearDoesNotCountAsValidChange() throws Exception {
    start();
    remote.callback(3, "gear", -1003);
    remote.callback(3, "gear", 1001);
    idle();
    assertEquals(1, snapshot().optInt("invalidGearCallbacks"));
    assertEquals(0, snapshot().optInt("callbackChanges"));
    assertTrue(snapshot().optString("callbackLabel").contains("不等同N"));
  }

  @Test public void stopsOnTimeoutAndIgnoresLateCallbacks() throws Exception {
    start();
    shadowOf(Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS);
    assertFalse(probe.active());
    assertEquals("timeout", snapshot().optString("phase"));
    assertEquals(1, context.unbinds);
    remote.callback(3, "gear", 1000);
    idle();
    assertEquals(0, snapshot().optInt("gearCallbackCount"));
  }

  @Test public void bindingTimeoutDoesNotRegisterOrRead() {
    context.connect = false;
    start();
    shadowOf(Looper.getMainLooper()).idleFor(9, TimeUnit.SECONDS);
    assertFalse(probe.active());
    assertTrue(remote.transactions.isEmpty());
    assertEquals(1, context.unbinds);
  }

  @Test public void routeRedirectionUsesExactHostAndComponent() {
    install("com.vendor.actualplatform");
    probe = create(() -> "com.vendor.actualplatform");
    start();
    assertEquals("com.vendor.actualplatform", context.intent.getPackage());
    assertEquals("com.vendor.actualplatform", context.intent.getComponent().getPackageName());
    assertEquals("com.vendor.actualplatform", snapshot().optString("hostPackage"));
  }

  @Test public void unreadableRouteFailsWithoutGuessingDefault() {
    probe = create(() -> { throw new SecurityException("hidden API denied"); });
    start();
    assertEquals(0, context.binds);
    assertEquals("route-error", snapshot().optString("phase"));
  }

  @Test public void nonExportedServiceIsNotBound() {
    serviceInfo.exported = false;
    start();
    assertEquals(0, context.binds);
    assertFalse(probe.active());
  }

  @Test public void nonSystemHostIsNotBound() {
    serviceInfo.applicationInfo.flags = 0;
    start();
    assertEquals(0, context.binds);
  }

  @Test public void disabledServiceIsNotBound() {
    serviceInfo.enabled = false;
    start();
    assertEquals(0, context.binds);
  }

  @Test public void deniedEntryPermissionDoesNotAttemptBinding() {
    serviceInfo.permission = "vendor.signature.PROTECTED";
    start();
    assertEquals(0, context.binds);
    assertTrue(diagnostics.lines("").contains("服务入口权限未授予"));
  }

  @Test public void bindFalseAndBindExceptionAreReported() {
    context.accepted = false;
    start();
    assertEquals("bind-error", snapshot().optString("phase"));
    assertEquals(0, context.unbinds);
    probe = create(() -> "");
    context.accepted = true;
    context.failure = new SecurityException("bind denied");
    start();
    assertEquals("bind-error", snapshot().optString("phase"));
    assertTrue(diagnostics.lines("").contains("bind denied"));
  }

  @Test public void registrationRejectedStillRecordsIndependentRead() {
    remote.registerResult = -1002;
    start();
    assertFalse(probe.active());
    assertEquals(-1002, snapshot().optInt("registerResult"));
    assertEquals(1002, snapshot().optInt("readRaw"));
    assertEquals(Arrays.asList(8, 12, 9), remote.transactions);
  }

  @Test public void readExceptionDoesNotTurnRegistrationIntoSuccessData() {
    remote.denyRead = true;
    start();
    assertTrue(probe.active());
    assertTrue(snapshot().has("readError"));
    assertFalse(snapshot().has("readRaw"));
    assertEquals(0, snapshot().optInt("gearCallbackCount"));
  }

  @Test public void registrationExceptionIsVisibleAndNoRetryLoopRuns() {
    remote.denyRegister = true;
    start();
    assertFalse(probe.active());
    assertTrue(snapshot().optString("registerError").contains("denied"));
    assertEquals(1002, snapshot().optInt("readRaw"));
    int calls = remote.transactions.size();
    shadowOf(Looper.getMainLooper()).idleFor(90, TimeUnit.SECONDS);
    assertEquals(calls, remote.transactions.size());
  }

  @Test public void disconnectUnregistersOnceAndDoesNotReconnect() {
    start();
    context.connection.onServiceDisconnected(context.intent.getComponent());
    context.connection.onBindingDied(context.intent.getComponent());
    idle();
    assertFalse(probe.active());
    assertEquals(1, context.unbinds);
    assertEquals(Arrays.asList(8, 12, 9), remote.transactions);
  }

  @Test public void stoppingDuringRegistrationStillUnregisters() {
    remote.duringRegister = () -> probe.stop("stopped", "during register");
    start();
    assertFalse(probe.active());
    assertEquals(Arrays.asList(8, 9), remote.transactions);
  }

  @Test public void queuedCallbacksAreBoundedAndIgnoredAfterStop() throws Exception {
    start();
    for (int index = 0; index < 200; index++) remote.callback(3, "gear", 1002);
    idle();
    assertEquals(64, snapshot().optInt("gearCallbackCount"));
    assertEquals(136, snapshot().optInt("droppedCallbacks"));
    remote.callback(3, "gear", 1000);
    probe.stop("stopped", "before queued callback");
    idle();
    assertEquals(64, snapshot().optInt("gearCallbackCount"));
  }

  @Test public void callbacksRequireServiceUid() throws Exception {
    serviceInfo.applicationInfo.uid = android.os.Process.myUid() + 42;
    start();
    try {
      remote.callback(3, "gear", 1002);
      fail("Unexpected caller accepted");
    } catch (SecurityException expected) {
      assertTrue(expected.getMessage().contains("UID"));
    }
    idle();
    assertEquals(0, snapshot().optInt("gearCallbackCount"));
  }

  @Test public void reportPersistsAndExportsWithoutBecomingAudioSample() throws Exception {
    start();
    remote.callback(3, "gear", 1002);
    idle();
    probe.stop("stopped", "finished");
    idle();
    JSONObject persisted = Diagnostics.latestPlatformProbe(app);
    assertEquals(1, persisted.optInt("gearCallbackCount"));
    JSONObject exported = new JSONObject(diagnostics.export(false, new Store(app)));
    assertEquals("0.3.34", exported.optString("version"));
    assertEquals(1, exported.getJSONObject("platformVehicleProbe").optInt("gearCallbackCount"));
    assertEquals(0, exported.getJSONArray("candidates").length());
  }

  @Test public void stoppedQueuedWorkCannotBindLater() {
    executor.queued = true;
    probe.start();
    probe.stop("stopped", "cancel before route");
    executor.drain();
    idle();
    assertEquals(0, context.binds);
  }

  @Test public void stalledWorkBlocksRepeatedSessionsWithoutQueuingMoreCalls() {
    executor.queued = true;
    probe.start();
    shadowOf(Looper.getMainLooper()).idleFor(9, TimeUnit.SECONDS);
    int queued = executor.tasks.size();
    PlatformVehicleProbe another = create(() -> "");
    another.start();
    assertFalse(another.active());
    assertEquals("busy", snapshot().optString("phase"));
    assertEquals(queued, executor.tasks.size());
    executor.drain();
    idle();
    assertEquals("busy", snapshot().optString("phase"));
    assertEquals(0, context.binds);
  }

  @Test public void oldCleanupCannotReplaceNewSessionReport() {
    start();
    long previous = snapshot().optLong("sessionId");
    probe.stop("stopped", "first session");
    probe = create(() -> "");
    probe.start();
    idle();
    assertNotEquals(previous, snapshot().optLong("sessionId"));
    assertEquals("listening", snapshot().optString("phase"));
    assertFalse(snapshot().has("unregisterResult"));
  }

  @Test public void unexpectedDescriptorFailsBeforeAnyVehicleTransactions() {
    remote.attachInterface(null, "unexpected.service");
    start();
    assertFalse(probe.active());
    assertEquals("protocol-error", snapshot().optString("phase"));
    assertTrue(remote.transactions.isEmpty());
    assertEquals(1, context.unbinds);
  }

  @Test public void unsupportedReadDoesNotFabricateGearValue() {
    remote.unsupportedRead = true;
    start();
    assertTrue(probe.active());
    assertTrue(snapshot().optString("readError").contains("Unsupported"));
    assertFalse(snapshot().has("readRaw"));
  }

  @Test public void nullBindingStopsWithoutRegistering() {
    context.connect = false;
    start();
    context.connection.onNullBinding(context.intent.getComponent());
    idle();
    assertFalse(probe.active());
    assertEquals("null-binding", snapshot().optString("phase"));
    assertEquals(1, context.unbinds);
    assertTrue(remote.transactions.isEmpty());
  }

  @Test public void corruptPersistedReportDoesNotPreventDiagnostics() throws Exception {
    app.getSharedPreferences("platform_vehicle_probe", 0).edit()
        .putString("latest", "not json").commit();
    assertTrue(Diagnostics.latestPlatformProbe(app).has("loadError"));
  }

  @Test public void restoredActiveReportIsMarkedInterruptedRatherThanConnected() throws Exception {
    app.getSharedPreferences("platform_vehicle_probe", 0).edit()
        .putString("latest", new JSONObject().put("sessionId", 42).put("active", true)
            .put("connected", true).put("registered", true).toString()).commit();
    Diagnostics restored = new Diagnostics();
    restored.attach(app);
    JSONObject persisted = Diagnostics.latestPlatformProbe(app);
    assertFalse(persisted.optBoolean("active"));
    assertFalse(persisted.optBoolean("registered"));
    assertEquals("previous-session-interrupted", persisted.optString("phase"));
    assertTrue(persisted.optBoolean("cleanupUnknown"));
  }
}
