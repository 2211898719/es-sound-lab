package cn.es.soundlab;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only documented IDs from the inspected APK are automatic sound triggers. */
public final class Protocol {
  private static final Pattern PROPERTY =
      Pattern.compile(
          "receive\\s+(\\S+),\\s*value\\s*:\\s*CarPropertyValue\\{id=(0[xX][0-9a-fA-F]+),\\s*area=(0[xX][0-9a-fA-F]+),\\s*status=(\\d+),\\s*value=([^,}]+)([^}]*)\\}");
  private static final Pattern HEADER =
      Pattern.compile("^\\s*(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+(.*?)\\s*:\\s*(.*)$");
  private static final Pattern COLOR = Pattern.compile("(?:^|,)\\s*color=([^,}\\s]+)");
  private static final Pattern RELEVANT =
      Pattern.compile(
          "MessageProperty|VCU_PRNDGearAct|CarProperty|VehicleDoor|VehicleState|CarCabin|LockPrompt|door.?lock|lock.?status|charging|charge.?state|charge.?status|plug.?state|battery|Window|SeatBelt|seat.?belt|Trunk|Gear|vehicle.?speed|解锁|闭锁|充电",
          Pattern.CASE_INSENSITIVE);
  public static final String[] DOORS = {"front_left", "front_right", "rear_left", "rear_right"};
  public static final String[] TOPICS = {
    "VehicleDoor/Door/FrontLeft",
    "VehicleDoor/Door/FrontRight",
    "VehicleDoor/Door/RearLeft",
    "VehicleDoor/Door/RearRight"
  };
  public static final long[] DOOR_IDS = {100663340L, 100663341L, 100663342L, 100663343L};
  public static final int DOOR_POS = 373295872,
      GEAR_SELECTION = 289408000,
      CURRENT_GEAR = 289408001,
      SPEED = 291504647;

  public static final class Sample {
    public final String topic, area, raw, source;
    public final long propertyId, time;
    public final int status;
    public final String key, value;

    public Sample(
        String topic,
        long id,
        String area,
        int status,
        String raw,
        long time,
        String source,
        String key,
        String value) {
      this.topic = topic;
      this.propertyId = id;
      this.area = area;
      this.status = status;
      this.raw = raw;
      this.time = time;
      this.source = source;
      this.key = key;
      this.value = value;
    }

    public String identity() {
      return source + "|" + topic + "|" + propertyId + "|" + area;
    }
  }

  public static boolean relevant(String line) {
    return line != null && line.length() <= 8192 && RELEVANT.matcher(line).find();
  }

  public static long logTime(String line) {
    Matcher m = HEADER.matcher(line);
    if (!m.matches()) return -1;
    try {
      return new java.math.BigDecimal(m.group(1))
          .movePointRight(3)
          .setScale(0, java.math.RoundingMode.DOWN)
          .longValueExact();
    } catch (Exception e) {
      return -1;
    }
  }

  public static Sample decode(String line) {
    if (line == null || line.length() > 8192) return null;
    long time = logTime(line);
    if (time < 0) return null;
    Matcher m = PROPERTY.matcher(line);
    if (!m.find()) return null;
    try {
      String topic = m.group(1),
          area = m.group(3).toLowerCase(Locale.ROOT),
          raw = m.group(5).trim();
      long id = Long.parseLong(m.group(2).substring(2), 16);
      int status = Integer.parseInt(m.group(4));
      String key = null, value = null;
      double n = number(raw);
      if (status == 0 && Double.isFinite(n)) {
        for (int i = 0; i < 4; i++)
          if (topic.equals(TOPICS[i]) && id == DOOR_IDS[i] && (n == 0 || n == 1)) {
            key = "door." + DOORS[i];
            value = String.valueOf((int) n);
            Matcher color = COLOR.matcher(m.group(6));
            if (i > 0 && color.find()) {
              double c = number(color.group(1));
              value = Double.isFinite(c) ? (Math.abs(c - 2) < .01 ? "1" : "0") : null;
            }
          }
        if ((topic.equals("DrivingInfo/Gear") && id == 67108874L)
            || (topic.equals("AVM/DrivingInfo/Gear") && id == 1728053436L)) {
          key = "gear";
          value =
              topic.equals("AVM/DrivingInfo/Gear") && (n < 1 || n > 4) ? null : gearLog((int) n);
        }
        if ((topic.equals("VehicleDoor/Door/Back") && id == 100663345L)
            || (topic.equals("VehicleDoor/TrunkSts") && id == 150995050L)) {
          if (n == 0 || n == 1) {
            key = "tailgate";
            value = String.valueOf((int) n);
            Matcher c = COLOR.matcher(m.group(6));
            if (c.find()) {
              double v = number(c.group(1));
              value = Math.abs(v - 2) < .01 ? "1" : (v == 0 || v == 1 ? "0" : null);
            }
          }
        }
        if (topic.equals("DrivingInfo/Speed") && id == 67108875L && n >= 0 && n <= 300) {
          key = "speed";
          value = Double.toString(n);
        }
      }
      if (key == null) {
        for (int i = 0; i < 4; i++)
          if (topic.equals(TOPICS[i]) && id == DOOR_IDS[i]) key = "door." + DOORS[i];
        if (topic.equals("DrivingInfo/Gear") && id == 67108874L) key = "gear";
        if (topic.equals("DrivingInfo/Speed") && id == 67108875L) key = "speed";
        if ((topic.equals("VehicleDoor/Door/Back") && id == 100663345L)
            || (topic.equals("VehicleDoor/TrunkSts") && id == 150995050L)) key = "tailgate";
      }
      return new Sample(topic, id, area, status, raw, time, "日志", key, value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static String gearLog(int n) {
    switch (n) {
      case 101:
        return "1";
      case 102:
        return "2";
      case 103:
      case 300:
        return "3";
      case 113:
        return "4";
      default:
        return n >= 1 && n <= 4 ? String.valueOf(n) : null;
    }
  }

  public static Sample property(boolean mega, int id, int area, int status, Object raw, long now) {
    String key = null, value = null;
    double n = raw instanceof Number ? ((Number) raw).doubleValue() : Double.NaN;
    if (status == 0) {
      for (int i = 0; i < 4; i++) {
        boolean match =
            mega
                ? (id == DOOR_IDS[i] && (area == 0 || area == new int[] {16, 64, 256, 1024}[i]))
                : (id == DOOR_POS && area == new int[] {1, 4, 16, 64}[i]);
        if (match && Double.isFinite(n) && n >= 0 && (mega ? (n == 0 || n == 1) : true)) {
          key = "door." + DOORS[i];
          value = n == 0 ? "0" : "1";
        }
      }
      if (mega && id == 100663305)
        for (int i = 0; i < 4; i++)
          if (area == new int[] {16, 64, 256, 1024}[i] && (n == 0 || n == 1)) {
            key = "door." + DOORS[i];
            value = String.valueOf((int) n);
          }
      if (mega && id == 67108874 && Double.isFinite(n)) {
        key = "gear";
        switch ((int) n) {
          case 0:
          case 101:
            value = "1";
            break;
          case 1:
          case 102:
            value = "2";
            break;
          case 2:
          case 103:
          case 300:
            value = "3";
            break;
          case 3:
          case 113:
            value = "4";
            break;
          case 4:
            value = "5";
            break;
          default:
            break;
        }
      }
      if (!mega && (id == GEAR_SELECTION || id == CURRENT_GEAR) && area == 0) {
        key = "gear";
        switch ((int) n) {
          case 1:
            value = "3";
            break;
          case 2:
            value = "2";
            break;
          case 4:
            value = "1";
            break;
          case 8:
            value = "4";
            break;
          default:
            if (n >= 16 && n <= 4096 && (((int) n & ((int) n - 1)) == 0))
              value = id == CURRENT_GEAR ? "4" : "5";
        }
      }
      if (!mega && id == SPEED && n >= 0 && n <= 84) {
        key = "speed";
        value = Double.toString(n * 3.6);
      }
      if (mega && id == 67108875 && n >= 0 && n <= 300) {
        key = "speed";
        value = Double.toString(n);
      }
      if (mega && id == 100663345 && (n == 0 || n == 1)) {
        key = "tailgate";
        value = String.valueOf((int) n);
      }
    }
    if (key == null) {
      for (int i = 0; i < 4; i++) {
        boolean match =
            mega
                ? ((id == DOOR_IDS[i] && (area == 0 || area == new int[] {16, 64, 256, 1024}[i]))
                    || (id == 100663305 && area == new int[] {16, 64, 256, 1024}[i]))
                : (id == DOOR_POS && area == new int[] {1, 4, 16, 64}[i]);
        if (match) key = "door." + DOORS[i];
      }
      if ((mega && id == 67108874)
          || (!mega && (id == GEAR_SELECTION || id == CURRENT_GEAR) && area == 0)) key = "gear";
      if ((mega && id == 67108875) || (!mega && id == SPEED)) key = "speed";
      if (mega && id == 100663345) key = "tailgate";
    }
    return new Sample(
        "property/0x" + Integer.toHexString(id),
        id,
        "0x" + Integer.toHexString(area),
        status,
        String.valueOf(raw),
        now,
        mega ? "Mega" : "Android",
        key,
        value);
  }

  public static String event(String key, String value) {
    if (value == null) return null;
    if (key.startsWith("door.") || key.equals("tailgate"))
      return key + (value.equals("1") ? ".open" : ".close");
    if (key.equals("gear")) {
      int i = (int) number(value);
      return i >= 1 && i <= 5 ? "gear." + new String[] {"p", "r", "n", "d", "m"}[i - 1] : null;
    }
    return null;
  }

  public static double number(String s) {
    try {
      double n = Double.parseDouble(s);
      return Double.isFinite(n) ? n : Double.NaN;
    } catch (Exception e) {
      return Double.NaN;
    }
  }
}
