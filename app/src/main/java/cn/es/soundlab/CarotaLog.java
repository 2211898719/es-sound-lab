package cn.es.soundlab;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CarotaLog {
  public static final String GEAR_TOPIC = "Carota/VehicleCondition/GearState";
  private static final Pattern RECORD = Pattern.compile(
      "^\\[INFO\\] (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) in ConditionHandlerImpl\\.kt\\(\\d+\\) >Vehicle condition data: (.+)$");
  private static final Pattern GEAR = Pattern.compile("(?:^|;)\\s*GearState\\s*=\\s*([A-Z_]+)\\s*(?=;|$)");
  private static final String HEADER = " 0 0 I CarotaVehicleCondition: ";

  public static boolean relevant(String line) {
    return line != null && line.length() <= 8192 && RECORD.matcher(line).matches();
  }

  public static String normalize(String line, long now) {
    if (!relevant(line)) return null;
    Matcher record = RECORD.matcher(line);
    if (!record.matches()) return null;
    return LogFormat.normalize(record.group(1) + HEADER + record.group(2), now);
  }

  public static Protocol.Sample decode(String normalized) {
    if (normalized == null || normalized.length() > 8192) return null;
    int body = normalized.indexOf(HEADER);
    long time = Protocol.logTime(normalized);
    if (body < 0 || time < 0) return null;
    Matcher gear = GEAR.matcher(normalized.substring(body + HEADER.length()));
    String raw = gear.find() ? gear.group(1) : "MISSING";
    if (gear.find()) raw = "DUPLICATE";
    String value = raw.equals("PARKING") ? "1" : raw.equals("REVERSE") ? "2"
        : raw.equals("DRIVE") ? "4" : null;
    return new Protocol.Sample(GEAR_TOPIC, 0, "0x0", 0, raw, time, "日志", "gear", value);
  }

  public static boolean gear(Protocol.Sample sample) {
    return sample != null && sample.source.equals("日志") && GEAR_TOPIC.equals(sample.topic)
        && sample.propertyId == 0 && sample.area.equals("0x0") && "gear".equals(sample.key);
  }

  private CarotaLog() {}
}
