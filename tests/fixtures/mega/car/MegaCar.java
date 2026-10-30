package mega.car;

import android.content.Context;
import android.os.Handler;

/** Test-only SDK lifecycle. Application DEX does not contain this class. */
public final class MegaCar {
  public interface CarServiceLifecycleListener {
    void onLifecycleChanged(MegaCar car, boolean ready);
  }

  public static Object nextManager;
  public static MegaCar latest;
  public Object manager;
  private CarServiceLifecycleListener listener;
  public boolean connected = true;

  public static MegaCar createCar(
      Context context, Handler handler, long timeout, CarServiceLifecycleListener listener) {
    if (nextManager == null) throw new IllegalStateException("test SDK inactive");
    latest = new MegaCar();
    latest.manager = nextManager;
    latest.listener = listener;
    listener.onLifecycleChanged(latest, true); // Deliberately synchronous, before assignment.
    return latest;
  }

  public boolean isConnected() {
    return connected;
  }

  public Object getCarManager(String name) {
    return manager;
  }

  public void disconnect() {
    connected = false;
  }

  public void lifecycle(boolean ready) {
    connected = ready;
    listener.onLifecycleChanged(this, ready);
  }
}
