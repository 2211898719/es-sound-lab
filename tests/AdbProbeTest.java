package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class AdbProbeTest {
  private Application app;
  private Handler handler;

  public static final class ExistingCar {
    boolean connected = true;

    public boolean isConnected() { return connected; }
  }

  public static final class ExistingSdk {
    static ExistingCar car;
    static int calls;
    static Context context;
    static Handler handler;

    public static ExistingCar createCar(Context context, Handler handler) {
      calls++;
      ExistingSdk.context = context;
      ExistingSdk.handler = handler;
      return car;
    }
  }

  public static final class BindingOnlySdk {
    static int calls;

    public static ExistingCar createCar(Context context, Handler handler, long timeout, Object listener) {
      calls++;
      throw new AssertionError("binding factory must not be used");
    }
  }

  public static final class DeniedSdk {
    public static ExistingCar createCar(Context context, Handler handler) {
      throw new SecurityException("service lookup denied");
    }
  }

  @Before
  public void setup() {
    app = RuntimeEnvironment.getApplication();
    handler = new Handler(Looper.getMainLooper());
    ExistingSdk.car = new ExistingCar();
    ExistingSdk.calls = 0;
    ExistingSdk.context = null;
    ExistingSdk.handler = null;
    BindingOnlySdk.calls = 0;
  }

  private JSONObject report() throws Exception {
    return new JSONObject().put("source", "adb-shell-native-read-probe").put("uid", 2000)
        .put("properties", AdbVehicleProbe.targets(app));
  }

  private JSONObject initializeFailure(Class<?> sdk) throws Exception {
    JSONObject report = report();
    try {
      AdbVehicleProbe.initialize(sdk, app, handler, report);
      fail("initialization must fail");
    } catch (Exception error) {
      AdbVehicleProbe.recordError(report, error);
    }
    AdbVehicleProbe.summarize(report);
    assertEquals("service-connect", report.getString("failedPhase"));
    assertFalse(report.getBoolean("serviceConnected"));
    assertFalse(report.getBoolean("serviceBindingRequested"));
    assertFalse(report.getBoolean("readAttempted"));
    assertFalse(report.getBoolean("subscriptionVerified"));
    return report;
  }

  @Test
  public void existingServiceFactoryPreservesContextAndNeverBindsOrStartsService() throws Exception {
    Context context = new ContextWrapper(app) {
      @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
        throw new AssertionError("must not bind a service");
      }

      @Override public ComponentName startService(Intent intent) {
        throw new AssertionError("must not start a service");
      }
    };
    JSONObject report = report();
    assertSame(ExistingSdk.car, AdbVehicleProbe.initialize(ExistingSdk.class, context, handler, report));
    assertSame(context, ExistingSdk.context);
    assertSame(handler, ExistingSdk.handler);
    assertEquals(1, ExistingSdk.calls);
    assertTrue(report.getBoolean("serviceConnected"));
    assertFalse(report.getBoolean("serviceBindingRequested"));
    assertEquals("MegaCar.createCar(Context, Handler)", report.getString("sdkInitializer"));
  }

  @Test
  public void missingQueryOverloadDoesNotFallBackToBindingFactory() throws Exception {
    JSONObject report = initializeFailure(BindingOnlySdk.class);
    assertTrue(report.getString("error").contains("不回退"));
    assertEquals(0, BindingOnlySdk.calls);
  }

  @Test
  public void missingExistingServiceStopsWithoutRetryingOrClaimingConnection() throws Exception {
    ExistingSdk.car.connected = false;
    JSONObject report = initializeFailure(ExistingSdk.class);
    assertEquals(1, ExistingSdk.calls);
    assertTrue(report.getString("error").contains("未取得已运行的车辆服务"));
  }

  @Test
  public void nullFactoryResultCannotVerifyConnection() throws Exception {
    ExistingSdk.car = null;
    assertTrue(initializeFailure(ExistingSdk.class).getString("error").contains("returned null"));
  }

  @Test
  public void lookupDenialRetainsItsActualCauseAndDoesNotTryAnotherIdentity() throws Exception {
    JSONObject report = initializeFailure(DeniedSdk.class);
    assertEquals("java.lang.SecurityException: service lookup denied", report.getString("error"));
    assertEquals(2000, report.getInt("uid"));
  }

  @Test
  public void failureRetainsPipelinePhaseAndUnwrapsReflectionErrors() throws Exception {
    for (String phase : new String[] {"context-setup", "permission-check", "sdk-load", "manager-create", "property-read"}) {
      JSONObject report = report().put("phase", phase);
      AdbVehicleProbe.recordError(report,
          new InvocationTargetException(new InvocationTargetException(new IllegalStateException("test"))));
      assertEquals(phase, report.getString("failedPhase"));
      assertEquals("java.lang.IllegalStateException: test", report.getString("error"));
    }
  }

  @Test
  public void connectedServiceWithNoSamplesDoesNotImplyReadOrSubscription() throws Exception {
    JSONObject report = report().put("sdkLoaded", true);
    AdbVehicleProbe.initialize(ExistingSdk.class, app, handler, report);
    JSONObject parsed = AdbDiagnosticCommands.probeResult(AdbVehicleProbe.MARKER + report, false);
    assertTrue(parsed.getBoolean("processVerified"));
    assertTrue(parsed.getBoolean("serviceConnected"));
    assertFalse(parsed.getBoolean("readAttempted"));
    assertFalse(parsed.getBoolean("readVerified"));
    String summary = AdbDiagnosticsActivity.probeSummary(parsed);
    assertTrue(summary.contains("车辆服务：已连接"));
    assertTrue(summary.contains("实际读取：未执行"));
    assertTrue(summary.contains("订阅：本轮不测试"));
  }

  @Test
  public void propertyDenialIsReadAttemptNotSuccessfulValueOrConnectionFailure() throws Exception {
    JSONObject report = report().put("serviceConnected", true).put("managerAvailable", true);
    VehicleAccessTest.Manager manager = new VehicleAccessTest.Manager();
    for (int index = 0; index < report.getJSONArray("properties").length(); index++)
      manager.readDenied.add(report.getJSONArray("properties").getJSONObject(index).getInt("propertyId"));
    AdbVehicleProbe.snapshot(manager, report.getJSONArray("properties"));
    AdbVehicleProbe.summarize(report);
    assertTrue(report.getBoolean("readAttempted"));
    assertFalse(report.getBoolean("readVerified"));
    assertTrue(report.getBoolean("serviceConnected"));
    String summary = AdbDiagnosticsActivity.probeSummary(report);
    assertTrue(summary.contains("实际读取：已尝试，未取得有效值"));
    assertFalse(summary.contains("实际读取：未执行"));
  }

  @Test
  public void oldBindingFailureIsStillDisplayedAsUnattemptedReadWithOriginalError() throws Exception {
    JSONObject report = report().put("sdk", "com.mega.car").put("outcome", "probe-error")
        .put("error", "Unable to find app for caller when binding service");
    JSONObject parsed = AdbDiagnosticCommands.probeResult(AdbVehicleProbe.MARKER + report, false);
    String summary = AdbDiagnosticsActivity.probeSummary(parsed);
    assertTrue(summary.contains("shell已启动"));
    assertTrue(summary.contains("SDK：已加载"));
    assertTrue(summary.contains("车辆服务：未确认连接"));
    assertTrue(summary.contains("实际读取：未执行"));
    assertTrue(summary.contains("Unable to find app for caller"));
  }

  @Test
  public void permissionMetadataDoesNotCountAsAttemptedOrSuccessfulRead() throws Exception {
    JSONObject report = report();
    for (int index = 0; index < report.getJSONArray("properties").length(); index++)
      report.getJSONArray("properties").getJSONObject(index).put("permissionGranted", index == 0);
    AdbVehicleProbe.summarize(report);
    String summary = AdbDiagnosticsActivity.probeSummary(report);
    assertTrue(summary.contains("权限元数据：1/4项获授"));
    assertTrue(summary.contains("实际读取：未执行"));
    assertFalse(report.getBoolean("readAttempted"));
    assertFalse(report.getBoolean("readVerified"));
  }

  @Test
  public void serviceFailureSummaryNamesTheStageAndReason() throws Exception {
    ExistingSdk.car.connected = false;
    String summary = AdbDiagnosticsActivity.probeSummary(initializeFailure(ExistingSdk.class));
    assertTrue(summary.contains("失败阶段：车辆服务连接"));
    assertTrue(summary.contains("未取得已运行的车辆服务"));
    assertFalse(summary.contains("取得有效属性值"));
  }
}
