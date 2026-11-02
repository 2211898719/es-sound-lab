package cn.es.soundlab;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Xml;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

final class HidlDiagnostics {
  static final int COMMAND_LIMIT = 512 * 1024, TOTAL_LIMIT = 4 * 1024 * 1024;
  static final String TARGET = "vendor.mega.message_center";
  private static final Pattern EXIT = Pattern.compile("\\nES_HIDL_EXIT=(-?\\d+)\\s*$");
  private static final Pattern CLOCK = Pattern.compile(
      "(?m)^(\\d{9,12}) (\\d{4})-(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.000) ([+-]\\d{4})\\s*$");
  private static final Pattern FILE = Pattern.compile(
      "(?ms)^ES_VINTF_FILE=([^\\r\\n]+)\\r?\\n(.*?)\\r?\\nES_VINTF_FILE_END=(\\d+):[ \\t]*(\\d*)[ \\t]*\\r?$" );
  private static final Pattern FQNAME = Pattern.compile(
      "vendor\\.mega\\.message_center@[^\\s]+::IMessageCenter/[^\\s]+" );

  interface Checkpoint { void save() throws Exception; }
  interface Lookup { JSONObject run(AdbClient client) throws Exception; }

  static boolean loopback(String host) {
    return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
  }

  static JSONObject snapshot() throws Exception {
    return new JSONObject().put("requested", false).put("scope", "shell-evidence-and-app-uid-discovery")
        .put("commands", new JSONObject()).put("sameDeviceVerified", false)
        .put("note", "shell清单与App访问身份不同；看不到不代表整机不存在。日志无匹配不代表没有拒绝。回环地址也不证明未经过转发。");
  }

  static AdbDiagnosticCommands.Command command(String name, String body) {
    return new AdbDiagnosticCommands.Command(name, "(" + body
        + "); es_hidl_rc=$?; printf '\\nES_HIDL_EXIT=%s\\n' \"$es_hidl_rc\"");
  }

  static List<AdbDiagnosticCommands.Command> inventory() {
    List<AdbDiagnosticCommands.Command> result = new ArrayList<>();
    result.add(command("hidl-registered-services", "timeout 6 lshal --types=b --neat -i -t -p"));
    result.add(command("hidl-declared-services", "timeout 6 lshal --types=v --neat -i -t"));
    for (String partition : new String[] {"vendor", "odm", "system", "system_ext", "product"}) {
      String root = "/" + partition + "/etc/vintf";
      String script = "ls -ld " + root + " " + root + "/manifest 2>&1; "
          + "printf 'ES_VINTF_ROOT_RC=%s\\n' \"$?\"; es_files=0; "
          + "for es_file in " + root + "/manifest.xml " + root + "/manifest/*.xml "
          + root + "/manifest/*/*.xml /" + partition + "/manifest.xml; do "
          + "[ -f \"$es_file\" ] || continue; "
          + "if [ \"$es_files\" -ge 8 ]; then printf 'ES_VINTF_FILE_LIMIT=8\\n'; break; fi; "
          + "es_files=$((es_files + 1)); printf 'ES_VINTF_FILE=%s\\n' \"$es_file\"; "
          + "es_bytes=$(wc -c < \"$es_file\" 2>/dev/null); "
          + "head -c 65536 \"$es_file\" 2>&1; es_read_rc=$?; "
          + "printf '\\nES_VINTF_FILE_END=%s:%s\\n' \"$es_read_rc\" \"$es_bytes\"; "
          + "done; printf 'ES_VINTF_FILES=%s\\n' \"$es_files\"";
      result.add(command("hidl-vintf-" + partition, "timeout 6 sh -c " + quote(script)));
    }
    return result;
  }

  private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

  static String outputBody(String output) { return EXIT.matcher(output).replaceFirst(""); }

  static JSONObject status(JSONObject item) throws Exception {
    String raw = item.optString("output"), body = outputBody(raw);
    Matcher exit = EXIT.matcher(raw);
    Integer code = exit.find() ? Integer.valueOf(exit.group(1)) : null;
    String lower = body.toLowerCase(Locale.ROOT);
    String state = !item.optBoolean("transportCompleted") ? "transport-error"
        : item.optBoolean("truncated") ? "truncated"
        : code == null ? "exit-marker-missing"
        : code == 0 ? "collected"
        : code == 124 || code == 137 ? "timeout"
        : code == 127 ? "tool-unavailable"
        : lower.contains("permission denied") || lower.contains("operation not permitted") ? "permission-denied"
        : lower.contains("unknown option") || lower.contains("unrecognized option")
            || lower.contains("invalid option") || lower.contains("unrecognized type") ? "unsupported"
        : "command-error";
    return new JSONObject().put("state", state).put("exitCode", code == null ? JSONObject.NULL : code)
        .put("transportCompleted", item.optBoolean("transportCompleted"))
        .put("truncated", item.optBoolean("truncated"));
  }

  static void record(JSONObject snapshot, JSONObject item) throws Exception {
    JSONObject details = status(item);
    String name = item.getString("name"), body = outputBody(item.optString("output"));
    if (name.equals("hidl-registered-services") || name.equals("hidl-declared-services")) {
      JSONArray observed = new JSONArray();
      for (String line : body.split("\\r?\\n")) {
        Matcher match = FQNAME.matcher(line);
        if (match.find() && observed.length() < 128)
          observed.put(new JSONObject().put("fqname", match.group()).put("raw", clip(line, 1024)));
      }
      details.put("observedTargetRows", observed)
          .put("interpretation", observed.length() == 0 ? "not-observed-not-proof-of-absence" : "rows-observed-not-app-access-proof");
    } else if (name.startsWith("hidl-vintf-")) {
      JSONArray files = new JSONArray();
      Matcher match = FILE.matcher(body);
      while (match.find() && files.length() < 8) {
        String xml = match.group(2), length = match.group(4);
        int readCode = Integer.parseInt(match.group(3));
        JSONObject file = new JSONObject().put("path", match.group(1)).put("readExitCode", readCode)
            .put("fileBytes", length.isEmpty() ? JSONObject.NULL : Long.parseLong(length));
        boolean truncated = !length.isEmpty() && Long.parseLong(length) > 65536;
        file.put("truncated", truncated);
        if (readCode == 0 && !truncated) {
          try { file.put("targetDeclarations", declarations(xml)); }
          catch (Exception error) { file.put("parseError", clip(error.toString(), 1024)); }
        }
        files.put(file);
      }
      details.put("files", files).put("fileLimitReached", body.contains("ES_VINTF_FILE_LIMIT="))
          .put("rootAccessIssue", !body.contains("ES_VINTF_ROOT_RC=0"))
          .put("interpretation", "bounded-manifest-snapshot-not-whole-system-proof");
    }
    snapshot.getJSONObject("commands").put(name, details);
  }

  static JSONArray declarations(String xml) throws Exception {
    String lower = xml.toLowerCase(Locale.ROOT);
    if (lower.contains("<!doctype") || lower.contains("<!entity"))
      throw new IllegalArgumentException("DTD/entity declarations are not accepted");
    XmlPullParser parser = Xml.newPullParser();
    parser.setInput(new StringReader(xml));
    JSONArray result = new JSONArray();
    JSONObject hal = null;
    int depth = -1;
    for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
      if (event == XmlPullParser.START_TAG) {
        String tag = parser.getName();
        if ("hal".equals(tag)) {
          depth = parser.getDepth();
          hal = new JSONObject().put("format", parser.getAttributeValue(null, "format"))
              .put("versions", new JSONArray()).put("interfaces", new JSONArray())
              .put("instances", new JSONArray()).put("fqnames", new JSONArray());
        } else if (hal != null && (tag.equals("name") || tag.equals("version")
            || tag.equals("transport") || tag.equals("instance") || tag.equals("fqname"))) {
          boolean direct = parser.getDepth() == depth + 1;
          String value = parser.nextText().trim();
          if (tag.equals("name") && direct) hal.put("name", value);
          else if (tag.equals("name")) hal.getJSONArray("interfaces").put(value);
          else if (tag.equals("transport")) hal.put("transport", value);
          else hal.getJSONArray(tag.equals("version") ? "versions"
              : tag.equals("instance") ? "instances" : "fqnames").put(value);
        }
      } else if (event == XmlPullParser.END_TAG && parser.getDepth() == depth
          && "hal".equals(parser.getName())) {
        if (hal != null && TARGET.equals(hal.optString("name")) && result.length() < 64) result.put(hal);
        hal = null;
      }
    }
    return result;
  }

  static AdbDiagnosticCommands.Command logs(JSONObject clock) {
    Matcher match = CLOCK.matcher(outputBody(clock.optString("output")));
    String since = match.find() ? " -T " + quote(match.group(3)) : " -t 2000";
    return command("hidl-security-logs", "timeout 6 logcat -d -b all -v epoch" + since
        + " -m 2000 hwservicemanager:V HidlServiceManagement:V ServiceManagement:V"
        + " libhidl:V SELinux:V auditd:V libc:W '*:S'");
  }

  static void logEvidence(JSONObject snapshot, JSONObject item, JSONObject lookup) throws Exception {
    JSONArray denials = new JSONArray();
    int lines = 0, inWindow = 0;
    Pattern timestamp = Pattern.compile("^\\s*(\\d{9,12}\\.\\d+)\\s+");
    for (String line : outputBody(item.optString("output")).split("\\r?\\n")) {
      Matcher time = timestamp.matcher(line);
      if (!time.find()) continue;
      lines++;
      long millis = (long) (Double.parseDouble(time.group(1)) * 1000);
      boolean inTime = lookup.has("startedAt") && millis >= lookup.optLong("startedAt") - 2000
          && millis <= lookup.optLong("finishedAt", lookup.optLong("startedAt")) + 2000;
      if (inTime) inWindow++;
      String lower = line.toLowerCase(Locale.ROOT);
      if ((lower.contains("denied") || lower.contains("cannot find") || lower.contains("not found"))
          && denials.length() < 128) {
        boolean uid = lookup.has("uid") && line.matches(".*\\buid[= ]+" + lookup.optInt("uid") + "\\b.*");
        boolean pid = lookup.has("pid") && line.matches(".*\\bpid[= ]+" + lookup.optInt("pid") + "\\b.*");
        denials.put(new JSONObject().put("raw", clip(line, 2048)).put("at", millis)
            .put("withinAppLookupWindow", inTime).put("mentionsAppIdentity", uid || pid)
            .put("mentionsMessageCenter", lower.contains("message_center") || lower.contains("messagecenter")));
      }
    }
    snapshot.put("logEvidence", new JSONObject().put("parsedLines", lines)
        .put("linesWithinAppLookupWindow", inWindow).put("denialsOrLookupFailures", denials)
        .put("note", "最多2000条匹配日志/128条摘要；轮转、过滤、权限、时钟与转发均可能影响覆盖，未匹配不能排除拒绝。"));
  }

  static JSONObject lookup(AdbClient client) throws Exception {
    Handler main = new Handler(Looper.getMainLooper());
    MessageCenterProbe probe = new MessageCenterProbe(main, new Diagnostics());
    main.post(() -> {
      if (!client.isCancelled()) probe.startDiscoveryOnly();
      else probe.stop("cancelled", "ADB定位已取消，不开始查找");
    });
    long deadline = SystemClock.elapsedRealtime() + 20000;
    try {
      while (SystemClock.elapsedRealtime() < deadline) {
        if (client.isCancelled()) throw new InterruptedIOException("HIDL定位已取消");
        JSONObject report = probe.snapshot();
        if (report.has("startedAt") && !report.optBoolean("active")) return report;
        Thread.sleep(25);
      }
      return probe.snapshot().put("coordinatorTimeout", true);
    } finally {
      main.post(() -> probe.stop("stopped", "联合定位结束或取消；仅发现，无订阅"));
    }
  }

  static int collect(AdbClient client, String host, JSONObject root, AdbClient.Progress progress,
      Checkpoint checkpoint, Lookup lookup) throws Exception {
    JSONObject snapshot = root.getJSONObject("hidlDiagnostics");
    snapshot.put("requested", true).put("startedAt", System.currentTimeMillis())
        .put("loopbackAdbAddress", loopback(host));
    int[] bytes = {0};
    try {
      JSONObject before = run(client, root, command("hidl-device-clock-before",
          "date '+%s %Y-%m-%d %H:%M:%S.000 %z'"), bytes, progress, checkpoint);
      JSONObject appLookup = loopback(host) ? lookup.run(client)
          : new JSONObject().put("phase", "skipped-non-loopback-adb")
              .put("note", "远端ADB与本App可能不是同一设备，不复现本地查找");
      snapshot.put("appLookup", appLookup);
      Matcher clock = CLOCK.matcher(outputBody(before.optString("output")));
      snapshot.put("logsAnchoredToDeviceClock", clock.find());
      if (clock.reset().find()) {
        long device = Long.parseLong(clock.group(1)) * 1000;
        snapshot.put("deviceClockOffsetMillis", device
            - (before.getLong("startedAt") + before.getLong("finishedAt")) / 2);
      }
      checkpoint.save();
      JSONObject logs = run(client, root, logs(before), bytes, progress, checkpoint);
      logEvidence(snapshot, logs, appLookup);
      for (AdbDiagnosticCommands.Command command : inventory())
        run(client, root, command, bytes, progress, checkpoint);
      run(client, root, command("hidl-device-clock-after", "date '+%s %Y-%m-%d %H:%M:%S.000 %z'"),
          bytes, progress, checkpoint);
      snapshot.put("collectionComplete", true);
    } finally {
      snapshot.put("finishedAt", System.currentTimeMillis()).put("outputBytes", bytes[0]);
      checkpoint.save();
    }
    return bytes[0];
  }

  private static JSONObject run(AdbClient client, JSONObject root, AdbDiagnosticCommands.Command command,
      int[] bytes, AdbClient.Progress progress, Checkpoint checkpoint) throws Exception {
    JSONObject item = new JSONObject().put("name", command.name).put("shell", command.shell)
        .put("startedAt", System.currentTimeMillis());
    root.getJSONArray("commands").put(item);
    progress.message("HIDL定点定位 · " + command.name);
    try {
      int room = TOTAL_LIMIT - bytes[0];
      if (room <= 0) { item.put("budgetExhausted", true); throw new IllegalStateException("HIDL输出达到4MiB上限"); }
      AdbClient.Result output = client.shell(command.shell, Math.min(COMMAND_LIMIT, room));
      bytes[0] += output.text.getBytes(StandardCharsets.UTF_8).length;
      item.put("output", output.text).put("truncated", output.truncated).put("transportCompleted", true);
    } catch (Exception error) {
      item.put("transportCompleted", false).put("error", error.toString());
      if (error instanceof AdbClient.ShellException)
        item.put("output", ((AdbClient.ShellException) error).partialOutput);
      throw error;
    } finally {
      JSONObject details = status(item);
      item.put("exitCode", details.opt("exitCode"));
      item.put("finishedAt", System.currentTimeMillis());
      record(root.getJSONObject("hidlDiagnostics"), item);
      checkpoint.save();
    }
    return item;
  }

  static String summary(JSONObject snapshot) {
    if (snapshot == null || !snapshot.optBoolean("requested")) return "HIDL定点定位：本轮未运行";
    JSONObject lookup = snapshot.optJSONObject("appLookup"), commands = snapshot.optJSONObject("commands");
    StringBuilder text = new StringBuilder("HIDL定点定位：")
        .append(snapshot.optBoolean("collectionComplete") ? "采集流程完成（不等于接口可用）" : "部分结果")
        .append("\nApp原UID查找：").append(lookup == null ? "未执行" : lookup.optString("phase"))
        .append(" · 仅发现/核对，未订阅");
    if (lookup != null && lookup.has("error")) text.append("\n").append(lookup.optString("error"));
    if (commands != null) for (String name : new String[] {"hidl-registered-services", "hidl-declared-services", "hidl-security-logs"}) {
      JSONObject item = commands.optJSONObject(name);
      text.append("\n").append(name).append("：").append(item == null ? "未采集" : item.optString("state"));
      if (item != null && item.has("observedTargetRows"))
        text.append(" · 目标行=").append(item.optJSONArray("observedTargetRows").length());
    }
    return text.append("\n清单身份为shell；看不到不代表不存在。请导出本次ADB诊断JSON。" ).toString();
  }

  private static String clip(String value, int limit) { return value.substring(0, Math.min(value.length(), limit)); }
  private HidlDiagnostics() {}
}
