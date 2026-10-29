package cn.es.soundlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.SparseArray;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only adapters for the ES routes present in the source APK. No control calls. */
public final class VehicleAccess {
  public interface Listener {
    void sample(Protocol.Sample sample);

    void reset(String source);
  }

  private final Context context;
  private final Handler io;
  private final Diagnostics diag;
  private final Listener listener;
  private final Store store;
  private boolean closed, discovery, bound;
  private long retryAt;
  private IBinder remote;
  private Reader primary, supplement;
  private static final String DESAY = "com.desaysv.ivi.vds.carinfo",
      BUS = "com.desaysv.ivi.vdb.IVDBus",
      NOTIFY = "com.desaysv.ivi.vdb.IVDBusNotify";
  private static final int MODULE = 327684;
  private static final int[] COMMANDS = {15, 16, 17, 18, 26};

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

  private boolean service(String pkg, String cls) {
    try {
      ServiceInfo s = context.getPackageManager().getServiceInfo(new ComponentName(pkg, cls), 0);
      return s.enabled && s.exported && s.applicationInfo.enabled;
    } catch (Exception e) {
      return false;
    }
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (closed) return;
          try {
            if (service(DESAY, DESAY + ".service.CarInfoService")) {
              if (!bound && System.currentTimeMillis() >= retryAt) bindDesay();
              if (remote != null) readDesay();
              if (remote == null && primary == null && System.currentTimeMillis() >= retryAt)
                startReaders();
            } else if (primary == null) startReaders();
            if (primary != null) primary.tick();
            if (supplement != null) supplement.tick();
          } catch (Exception | LinkageError e) {
            diag.line("车辆接口", cause(e));
          }
          io.postDelayed(this, 3000);
        }
      };

  private void startReaders() {
    boolean mega = service("com.mega.car", "com.mega.car.MegaCarService");
    primary = new Reader(mega, false);
    if (mega && service("com.android.car", "com.android.car.CarService"))
      supplement = new Reader(false, true);
  }

  private void bindDesay() {
    retryAt = System.currentTimeMillis() + 15000;
    try {
      bound =
          context.bindService(
              new Intent("action.desaysv.ivi.vds.carinfo.SERVICE")
                  .setComponent(new ComponentName(DESAY, DESAY + ".service.CarInfoService")),
              connection,
              Context.BIND_AUTO_CREATE);
      diag.nativeStatus = bound ? "德赛车辆服务连接中" : "德赛车辆服务不可绑定";
      if (!bound) startReaders();
    } catch (Exception e) {
      diag.nativeStatus = "德赛接口访问受限";
      diag.line("车辆接口", cause(e));
      startReaders();
    }
  }

  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
          io.post(
              () -> {
                if (closed) return;
                remote = b;
                listener.reset("Desay");
                diag.nativeStatus = "德赛 CarInfo 已连接";
                try {
                  Parcel q = Parcel.obtain(), r = Parcel.obtain();
                  try {
                    q.writeInterfaceToken(BUS);
                    Bundle data = new Bundle();
                    data.putIntArray("CMD_ID_ARRAY", COMMANDS);
                    write(q, data);
                    q.writeStrongBinder(callback);
                    if (!b.transact(4, q, r, 0))
                      throw new RemoteException("subscription unavailable");
                    r.readException();
                  } finally {
                    q.recycle();
                    r.recycle();
                  }
                } catch (Exception e) {
                  diag.line("德赛订阅", cause(e));
                }
                readDesay();
              });
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
          io.post(
              () -> {
                remote = null;
                listener.reset("Desay");
                diag.nativeStatus = "德赛接口断开，等待重连";
              });
        }
      };

  private static void write(Parcel p, Bundle b) {
    p.writeInt(1);
    p.writeInt(MODULE);
    p.writeBundle(b);
    p.writeInt(0);
    p.writeLong(0);
  }

  private final Binder callback =
      new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
          if (code == IBinder.INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(NOTIFY);
            return true;
          }
          if (code != 1) return super.onTransact(code, data, reply, flags);
          data.enforceInterface(NOTIFY);
          if (data.readInt() != 0) {
            final int module = data.readInt();
            final Bundle b = data.readBundle(getClass().getClassLoader());
            data.readInt();
            data.readLong();
            io.post(() -> acceptDesay(module, b));
          }
          return true;
        }
      };

  private void readDesay() {
    IBinder b = remote;
    if (b == null) return;
    try {
      for (int cmd : COMMANDS) {
        Parcel q = Parcel.obtain(), r = Parcel.obtain();
        try {
          q.writeInterfaceToken(BUS);
          Bundle d = new Bundle();
          d.putInt("CMD_ID", cmd);
          write(q, d);
          if (!b.transact(1, q, r, 0)) continue;
          r.readException();
          if (r.readInt() != 0) {
            int module = r.readInt();
            Bundle data = r.readBundle(getClass().getClassLoader());
            r.readInt();
            r.readLong();
            if (data != null) {
              data.putInt("CMD_ID", cmd);
              acceptDesay(module, data);
            }
          }
        } finally {
          q.recycle();
          r.recycle();
        }
      }
    } catch (Exception e) {
      diag.line("德赛读取", cause(e));
      remote = null;
      listener.reset("Desay");
      if (bound) {
        try {
          context.unbindService(connection);
        } catch (Exception ignored) {
        }
        bound = false;
      }
      retryAt = System.currentTimeMillis() + 10000;
    }
  }

  private void acceptDesay(int module, Bundle b) {
    if (closed || module != MODULE || b == null) return;
    int cmd = b.getInt("CMD_ID", -1);
    for (int i = 0; i < COMMANDS.length; i++)
      if (cmd == COMMANDS[i]) {
        int[] raw = b.getIntArray("VALUE");
        boolean valid = !b.getBoolean("IS_NULL", false) && raw != null && raw.length > 0;
        String value = valid ? String.valueOf(raw[0]) : null,
            key = i < 4 ? "door." + Protocol.DOORS[i] : "gear";
        if (i < 4 && valid && (raw[0] < 0 || raw[0] > 1)) value = null;
        if (i == 4 && valid) {
          int n = raw[0];
          value = n >= 6 && n <= 12 ? "4" : n >= 1 && n <= 5 ? String.valueOf(n) : null;
        }
        listener.sample(
            new Protocol.Sample(
                "desay/CMD_" + cmd,
                cmd,
                "0x0",
                valid ? 0 : 1,
                valid ? String.valueOf(raw[0]) : "null",
                System.currentTimeMillis(),
                "Desay",
                key,
                value));
      }
  }

  public void stop() {
    closed = true;
    io.removeCallbacks(tick);
    if (bound) {
      try {
        context.unbindService(connection);
      } catch (Exception ignored) {
      }
      bound = false;
    }
    if (primary != null) primary.close();
    if (supplement != null) supplement.close();
    remote = null;
  }

  private final class Reader {
    final boolean mega, gearOnly;
    final String name, root;
    ClassLoader loader;
    Object car, manager, propertyCallback, lifecycle;
    final List<Property> properties = new ArrayList<>();
    final Set<Integer> ids = new HashSet<>();
    long nextConnect;

    Reader(boolean m, boolean g) {
      mega = m;
      gearOnly = g;
      name = m ? "Mega" : "Android";
      root = m ? "mega.car" : "android.car";
    }

    void tick() {
      if (closed) return;
      if (car == null && System.currentTimeMillis() >= nextConnect) connect();
      if (car != null && manager == null && System.currentTimeMillis() >= nextConnect) {
        nextConnect = System.currentTimeMillis() + 30000;
        try {
          if (Boolean.TRUE.equals(call(car, "isConnected"))) setup();
        } catch (Exception ignored) {
        }
      }
      if (manager != null) {
        for (Property p : new ArrayList<>(properties))
          try {
            Object value =
                manager
                    .getClass()
                    .getMethod("getProperty", Class.class, int.class, int.class)
                    .invoke(manager, p.type, p.id, p.area);
            accept(value);
            p.failures = 0;
            p.failed = false;
          } catch (Exception e) {
            if (!p.failed) {
              p.failed = true;
              diag.line(name + "读取", "0x" + Integer.toHexString(p.id) + " / " + cause(e));
            }
            if (++p.failures >= 3)
              listener.sample(
                  Protocol.property(mega, p.id, p.area, 1, null, System.currentTimeMillis()));
          }
      }
    }

    void connect() {
      nextConnect = System.currentTimeMillis() + 30000;
      try {
        loader = loadSdk();
        Class<?> cls = Class.forName(root + (mega ? ".MegaCar" : ".Car"), true, loader);
        try {
          Class<?> life =
              Class.forName(cls.getName() + "$CarServiceLifecycleListener", false, loader);
          lifecycle =
              Proxy.newProxyInstance(
                  loader,
                  new Class[] {life},
                  (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class)
                      return proxyValue(proxy, method, args);
                    if (method.getName().equals("onLifecycleChanged")
                        && args != null
                        && args.length >= 2) {
                      boolean ready = Boolean.TRUE.equals(args[1]);
                      io.post(
                          () -> {
                            if (closed) return;
                            if (ready) setup();
                            else {
                              unregister();
                              manager = null;
                              listener.reset(name);
                              diag.nativeStatus = name + " 服务已断开";
                            }
                          });
                    }
                    return null;
                  });
          car =
              cls.getMethod("createCar", Context.class, Handler.class, long.class, life)
                  .invoke(null, context, io, 0L, lifecycle);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
          if (mega) throw e;
          ServiceConnection c =
              new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) {
                  io.post(() -> setup());
                }

                public void onServiceDisconnected(ComponentName n) {
                  io.post(
                      () -> {
                        manager = null;
                        listener.reset(name);
                      });
                }
              };
          car =
              cls.getMethod("createCar", Context.class, ServiceConnection.class, Handler.class)
                  .invoke(null, context, c, io);
          call(car, "connect");
        }
        if (car == null) throw new IllegalStateException("createCar returned null");
        diag.nativeStatus = name + " 车辆服务连接中";
        try {
          if (Boolean.TRUE.equals(call(car, "isConnected"))) setup();
        } catch (Exception ignored) {
        }
      } catch (Exception | LinkageError e) {
        diag.nativeStatus = name + " 接口暂不可用 · 可使用日志检测";
        diag.line(name + "连接", cause(e));
        car = null;
      }
    }

    ClassLoader loadSdk() throws Exception {
      ClassLoader base = context.getClassLoader();
      String type = root + (mega ? ".MegaCar" : ".Car");
      try {
        Class.forName(type, false, base);
        return base;
      } catch (ClassNotFoundException ignored) {
      }
      if (mega) {
        String path = context.getPackageManager().getApplicationInfo("com.mega.car", 0).sourceDir;
        ClassLoader l = new PathClassLoader(path, base);
        Class.forName(type, false, l);
        return l;
      }
      for (String path :
          new String[] {
            "/system/framework/android.car.jar",
            "/system_ext/framework/android.car.jar",
            "/system/priv-app/CarService/CarService.apk"
          })
        if (new File(path).isFile()) {
          ClassLoader l = new PathClassLoader(path, base);
          try {
            Class.forName(type, false, l);
            return l;
          } catch (ClassNotFoundException ignored) {
          }
        }
      throw new ClassNotFoundException(type);
    }

    void setup() {
      if (closed || car == null || manager != null) return;
      try {
        manager = car.getClass().getMethod("getCarManager", String.class).invoke(car, "property");
        if (manager == null) throw new IllegalStateException("property manager unavailable");
        properties.clear();
        ids.clear();
        Object list = call(manager, "getPropertyList");
        if (list instanceof Iterable)
          for (Object config : (Iterable<?>) list) {
            int id = ((Number) call(config, "getPropertyId")).intValue();
            int[] areas = (int[]) call(config, "getAreaIds");
            Class<?> type = (Class<?>) call(config, "getPropertyType");
            diag.line(
                name + "属性",
                "0x"
                    + Integer.toHexString(id)
                    + " · areas="
                    + Arrays.toString(areas)
                    + " · "
                    + type.getSimpleName());
            if (wanted(id)) {
              if (areas.length == 0) areas = new int[] {0};
              for (int area : areas) properties.add(new Property(id, area, type));
              ids.add(id);
            }
          }
        if (properties.isEmpty()) fallback();
        subscribe();
        diag.nativeStatus = name + " 已连接 · " + properties.size() + " 个可读属性区域";
        listener.reset(name);
      } catch (Exception | LinkageError e) {
        diag.nativeStatus = name + " 属性权限或接口不可用";
        diag.line(name + "属性", cause(e));
        manager = null;
      }
    }

    boolean wanted(int id) {
      if (gearOnly) return id == Protocol.GEAR_SELECTION || id == Protocol.CURRENT_GEAR;
      if (discovery && ids.size() < 80) return true;
      if (mega) {
        if (id == 100663305 || id == 67108874 || id == 67108875 || id == 100663345) return true;
        for (long n : Protocol.DOOR_IDS) if (id == n) return true;
      } else if (id == Protocol.DOOR_POS
          || id == Protocol.GEAR_SELECTION
          || id == Protocol.CURRENT_GEAR
          || id == Protocol.SPEED) return true;
      org.json.JSONArray rules = store.rules();
      for (int i = 0; i < rules.length(); i++)
        if (rules.optJSONObject(i).optLong("propertyId") == id) return true;
      return false;
    }

    void fallback() {
      if (mega) {
        for (int i = 0; i < 4; i++) {
          properties.add(new Property((int) Protocol.DOOR_IDS[i], 0, Integer.class));
          ids.add((int) Protocol.DOOR_IDS[i]);
        }
        properties.add(new Property(67108874, 0, Integer.class));
        ids.add(67108874);
      } else {
        if (!gearOnly)
          for (int a : new int[] {1, 4, 16, 64})
            properties.add(new Property(Protocol.DOOR_POS, a, Integer.class));
        properties.add(new Property(Protocol.GEAR_SELECTION, 0, Integer.class));
        ids.add(Protocol.GEAR_SELECTION);
        if (!gearOnly) ids.add(Protocol.DOOR_POS);
      }
    }

    void subscribe() {
      try {
        Class<?> cb =
            Class.forName(
                root + ".hardware.property.CarPropertyManager$CarPropertyEventCallback",
                false,
                loader);
        propertyCallback =
            Proxy.newProxyInstance(
                loader,
                new Class[] {cb},
                (proxy, method, args) -> {
                  if (method.getDeclaringClass() == Object.class)
                    return proxyValue(proxy, method, args);
                  if (method.getName().equals("isSticky")) return true;
                  if (method.getName().equals("onChangeEvent") && args != null && args.length > 0) {
                    Object value = args[0];
                    io.post(
                        () -> {
                          if (!closed) accept(value);
                        });
                  }
                  if (method.getName().equals("onErrorEvent"))
                    diag.line(name + "回调", "属性读取出错 " + Arrays.toString(args));
                  return null;
                });
        if (mega) {
          try {
            SparseArray<Float> rates = new SparseArray<>();
            for (int id : ids) rates.put(id, 0f);
            manager
                .getClass()
                .getMethod("registerCallback", cb, SparseArray.class)
                .invoke(manager, propertyCallback, rates);
            return;
          } catch (NoSuchMethodException ignored) {
          }
        }
        Method register =
            manager.getClass().getMethod("registerCallback", cb, int.class, float.class);
        for (int id : ids)
          try {
            register.invoke(manager, propertyCallback, id, 0f);
          } catch (Exception e) {
            diag.line(name + "订阅", "0x" + Integer.toHexString(id) + " / " + cause(e));
          }
      } catch (Exception | LinkageError e) {
        diag.line(name + "订阅", "改用定时读取 · " + cause(e));
      }
    }

    void accept(Object value) {
      if (value == null || closed) return;
      try {
        int id = ((Number) call(value, "getPropertyId")).intValue(),
            area = ((Number) call(value, "getAreaId")).intValue(),
            status = ((Number) call(value, "getStatus")).intValue();
        Object raw = call(value, "getValue");
        if (id == Protocol.SPEED || id == 67108875)
          try {
            long stamp = ((Number) call(value, "getTimestamp")).longValue();
            if (stamp > 0 && android.os.SystemClock.elapsedRealtimeNanos() - stamp > 5000000000L)
              status = 1;
          } catch (Exception ignored) {
          }
        listener.sample(Protocol.property(mega, id, area, status, raw, System.currentTimeMillis()));
      } catch (Exception e) {
        diag.line(name + "解码", cause(e));
      }
    }

    void unregister() {
      if (manager == null || propertyCallback == null) return;
      try {
        Class<?> cb = propertyCallback.getClass().getInterfaces()[0];
        try {
          manager.getClass().getMethod("unregisterCallback", cb).invoke(manager, propertyCallback);
        } catch (NoSuchMethodException e) {
          manager
              .getClass()
              .getMethod("unregisterCallback", cb, Set.class)
              .invoke(manager, propertyCallback, ids);
        }
      } catch (Exception ignored) {
      }
      propertyCallback = null;
    }

    void close() {
      unregister();
      if (car != null)
        try {
          call(car, "disconnect");
        } catch (Exception ignored) {
        }
      car = null;
      manager = null;
    }
  }

  private static final class Property {
    final int id, area;
    final Class<?> type;
    boolean failed;
    int failures;

    Property(int i, int a, Class<?> t) {
      id = i;
      area = a;
      type = t;
    }
  }

  private static Object call(Object o, String method) throws Exception {
    return o.getClass().getMethod(method).invoke(o);
  }

  private static Object proxyValue(Object proxy, Method m, Object[] args) {
    switch (m.getName()) {
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
