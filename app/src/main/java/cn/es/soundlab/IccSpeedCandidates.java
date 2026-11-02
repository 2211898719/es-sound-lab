package cn.es.soundlab;

import java.math.BigDecimal;
import org.json.JSONObject;

final class IccSpeedCandidates {
  static final long MAX_AGE_MILLIS = 5000;

  static Long raw(Protocol.Sample sample) {
    if (!IccVehicleWire.state(sample) || !"speed".equals(sample.key)
        || sample.raw == null || !sample.raw.matches("[0-9]{1,10}")) return null;
    try {
      long value = Long.parseLong(sample.raw);
      return value <= Integer.MAX_VALUE ? value : null;
    } catch (NumberFormatException invalid) { return null; }
  }

  private static BigDecimal[] values(long raw) {
    BigDecimal number = BigDecimal.valueOf(raw);
    return new BigDecimal[] {number, number.movePointLeft(1), number.movePointLeft(2),
        number.multiply(new BigDecimal("3.6"))};
  }

  static boolean fresh(Protocol.Sample sample, boolean active, long receivedAt, long now) {
    return active && raw(sample) != null && now >= receivedAt && now - receivedAt <= MAX_AGE_MILLIS
        && now >= sample.time && now - sample.time <= MAX_AGE_MILLIS;
  }

  static String display(Protocol.Sample sample, boolean fresh) {
    Long raw = raw(sample);
    if (raw == null) return "候选换算：—（无有效ICC原值）";
    if (!fresh) return "候选换算：—（等待新鲜ICC回复）";
    BigDecimal[] values = values(raw);
    return "候选 km/h · 均待核实\nA ×1：" + format(values[0])
        + "\nB ÷10：" + format(values[1]) + "\nC ÷100：" + format(values[2])
        + "\nD ×3.6：" + format(values[3]) + "\nD假设原值为m/s；不触发音效";
  }

  private static String format(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

  static JSONObject export(Protocol.Sample sample, boolean fresh) throws Exception {
    JSONObject result = new JSONObject().put("confirmed", false).put("automaticAudio", false)
        .put("candidateUnit", "km/h").put("upstreamTimestampAvailable", false)
        .put("raw", sample == null || sample.raw == null ? JSONObject.NULL : sample.raw);
    Long raw = raw(sample);
    result.put("validRaw", raw != null).put("freshReply", raw != null && fresh);
    JSONObject candidates = new JSONObject();
    if (raw != null) {
      BigDecimal[] values = values(raw);
      candidates.put("A_raw_as_kmh", values[0]).put("B_raw_div_10", values[1])
          .put("C_raw_div_100", values[2]).put("D_raw_mps_times_3_6", values[3]);
    }
    return result.put("candidates", candidates);
  }
}
