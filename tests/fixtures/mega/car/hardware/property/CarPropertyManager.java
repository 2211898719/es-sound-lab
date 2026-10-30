package mega.car.hardware.property;

/** Test-only shape of the inspected vendor callback; never included in the application. */
public final class CarPropertyManager {
  public interface CarPropertyEventCallback {
    default boolean isSticky() {
      return true;
    }

    void onChangeEvent(Object value);

    void onErrorEvent(int id, int area);
  }
}
