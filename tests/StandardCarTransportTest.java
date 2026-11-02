package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class StandardCarTransportTest {
  private StandardCarTransport transport;
  private final ArrayList<Boolean> ready = new ArrayList<>();
  private final ArrayList<StandardCarProbe.Value> values = new ArrayList<>();
  private final ArrayList<String> errors = new ArrayList<>();
  private final StandardCarProbe.Sink sink = new StandardCarProbe.Sink() {
    @Override public void ready(boolean connected) { ready.add(connected); }
    @Override public void value(StandardCarProbe.Value value) { values.add(value); }
    @Override public void error(int property, String detail) { errors.add(property + ":" + detail); }
  };
  @Before public void setup() throws Exception {
    transport = new StandardCarTransport(RuntimeEnvironment.getApplication(), new Handler(Looper.getMainLooper()));
    transport.connect(sink); transport.prepare(sink);
  }
  @Test public void usesAsyncZeroWaitAndPropertyManager() throws Exception {
    assertEquals(0, Car.waitTimeout); assertEquals(Boolean.TRUE, ready.get(0));
    assertTrue(transport.register(StandardCarProbe.SELECTION));
    StandardCarProbe.Value value = transport.read(StandardCarProbe.SELECTION);
    assertEquals(4, value.raw); assertEquals(0, value.status);
    assertEquals(StandardCarProbe.SELECTION, value.property);
    assertEquals(1000000L, value.timestamp);
  }
  @Test public void proxyReceivesValuesAndBothErrorOverloads() throws Exception {
    transport.register(StandardCarProbe.SELECTION);
    CarPropertyEventCallback callback = Car.last.manager.callbacks.get(StandardCarProbe.SELECTION);
    callback.onChangeEvent(new CarPropertyValue<>(StandardCarProbe.SELECTION, 0, 1, 123L, 2));
    callback.onErrorEvent(StandardCarProbe.SELECTION, 0);
    callback.onErrorEvent(StandardCarProbe.SELECTION, 0, 7);
    assertEquals(1, values.size()); assertEquals(1, values.get(0).status);
    assertEquals(123L, values.get(0).timestamp);
    assertTrue(errors.get(0).contains("no error code")); assertTrue(errors.get(1).contains("code=7"));
  }
  @Test public void sameCallbackIdentityIsRemovedAndCarDisconnected() throws Exception {
    transport.register(StandardCarProbe.SELECTION); transport.register(StandardCarProbe.CURRENT);
    Object callback = Car.last.manager.callbacks.get(StandardCarProbe.SELECTION);
    assertEquals(System.identityHashCode(callback), callback.hashCode());
    assertFalse(callback.equals(new Object()));
    transport.unregister(StandardCarProbe.SELECTION); transport.unregister(StandardCarProbe.CURRENT);
    transport.disconnect(); assertTrue(Car.last.disconnected); assertTrue(Car.last.manager.callbacks.isEmpty());
  }
  @Test public void invocationUnwrapsSecurityDenialAndNullCallbackIsReported() throws Exception {
    Car.last.manager.denied = true;
    try { transport.register(StandardCarProbe.SELECTION); fail("denial expected"); }
    catch (SecurityException expected) { assertEquals("fixture: denied", expected.getMessage()); }
    Car.last.manager.denied = false; transport.register(StandardCarProbe.SELECTION);
    Car.last.manager.callbacks.get(StandardCarProbe.SELECTION).onChangeEvent(null);
    assertTrue(errors.get(0).contains("null CarPropertyValue"));
    Car.last.listener.onLifecycleChanged(Car.last, false); assertEquals(Boolean.FALSE, ready.get(1));
  }
}
