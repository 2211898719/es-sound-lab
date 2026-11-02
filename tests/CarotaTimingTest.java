package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class CarotaTimingTest {
  private Protocol.Sample sample(long time) {
    return CarotaLog.decode(CarotaLog.normalize(CarotaLogTest.line(time, "PARKING"), time));
  }

  @Test public void freshOldFutureAndPreBaselineAgesAreSeparated() throws Exception {
    long now = System.currentTimeMillis(); CarotaTiming timing = new CarotaTiming();
    timing.observed(sample(now - 100), now - 1000, now);
    timing.observed(sample(now - 6000), now - 1000, now);
    timing.observed(sample(now + 1000), now - 1000, now);
    JSONObject report = timing.snapshot();
    assertEquals(3, report.getInt("samples")); assertEquals(-1000, report.getLong("lastAgeMillis"));
    assertEquals(6000, report.getLong("maxAgeMillis")); assertEquals(1, report.getInt("expiredAtRead"));
    assertEquals(1, report.getInt("beforeBaseline")); assertEquals(1, report.getInt("futureAtRead"));
    assertTrue(timing.summary(now).contains("时间超前"));
    assertTrue(timing.summary(now).contains("不包含上游采样/查询延迟"));
  }

  @Test public void queueExpiryIsIndependentAndClearResetsObservationNotCalibration() throws Exception {
    long now = System.currentTimeMillis(); Diagnostics diagnostics = new Diagnostics();
    diagnostics.carotaObserved(sample(now), now - 1000, now);
    diagnostics.carotaProcessed(now, now + 6000, true);
    diagnostics.carotaProcessed(now + 7000, now + 7020, false);
    JSONObject report = diagnostics.carotaTimingSnapshot();
    assertEquals(6000, report.getLong("maxQueueMillis")); assertEquals(20, report.getLong("lastQueueMillis"));
    assertEquals(1, report.getInt("expiredOnMain")); assertEquals(0, report.getInt("expiredAtRead"));
    diagnostics.clear(); assertEquals(0, diagnostics.carotaTimingSnapshot().getInt("samples"));
  }

  @Test public void otherSourcesAreNotMistakenForCarotaLatency() throws Exception {
    CarotaTiming timing = new CarotaTiming();
    timing.observed(StateCalibrationTest.sample("DrivingInfo/Gear", 67108874L, "1"), 0, 1000);
    assertEquals(0, timing.snapshot().getInt("samples"));
  }
}
