package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccSpeedReadingTest {
  private Protocol.Sample speed(int... pairs) throws Exception {
    for (Protocol.Sample sample : IccVehicleWire.decode(IccVehicleStateTest.body(pairs)).stateSamples(1000))
      if (sample.key.equals("speed")) return sample;
    throw new AssertionError("speed sample missing");
  }

  @Test public void explicitValuesUseConfirmedRawKmhAndNeverTriggerAudio() throws Exception {
    for (int raw : new int[] {0, 1, 12, 123}) {
      Protocol.Sample sample = speed(2, raw);
      assertEquals(Integer.valueOf(raw), IccSpeedReading.kmh(sample));
      assertFalse(IccSpeedReading.omittedZero(sample));
      assertNull(Protocol.event("speed", sample.value));
      JSONObject report = IccSpeedReading.export(sample, true);
      assertTrue(report.getBoolean("confirmed"));
      assertEquals("user-verified-this-vehicle", report.getString("confirmation"));
      assertEquals("km/h", report.getString("unit"));
      assertEquals(1, report.getInt("scale"));
      assertFalse(report.getBoolean("automaticAudio"));
    }
  }

  @Test public void omissionBecomesZeroOnlyForSuccessfulSnapshotWithKnownGear() throws Exception {
    for (int gear = 1; gear <= 4; gear++) {
      Protocol.Sample sample = speed(9, gear);
      assertTrue(IccSpeedReading.omittedZero(sample));
      assertEquals("MISSING", sample.raw);
      assertEquals(Integer.valueOf(0), IccSpeedReading.kmh(sample));
      assertTrue(IccSpeedReading.display(sample, true).contains("省略字段"));
    }
    assertNull(IccSpeedReading.kmh(speed(7, 1)));
    assertNull(IccSpeedReading.kmh(speed(9, 0)));
    assertNull(IccSpeedReading.kmh(speed(9, 5)));
    assertNull(IccSpeedReading.kmh(speed(9, 1, 2, -1)));
    assertNull(IccSpeedReading.kmh(null));
    assertTrue(IccSpeedReading.export(null, false).isNull("kmh"));
  }

  @Test public void staleStoppedOrFutureSamplesNeverDisplayLiveZero() throws Exception {
    Protocol.Sample sample = speed(9, 1);
    assertTrue(IccSpeedReading.fresh(sample, true, 1000, 6000));
    assertFalse(IccSpeedReading.fresh(sample, true, 1000, 6001));
    assertFalse(IccSpeedReading.fresh(sample, false, 1000, 1001));
    assertFalse(IccSpeedReading.fresh(sample, true, 2000, 1500));
    assertFalse(IccSpeedReading.fresh(sample, true, 500, 999));
    assertFalse(IccSpeedReading.display(sample, false).contains("0 km/h"));
    assertFalse(IccSpeedReading.export(sample, false).getBoolean("freshReply"));
  }

  @Test public void wrongSourceStatusOrIdentityCannotSupplySpeed() {
    for (Protocol.Sample sample : new Protocol.Sample[] {
        new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x0", 0, "10", 1000, "日志", "speed", "10"),
        new Protocol.Sample("ICC/VehicleCondition/Speed", 9, "0x0", 0, "10", 1000, "ICC", "speed", "10"),
        new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x0", 1, "10", 1000, "ICC", "speed", "10")})
      assertNull(IccSpeedReading.kmh(sample));
  }

  @Test public void confirmedAutoHoldDefaultsDoNotOverwriteManualCalibrationOrClear() {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    store = new Store(RuntimeEnvironment.getApplication());
    assertEquals("holding", store.autoHoldState("2"));
    assertEquals("released", store.autoHoldState("1"));
    assertFalse(store.enabled("autohold"));
    store.calibrateAutoHold("1", "2");
    assertEquals("holding", new Store(RuntimeEnvironment.getApplication()).autoHoldState("1"));
    store.prefs.edit().putString("autohold.holding", "").putString("autohold.released", "").commit();
    assertFalse(new Store(RuntimeEnvironment.getApplication()).autoHoldCalibrated());
  }
}
