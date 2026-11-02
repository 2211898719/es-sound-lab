package cn.es.soundlab;

import android.content.Context;
import android.os.Handler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class StandardCarTransport implements StandardCarProbe.Transport {
  private final Context context;
  private final Handler handler;
  private Object car, manager, callback;
  private Class<?> carClass, managerClass, callbackClass;

  StandardCarTransport(Context context, Handler handler) {
    this.context = context.getApplicationContext();
    this.handler = handler;
  }

  @Override public void connect(StandardCarProbe.Sink sink) throws Exception {
    carClass = Class.forName("android.car.Car");
    Class<?> lifecycle = Class.forName("android.car.Car$CarServiceLifecycleListener");
    Object listener = Proxy.newProxyInstance(lifecycle.getClassLoader(), new Class<?>[] {lifecycle},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
          if ("onLifecycleChanged".equals(method.getName())) sink.ready(Boolean.TRUE.equals(args[1]));
          return null;
        });
    car = invoke(carClass.getMethod("createCar", Context.class, Handler.class, long.class, lifecycle),
        null, context, handler, 0L, listener);
    if (car == null) throw new IllegalStateException("Car.createCar returned null");
  }

  @Override public void prepare(StandardCarProbe.Sink sink) throws Exception {
    manager = invoke(carClass.getMethod("getCarManager", String.class), car, "property");
    if (manager == null) throw new IllegalStateException("property manager returned null");
    managerClass = Class.forName("android.car.hardware.property.CarPropertyManager");
    callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
    callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[] {callbackClass},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
          try {
            if ("onChangeEvent".equals(method.getName())) sink.value(sample(args[0]));
            else if ("onErrorEvent".equals(method.getName()))
              sink.error((Integer) args[0], "area=" + args[1]
                  + (args.length > 2 ? " code=" + args[2] : " (no error code)"));
          } catch (Exception | LinkageError error) { sink.error(0, StandardCarProbe.describe(error)); }
          return null;
        });
  }

  @Override public boolean register(int property) throws Exception {
    return Boolean.TRUE.equals(invoke(managerClass.getMethod("registerCallback", callbackClass,
        int.class, float.class), manager, callback, property, 0f));
  }

  @Override public StandardCarProbe.Value read(int property) throws Exception {
    return sample(invoke(managerClass.getMethod("getProperty", Class.class, int.class, int.class),
        manager, Integer.class, property, 0));
  }

  @Override public void unregister(int property) throws Exception {
    invoke(managerClass.getMethod("unregisterCallback", callbackClass, int.class), manager, callback, property);
  }

  @Override public void disconnect() throws Exception {
    if (car != null) invoke(carClass.getMethod("disconnect"), car);
  }

  private static StandardCarProbe.Value sample(Object value) throws Exception {
    if (value == null) throw new IllegalStateException("null CarPropertyValue");
    Class<?> type = Class.forName("android.car.hardware.CarPropertyValue");
    return new StandardCarProbe.Value((Integer) invoke(type.getMethod("getPropertyId"), value),
        (Integer) invoke(type.getMethod("getAreaId"), value),
        (Integer) invoke(type.getMethod("getStatus"), value),
        (Long) invoke(type.getMethod("getTimestamp"), value),
        invoke(type.getMethod("getValue"), value));
  }

  private static Object invoke(Method method, Object target, Object... args) throws Exception {
    try { return method.invoke(target, args); }
    catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw error;
    }
  }

  private static Object objectMethod(Object proxy, Method method, Object[] args) {
    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
    if ("equals".equals(method.getName())) return proxy == args[0];
    return "ES-StandardCarReadOnlyCallback";
  }
}
