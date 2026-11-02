package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class StateCalibrationTest {
  private Store store;

  static Protocol.Sample sample(String topic, long property, String raw) {
    long now = System.currentTimeMillis();
    return Protocol.decode(String.format(java.util.Locale.ROOT,
        "%d.%03d 123 456 I MessageProperty: receive %s, value: CarPropertyValue{id=0x%x, area=0x0, status=0, value=%s}",
        now / 1000, now % 1000, topic, property, raw));
  }

  @Before public void setup() {
    store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
  }

  @Test public void gearZeroBasedMapPersistsAndUnknownNeverFallsBackToDefault() {
    Protocol.Sample subject = sample("DrivingInfo/Gear", 67108874L, "0");
    store.calibrateState(subject, new String[] {"0", "1", "2", "3", "4"}, false);
    Store restored = new Store(RuntimeEnvironment.getApplication());
    assertEquals("1", restored.calibratedLogSample(subject).value);
    assertEquals("2", restored.calibratedLogSample(sample(subject.topic, subject.propertyId, "1")).value);
    assertEquals("5", restored.calibratedLogSample(sample(subject.topic, subject.propertyId, "4")).value);
    assertNull(restored.calibratedLogSample(sample(subject.topic, subject.propertyId, "101")).value);
    assertFalse(restored.enabled("gear"));
    assertEquals("3", restored.stateCalibration("gear").optJSONObject("mapping").optString("2"));
  }

  @Test public void optionalMAndResetReturnToOriginalObservedMapping() {
    Protocol.Sample subject = sample("DrivingInfo/Gear", 67108874L, "1");
    store.calibrateState(subject, new String[] {"0", "1", "2", "3", ""}, false);
    assertNull(store.calibratedLogSample(sample(subject.topic, subject.propertyId, "4")).value);
    store.clearStateCalibration("gear");
    assertEquals("4", store.calibratedLogSample(sample(subject.topic, subject.propertyId, "4")).value);
  }

  @Test public void duplicateMissingNegativeFractionalAndHugeRawValuesCannotSave() {
    Protocol.Sample subject = sample("DrivingInfo/Gear", 67108874L, "1");
    for (String[] mapping : new String[][] {
        {"0", "0", "2", "3", ""}, {"", "1", "2", "3", ""}, {"-1", "1", "2", "3", ""},
        {"0.5", "1", "2", "3", ""}, {"2147483648", "1", "2", "3", ""}, {"0", "1"}}) {
      try { store.calibrateState(subject, mapping, false); fail("invalid mapping accepted"); }
      catch (IllegalArgumentException expected) {}
      assertEquals(0, store.stateCalibration("gear").length());
    }
  }

  @Test public void doorAndTailgateCanInvertOrMapObservedIntegerStates() {
    for (String key : new String[] {"door.front_left", "door.front_right", "door.rear_left", "door.rear_right", "tailgate"}) {
      int index = java.util.Arrays.asList("door.front_left", "door.front_right", "door.rear_left", "door.rear_right").indexOf(key);
      String topic = index < 0 ? "VehicleDoor/Door/Back" : Protocol.TOPICS[index];
      long property = index < 0 ? 100663345L : Protocol.DOOR_IDS[index];
      Protocol.Sample subject = sample(topic, property, "7");
      store.calibrateState(subject, new String[] {"7", "0"}, false);
      assertEquals("0", store.calibratedLogSample(subject).value);
      assertEquals("1", store.calibratedLogSample(sample(topic, property, "0")).value);
      assertNull(store.calibratedLogSample(sample(topic, property, "1")).value);
    }
  }

  @Test public void mapsAreBoundToExactLogIdentityAndNeverChangeNativeOrAliases() {
    Protocol.Sample subject = sample("DrivingInfo/Gear", 67108874L, "1");
    store.calibrateState(subject, new String[] {"0", "1", "2", "3", "4"}, false);
    Protocol.Sample alias = sample("AVM/DrivingInfo/Gear", 1728053436L, "1");
    assertEquals("1", store.calibratedLogSample(alias).value);
    Protocol.Sample nativeSample = Protocol.property(true, 67108874, 0, 0, 1, System.currentTimeMillis());
    assertSame(nativeSample, store.calibratedLogSample(nativeSample));
    Protocol.Sample differentArea = new Protocol.Sample(subject.topic, subject.propertyId, "0x1", 0,
        "1", subject.time, "日志", "gear", null);
    assertSame(differentArea, store.calibratedLogSample(differentArea));
  }

  @Test public void invalidStatusAndFractionalRawCannotBecomeValidAfterCalibration() {
    Protocol.Sample subject = sample("DrivingInfo/Gear", 67108874L, "1");
    store.calibrateState(subject, new String[] {"0", "1", "2", "3", "4"}, false);
    Protocol.Sample failed = new Protocol.Sample(subject.topic, subject.propertyId, subject.area, 1,
        "1", subject.time, "日志", "gear", "2");
    assertNull(store.calibratedLogSample(failed).value);
    assertNull(store.calibratedLogSample(sample(subject.topic, subject.propertyId, "1.5")).value);
  }

  @Test public void speedUnitMapsOnlyKnownLogAndRejectsOutOfRangeAndNaN() {
    Protocol.Sample subject = sample("DrivingInfo/Speed", 67108875L, "10");
    store.calibrateState(subject, null, true);
    assertEquals(36.0, Protocol.number(store.calibratedLogSample(subject).value), .001);
    for (String raw : new String[] {"-1", "NaN", "Infinity", "100"})
      assertNull(store.calibratedLogSample(sample(subject.topic, subject.propertyId, raw)).value);
    store.calibrateState(subject, null, false);
    assertEquals(10.0, Protocol.number(store.calibratedLogSample(subject).value), .001);
  }

  @Test public void unknownIdentityAndUnsupportedStateCannotBeFabricatedByCalibration() {
    Protocol.Sample known = sample("DrivingInfo/Gear", 67108874L, "1");
    for (Protocol.Sample subject : new Protocol.Sample[] {null,
        new Protocol.Sample("FakeTopic", 123, "0x0", 0, "1", known.time, "日志", "gear", "1"),
        new Protocol.Sample(known.topic, known.propertyId, known.area, 0, "1", known.time, "日志", "charge", "1"),
        Protocol.property(true, 67108874, 0, 0, 1, known.time)}) {
      try { store.calibrateState(subject, new String[] {"0", "1", "2", "3", "4"}, false); fail(); }
      catch (IllegalArgumentException expected) {}
    }
    assertEquals(0, store.stateCalibration("gear").length());
  }

  @Test public void invalidObservedRawCannotCreateCalibration() {
    Protocol.Sample known = sample("DrivingInfo/Gear", 67108874L, "1");
    for (String raw : new String[] {"-1", "NaN", "Infinity", "1.5", "2147483648"}) {
      Protocol.Sample invalid = new Protocol.Sample(known.topic, known.propertyId, known.area, 0,
          raw, known.time, known.source, known.key, null);
      try { store.calibrateState(invalid, new String[] {"0", "1", "2", "3", "4"}, false); fail(); }
      catch (IllegalArgumentException expected) {}
    }
    Protocol.Sample speed = sample("DrivingInfo/Speed", 67108875L, "0");
    for (String raw : new String[] {"-1", "NaN", "Infinity", "301"}) {
      Protocol.Sample invalid = new Protocol.Sample(speed.topic, speed.propertyId, speed.area, 0,
          raw, speed.time, speed.source, speed.key, null);
      try { store.calibrateState(invalid, null, false); fail(); }
      catch (IllegalArgumentException expected) {}
    }
    assertEquals(0, store.stateCalibration("gear").length());
    assertEquals(0, store.stateCalibration("speed").length());
  }

  @Test public void esPresetIsOptionalPersistentAndDoesNotFabricateDataOrGrantConfirmation() throws Exception {
    assertFalse(store.esStatePreset("gear"));
    store.calibrateLock("0", "1");
    store.calibrateAutoHold("2", "1");
    store.applyEsStatePreset(new String[] {"gear", "speed", "door.front_left", "door.front_right",
        "door.rear_left", "door.rear_right", "tailgate"});
    Store restored = new Store(RuntimeEnvironment.getApplication());
    int presets = 0;
    for (VehicleStateSpec spec : VehicleStateSpec.ALL) if (restored.esStatePreset(spec.key)) presets++;
    assertEquals(7, presets);
    assertFalse(restored.esStatePreset("lock")); assertFalse(restored.esStatePreset("autohold"));
    assertFalse(restored.esStatePreset("charge"));
    assertEquals("locked", restored.lockState("0"));
    assertEquals("holding", restored.autoHoldState("2"));
    assertFalse(restored.enabled("gear"));
    assertEquals("file", restored.prefs.getString("log.backend", "file"));
    JSONObject export = new JSONObject(new Diagnostics().export(false, restored));
    assertEquals(0, export.getJSONArray("currentStates").length());
    JSONObject preset = export.getJSONObject("stateCalibrations").getJSONObject("gear");
    assertEquals(Store.ES_STATE_PRESET, preset.getString("preset"));
    assertFalse(preset.getBoolean("verifiedOnVehicle"));
  }

  @Test public void esPresetKeepsKnownGearAliasesAndNeverInventsM() {
    store.applyEsStatePreset(new String[] {"gear"});
    String[] rawValues = {"1", "101", "2", "102", "3", "103", "300", "4", "113"};
    String[] expected = {"1", "1", "2", "2", "3", "3", "3", "4", "4"};
    for (int index = 0; index < rawValues.length; index++)
      assertEquals(expected[index], store.calibratedLogSample(sample("DrivingInfo/Gear", 67108874L, rawValues[index])).value);
    for (String raw : new String[] {"0", "5", "1.5", "-1", "999"})
      assertNull(store.calibratedLogSample(sample("DrivingInfo/Gear", 67108874L, raw)).value);
    assertEquals("2", store.calibratedLogSample(sample("AVM/DrivingInfo/Gear", 1728053436L, "2")).value);
    assertNull(store.calibratedLogSample(sample("AVM/DrivingInfo/Gear", 1728053436L, "102")).value);
    Protocol.Sample nativeSample = Protocol.property(true, 67108874, 0, 0, 2, System.currentTimeMillis());
    assertSame(nativeSample, store.calibratedLogSample(nativeSample));
    Protocol.Sample failed = Protocol.decode("1.000 1 1 I MessageProperty: receive DrivingInfo/Gear, value: CarPropertyValue{id=0x400000a, area=0x0, status=1, value=2}");
    assertNull(store.calibratedLogSample(failed).value);
  }

  @Test public void esPresetKeepsColorRulesInsteadOfReducingEverythingToBinaryRaw() {
    store.applyEsStatePreset(new String[] {"door.front_right", "tailgate", "speed"});
    Protocol.Sample door = Protocol.decode("1.000 1 1 I MessageProperty: receive VehicleDoor/Door/FrontRight, value: CarPropertyValue{id=0x600002d, area=0x0, status=0, value=0, color=2}");
    assertEquals("1", store.calibratedLogSample(door).value);
    Protocol.Sample tail = Protocol.decode("1.000 1 1 I MessageProperty: receive VehicleDoor/TrunkSts, value: CarPropertyValue{id=0x900006a, area=0x0, status=0, value=1, color=0}");
    assertEquals("0", store.calibratedLogSample(tail).value);
    assertEquals(10, Protocol.number(store.calibratedLogSample(sample("DrivingInfo/Speed", 67108875L, "10")).value), .001);
    assertNull(store.calibratedLogSample(sample("DrivingInfo/Speed", 67108875L, "301")).value);
  }

  @Test public void esPresetSelectionReplacesOnlySelectedManualMapsAndClearPreservesOthers() {
    Protocol.Sample gear = sample("DrivingInfo/Gear", 67108874L, "1");
    store.calibrateState(gear, new String[] {"0", "1", "2", "3", "4"}, false);
    store.applyEsStatePreset(new String[] {"speed"});
    assertEquals("2", store.calibratedLogSample(gear).value);
    assertTrue(store.stateCalibration("gear").has("mapping"));
    store.applyEsStatePreset(new String[] {"gear", "tailgate"});
    assertEquals("1", store.calibratedLogSample(gear).value);
    assertFalse(store.stateCalibration("gear").has("mapping"));
    assertFalse(store.esStatePreset("speed"));
    store.calibrateState(gear, new String[] {"0", "1", "2", "3", "4"}, false);
    assertFalse(store.esStatePreset("gear"));
    assertEquals("2", store.calibratedLogSample(gear).value);
    store.clearEsStatePreset();
    assertTrue(store.stateCalibration("gear").has("mapping"));
    assertFalse(store.esStatePreset("tailgate"));
    store.clearStateCalibration("gear");
    assertEquals("1", store.calibratedLogSample(gear).value);
  }

  @Test public void unsupportedOrEmptyPresetSelectionIsRejectedAtomically() {
    store.applyEsStatePreset(new String[] {"gear"});
    String before = store.stateCalibration("gear").toString();
    for (String[] selection : new String[][] {null, {}, {"speed", "lock"}, {"autohold"}, {"charge"}, {"unknown"}}) {
      try { store.applyEsStatePreset(selection); fail(); }
      catch (IllegalArgumentException expected) {}
      assertEquals(before, store.stateCalibration("gear").toString());
      assertEquals(0, store.stateCalibration("speed").length());
    }
    assertFalse(store.lockCalibrated()); assertTrue(store.autoHoldCalibrated());
  }

  @Test public void invalidAvmStatusKeepsKnownKeyButCannotDriveState() {
    Protocol.Sample invalid = Protocol.decode("1.000 1 1 I MessageProperty: receive AVM/DrivingInfo/Gear, value: CarPropertyValue{id=0x670000bc, area=0x0, status=1, value=2}");
    assertEquals("gear", invalid.key); assertNull(invalid.value);
  }

  @Test public void dashboardEvidenceAndCalibrationExportWithoutLosingExistingFields() throws Exception {
    Protocol.Sample sample = sample("DrivingInfo/Gear", 67108874L, "1");
    store.calibrateState(sample, new String[] {"0", "1", "2", "3", "4"}, false);
    Diagnostics diagnostics = new Diagnostics();
    diagnostics.stateReading(sample); diagnostics.stateReading(sample);
    diagnostics.stateReading(sample(sample.topic, sample.propertyId, "2"));
    diagnostics.live("gear", "2", "日志");
    diagnostics.calibrating = true;
    JSONObject exported = new JSONObject(diagnostics.export(false, store));
    assertTrue(exported.getBoolean("calibrating"));
    assertEquals(3, exported.getJSONArray("currentStates").getJSONObject(0).getInt("count"));
    assertEquals(1, exported.getJSONArray("currentStates").getJSONObject(0).getInt("changes"));
    assertTrue(exported.getJSONObject("stateCalibrations").has("gear"));
    assertTrue(exported.has("lockConfiguration")); assertTrue(exported.has("autoHoldConfiguration"));
    diagnostics.clear(); assertTrue(diagnostics.stateReadings().isEmpty());
    assertTrue(store.stateCalibration("gear").has("mapping"));
  }
}
