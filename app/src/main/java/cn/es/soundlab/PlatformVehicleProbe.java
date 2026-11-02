package cn.es.soundlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PlatformVehicleProbe {
  static final String ACTION = "com.iflytek.autofly.systemservice";
  static final String DESCRIPTOR = "com.iflytek.autofly.systemservice.aidl.ISystemServiceAIDL";
  static final String CALLBACK = "com.iflytek.autofly.systemservice.aidl.ISystemServiceAIDLListener";
  static final long OPERATION_TIMEOUT = 8000, SESSION_TIMEOUT = 60000;
  private static final AtomicLong sessions = new AtomicLong();
  private static final AtomicBoolean remoteBusy = new AtomicBoolean();
  private static final ExecutorService remoteCalls = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "ES-PlatformReadOnly");
    thread.setDaemon(true);
    return thread;
  });

  interface RouteReader {
    String read() throws Exception;
  }

  private final Context context;
  private final Handler main;
  private final Diagnostics diagnostics;
  private final ExecutorService worker;
  private final RouteReader route;
  private final JSONObject report = new JSONObject();
  private final JSONArray events = new JSONArray();
  private final AtomicInteger queuedCallbacks = new AtomicInteger(), droppedCallbacks = new AtomicInteger();
  private final AtomicBoolean cleanupStarted = new AtomicBoolean();
  private volatile boolean closed;
  private volatile boolean registerAttempted;
  private boolean started, bound, leased;
  private long startedElapsed;
  private int serviceUid = -1;
  private Integer previousCallback;
  private IBinder remote;
  private final Runnable timeout = () -> stop("timeout", "检测超时；同步Binder调用不能强制取消，注销结果单独记录");

  public PlatformVehicleProbe(Context context, Handler main, Diagnostics diagnostics) {
    this(context, main, diagnostics, remoteCalls,
        () -> (String) Class.forName("android.os.SystemProperties")
            .getMethod("get", String.class, String.class)
            .invoke(null, "ro.autofly.service.system", ""));
  }

  PlatformVehicleProbe(Context context, Handler main, Diagnostics diagnostics,
      ExecutorService worker, RouteReader route) {
    this.context = context;
    this.main = main;
    this.diagnostics = diagnostics;
    this.worker = worker;
    this.route = route;
  }

  private final ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
      if (closed) return;
      if (remote != null) return;
      remote = binder;
      put("connected", true);
      event("connected", name.flattenToShortString());
      arm(OPERATION_TIMEOUT);
      worker.execute(() -> query(binder));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      stop("disconnected", "平台服务已断开，不自动重连");
    }

    @Override
    public void onBindingDied(ComponentName name) {
      stop("binding-died", "绑定已失效，不自动重连");
    }

    @Override
    public void onNullBinding(ComponentName name) {
      stop("null-binding", "平台服务未返回Binder");
    }
  };

  private final Binder callback = new Binder() {
    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code == INTERFACE_TRANSACTION) {
        if (reply != null) reply.writeString(CALLBACK);
        return true;
      }
      if (code < 1 || code > 6) return super.onTransact(code, data, reply, flags);
      if (Binder.getCallingUid() != serviceUid)
        throw new SecurityException("Unexpected platform callback UID");
      data.enforceInterface(CALLBACK);
      String type = "";
      int value = 0, extra = 0;
      if (code == 1) value = data.readInt();
      else if (code == 2) {
        value = data.readInt();
        extra = data.readInt();
      } else if (code == 3 || code == 5) {
        type = data.readString();
        value = data.readInt();
      }
      enqueueCallback(code, type, value, extra);
      if (reply != null) reply.writeNoException();
      return true;
    }
  };

  public void start() {
    if (started || closed) return;
    started = true;
    startedElapsed = android.os.SystemClock.elapsedRealtime();
    put("sessionId", sessions.incrementAndGet());
    put("source", "app-uid-platform-gear-read-only");
    put("uid", android.os.Process.myUid());
    put("startedAt", System.currentTimeMillis());
    put("phase", "preflight");
    put("active", true);
    put("connected", false);
    put("registered", false);
    put("audioConnected", false);
    put("sourceTimestampAvailable", false);
    put("freshness", "unverified: query may use old preferences; callback has no source timestamp");
    put("events", events);
    diagnostics.beginPlatformProbe(report);
    leased = remoteBusy.compareAndSet(false, true);
    if (!leased) {
      closed = true;
      put("active", false);
      put("phase", "busy");
      event("busy", "上次远端调用尚未结束；不累积新的Binder调用，请稍后重试");
      return;
    }
    event("start", "本App原UID；仅查询gear及注册消费端回调，不触发音效");
    arm(OPERATION_TIMEOUT);
    worker.execute(() -> {
      try {
        String value = route.read();
        main.post(() -> bind(value));
      } catch (Exception | LinkageError error) {
        main.post(() -> stop("route-error", "无法确认服务路由：" + describe(error)));
      }
    });
  }

  private void bind(String property) {
    if (closed) return;
    try {
      if (property == null) throw new IllegalStateException("route property returned null");
      String host = property.isEmpty() ? "com.iflytek.autofly.platformservice" : property;
      put("routeProperty", property);
      put("hostPackage", host);
      Intent intent = new Intent(ACTION).setPackage(host);
      ResolveInfo resolved = context.getPackageManager().resolveService(intent, 0);
      ServiceInfo info = resolved == null ? null : resolved.serviceInfo;
      if (info == null || !host.equals(info.packageName) || !info.exported || !info.enabled
          || info.applicationInfo == null || !info.applicationInfo.enabled
          || (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)
        throw new SecurityException("未找到已启用且导出的系统平台服务");
      if (info.permission != null && !info.permission.isEmpty()
          && context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("服务入口权限未授予：" + info.permission);
      serviceUid = info.applicationInfo.uid;
      put("serviceUid", serviceUid);
      put("entryPermission", info.permission == null ? "" : info.permission);
      put("hostVersion", context.getPackageManager().getPackageInfo(host, 0).versionName);
      ComponentName component = new ComponentName(info.packageName, info.name);
      put("component", component.flattenToString());
      put("phase", "binding");
      put("autoCreateConsented", true);
      bound = true;
      if (!context.bindService(intent.setComponent(component), connection, Context.BIND_AUTO_CREATE)) {
        bound = false;
        throw new IllegalStateException("bindService returned false");
      }
      if (closed) return;
      event("bind-requested", "已请求绑定；首次启动可能引起原厂服务初始化");
      arm(OPERATION_TIMEOUT);
    } catch (Exception | LinkageError error) {
      stop("bind-error", describe(error));
    }
  }

  private void query(IBinder binder) {
    try {
      if (closed) return;
      if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
        throw new SecurityException("Unexpected platform Binder descriptor");
      int registration;
      try {
        if (closed) return;
        registerAttempted = true;
        registration = listenerTransaction(binder, 8);
        int result = registration;
        main.post(() -> {
          if (closed) return;
          put("registerResult", result);
          put("registered", result == 0);
          event("register", "result=" + result + "；成功不等于已收到车辆状态");
        });
      } catch (Exception | LinkageError error) {
        registration = -1;
        main.post(() -> {
          if (closed) return;
          put("registerError", describe(error));
          event("register-error", describe(error));
        });
      }
      if (closed) return;
      try {
        main.post(() -> {
          if (!closed) put("readAttempted", true);
        });
        int value = readGear(binder);
        main.post(() -> {
          if (closed) return;
          put("readCount", 1);
          put("readRaw", value);
          put("readValueRecognized", value >= 1000 && value <= 1003);
          put("readLabel", gearLabel(value));
          put("readReceivedAt", System.currentTimeMillis());
          put("readMayBeCached", true);
          event("read", "gear=" + value + " / " + gearLabel(value) + "（可能是旧缓存）");
        });
      } catch (Exception | LinkageError error) {
        main.post(() -> {
          if (closed) return;
          put("readError", describe(error));
          event("read-error", describe(error));
        });
      }
      int result = registration;
      main.post(() -> {
        if (closed) return;
        if (result != 0) {
          stop("register-failed", "查询已尝试，回调注册未成功");
          return;
        }
        put("phase", "listening");
        event("listening", "等待状态回调；无需为检测专门切挡或行驶");
        arm(45000);
      });
    } catch (Exception | LinkageError error) {
      main.post(() -> stop("protocol-error", describe(error)));
    } finally {
      if (closed) cleanup(binder);
    }
  }

  private int listenerTransaction(IBinder binder, int transaction) throws RemoteException {
    Parcel request = Parcel.obtain(), reply = Parcel.obtain();
    try {
      request.writeInterfaceToken(DESCRIPTOR);
      request.writeStrongBinder(callback);
      if (!binder.transact(transaction, request, reply, 0))
        throw new RemoteException("Unsupported listener transaction");
      reply.readException();
      return reply.readInt();
    } finally {
      reply.recycle();
      request.recycle();
    }
  }

  private int readGear(IBinder binder) throws RemoteException {
    Parcel request = Parcel.obtain(), reply = Parcel.obtain();
    try {
      request.writeInterfaceToken(DESCRIPTOR);
      request.writeString("gear");
      if (!binder.transact(12, request, reply, 0))
        throw new RemoteException("Unsupported gear query");
      reply.readException();
      return reply.readInt();
    } finally {
      reply.recycle();
      request.recycle();
    }
  }

  private void enqueueCallback(int code, String type, int value, int extra) {
    if (closed) return;
    if (queuedCallbacks.incrementAndGet() > 64) {
      queuedCallbacks.decrementAndGet();
      droppedCallbacks.incrementAndGet();
      return;
    }
    String key = type == null ? "" : type.substring(0, Math.min(type.length(), 160));
    long receivedAt = System.currentTimeMillis();
    main.post(() -> {
      try {
        if (closed) return;
        if (code == 3) {
          increment("vehicleCallbackCount");
          if ("gear".equals(key)) {
            increment("gearCallbackCount");
            put("callbackRaw", value);
            put("callbackLabel", gearLabel(value));
            put("callbackReceivedAt", receivedAt);
            if (value >= 1000 && value <= 1003) {
              if (previousCallback != null && previousCallback != value) increment("callbackChanges");
              previousCallback = value;
            } else {
              increment("invalidGearCallbacks");
              previousCallback = null;
            }
          }
          event("vehicle-callback", key + "=" + value + ("gear".equals(key) ? " / " + gearLabel(value) : ""));
        } else if (code == 6) {
          increment("readyCount");
          event("service-ready", "就绪通知，不等于有效挡位");
        } else if (code == 4) {
          increment("uniqueCodeReadyCount");
          event("unique-code-ready", "标识初始化通知，不读取车辆标识");
        } else {
          increment("otherCallbackCount");
          event("other-callback", "code=" + code + " type=" + key + " value=" + value + " extra=" + extra);
        }
      } finally {
        queuedCallbacks.decrementAndGet();
      }
    });
  }

  public boolean active() {
    return started && !closed;
  }

  public void stop(String reason, String detail) {
    if (closed) return;
    closed = true;
    main.removeCallbacks(timeout);
    put("active", false);
    put("connected", false);
    put("registered", false);
    put("phase", reason);
    put("stopDetail", detail);
    put("finishedAt", System.currentTimeMillis());
    put("cleanupPending", remote != null);
    if (bound) {
      bound = false;
      try {
        context.unbindService(connection);
      } catch (Exception error) {
        put("unbindError", describe(error));
      }
    }
    event("stop", detail);
    IBinder binder = remote;
    worker.execute(() -> {
      if (binder != null) cleanup(binder);
      else release();
    });
  }

  private void cleanup(IBinder binder) {
    if (!cleanupStarted.compareAndSet(false, true)) return;
    try {
      Integer result = registerAttempted ? listenerTransaction(binder, 9) : null;
      main.post(() -> {
        if (result != null) put("unregisterResult", result);
        put("cleanupPending", false);
        event("cleanup", result == null ? "未尝试注册，无需注销" : "注销result=" + result);
      });
    } catch (Exception | LinkageError error) {
      main.post(() -> {
        put("cleanupPending", false);
        put("unregisterError", describe(error));
        event("cleanup-error", describe(error));
      });
    } finally {
      release();
    }
  }

  private void release() {
    if (leased) {
      leased = false;
      remoteBusy.set(false);
    }
  }

  private void arm(long duration) {
    main.removeCallbacks(timeout);
    long remaining = SESSION_TIMEOUT - (android.os.SystemClock.elapsedRealtime() - startedElapsed);
    main.postDelayed(timeout, Math.max(1, Math.min(duration, remaining)));
  }

  private void increment(String key) {
    put(key, report.optLong(key) + 1);
  }

  private void put(String key, Object value) {
    try {
      report.put(key, value);
    } catch (Exception ignored) {
    }
  }

  private void event(String kind, String detail) {
    try {
      if (detail.length() > 800) detail = detail.substring(0, 800);
      if (events.length() >= 120) {
        events.remove(0);
        increment("eventsDropped");
      }
      events.put(new JSONObject().put("at", System.currentTimeMillis()).put("kind", kind).put("detail", detail));
      put("droppedCallbacks", droppedCallbacks.get());
      diagnostics.platformProbe(report);
      diagnostics.line("讯飞只读/" + kind, detail);
    } catch (Exception ignored) {
    }
  }

  static String gearLabel(int value) {
    switch (value) {
      case 1000: return "R（客户端枚举，待实车核对）";
      case 1001: return "GEAR_NONE（不等同N挡）";
      case 1002: return "P（客户端枚举，待实车核对）";
      case 1003: return "D（客户端枚举，待实车核对）";
      default: return "错误或未知编码";
    }
  }

  private static String describe(Throwable error) {
    while (error.getCause() != null && error.getCause() != error) error = error.getCause();
    return error.getClass().getSimpleName() + ": " + error.getMessage();
  }
}
