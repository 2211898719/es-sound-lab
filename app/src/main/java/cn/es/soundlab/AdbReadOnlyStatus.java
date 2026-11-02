package cn.es.soundlab;

import java.util.Locale;
import org.json.JSONObject;

final class AdbReadOnlyStatus {
  static JSONObject snapshot() throws Exception {
    return new JSONObject().put("scope", "read-only-snapshot")
        .put("vehicleDebug", new JSONObject().put("state", "not-collected"))
        .put("properties", new JSONObject()).put("network", new JSONObject())
        .put("lanReachabilityVerified", false)
        .put("note", "属性快照不是授权或实时车况验证；监听地址不代表局域网可达。空值不等于false，不写任何开关。");
  }

  static void record(JSONObject snapshot, String name, String shell, String output,
      boolean truncated, boolean completed) throws Exception {
    if (shell.startsWith("getprop ")) {
      String property = shell.substring(8);
      JSONObject value = new JSONObject().put("raw", output)
          .put("state", !completed ? "transport-error" : truncated ? "truncated" : "unrecognized");
      String normalized = output.trim().toLowerCase(Locale.ROOT);
      if (completed && !truncated) {
        if (normalized.isEmpty()) value.put("state", "empty");
        else if (property.equals("persist.mega.car.userdebug")) {
          if (normalized.matches("1|true|y|yes|on"))
            value.put("state", "true").put("value", true);
          else if (normalized.matches("0|false|n|no|off"))
            value.put("state", "false").put("value", false);
        } else if (!normalized.contains("\n") && !normalized.contains("denied")
            && !normalized.contains("error") && !normalized.contains("not found"))
          value.put("state", "value").put("value", output.trim());
      }
      snapshot.getJSONObject("properties").put(property, value);
      if (property.equals("persist.mega.car.userdebug")) snapshot.put("vehicleDebug", value);
    } else if (name.startsWith("network-")) {
      snapshot.getJSONObject("network").put(name, new JSONObject().put("raw", output)
          .put("transportCompleted", completed).put("truncated", truncated)
          .put("interpretation", "raw-output-only"));
    }
  }

  static String summary(JSONObject snapshot) {
    JSONObject debug = snapshot == null ? null : snapshot.optJSONObject("vehicleDebug");
    String state = debug == null ? "not-collected" : debug.optString("state");
    String label = state.equals("true") ? "读到true（不等于车辆接口已通过）"
        : state.equals("false") ? "读到false（未修改）"
        : state.equals("empty") ? "空值，不能当作关闭或未配置的确定证据"
        : state.equals("truncated") ? "输出截断，无法判断"
        : state.equals("transport-error") ? "传输失败，无法判断"
        : state.equals("not-collected") ? "尚未采集" : "返回值无法识别，检查原始输出";
    return "车辆调试属性：" + label + "\n网络ADB：只读端口/地址快照，尚未验证外部设备可达";
  }

  private AdbReadOnlyStatus() {}
}
