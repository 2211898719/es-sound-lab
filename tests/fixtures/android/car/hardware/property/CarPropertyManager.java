package android.car.hardware.property;

import android.car.hardware.CarPropertyValue;
import java.util.HashMap;

public final class CarPropertyManager {
  public interface CarPropertyEventCallback {
    void onChangeEvent(CarPropertyValue<?> value);
    void onErrorEvent(int property, int area);
    default void onErrorEvent(int property, int area, int code) { onErrorEvent(property, area); }
  }
  public final HashMap<Integer, CarPropertyEventCallback> callbacks = new HashMap<>();
  public boolean denied;
  public boolean registerCallback(CarPropertyEventCallback callback, int property, float rate) {
    if (denied) throw new SecurityException("fixture: denied");
    if (rate != 0f) throw new IllegalArgumentException("not on-change");
    callbacks.put(property, callback); return true;
  }
  public <T> CarPropertyValue<Integer> getProperty(Class<T> type, int property, int area) {
    if (type != Integer.class || area != 0) throw new IllegalArgumentException("wrong read signature");
    return new CarPropertyValue<>(property, area, 0, 1000000L, 4);
  }
  public void unregisterCallback(CarPropertyEventCallback callback, int property) {
    if (!callback.equals(callbacks.get(property))) throw new IllegalArgumentException("different callback");
    callbacks.remove(property);
  }
}
