package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mega.car.MegaCar;
import mega.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class VehicleAccessTest {
  public static final class Value {
    final int id, area, status;
    final Object raw;
    final long stamp;

    Value(int i, int a, int s, Object r, long t) {
      id = i;
      area = a;
      status = s;
      raw = r;
      stamp = t;
    }

    public int getPropertyId() {
      return id;
    }

    public int getAreaId() {
      return area;
    }

    public int getStatus() {
      return status;
    }

    public Object getValue() {
      return raw;
    }

    public long getTimestamp() {
      return stamp;
    }
  }

  public static final class ConfigValue {
    final int id;
    final int[] areas;

    ConfigValue(int i, int... a) {
      id = i;
      areas = a;
    }

    public int getPropertyId() {
      return id;
    }

    public int[] getAreaIds() {
      return areas;
    }

    public Class<?> getPropertyType() {
      return Integer.class;
    }
  }

  public static final class Manager {
    final Map<Integer, CarPropertyEventCallback> callbacks = new HashMap<>();
    final List<ConfigValue> configs = new ArrayList<>();
    final Set<Integer> denied = new HashSet<>(), readDenied = new HashSet<>();
    final Map<Integer, Object> values = new HashMap<>();
    final Map<Integer, Integer> reads = new HashMap<>();
    boolean denyList;
    int unregisters;

    public List<ConfigValue> getPropertyList() {
      if (denyList) throw new SecurityException("list denied");
      return configs;
    }

    public void registerCallback(CarPropertyEventCallback cb, SparseArray<Float> rates) {
      assertEquals("per-domain isolation", 1, rates.size());
      assertFalse("avoid vendor async sticky exceptions", cb.isSticky());
      int id = rates.keyAt(0);
      callbacks.put(id, cb); // Real SDK mutates local state before the remote call.
      if (denied.contains(id)) throw new SecurityException("denied " + id);
    }

    public Value getProperty(Class<?> type, int id, int area) {
      reads.put(id, reads.getOrDefault(id, 0) + 1);
      if (readDenied.contains(id)) throw new SecurityException("read denied");
      Object raw = values.get(id);
      if (raw == null) {
        if (id == 67108875) raw = Float.valueOf(0);
        else raw = Integer.valueOf(1);
      }
      return new Value(id, area, 0, raw, SystemClock.uptimeMillis());
    }

    public void unregisterCallback(CarPropertyEventCallback cb, Set<Integer> ids) {
      unregisters++;
      for (int id : ids) callbacks.remove(id, cb);
    }

    void emit(int id, int area, int status, Object value, long timestamp) {
      callbacks.get(id).onChangeEvent(new Value(id, area, status, value, timestamp));
    }
  }

  private VehicleAccess access;
  private Manager manager;
  private Diagnostics diagnostics;
  private Store store;
  private final List<Protocol.Sample> samples = new ArrayList<>();
  private final List<String> order = new ArrayList<>();

  @Before
  public void prepare() {
    store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    diagnostics = new Diagnostics();
    manager = new Manager();
    MegaCar.nextManager = manager;
    access =
        new VehicleAccess(
            RuntimeEnvironment.getApplication(),
            new Handler(Looper.getMainLooper()),
            diagnostics,
            store,
            new VehicleAccess.Listener() {
              public void sample(Protocol.Sample sample) {
                samples.add(sample);
                order.add("sample");
              }

              public void reset(String source) {
                order.add("reset:" + source);
              }
            });
  }

  private void start() {
    access.start(false);
    shadowOf(Looper.getMainLooper()).idle();
  }

  @After
  public void finish() {
    access.stop();
    MegaCar.nextManager = null;
    MegaCar.latest = null;
  }

  @Test
  public void missingAndPartialCatalogStillRegistersAllNineEssentialProperties() {
    manager.configs.add(new ConfigValue(100663340, 16));
    start();
    assertEquals(9, manager.callbacks.size());
    assertTrue(manager.callbacks.containsKey(Protocol.CENTRAL_LOCK));
    assertTrue(manager.callbacks.containsKey(Protocol.AUTO_HOLD));
    assertEquals("reset:Mega", order.get(0));
    assertEquals(9, samples.size());
    assertEquals("0x10", samples.get(0).area);
    assertTrue(diagnostics.nativeManagerStatus.contains("Manager"));
    assertTrue(diagnostics.nativeSdkStatus.contains("共享"));
  }

  @Test
  public void deniedDomainAndCatalogDoNotPreventOtherSubscriptionsOrReads() {
    manager.denyList = true;
    manager.denied.add(Protocol.CENTRAL_LOCK);
    manager.readDenied.add(Protocol.CENTRAL_LOCK);
    start();
    assertEquals(8, manager.callbacks.size());
    Protocol.Sample lock =
        samples.stream().filter(s -> s.propertyId == Protocol.CENTRAL_LOCK).findFirst().get();
    assertNull(lock.value);
    assertEquals("lock", lock.key);
    int beforeGear = manager.reads.get(67108874);
    int beforeLock = manager.reads.get(Protocol.CENTRAL_LOCK);
    shadowOf(Looper.getMainLooper()).idleFor(3100, TimeUnit.MILLISECONDS);
    assertEquals(beforeGear, (int) manager.reads.get(67108874));
    assertTrue(manager.reads.get(Protocol.CENTRAL_LOCK) > beforeLock);
    assertTrue(diagnostics.nativeSummary().contains("SecurityException"));
  }

  @Test
  public void callbacksDecodeLockHoldAndRejectOlderSnapshotsAndWrongAreas() {
    start();
    long t = SystemClock.uptimeMillis() + 10;
    manager.emit(67108874, 0, 0, 2, t);
    manager.emit(67108874, 0, 0, 4, t + 1);
    manager.emit(67108874, 0, 0, 2, t); // Late R cannot resurrect.
    manager.emit(Protocol.CENTRAL_LOCK, 0, 0, 0, t);
    manager.emit(Protocol.AUTO_HOLD, 0, 0, 2, t);
    int count = samples.size();
    manager.emit(Protocol.AUTO_HOLD, 1, 0, 1, t);
    assertEquals(count, samples.size());
    List<Protocol.Sample> gears = new ArrayList<>();
    for (Protocol.Sample s : samples) if (s.key.equals("gear")) gears.add(s);
    assertEquals(3, gears.size());
    assertEquals("2", gears.get(1).value);
    assertEquals("4", gears.get(2).value);
    assertEquals("lock", samples.get(count - 2).key);
    assertEquals("autohold", samples.get(count - 1).key);
    assertEquals("2", samples.get(count - 1).value);
  }

  @Test
  public void disconnectInvalidatesOldCallbacksAndRecoveryResetsBeforeSamples() {
    start();
    CarPropertyEventCallback old = manager.callbacks.get(Protocol.AUTO_HOLD);
    MegaCar.latest.lifecycle(false);
    shadowOf(Looper.getMainLooper()).idle();
    int count = samples.size();
    old.onChangeEvent(new Value(Protocol.AUTO_HOLD, 0, 0, 2, 999));
    assertEquals(count, samples.size());
    order.clear();
    MegaCar.latest.lifecycle(true);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals("reset:Mega", order.get(0));
    assertEquals(count + 9, samples.size());
    old.onChangeEvent(new Value(Protocol.AUTO_HOLD, 0, 0, 2, 1000));
    assertEquals(count + 9, samples.size());
    access.stop();
    old.onChangeEvent(new Value(67108874, 0, 0, 2, 1001));
    assertEquals(count + 9, samples.size());
    assertEquals(2, manager.unregisters);
  }

  @Test
  public void errorCallbackInvalidatesStateAndRecoveryAndDiagnosticExportAreExplicit()
      throws Exception {
    start();
    manager.callbacks.get(Protocol.AUTO_HOLD).onErrorEvent(Protocol.AUTO_HOLD, 0);
    Protocol.Sample error = samples.get(samples.size() - 1);
    assertEquals("autohold", error.key);
    assertEquals(1, error.status);
    assertNull(error.value);
    manager.emit(Protocol.AUTO_HOLD, 0, 0, 2, SystemClock.uptimeMillis() + 1);
    org.json.JSONObject report = new org.json.JSONObject(diagnostics.export(false, store));
    assertEquals("Mega native property subscription", report.getString("eventBackend"));
    assertEquals("es-observed", report.getString("megaGearEncoding"));
    assertTrue(report.getJSONArray("nativeSubscriptions").toString().contains("callbackCount"));
  }

  @Test
  public void discoveryAndRuleExpansionPreserveBaselineAndExistingCallbackIdentity() {
    start();
    CarPropertyEventCallback original = manager.callbacks.get(67108874);
    manager.configs.add(new ConfigValue(123456, 5));
    order.clear();
    access.discovery(true);
    assertTrue(manager.callbacks.containsKey(123456));
    assertSame(original, manager.callbacks.get(67108874));
    assertFalse(order.contains("reset:Mega"));
    access.discovery(false);
    assertSame(original, manager.callbacks.get(67108874));
    assertFalse(manager.callbacks.containsKey(123456));
    assertFalse(order.contains("reset:Mega"));
  }

  @Test
  public void retryAfterPermissionRejectionReachesServiceAndKeepsOtherDomains() {
    manager.denied.add(Protocol.AUTO_HOLD);
    start();
    CarPropertyEventCallback gear = manager.callbacks.get(67108874);
    assertFalse(manager.callbacks.containsKey(Protocol.AUTO_HOLD));
    manager.denied.clear();
    order.clear();
    access.discovery(false);
    assertTrue(manager.callbacks.containsKey(Protocol.AUTO_HOLD));
    assertSame(gear, manager.callbacks.get(67108874));
    assertFalse(order.contains("reset:Mega"));
  }

  @Test
  public void sdkGearSelectionAndUptimeSpeedDoNotUseNanosecondAgeMath() {
    store.prefs.edit().putBoolean("mega.gear.sdkEnums", true).commit();
    start();
    long t = SystemClock.uptimeMillis();
    manager.emit(67108874, 0, 0, 1, t);
    assertEquals("2", samples.get(samples.size() - 1).value);
    manager.emit(67108875, 0, 0, 20f, t);
    assertEquals("20.0", samples.get(samples.size() - 1).value);
    manager.emit(67108875, 0, 0, 20f, Long.MAX_VALUE);
    assertNull(samples.get(samples.size() - 1).value);
  }
}
