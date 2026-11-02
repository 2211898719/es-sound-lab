package cn.es.soundlab;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only Mega property subscription for ES. Never issues vehicle control calls. */
public final class VehicleAccess {
  public interface Listener {
    void sample(Protocol.Sample sample);

    void reset(String source);
  }

  private final Context context;
  private final Handler io;
  private final Diagnostics diag;
  private final Store store;
  private final Listener listener;
  private boolean closed, discovery, permissionBlocked;
  private long nextConnect;
  private int epoch, subscriptionEpoch;
  private Object car, manager, callback;
  private ClassLoader loader;
  private final Map<Integer, List<Property>> properties = new LinkedHashMap<>();
  private final Set<Integer> subscribed = new LinkedHashSet<>();
  private final Map<String, Long> timestamps = new LinkedHashMap<>();
  private static final int[] REQUIRED = {
    100663340,
    100663341,
    100663342,
    100663343,
    100663345,
    67108874,
    Protocol.CENTRAL_LOCK,
    Protocol.AUTO_HOLD,
    67108875
  };

  public VehicleAccess(Context c, Handler h, Diagnostics d, Store s, Listener l) {
    context = c;
    io = h;
    diag = d;
    store = s;
    listener = l;
  }

  public void start(boolean scan) {
    discovery = scan;
    io.post(tick);
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (closed) return;
          if (SystemClock.uptimeMillis() >= nextConnect) {
            if (car == null) connect();
            else if (manager == null) {
              nextConnect = SystemClock.uptimeMillis() + 30000;
              try {
                if (Boolean.TRUE.equals(call(car, "isConnected"))) setup();
              } catch (Exception | LinkageError e) {
                diag.line("Mega连接", cause(e));
              }
            }
          }
          if (manager != null) {
            // Registered discrete properties use callbacks, never a periodic snapshot that can
            // overwrite a newer R -> D event. Only failed subscriptions fall back to reads.
            for (List<Property> zones : properties.values())
              for (Property p : zones) if (!subscribed.contains(p.id)) read(p);
            status();
          }
          io.postDelayed(this, 3000);
        }
      };

  private void dispatch(Runnable action) {
    if (Looper.myLooper() == io.getLooper()) action.run();
    else io.post(action);
  }

  private void connect() {
    nextConnect = SystemClock.uptimeMillis() + 30000;
    boolean allDenied = true;
    for (int id : REQUIRED) if (deniedPermission(id) == null) allDenied = false;
    if (allDenied) {
      diag.nativeStatus = "Mega 原生权限未授予 · 已暂停无效的直接读取/订阅，请使用ADB只读验证";
      diag.nativeSdkStatus = "Mega SDK：权限预检阻断，未重复加载";
      diag.nativeManagerStatus = "属性管理器：未连接（应用缺少签名/特权权限）";
      for (int id : REQUIRED) diag.nativeSubscription(id, false, deniedPermission(id));
      if (!permissionBlocked) {
        listener.reset("Mega");
        diag.line("Mega权限", diag.nativeStatus);
      }
      permissionBlocked = true;
      return;
    }
    permissionBlocked = false;
    final int token = ++epoch;
    try {
      loader = loadSdk();
      Class<?> cls = Class.forName("mega.car.MegaCar", true, loader);
      Class<?> life = Class.forName(cls.getName() + "$CarServiceLifecycleListener", false, loader);
      Object lifecycle =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {life},
              (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class)
                  return proxyValue(proxy, method, args);
                if (method.getName().equals("onLifecycleChanged")
                    && args != null
                    && args.length >= 2) {
                  Object origin = args[0];
                  boolean ready = Boolean.TRUE.equals(args[1]);
                  // Always enqueue: createCar may call back before assigning our car reference.
                  io.post(
                      () -> {
                        if (closed || token != epoch || origin != car) return;
                        if (ready) setup();
                        else {
                          detach();
                          listener.reset("Mega");
                          diag.nativeStatus = "Mega 服务断开，等待原生连接恢复";
                        }
                      });
                }
                return null;
              });
      car =
          cls.getMethod("createCar", Context.class, Handler.class, long.class, life)
              .invoke(null, context, io, 0L, lifecycle);
      if (car == null) throw new IllegalStateException("createCar returned null");
      diag.nativeStatus = "Mega 原生车辆服务连接中";
      if (Boolean.TRUE.equals(call(car, "isConnected"))) setup();
    } catch (Exception | LinkageError e) {
      diag.nativeStatus = "Mega 原生接口不可用 · 请查看 SDK / 权限诊断";
      diag.line("Mega连接", cause(e));
      if (car != null) {
        try {
          call(car, "disconnect");
        } catch (Exception | LinkageError ignored) {
        }
      }
      car = null;
      ++epoch;
    }
  }

  private ClassLoader loadSdk() throws Exception {
    ClassLoader base = context.getClassLoader();
    try {
      Class.forName("mega.car.MegaCar", false, base);
      diag.nativeSdkStatus = "Mega SDK：应用或系统共享类加载器";
      return base;
    } catch (ClassNotFoundException | LinkageError e) {
      diag.line("Mega SDK", "基础类加载器不可用 · " + cause(e));
    }
    // Use installed, known clients and all their splits/shared libraries. Do not bundle
    // extracted vendor DEX or assume that the server's small proxy APK contains the SDK.
    for (String pkg : new String[] {"com.mega.car", "com.iflytek.autofly.carcontroladapter"}) {
      try {
        ApplicationInfo app =
            context
                .getPackageManager()
                .getApplicationInfo(pkg, PackageManager.GET_SHARED_LIBRARY_FILES);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (app.sourceDir != null) paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) java.util.Collections.addAll(paths, app.splitSourceDirs);
        if (app.sharedLibraryFiles != null)
          java.util.Collections.addAll(paths, app.sharedLibraryFiles);
        ClassLoader candidate = new PathClassLoader(android.text.TextUtils.join(":", paths), base);
        Class.forName("mega.car.MegaCar", true, candidate);
        diag.nativeSdkStatus = "Mega SDK：" + pkg + "（含分包及共享库）";
        diag.line("Mega SDK", diag.nativeSdkStatus);
        return candidate;
      } catch (Exception | LinkageError e) {
        diag.line("Mega SDK", pkg + " · " + cause(e));
      }
    }
    diag.nativeSdkStatus = "Mega SDK 未找到或缺少依赖（不等于权限拒绝）";
    throw new ClassNotFoundException("mega.car.MegaCar / installed SDK dependencies");
  }

  private void setup() {
    if (closed || car == null || manager != null) return;
    try {
      manager = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
      if (manager == null) throw new IllegalStateException("property manager unavailable");
      diag.nativeManagerStatus = "属性管理器：" + manager.getClass().getName();
      diag.line("Mega属性", diag.nativeManagerStatus);
      properties.clear();
      timestamps.clear();
      diag.resetNativeProperties();
      // Enumeration is optional: a denied/partial list must not block the other domains.
      enumerate();
      for (int id : REQUIRED)
        if (!properties.containsKey(id))
          add(id, new int[] {0}, id == 67108875 ? Float.class : Integer.class);
      // Reset before registering or reading; the first valid sample establishes baseline.
      listener.reset("Mega");
      try {
        subscribe();
      } catch (Exception | LinkageError e) {
        diag.line("Mega订阅", "回调接口不可用，逐项只读轮询 · " + cause(e));
        for (int id : properties.keySet()) diag.nativeSubscription(id, false, cause(e));
      }
      for (List<Property> zones : properties.values()) for (Property p : zones) read(p);
      status();
    } catch (Exception | LinkageError e) {
      diag.nativeStatus = "Mega 属性管理器不可用 · " + cause(e);
      diag.line("Mega属性", cause(e));
      detach();
      listener.reset("Mega");
    }
  }

  public void discovery(boolean scan) {
    if (closed) return;
    discovery = scan;
    if (manager == null) return;
    Set<Integer> previous = new LinkedHashSet<>(properties.keySet());
    if (!scan) {
      Set<Integer> removed = new LinkedHashSet<>();
      for (int id : properties.keySet()) if (!wanted(id)) removed.add(id);
      if (!removed.isEmpty() && unregisterIds(manager, callback, removed)) {
        for (int id : removed) {
          properties.remove(id);
          subscribed.remove(id);
          timestamps.keySet().removeIf(key -> key.startsWith(id + ":"));
          diag.nativeSubscription(id, false, "扩展检测或规则已结束，停止采集");
        }
      }
    }
    enumerate();
    try {
      subscribe();
    } catch (Exception | LinkageError e) {
      diag.line("Mega订阅", cause(e));
    }
    for (Map.Entry<Integer, List<Property>> entry : properties.entrySet())
      if (!previous.contains(entry.getKey())) for (Property p : entry.getValue()) read(p);
    status();
  }

  private void enumerate() {
    try {
      Object list = call(manager, "getPropertyList");
      if (list instanceof Iterable<?>) {
        for (Object config : (Iterable<?>) list) {
          int id = ((Number) call(config, "getPropertyId")).intValue();
          if (properties.containsKey(id) || !wanted(id)) continue;
          int[] areas = (int[]) call(config, "getAreaIds");
          Class<?> type = (Class<?>) call(config, "getPropertyType");
          if (areas == null || areas.length == 0) areas = new int[] {0};
          add(id, areas, type);
        }
      }
    } catch (Exception | LinkageError e) {
      diag.line("Mega属性目录", "枚举失败，逐项验证已知只读属性 · " + cause(e));
    }
  }

  private boolean wanted(int id) {
    for (int required : REQUIRED) if (required == id) return true;
    if (id == 100663305) return true; // Legacy door aggregation guard in MonitorService.
    org.json.JSONArray rules = store.rules();
    for (int i = 0; i < rules.length(); i++) {
      org.json.JSONObject rule = rules.optJSONObject(i);
      if (rule != null
          && rule.optString("source").equals("Mega")
          && rule.optLong("propertyId") == id) return true;
    }
    return discovery && properties.size() < 80;
  }

  private void add(int id, int[] areas, Class<?> type) {
    List<Property> zones = new ArrayList<>();
    for (int area : areas) zones.add(new Property(id, area, type));
    properties.put(id, zones);
    diag.property("Mega", id, areas, type.getSimpleName());
  }

  private void subscribe() throws Exception {
    Class<?> cb =
        Class.forName(
            "mega.car.hardware.property.CarPropertyManager$CarPropertyEventCallback",
            false,
            loader);
    final Object activeManager = manager;
    final int token = subscriptionEpoch;
    if (callback == null)
      callback =
          Proxy.newProxyInstance(
              loader,
              new Class<?>[] {cb},
              (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class)
                  return proxyValue(proxy, method, args);
                // The SDK's sticky helper reads its own annotation areas and can throw
                // asynchronously
                // on a denied domain. We read initial values explicitly with per-property
                // isolation.
                if (method.getName().equals("isSticky")) return false;
                if (method.getName().equals("onChangeEvent") && args != null && args.length > 0) {
                  Object value = args[0];
                  dispatch(
                      () -> {
                        if (!closed && token == subscriptionEpoch && manager == activeManager)
                          accept(value, true);
                      });
                } else if (method.getName().equals("onErrorEvent")
                    && args != null
                    && args.length >= 2) {
                  int id = ((Number) args[0]).intValue(), area = ((Number) args[1]).intValue();
                  dispatch(
                      () -> {
                        if (closed
                            || token != subscriptionEpoch
                            || manager != activeManager
                            || !selected(id, area)) return;
                        diag.nativePropertyError(id, area, "属性错误回调，等待有效样本恢复");
                        listener.sample(
                            Protocol.property(true, id, area, 1, null, System.currentTimeMillis()));
                      });
                }
                return null;
              });
    Method register;
    boolean batch;
    try {
      register = manager.getClass().getMethod("registerCallback", cb, SparseArray.class);
      batch = true;
    } catch (NoSuchMethodException e) {
      register = manager.getClass().getMethod("registerCallback", cb, int.class, float.class);
      batch = false;
    }
    for (int id : properties.keySet()) {
      if (subscribed.contains(id)) continue;
      String denied = deniedPermission(id);
      if (denied != null) {
        diag.nativeSubscription(id, false, denied);
        continue;
      }
      try {
        Object result;
        if (batch) {
          SparseArray<Float> rates = new SparseArray<>();
          rates.put(id, 0f);
          result = register.invoke(manager, callback, rates);
        } else result = register.invoke(manager, callback, id, 0f);
        if (Boolean.FALSE.equals(result)) throw new IllegalStateException("registerCallback=false");
        subscribed.add(id);
        diag.nativeSubscription(id, true, "已注册，等待真实回调");
      } catch (Exception | LinkageError e) {
        // The vendor SDK adds local listeners before the remote permission check. Clear
        // that entry so a later explicit retry really reaches the service again.
        unregisterIds(manager, callback, java.util.Collections.singleton(id));
        diag.nativeSubscription(id, false, cause(e));
        diag.line("Mega订阅", "0x" + Integer.toHexString(id) + " · 改用3秒只读轮询 · " + cause(e));
      }
    }
  }

  private void read(Property p) {
    String denied = deniedPermission(p.id);
    if (denied != null) {
      if (!p.failed) {
        p.failed = true;
        diag.nativePropertyError(p.id, p.area, denied);
        listener.sample(Protocol.property(true, p.id, p.area, 1, null, System.currentTimeMillis()));
      }
      return;
    }
    try {
      Object value =
          manager
              .getClass()
              .getMethod("getProperty", Class.class, int.class, int.class)
              .invoke(manager, p.type, p.id, p.area);
      if (value == null) throw new IllegalStateException("getProperty returned null");
      accept(value, false);
      p.failed = false;
    } catch (Exception | LinkageError e) {
      if (!p.failed) {
        diag.line("Mega读取", "0x" + Integer.toHexString(p.id) + " / " + p.area + " · " + cause(e));
        p.failed = true;
      }
      diag.nativePropertyError(p.id, p.area, cause(e));
      listener.sample(Protocol.property(true, p.id, p.area, 1, null, System.currentTimeMillis()));
    }
  }

  private String deniedPermission(int id) {
    String domain;
    if ((id >= 0x0600002c && id <= 0x0600002f) || id == 0x06000031) domain = "ENTRY_LOCK";
    else if (id == 0x0400000a || id == 0x0400000b) domain = "CAR_DRIVING";
    else if (id == Protocol.CENTRAL_LOCK) domain = "VEHICLE_CONTROL";
    else if (id == Protocol.AUTO_HOLD) domain = "SEQUOIA";
    else return null;
    String permission = "mega.car.permission." + domain;
    try {
      android.content.pm.PermissionInfo info = context.getPackageManager().getPermissionInfo(permission, 0);
      if ((info.protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE)
              == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE
          && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
        return permission + " 未授予（签名/特权权限），跳过重复直接访问";
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    return null;
  }

  private boolean selected(int id, int area) {
    List<Property> zones = properties.get(id);
    if (zones != null) for (Property p : zones) if (p.area == area) return true;
    return false;
  }

  private void accept(Object value, boolean fromCallback) {
    if (closed || value == null) return;
    try {
      int id = ((Number) call(value, "getPropertyId")).intValue();
      int area = ((Number) call(value, "getAreaId")).intValue();
      if (!selected(id, area)) return;
      int status = ((Number) call(value, "getStatus")).intValue();
      Object raw = call(value, "getValue");
      long stamp = 0;
      try {
        stamp = ((Number) call(value, "getTimestamp")).longValue();
      } catch (Exception ignored) {
      }
      String key = id + ":" + area;
      Long previous = timestamps.get(key);
      if (stamp > 0 && previous != null && stamp < previous) return;
      if (stamp > 0 && (id != 67108875 || stamp <= SystemClock.uptimeMillis() + 500))
        timestamps.put(key, stamp);
      diag.nativeSample(id, area, stamp, fromCallback, status, String.valueOf(raw));
      // Mega's timestamp may be uptime milliseconds, unlike Android Car's nanoseconds.
      // Preserve it for diagnosis; only explicit unavailable status invalidates discrete events.
      // For speed, accept only timestamps compatible with the SDK uptime-ms constructor.
      // Other clock formats remain unavailable until their server contract is confirmed.
      if (id == 67108875) {
        if (stamp <= 0
            || stamp > SystemClock.uptimeMillis() + 500
            || SystemClock.uptimeMillis() - stamp > 5000) status = 1;
      }
      Protocol.Sample sample =
          Protocol.property(true, id, area, status, raw, System.currentTimeMillis());
      if (id == 67108874
          && area == 0
          && status == 0
          && store.prefs.getBoolean("mega.gear.sdkEnums", false))
        sample =
            new Protocol.Sample(
                sample.topic,
                sample.propertyId,
                sample.area,
                sample.status,
                sample.raw,
                sample.time,
                sample.source,
                sample.key,
                Protocol.megaGear(raw, true));
      listener.sample(sample);
      status();
    } catch (Exception | LinkageError e) {
      diag.line("Mega解码", cause(e));
    }
  }

  private void status() {
    int blocked = 0;
    for (int id : properties.keySet()) if (deniedPermission(id) != null) blocked++;
    diag.nativeStatus =
        "Mega 原生属性 · 已注册 "
            + subscribed.size()
            + "/"
            + properties.size()
            + " · 轮询 "
            + (properties.size() - subscribed.size() - blocked)
            + " · 权限阻断 " + blocked
            + " · 不使用日志触发";
  }

  private boolean unregisterIds(Object target, Object cb, Set<Integer> ids) {
    if (target == null || cb == null) return false;
    try {
      Class<?> type = cb.getClass().getInterfaces()[0];
      target.getClass().getMethod("unregisterCallback", type, Set.class).invoke(target, cb, ids);
      return true;
    } catch (Exception | LinkageError e) {
      diag.line("Mega取消订阅", cause(e));
      return false;
    }
  }

  private void detach() {
    ++subscriptionEpoch;
    Object oldManager = manager, oldCallback = callback;
    manager = null;
    callback = null;
    if (oldManager != null && oldCallback != null) {
      try {
        Class<?> cb = oldCallback.getClass().getInterfaces()[0];
        try {
          oldManager.getClass().getMethod("unregisterCallback", cb).invoke(oldManager, oldCallback);
        } catch (NoSuchMethodException e) {
          oldManager
              .getClass()
              .getMethod("unregisterCallback", cb, Set.class)
              .invoke(oldManager, oldCallback, new LinkedHashSet<>(properties.keySet()));
        }
      } catch (Exception | LinkageError e) {
        diag.line("Mega取消订阅", cause(e));
      }
    }
    subscribed.clear();
    timestamps.clear();
    diag.nativeDisconnected();
  }

  public void stop() {
    closed = true;
    ++epoch;
    io.removeCallbacks(tick);
    detach();
    if (car != null) {
      try {
        call(car, "disconnect");
      } catch (Exception | LinkageError ignored) {
      }
    }
    car = null;
  }

  private static final class Property {
    final int id, area;
    final Class<?> type;
    boolean failed;

    Property(int i, int a, Class<?> t) {
      id = i;
      area = a;
      type = t;
    }
  }

  private static Object call(Object target, String method) throws Exception {
    return target.getClass().getMethod(method).invoke(target);
  }

  private static Object proxyValue(Object proxy, Method method, Object[] args) {
    switch (method.getName()) {
      case "hashCode":
        return System.identityHashCode(proxy);
      case "equals":
        return args != null && args.length == 1 && args[0] == proxy;
      case "toString":
        return "ESSoundReadOnlyCallback";
      default:
        return null;
    }
  }

  private static String cause(Throwable t) {
    while (t.getCause() != null && t.getCause() != t) t = t.getCause();
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }
}
