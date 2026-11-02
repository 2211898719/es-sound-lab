package cn.es.soundlab;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import dalvik.system.PathClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AdbVehicleProbe {
  static final String MARKER = "ES_VEHICLE_PROBE=";
  private static final int[] IDS = {0x0600002c, 0x0400000a, 0x67000096, 0x50000022};
  private static final String[] DOMAINS = {"ENTRY_LOCK", "CAR_DRIVING", "VEHICLE_CONTROL", "SEQUOIA"};
  private final JSONObject report = new JSONObject();
  private final AtomicBoolean finished = new AtomicBoolean();
  private JSONArray targets;
  private Object car, manager;
  private Handler handler;
  private int rounds;

  public static void main(String[] arguments) {
    AdbVehicleProbe probe = new AdbVehicleProbe();
    Thread timeout = new Thread(() -> {
      try {
        Thread.sleep(11000);
        probe.error(new IllegalStateException("只读探针超过11秒期限"));
        probe.finish("timed-out");
      } catch (InterruptedException ignored) {
      }
    }, "vehicle-probe-timeout");
    timeout.setDaemon(true);
    timeout.start();
    try {
      probe.report.put("source", "adb-shell-native-read-probe").put("uid", Process.myUid())
          .put("schemaVersion", 2).put("phase", "process-start")
          .put("startedAt", System.currentTimeMillis()).put("subscriptionVerified", false)
          .put("processVerified", false).put("contextReady", false).put("sdkLoaded", false)
          .put("permissionsChecked", false)
          .put("serviceConnected", false).put("managerAvailable", false)
          .put("readAttempted", false);
      if (Process.myUid() != 2000) throw new SecurityException("验证仅允许ADB shell UID 2000，不尝试root或提权");
      probe.report.put("processVerified", true).put("phase", "context-setup");
      if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
      probe.handler = new Handler(Looper.getMainLooper());
      Class<?> threadClass = Class.forName("android.app.ActivityThread");
      Object thread = threadClass.getMethod("systemMain").invoke(null);
      Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
      Context shell = system.createPackageContext("com.android.shell", 0);
      probe.report.put("contextReady", true).put("phase", "permission-check");
      probe.targets = targets(shell);
      probe.report.put("properties", probe.targets).put("permissionsChecked", true)
          .put("phase", "sdk-load");
      ClassLoader loader = loadSdk(shell, probe.report);
      Class<?> mega = Class.forName("mega.car.MegaCar", true, loader);
      probe.report.put("sdkLoaded", true);
      probe.car = initialize(mega, shell, probe.handler, probe.report);
      probe.handler.post(probe::read);
      Looper.loop();
    } catch (Throwable error) {
      probe.error(error);
      probe.finish("probe-error");
    }
  }

  static Object initialize(Class<?> sdk, Context context, Handler handler, JSONObject report)
      throws Exception {
    report.put("phase", "service-connect")
        .put("sdkInitializer", "MegaCar.createCar(Context, Handler)")
        .put("serviceBindingRequested", false).put("serviceConnected", false);
    Method factory;
    try {
      factory = sdk.getMethod("createCar", Context.class, Handler.class);
    } catch (NoSuchMethodException error) {
      throw new NoSuchMethodException("SDK缺少两参数createCar；本轮不回退到绑定服务入口");
    }
    Object car = factory.invoke(null, context, handler);
    requireConnected(car, report);
    return car;
  }

  private static void requireConnected(Object car, JSONObject report) throws Exception {
    report.put("phase", "service-connect").put("serviceConnected", false);
    if (car == null) throw new IllegalStateException("MegaCar.createCar returned null");
    if (!Boolean.TRUE.equals(call(car, "isConnected")))
      throw new IllegalStateException("SDK未取得已运行的车辆服务；本轮不绑定、启动或持续重试服务");
    report.put("serviceConnected", true);
  }

  static JSONArray targets(Context context) throws Exception {
    JSONArray targets = new JSONArray();
    for (int index = 0; index < IDS.length; index++) {
      String permission = "mega.car.permission." + DOMAINS[index];
      targets.put(new JSONObject().put("propertyId", IDS[index])
          .put("hexId", "0x" + Integer.toHexString(IDS[index])).put("area", 0)
          .put("permission", permission)
          .put("permissionGranted", context.checkPermission(permission, Process.myPid(), Process.myUid())
              == PackageManager.PERMISSION_GRANTED)
          .put("samples", new JSONArray()));
    }
    return targets;
  }

  private void read() {
    if (finished.get()) return;
    try {
      requireConnected(car, report);
      if (manager == null) {
        report.put("phase", "manager-create");
        manager = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
        if (manager == null) throw new IllegalStateException("property manager unavailable");
        report.put("manager", manager.getClass().getName()).put("managerAvailable", true);
      }
      report.put("phase", "property-read");
      snapshot(manager, targets);
      boolean allDenied = true;
      for (int index = 0; index < targets.length(); index++)
        if (!targets.getJSONObject(index).optBoolean("readDenied")) allDenied = false;
      if (allDenied) {
        finish("permissions-denied");
        return;
      }
      if (++rounds < 4) handler.postDelayed(this::read, 2000);
      else finish("completed");
    } catch (Throwable error) {
      error(error);
      finish("probe-error");
    }
  }

  static void snapshot(Object manager, JSONArray targets) throws Exception {
    Method getter = manager.getClass().getMethod("getProperty", Class.class, int.class, int.class);
    for (int index = 0; index < targets.length(); index++) {
      JSONObject target = targets.getJSONObject(index);
      if (target.optBoolean("readDenied")) continue;
      JSONObject sample = new JSONObject().put("receivedUptimeMs", SystemClock.uptimeMillis());
      target.getJSONArray("samples").put(sample);
      try {
        int id = target.getInt("propertyId");
        Object value = getter.invoke(manager, Integer.class, id, 0);
        if (value == null) throw new IllegalStateException("getProperty returned null");
        int actualId = ((Number) call(value, "getPropertyId")).intValue();
        int area = ((Number) call(value, "getAreaId")).intValue();
        int status = ((Number) call(value, "getStatus")).intValue();
        Object raw = call(value, "getValue");
        sample.put("propertyId", actualId).put("area", area).put("status", status)
            .put("raw", raw == null ? JSONObject.NULL : String.valueOf(raw))
            .put("valid", actualId == id && area == 0 && status == 0 && raw instanceof Integer);
        try {
          sample.put("timestamp", call(value, "getTimestamp"));
        } catch (ReflectiveOperationException ignored) {
        }
      } catch (Throwable error) {
        Throwable cause = cause(error);
        sample.put("error", cause.toString()).put("valid", false);
        if (cause instanceof SecurityException) target.put("readDenied", true);
      }
    }
  }

  static void summarize(JSONObject report) throws Exception {
    JSONArray targets = report.optJSONArray("properties");
    boolean read = false, changed = false, attempted = false;
    if (targets != null)
      for (int index = 0; index < targets.length(); index++) {
        JSONObject target = targets.getJSONObject(index);
        JSONArray samples = target.getJSONArray("samples");
        attempted |= samples.length() > 0;
        String previous = null;
        boolean targetRead = false, targetChanged = false;
        for (int sampleIndex = 0; sampleIndex < samples.length(); sampleIndex++) {
          JSONObject sample = samples.getJSONObject(sampleIndex);
          if (!sample.optBoolean("valid")) continue;
          targetRead = true;
          String value = sample.getString("raw");
          if (previous != null && !previous.equals(value)) targetChanged = true;
          previous = value;
        }
        target.put("readVerified", targetRead).put("changeObserved", targetChanged);
        read |= targetRead;
        changed |= targetChanged;
      }
    report.put("readAttempted", attempted).put("readVerified", read)
        .put("changeObserved", changed).put("subscriptionVerified", false);
  }

  static void recordError(JSONObject report, Throwable error) throws Exception {
    report.put("failedPhase", report.optString("phase", "unknown"))
        .put("error", cause(error).toString());
  }

  private synchronized void error(Throwable error) {
    try {
      recordError(report, error);
    } catch (Exception ignored) {
    }
  }

  private synchronized void finish(String outcome) {
    if (!finished.compareAndSet(false, true)) return;
    try {
      summarize(report);
      report.put("outcome", outcome).put("finishedAt", System.currentTimeMillis());
      System.out.println(MARKER + report.toString());
      System.out.flush();
    } catch (Exception ignored) {
    } finally {
      System.exit(0);
    }
  }

  private static ClassLoader loadSdk(Context context, JSONObject report) throws Exception {
    ClassLoader base = AdbVehicleProbe.class.getClassLoader();
    try {
      Class.forName("mega.car.MegaCar", false, base);
      report.put("sdk", "system/app classloader");
      return base;
    } catch (ClassNotFoundException ignored) {
    }
    JSONArray errors = new JSONArray();
    report.put("sdkAttempts", errors);
    for (String pkg : new String[] {"com.mega.car", "com.iflytek.autofly.carcontroladapter"}) {
      try {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg,
            PackageManager.GET_SHARED_LIBRARY_FILES);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (info.sourceDir != null) paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) java.util.Collections.addAll(paths, info.splitSourceDirs);
        if (info.sharedLibraryFiles != null) java.util.Collections.addAll(paths, info.sharedLibraryFiles);
        ClassLoader candidate = new PathClassLoader(android.text.TextUtils.join(":", paths), base);
        Class.forName("mega.car.MegaCar", true, candidate);
        report.put("sdk", pkg);
        return candidate;
      } catch (Exception | LinkageError error) {
        errors.put(pkg + ": " + cause(error));
      }
    }
    throw new ClassNotFoundException("Mega SDK及依赖未加载");
  }

  private static Object call(Object target, String method) throws ReflectiveOperationException {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static Throwable cause(Throwable error) {
    while (error instanceof InvocationTargetException && error.getCause() != null)
      error = error.getCause();
    return error;
  }
}
