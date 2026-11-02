package android.car;

import android.content.Context;
import android.os.Handler;
import android.car.hardware.property.CarPropertyManager;

public final class Car {
  public interface CarServiceLifecycleListener { void onLifecycleChanged(Car car, boolean ready); }
  public static Car last;
  public static long waitTimeout;
  public final CarPropertyManager manager = new CarPropertyManager();
  public CarServiceLifecycleListener listener;
  public boolean disconnected;
  public static Car createCar(Context context, Handler handler, long timeout, CarServiceLifecycleListener callback) {
    waitTimeout = timeout;
    last = new Car(); last.listener = callback; callback.onLifecycleChanged(last, true);
    return last;
  }
  public Object getCarManager(String name) {
    if (!"property".equals(name)) throw new IllegalArgumentException(name);
    return manager;
  }
  public void disconnect() { disconnected = true; }
}
