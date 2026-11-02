package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccSpeedCandidatesTest {
  private Protocol.Sample sample(String raw, long time) {
    return new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x0", 0, raw, time,
        "ICC", "speed", null);
  }

  @Test public void allFourHypothesesKeepTheOriginalValueAndUnconfirmedStatus() throws Exception {
    Protocol.Sample sample = sample("123", 1000);
    String display = IccSpeedCandidates.display(sample, true);
    assertTrue(display.contains("均待核实"));
    assertTrue(display.contains("A ×1：123"));
    assertTrue(display.contains("B ÷10：12.3"));
    assertTrue(display.contains("C ÷100：1.23"));
    assertTrue(display.contains("D ×3.6：442.8"));
    assertTrue(display.contains("假设原值为m/s"));
    JSONObject report = IccSpeedCandidates.export(sample, true);
    assertEquals("123", report.getString("raw"));
    assertFalse(report.getBoolean("confirmed"));
    assertFalse(report.getBoolean("automaticAudio"));
    assertFalse(report.getBoolean("upstreamTimestampAvailable"));
    JSONObject candidates = report.getJSONObject("candidates");
    assertEquals(123, candidates.getDouble("A_raw_as_kmh"), .0001);
    assertEquals(12.3, candidates.getDouble("B_raw_div_10"), .0001);
    assertEquals(1.23, candidates.getDouble("C_raw_div_100"), .0001);
    assertEquals(442.8, candidates.getDouble("D_raw_mps_times_3_6"), .0001);
    assertNull(sample.value);
    assertEquals("123", sample.raw);
  }

  @Test public void missingAndInvalidRepliesNeverBecomeZeroSpeed() throws Exception {
    for (String raw : new String[] {"MISSING", "", "-1", "1.5", "NaN", "Infinity", "2147483648", "99999999999", null}) {
      Protocol.Sample sample = sample(raw, 1000);
      assertNull(IccSpeedCandidates.raw(sample));
      assertFalse(IccSpeedCandidates.display(sample, true).contains("A ×1"));
      JSONObject report = IccSpeedCandidates.export(sample, true);
      assertFalse(report.getBoolean("validRaw"));
      assertFalse(report.getBoolean("freshReply"));
      assertEquals(0, report.getJSONObject("candidates").length());
    }
    assertEquals(0, IccSpeedCandidates.export(null, true).getJSONObject("candidates").length());
  }

  @Test public void anExplicitZeroIsValidButDoesNotConfirmAnyUnit() throws Exception {
    Protocol.Sample zero = sample("0", 1000);
    assertEquals(Long.valueOf(0), IccSpeedCandidates.raw(zero));
    String display = IccSpeedCandidates.display(zero, true);
    assertTrue(display.contains("A ×1：0"));
    assertTrue(display.contains("B ÷10：0"));
    assertTrue(display.contains("C ÷100：0"));
    assertTrue(display.contains("D ×3.6：0"));
    assertFalse(IccSpeedCandidates.export(zero, true).getBoolean("confirmed"));
  }

  @Test public void otherSourcesPropertiesAreasOrStatusesCannotSupplyCandidates() {
    Protocol.Sample[] invalid = {
        new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x0", 0, "100", 1000, "日志", "speed", null),
        new Protocol.Sample("ICC/VehicleCondition/Speed", 9, "0x0", 0, "100", 1000, "ICC", "speed", null),
        new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x1", 0, "100", 1000, "ICC", "speed", null),
        new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x0", 1, "100", 1000, "ICC", "speed", null)
    };
    for (Protocol.Sample sample : invalid) assertNull(IccSpeedCandidates.raw(sample));
  }

  @Test public void freshReplyRequiresActiveSourceAndBothLocalTimesWithinTheWindow() {
    Protocol.Sample sample = sample("10", 1000);
    assertTrue(IccSpeedCandidates.fresh(sample, true, 1000, 6000));
    assertFalse(IccSpeedCandidates.fresh(sample, true, 1000, 6001));
    assertFalse(IccSpeedCandidates.fresh(sample, false, 1000, 1001));
    assertFalse(IccSpeedCandidates.fresh(sample, true, 2000, 1500));
    assertFalse(IccSpeedCandidates.fresh(sample, true, 500, 999));
    assertFalse(IccSpeedCandidates.fresh(sample("MISSING", 1000), true, 1000, 1001));
    assertFalse(IccSpeedCandidates.display(sample, false).contains("A ×1"));
    assertTrue(IccSpeedCandidates.display(sample, false).contains("等待新鲜ICC回复"));
  }

  @Test public void decimalFormattingIsExactLocaleIndependentAndDoesNotOverflow() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertTrue(IccSpeedCandidates.display(sample("1", 1000), true).contains("C ÷100：0.01"));
      String display = IccSpeedCandidates.display(sample("2147483647", 1000), true);
      assertTrue(display.contains("D ×3.6：7730941129.2"));
      assertFalse(display.contains("E+"));
    } finally { Locale.setDefault(previous); }
  }

  @Test public void diagnosticsExportKeepsHistoricalCandidatesExplicitlyNonLiveAfterStop() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    Diagnostics diagnostics = new Diagnostics();
    Protocol.Sample sample = new Protocol.Sample("ICC/VehicleCondition/Speed", 2, "0x0", 0, "123",
        System.currentTimeMillis(), "ICC", "speed", "123");
    diagnostics.stateReading(sample);
    diagnostics.live("speed", "不可用", "ICC");
    diagnostics.monitoring = true;
    JSONObject report = new JSONObject(diagnostics.export(false, store)).getJSONObject("iccSpeedComparison");
    assertTrue(report.getBoolean("freshReply"));
    assertTrue(report.has("replyReceivedAt"));
    assertEquals(123, report.getInt("kmh"));
    diagnostics.monitoring = false;
    report = new JSONObject(diagnostics.export(false, store)).getJSONObject("iccSpeedComparison");
    assertFalse(report.getBoolean("freshReply"));
    assertTrue(report.getBoolean("confirmed"));
    assertFalse(report.getBoolean("automaticAudio"));
    assertEquals("km/h", report.getString("unit"));
  }
}
