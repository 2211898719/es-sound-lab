package cn.es.soundlab;

import static org.junit.Assert.*;

import java.text.SimpleDateFormat;
import java.util.Locale;
import org.junit.Test;

public final class LogFormatTest {
  private static final String BODY = " 123 456 I MessageProperty: receive DrivingInfo/Gear, value: CarPropertyValue{id=0x400000a, area=0x0, status=0, value=2}";
  private long time(String text) throws Exception {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).parse(text).getTime();
  }

  @Test public void epochIsPreserved() {
    String line = "1793448000.123" + BODY;
    assertEquals(line, LogFormat.normalize(line, 1793448000123L));
    assertEquals("2", Protocol.decode(line).value);
  }

  @Test public void threadtimeUsesDeviceLocalYearAndTimezone() throws Exception {
    long now = time("2026-10-31 20:00:00.123");
    String normalized = LogFormat.normalize("10-31 20:00:00.123" + BODY, now);
    assertEquals(now, Protocol.logTime(normalized)); assertEquals("gear", Protocol.decode(normalized).key);
  }

  @Test public void yearIncludedIsNotReplacedWithCurrentYear() throws Exception {
    long now = time("2026-10-31 20:00:00.123");
    assertEquals(time("2025-10-31 20:00:00.123"), Protocol.logTime(LogFormat.normalize("2025-10-31 20:00:00.123" + BODY, now)));
  }

  @Test public void decemberNearJanuaryIsAssignedPreviousYear() throws Exception {
    long now = time("2027-01-01 00:00:00.123");
    assertEquals(time("2026-12-31 23:59:59.123"), Protocol.logTime(LogFormat.normalize("12-31 23:59:59.123" + BODY, now)));
  }

  @Test public void leapDayStillParsesWhenAdjacentYearIsNotLeap() throws Exception {
    long now = time("2028-02-29 20:00:00.123");
    assertEquals(now, Protocol.logTime(LogFormat.normalize("02-29 20:00:00.123" + BODY, now)));
  }

  @Test public void malformedMissingAndOversizedTimesAreRejected() throws Exception {
    long now = time("2026-10-31 20:00:00.123");
    assertNull(LogFormat.normalize("MessageProperty no time", now));
    assertNull(LogFormat.normalize("02-30 20:00:00.123" + BODY, now));
    assertNull(LogFormat.normalize("2026-13-31 20:00:00.123" + BODY, now));
    assertNull(LogFormat.normalize("x".repeat(8193), now));
    assertNull(LogFormat.normalize(null, now));
  }

  @Test public void freshnessRequiresSessionBoundaryAndFiveSecondAge() {
    assertTrue(LogFormat.fresh(10000, 9000, 12000));
    assertFalse(LogFormat.fresh(8999, 9000, 10000));
    assertFalse(LogFormat.fresh(10000, 9000, 15001));
    assertFalse(LogFormat.fresh(10501, 9000, 10000));
    assertTrue(LogFormat.fresh(10500, 9000, 10000));
  }

  @Test public void gearRejectsFractionalValueAndNonGlobalArea() {
    String line = "1793448000.123" + BODY;
    assertNull(Protocol.decode(line.replace("value=2}", "value=2.5}")).value);
    assertNull(Protocol.decode(line.replace("area=0x0", "area=0x1")).value);
  }
}
