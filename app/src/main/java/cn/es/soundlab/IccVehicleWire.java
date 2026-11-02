package cn.es.soundlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

final class IccVehicleWire {
  static final int MAX_BODY = 32768;
  private static final String[] KEYS = {"gear", "speed", "handbrake", "power", "charge", "lock"};
  private static final String[] TOPICS = {"Gear", "Speed", "Handbrake", "Power", "Charging", "Lock"};
  private static final String[] FIELDS = {"gearRaw", "speedRaw", "handbrakeRaw", "powerRaw", "chargingRaw", "lockRaw"};
  private static final int[] IDS = {9, 2, 6, 7, 8, 13};

  static boolean state(Protocol.Sample sample) {
    if (sample == null || !"ICC".equals(sample.source) || !"0x0".equals(sample.area) || sample.status != 0) return false;
    for (int index = 0; index < KEYS.length; index++)
      if (KEYS[index].equals(sample.key) && sample.propertyId == IDS[index]
          && ("ICC/VehicleCondition/" + TOPICS[index]).equals(sample.topic)) return true;
    return false;
  }

  static String stateLabel(Protocol.Sample sample) {
    if (!state(sample)) return "不可用";
    if (sample.key.equals("speed")) {
      Integer speed = IccSpeedReading.kmh(sample);
      return speed != null ? speed + " km/h" : sample.raw.equals("MISSING") ? "字段缺失" : "无效原始值";
    }
    if (sample.raw.equals("MISSING")) return "字段缺失";
    if (sample.value == null) return "未知(" + sample.raw + ")";
    if (sample.key.equals("charge")) return sample.raw.equals("1") ? "未充电"
        : sample.raw.equals("2") ? "充电待机" : sample.raw.equals("3") ? "慢充中" : "快充中";
    VehicleStateSpec spec = VehicleStateSpec.find(sample.key);
    return spec == null ? "不可用" : spec.label(sample.value);
  }

  static boolean gear(Protocol.Sample sample) {
    return sample != null && "ICC".equals(sample.source) && "gear".equals(sample.key)
        && "ICC/VehicleCondition/Gear".equals(sample.topic) && sample.propertyId == 9 && "0x0".equals(sample.area);
  }
  private static final byte[] FORMAT_KEY = "cK9vk17EwSC62def".getBytes(StandardCharsets.US_ASCII);

  static byte[] request(String seed) throws Exception {
    if (seed == null || !seed.matches("[a-z]{20}")) throw new IOException("invalid-seed");
    return crypt(Cipher.ENCRYPT_MODE, new byte[] {10, 3, 77, 68, 65}, seed);
  }

  static byte[] crypt(int mode, byte[] data, String seed) throws Exception {
    if (seed == null || seed.isEmpty() || seed.length() > 256) throw new IOException("invalid-seed");
    for (int index = 0; index < seed.length(); index++)
      if (seed.charAt(index) < 32 || seed.charAt(index) > 126) throw new IOException("invalid-seed");
    Mac mac = Mac.getInstance("HmacMD5");
    mac.init(new SecretKeySpec(FORMAT_KEY, "HmacMD5"));
    byte[] key = mac.doFinal(seed.getBytes(StandardCharsets.UTF_8));
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(FORMAT_KEY));
    return cipher.doFinal(data);
  }

  static final class Condition {
    final Integer gear;
    final JSONObject fields;

    Condition(Integer gear, JSONObject fields) { this.gear = gear; this.fields = fields; }

    String gearName() {
      if (gear == null) return "缺失";
      switch (gear) {
        case 1: return "P";
        case 2: return "N";
        case 3: return "D";
        case 4: return "R";
        default: return "未知(" + gear + ")";
      }
    }

    boolean knownGear() { return gear != null && gear >= 1 && gear <= 4; }

    Protocol.Sample gearSample(long receivedAt) {
      String value = gear == null ? null : gear == 1 ? "1" : gear == 2 ? "3"
          : gear == 3 ? "4" : gear == 4 ? "2" : null;
      return new Protocol.Sample("ICC/VehicleCondition/Gear", 9, "0x0", 0,
          gear == null ? "MISSING" : Integer.toString(gear), receivedAt, "ICC", "gear", value);
    }

    List<Protocol.Sample> stateSamples(long receivedAt) {
      List<Protocol.Sample> result = new ArrayList<>();
      result.add(gearSample(receivedAt));
      for (int index = 1; index < KEYS.length; index++) {
        Integer raw = fields.has(FIELDS[index]) ? fields.optInt(FIELDS[index]) : null;
        String value = null;
        if (KEYS[index].equals("speed"))
          value = raw == null ? knownGear() ? "0" : null : raw >= 0 ? raw.toString() : null;
        if (raw != null) {
          if (KEYS[index].equals("handbrake")) value = raw == 1 ? "released" : raw == 2 ? "engaged" : null;
          else if (KEYS[index].equals("power")) value = raw == 1 ? "off" : raw == 2 ? "acc"
              : raw == 3 ? "on" : raw == 4 ? "start" : null;
          else if (KEYS[index].equals("charge")) value = raw == 1 || raw == 2 ? "inactive"
              : raw == 3 || raw == 4 ? "charging" : null;
          else if (KEYS[index].equals("lock")) value = raw == 1 ? "unlocked" : raw == 2 ? "locked" : null;
        }
        result.add(new Protocol.Sample("ICC/VehicleCondition/" + TOPICS[index], IDS[index], "0x0", 0,
            raw == null ? "MISSING" : raw.toString(), receivedAt, "ICC", KEYS[index], value));
      }
      return result;
    }
  }

  static Condition decode(byte[] body) throws Exception {
    if (body == null || body.length == 0 || body.length > MAX_BODY) throw new IOException("body-size");
    Cursor cursor = new Cursor(body);
    Map<Integer, Integer> numbers = new LinkedHashMap<>();
    int fields = 0;
    while (cursor.offset < body.length) {
      if (++fields > 256) throw new IOException("too-many-fields");
      long tag = cursor.varint();
      if (tag <= 0 || tag > 0xffffffffL || tag >>> 3 == 0) throw new IOException("invalid-tag");
      int field = (int) (tag >>> 3), wire = (int) (tag & 7);
      boolean observed = field == 2 || field == 6 || field == 7 || field == 8 || field == 9 || field == 13;
      if (observed && wire != 0) throw new IOException("invalid-field-type");
      if (wire == 0) {
        long value = cursor.varint();
        if (observed) {
          if (value < Integer.MIN_VALUE || value > 0xffffffffL) throw new IOException("integer-overflow");
          if (numbers.put(field, (int) value) != null) throw new IOException("duplicate-field");
        }
      } else if (wire == 1) cursor.skip(8);
      else if (wire == 2) cursor.skip(cursor.varint());
      else if (wire == 5) cursor.skip(4);
      else throw new IOException("unsupported-wire-type");
    }
    if (numbers.isEmpty()) throw new IOException("no-condition-fields");
    JSONObject selected = new JSONObject();
    int[] ids = {2, 6, 7, 8, 9, 13};
    String[] names = {"speedRaw", "handbrakeRaw", "powerRaw", "chargingRaw", "gearRaw", "lockRaw"};
    for (int index = 0; index < ids.length; index++)
      if (numbers.containsKey(ids[index])) selected.put(names[index], numbers.get(ids[index]));
    return new Condition(numbers.get(9), selected);
  }

  private static final class Cursor {
    final byte[] bytes;
    int offset;

    Cursor(byte[] bytes) { this.bytes = bytes; }

    long varint() throws IOException {
      long result = 0;
      for (int index = 0; index < 10; index++) {
        if (offset >= bytes.length) throw new IOException("truncated-varint");
        int next = bytes[offset++] & 255;
        if (index == 9 && next > 1) throw new IOException("varint-overflow");
        result |= (long) (next & 127) << (index * 7);
        if ((next & 128) == 0) return result;
      }
      throw new IOException("varint-overflow");
    }

    void skip(long count) throws IOException {
      if (count < 0 || count > bytes.length - offset) throw new IOException("truncated-field");
      offset += (int) count;
    }
  }
}
