package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccVehicleStateTest {
  static byte[] body(int... pairs) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int index = 0; index < pairs.length; index += 2) {
      out.write(pairs[index] << 3);
      long value = pairs[index + 1];
      while ((value & ~127L) != 0) { out.write((int) value & 127 | 128); value >>>= 7; }
      out.write((int) value);
    }
    return out.toByteArray();
  }

  static IccVehicleProbe.Reply reply(int... pairs) { return new IccVehicleProbe.Reply(200, body(pairs)); }

  private Map<String, Protocol.Sample> states(int... pairs) throws Exception {
    Map<String, Protocol.Sample> result = new LinkedHashMap<>();
    for (Protocol.Sample sample : IccVehicleWire.decode(body(pairs)).stateSamples(123)) result.put(sample.key, sample);
    return result;
  }

  @Before public void setup() {
    new Store(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit();
  }

  @Test public void sixIndependentFieldsPreserveRawAndUseOnlyTheIccIdentity() throws Exception {
    Map<String, Protocol.Sample> result = states(9, 3, 2, 88, 6, 2, 7, 3, 8, 4, 13, 1);
    assertEquals(6, result.size());
    assertEquals("4", result.get("gear").value); assertEquals("3", result.get("gear").raw);
    assertEquals("engaged", result.get("handbrake").value); assertEquals("on", result.get("power").value);
    assertEquals("charging", result.get("charge").value); assertEquals("unlocked", result.get("lock").value);
    assertEquals("88", result.get("speed").value); assertEquals("88", result.get("speed").raw);
    for (Protocol.Sample sample : result.values()) {
      assertTrue(IccVehicleWire.state(sample)); assertEquals(123, sample.time);
      assertEquals("ICC", sample.source); assertEquals("0x0", sample.area);
    }
    assertFalse(result.containsKey("autohold")); assertFalse(result.containsKey("door.front_left"));
  }

  @Test public void missingFieldsNeverBecomeZeroReleasedOffOrUnlocked() throws Exception {
    Map<String, Protocol.Sample> result = states(9, 1);
    assertEquals("0", result.get("speed").value);
    assertEquals("MISSING", result.get("speed").raw);
    for (Protocol.Sample sample : result.values()) if (!sample.key.equals("gear") && !sample.key.equals("speed")) {
      assertNull(sample.value); assertEquals("MISSING", sample.raw);
      assertEquals("字段缺失", IccVehicleWire.stateLabel(sample));
    }
  }

  @Test public void unknownAndOutOfRangeEnumsNeverTurnIntoKnownStates() throws Exception {
    for (int value : new int[] {0, 5, 255, -1}) {
      Map<String, Protocol.Sample> result = states(6, value, 7, value, 8, value, 13, value);
      for (String key : new String[] {"handbrake", "power", "charge", "lock"}) {
        assertNull(result.get(key).value); assertEquals("未知(" + value + ")", IccVehicleWire.stateLabel(result.get(key)));
      }
    }
  }

  @Test public void handbrakeHasItsOwnStateAndCanNeverGenerateAutoHoldEvents() throws Exception {
    Protocol.Sample released = states(6, 1).get("handbrake"), engaged = states(6, 2).get("handbrake");
    assertEquals("已释放", IccVehicleWire.stateLabel(released)); assertEquals("已拉起", IccVehicleWire.stateLabel(engaged));
    assertNull(Protocol.event(released.key, released.value)); assertNull(Protocol.event(engaged.key, engaged.value));
  }

  @Test public void powerEnumsAreDisplayOnlyNotIgnitionOrReadinessEvents() throws Exception {
    String[] expected = {"OFF", "ACC", "ON", "START"};
    for (int raw = 1; raw <= 4; raw++) {
      Protocol.Sample sample = states(7, raw).get("power");
      assertEquals(expected[raw - 1], IccVehicleWire.stateLabel(sample));
      assertNull(Protocol.event(sample.key, sample.value));
    }
  }

  @Test public void confirmedSpeedUsesRawKmhAndOnlySuccessfulKnownGearOmissionBecomesZero() throws Exception {
    Protocol.Sample missing = states(9, 1).get("speed"), zero = states(2, 0).get("speed"), raw = states(2, 123).get("speed");
    assertEquals("0 km/h", IccVehicleWire.stateLabel(missing));
    assertEquals("0 km/h", IccVehicleWire.stateLabel(zero)); assertEquals("123 km/h", IccVehicleWire.stateLabel(raw));
    assertEquals("0", zero.value); assertEquals("123", raw.value);
    assertNull(states(7, 1).get("speed").value);
    assertNull(states(9, 0).get("speed").value);
    assertEquals("无效原始值", IccVehicleWire.stateLabel(states(2, -1).get("speed")));
  }

  @Test public void chargeTransitionsIgnoreFastSlowAndOffIdleChanges() throws Exception {
    TransitionGate gate = new TransitionGate();
    int changes = 0;
    String[] labels = {"未充电", "充电待机", "慢充中", "快充中", "充电待机", "未充电"};
    int[] values = {1, 2, 3, 4, 2, 1};
    for (int index = 0; index < values.length; index++) {
      Protocol.Sample sample = states(8, values[index]).get("charge");
      assertEquals(labels[index], IccVehicleWire.stateLabel(sample));
      if (gate.accept(sample.key, sample.value, sample.identity()) == TransitionGate.Change.CHANGED) {
        changes++; assertEquals(changes == 1 ? "charge.start" : "charge.stop", Protocol.event(sample.key, sample.value));
      }
    }
    assertEquals(2, changes); assertNull(Protocol.event("charge", null)); assertNull(Protocol.event("charge", "unknown"));
  }

  @Test public void logLockCalibrationDoesNotConfirmIccAndCannotReverseItsProtocolValues() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.calibrateLock("1", "2"); store.prefs.edit().putString("log.backend", "icc").commit();
    assertTrue(store.lockCalibrated()); assertTrue(store.lockTriggerCalibrated());
    assertEquals("locked", store.lockState("1"));
    assertEquals("unlocked", states(13, 1).get("lock").value);
    assertEquals("locked", states(13, 2).get("lock").value);
    assertTrue(store.mapped("lock.lock")); assertFalse(store.mapped("charge.start"));
    assertTrue(store.lockReadiness().contains("原厂车外"));
    store.iccTriggerConfirmed("lock", true); assertTrue(store.lockTriggerCalibrated());
    assertTrue(store.mapped("lock.lock")); assertFalse(store.iccTriggerConfirmed("charge"));
    store.prefs.edit().putString("log.backend", "file").commit(); assertTrue(store.lockTriggerCalibrated());
  }

  @Test public void confirmationIsSeparatePersistentAndDoesNotEnableSoundGroupsOrOutput() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().putString("log.backend", "icc").commit();
    store.iccTriggerConfirmed("charge", true); store.iccTriggerConfirmed("handbrake", true);
    Store restored = new Store(RuntimeEnvironment.getApplication());
    assertTrue(restored.iccTriggerConfirmed("charge")); assertTrue(restored.iccTriggerConfirmed("lock"));
    assertFalse(restored.iccTriggerConfirmed("handbrake")); assertTrue(restored.mapped("charge.start"));
    assertFalse(restored.enabled("charge")); assertTrue(restored.enabled("lock"));
    assertFalse(restored.prefs.getBoolean("lock.output.confirmed", false));
    Diagnostics diagnostics = new Diagnostics(); diagnostics.attach(RuntimeEnvironment.getApplication());
    JSONObject config = new JSONObject(diagnostics.export(false, restored)).getJSONObject("iccConfiguration");
    assertTrue(config.getBoolean("chargeConfirmed")); assertTrue(config.getBoolean("speedUnitConfirmed"));
    assertFalse(config.getBoolean("handbrakeIsAutoHold"));
  }

  @Test public void identityWhitelistRejectsOtherTopicsIdsAreasAndStatus() {
    String topic = "ICC/VehicleCondition/Charging";
    for (Protocol.Sample sample : new Protocol.Sample[] {
        new Protocol.Sample(topic, 9, "0x0", 0, "3", 1, "ICC", "charge", "charging"),
        new Protocol.Sample(topic, 8, "0x1", 0, "3", 1, "ICC", "charge", "charging"),
        new Protocol.Sample(topic, 8, "0x0", 1, "3", 1, "ICC", "charge", "charging"),
        new Protocol.Sample(topic, 8, "0x0", 0, "3", 1, "日志", "charge", "charging"),
        new Protocol.Sample(topic, 8, "0x0", 0, "3", 1, "ICC", "autohold", "holding")})
      assertFalse(IccVehicleWire.state(sample));
  }
}
