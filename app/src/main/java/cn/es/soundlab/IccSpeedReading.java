package cn.es.soundlab;

import org.json.JSONObject;

final class IccSpeedReading {
  static Integer kmh(Protocol.Sample sample) {
    if (!IccVehicleWire.state(sample) || !"speed".equals(sample.key) || sample.value == null) return null;
    try {
      int value = Integer.parseInt(sample.value);
      return value >= 0 ? value : null;
    } catch (NumberFormatException invalid) { return null; }
  }

  static boolean omittedZero(Protocol.Sample sample) {
    return sample != null && "MISSING".equals(sample.raw) && Integer.valueOf(0).equals(kmh(sample));
  }

  static boolean fresh(Protocol.Sample sample, boolean active, long received, long now) {
    return active && kmh(sample) != null && now >= received && now - received <= 5000
        && now >= sample.time && now - sample.time <= 5000;
  }

  static String display(Protocol.Sample sample, boolean fresh) {
    Integer speed = kmh(sample);
    if (speed == null) return "车速不可用 · 不补0";
    if (!fresh) return "等待新鲜ICC回复 · 历史值不作当前车速";
    return omittedZero(sample) ? "0 km/h · 本车静止时省略字段\n原报文字段仍记录为缺失"
        : speed + " km/h · 原值直读（本车已核对）";
  }

  static JSONObject export(Protocol.Sample sample, boolean fresh) throws Exception {
    Integer speed = kmh(sample);
    return new JSONObject().put("confirmed", true).put("confirmation", "user-verified-this-vehicle")
        .put("unit", "km/h").put("scale", 1).put("automaticAudio", false)
        .put("raw", sample == null ? JSONObject.NULL : sample.raw)
        .put("kmh", speed == null ? JSONObject.NULL : speed)
        .put("omittedZero", omittedZero(sample)).put("freshReply", speed != null && fresh)
        .put("upstreamTimestampAvailable", false);
  }
}
