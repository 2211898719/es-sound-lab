package cn.es.soundlab;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Diagnostics {
  private volatile JSONObject engineReading = new JSONObject();
  volatile JSONObject engineDemoReading = new JSONObject();
  volatile JSONObject enginePowertrain = new JSONObject();
  private final ArrayDeque<JSONObject> engineHistory = new ArrayDeque<>();
  private String engineMode = "paused";

  synchronized void engineEvent(String type, String detail) {
    try {
      engineHistory.addLast(new JSONObject().put("time", System.currentTimeMillis())
          .put("type", type).put("detail", detail));
      while (engineHistory.size() > 120) engineHistory.removeFirst();
    } catch (Exception ignored) {}
  }

  synchronized void engineMode(String mode) {
    try { engineReading.put("mode", mode); } catch (Exception ignored) {}
    if (!mode.equals(engineMode)) {
      engineMode = mode;
      engineEvent("模式变化", mode + " · " + engineReading.optString("status")
          + " · " + engineReading.optString("audio"));
    }
  }

  void engineReading(String status, boolean active, EngineMotion motion, float pitch, float gain, float load, String audio) {
    try {
      long elapsed = android.os.SystemClock.elapsedRealtime();
      boolean available = motion.available(elapsed);
      engineReading = new JSONObject().put("status", status).put("estimating", active).put("audio", audio)
          .put("rawKmh", available ? motion.raw() : JSONObject.NULL)
          .put("smoothedKmh", available ? motion.speed() : JSONObject.NULL)
          .put("accelerationMps2", available ? motion.acceleration() : JSONObject.NULL)
          .put("simulatedLoad", available ? load : JSONObject.NULL).put("sampleAgeMillis", motion.age(elapsed))
          .put("pitch", pitch).put("gain", gain).put("updatedAt", System.currentTimeMillis())
          .put("virtualRpm", pitch * 3000).put("virtualRpmReference", 3000)
          .put("upstreamTimestampAvailable", false).put("actualPowerMeasurement", false);
    } catch (Exception ignored) {}
  }

  JSONObject engineReading() { return engineReading; }
  public static final class Candidate {
    public Protocol.Sample sample;
    public long count, changes;
    public final LinkedHashSet<String> values = new LinkedHashSet<>();

    Candidate(Protocol.Sample s) {
      sample = s;
    }

    Candidate(Candidate other) {
      sample = other.sample;
      count = other.count;
      changes = other.changes;
      values.addAll(other.values);
    }
  }

  public static final class Live {
    public final String key, value, source;
    public final long at;

    Live(String k, String v, String s, long a) {
      key = k;
      value = v;
      source = s;
      at = a;
    }
  }

  public static final class StateReading {
    public final Protocol.Sample sample;
    public final long receivedAt, count, changes;

    StateReading(Protocol.Sample sample, StateReading previous) {
      this.sample = sample;
      receivedAt = System.currentTimeMillis();
      count = previous == null ? 1 : previous.count + 1;
      changes = previous == null ? 0 : previous.changes
          + (!previous.sample.raw.equals(sample.raw) || previous.sample.status != sample.status ? 1 : 0);
    }
  }

  private static final class SignalChange {
    final Protocol.Sample sample, previous;

    SignalChange(Protocol.Sample current, Protocol.Sample old) {
      sample = current;
      previous = old;
    }
  }

  private static final class PropertyInfo {
    final String source, type;
    final int id;
    final int[] areas;

    PropertyInfo(String name, int property, int[] zones, String kind) {
      source = name;
      id = property;
      areas = zones.clone();
      type = kind;
    }
  }

  private final ArrayDeque<String> lines = new ArrayDeque<>();
  private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
  private final LinkedHashMap<String, Live> live = new LinkedHashMap<>();
  private final LinkedHashMap<String, StateReading> stateReadings = new LinkedHashMap<>();
  public volatile boolean calibrating;

  public synchronized void stateReading(Protocol.Sample sample) {
    if (VehicleStateSpec.find(sample.key) == null) return;
    stateReadings.put(sample.key, new StateReading(sample, stateReadings.get(sample.key)));
  }

  public synchronized Map<String, StateReading> stateReadings() {
    return new LinkedHashMap<>(stateReadings);
  }
  private final ArrayList<String> markers = new ArrayList<>();
  private final ArrayDeque<SignalChange> history = new ArrayDeque<>();
  private final ArrayDeque<SignalChange> events = new ArrayDeque<>();
  private final LinkedHashMap<String, PropertyInfo> properties = new LinkedHashMap<>();
  private long filteredLogs, historyDropped, eventsDropped, propertiesDropped, logQueueDropped;
  private SessionJournal journal;
  private android.content.Context appContext;
  public volatile String lockTriggerStatus = "尚未收到中控锁状态";
  public volatile String autoHoldTriggerStatus = "尚未收到 AutoHold 状态";
  public volatile String lockFocusResult = "尚未申请";
  public volatile long lockFocusAt;
  public volatile String nativeStatus = "尚未开始车辆监听", logStatus = "尚未开始日志监听", playing = "待命";
  public volatile String nativeSdkStatus = "Mega SDK：尚未加载";
  public volatile String nativeManagerStatus = "属性管理器：尚未连接";
  public volatile boolean diagnosticLogs;
  private final LinkedHashMap<String, JSONObject> nativeProperties = new LinkedHashMap<>();
  public volatile String lockOutputStatus = "车外通道尚未测试";
  public volatile boolean scanning, monitoring;
  public volatile long revision;
  private JSONObject platformProbe = new JSONObject();
  private JSONObject messageCenterProbe = new JSONObject();
  private JSONObject standardCarProbe = new JSONObject();
  private JSONObject fileLog = new JSONObject();
  private JSONObject iccVehicleProbe = new JSONObject();
  private JSONObject iccHubProbe = new JSONObject();
  private JSONObject iccVehicleSource = new JSONObject();
  private long iccSourceSavedAt;
  private long fileLogSavedAt, acceptedLogs, rejectedLogs;
  private CarotaTiming carotaTiming = new CarotaTiming();

  public synchronized void resetCarotaTiming() { carotaTiming = new CarotaTiming(); }

  public synchronized void carotaObserved(Protocol.Sample sample, long baseline, long received) {
    carotaTiming.observed(sample, baseline, received);
  }

  public synchronized void carotaProcessed(long received, long now, boolean expired) {
    carotaTiming.processed(received, now, expired);
  }

  public synchronized JSONObject carotaTimingSnapshot() { return carotaTiming.snapshot(); }

  public synchronized String carotaTimingSummary(long now) { return carotaTiming.summary(now); }

  public synchronized void iccVehicleProbe(JSONObject report) {
    try {
      iccVehicleProbe = new JSONObject(report.toString());
      if (appContext != null) appContext.getSharedPreferences("icc_vehicle_probe", 0).edit()
          .putString("latest", iccVehicleProbe.toString()).apply();
    } catch (Exception ignored) {}
  }

  public synchronized JSONObject iccVehicleProbeSnapshot() {
    try { return new JSONObject(iccVehicleProbe.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public synchronized void iccHubProbe(JSONObject report) {
    try {
      iccHubProbe = new JSONObject(report.toString());
      if (appContext != null) appContext.getSharedPreferences("icc_hub_probe", 0).edit()
          .putString("latest", iccHubProbe.toString()).apply();
    } catch (Exception ignored) {}
  }

  public synchronized JSONObject iccHubProbeSnapshot() {
    try { return new JSONObject(iccHubProbe.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public synchronized String iccHubProbeSummary() {
    if (!iccHubProbe.has("sessionId")) return "尚未定位；点击开始后仅查询一次，不需要挂挡。";
    JSONObject probe = iccHubProbe;
    String phase = probe.optString("phase");
    String label = phase.equals("querying") ? "检测中" : phase.equals("complete") ? "查询完成"
        : phase.equals("target-not-listed") ? "本次未列出ICC" : phase.equals("stopped") ? "已停止"
        : phase.equals("timeout") ? "超时" : phase.equals("previous-session-interrupted") ? "上次检测中断" : "检测未成功";
    StringBuilder text = new StringBuilder("ICC服务定位 · ").append(label)
        .append("\n检测时间 ").append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            .format(new Date(probe.optLong("startedAt"))))
        .append(" · 查询 ").append(probe.optInt("attempts")).append("/1")
        .append(" · 耗时 ").append(probe.optLong("durationMillis")).append(" ms");
    if (probe.has("httpStatus")) text.append(" · HTTP ").append(probe.optInt("httpStatus"));
    JSONArray routes = probe.optJSONArray("routes");
    if (routes != null) for (int index = 0; index < routes.length(); index++) {
      JSONObject route = routes.optJSONObject(index);
      if (route == null) continue;
      String kind = route.optString("addressKind");
      text.append("\n").append(route.optString("module")).append(" → ");
      if (kind.equals("local-module-or-unspecified")) text.append("本地模块或未指定地址（待确认）");
      else if (kind.equals("unrecognized-address-redacted")) text.append("地址格式未识别，未导出原文");
      else text.append(route.optString("address"));
      if (route.has("port")) text.append(" · port ").append(route.optInt("port"));
      if (kind.equals("head-unit-address")) text.append("（车机本机地址）");
      if (kind.equals("other-address-unconfirmed")) text.append("（其他地址，节点归属待确认）");
    }
    if (phase.equals("complete") || phase.equals("target-not-listed")) {
      if (probe.optInt("iccRouteCount") == 0) text.append("\nota_icc_vsi：本次未列出，不代表不存在");
      if (probe.optInt("androidRouteCount") == 0) text.append("\nota_android_vsi：本次未列出");
    }
    JSONObject failure = probe.optJSONObject("failure");
    if (failure != null && failure.has("receivedBodyBytes")) {
      text.append("\n已接收 ").append(failure.optLong("receivedBodyBytes")).append(" 字节");
      if (failure.optLong("declaredBodyBytes", -1) >= 0)
        text.append(" / 声明 ").append(failure.optLong("declaredBodyBytes")).append(" 字节");
      text.append(" · 响应类型 ").append(failure.optString("responseType", "未取得"));
    }
    return text.append("\n").append(probe.optString("detail"))
        .append("\n只保留两项目标路由；这是单次登记快照，不是实时车况。").toString();
  }

  public synchronized void iccVehicleSource(JSONObject report) {
    try {
      boolean wasActive = iccVehicleSource.optBoolean("active");
      iccVehicleSource = new JSONObject(report.toString());
      long now = System.currentTimeMillis();
      if (appContext != null && (wasActive != report.optBoolean("active")
          || !report.optBoolean("active") || now - iccSourceSavedAt >= 5000)) {
        appContext.getSharedPreferences("icc_vehicle_source", 0).edit()
            .putString("latest", iccVehicleSource.toString()).apply();
        iccSourceSavedAt = now;
      }
    } catch (Exception ignored) {}
  }

  public synchronized JSONObject iccVehicleSourceSnapshot() {
    try { return new JSONObject(iccVehicleSource.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public synchronized String iccVehicleSourceSummary() {
    if (!iccVehicleSource.has("sessionId")) return "ICC车况来源：尚未启动；开始监听后查询";
    JSONObject last = iccVehicleSource.optJSONObject("lastSample");
    return "ICC车况来源 · " + (iccVehicleSource.optBoolean("active") ? "监听中" : "已停止")
        + " · " + iccVehicleSource.optString("phase")
        + "\n请求 " + iccVehicleSource.optLong("attempts") + " · HTTP 200 " + iccVehicleSource.optLong("http200")
        + " · 有效挡位 " + iccVehicleSource.optLong("knownGearSamples") + " · 变化 " + iccVehicleSource.optLong("gearChanges")
        + "\n失败 " + iccVehicleSource.optLong("failures") + " · 连续 " + iccVehicleSource.optLong("consecutiveFailures")
        + " · 自动恢复 " + iccVehicleSource.optLong("recoveries")
        + "\n完成后等待 " + iccVehicleSource.optInt("minIntervalMillis") + " ms · 最近查询 "
        + iccVehicleSource.optLong("lastDurationMillis") + " ms · 最近回复挡位 "
        + (last == null ? "未收到" : last.optString("gear")) + "（不保证当前）"
        + "\n" + iccVehicleSource.optString("detail");
  }

  public synchronized String iccVehicleProbeSummary() {
    if (!iccVehicleProbe.has("sessionId")) return "尚未检测；原App身份，只读，不请求ADB/root/READ_LOGS。";
    JSONObject probe = iccVehicleProbe;
    JSONObject last = probe.optJSONObject("lastSample");
    return (probe.optBoolean("active") ? "检测中" : "已停止") + " · " + probe.optString("phase")
        + "\n请求 " + probe.optInt("attempts") + " / 30 · HTTP 200 " + probe.optInt("http200")
        + " · 有效挡位 " + probe.optInt("knownGearSamples") + " · 变化 " + probe.optInt("gearChanges")
        + "\n上次HTTP：" + (probe.has("lastHttpStatus") ? probe.optInt("lastHttpStatus") : "未收到")
        + " · 查询耗时 " + probe.optLong("lastDurationMillis") + " ms"
        + "\n最近快照挡位：" + (last == null ? "未收到" : last.optString("gear"))
        + (last == null ? "" : " · 回复于 " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(new java.util.Date(last.optLong("receivedAt"))) + "（历史回复，不保证当前）")
        + "\n" + probe.optString("detail")
        + "\n固定代理192.168.55.31:51020 → ota_icc_vsi/cdt；回复时间不是信号时间。";
  }

  public synchronized void fileLog(JSONObject report) {
    try {
      String previous = fileLog.optString("phase");
      fileLog = new JSONObject(report.toString());
      long now = System.currentTimeMillis();
      if (appContext != null && (!previous.equals(fileLog.optString("phase")) || now - fileLogSavedAt >= 5000)) {
        appContext.getSharedPreferences("file_log", 0).edit().putString("latest", fileLog.toString()).apply();
        fileLogSavedAt = now;
      }
    } catch (Exception ignored) {}
  }

  public synchronized JSONObject fileLogSnapshot() {
    try { return new JSONObject(fileLog.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public synchronized void logSample(boolean accepted) {
    if (accepted) acceptedLogs++;
    else rejectedLogs++;
  }

  public synchronized String fileLogSummary() {
    StringBuilder text = new StringBuilder(fileLog.optString("path", FileLogReader.PATH))
        .append("\n身份：App原UID；仅只读，不自动回退或申请READ_LOGS")
        .append("\n状态：").append(fileLog.optString("phase", "尚未开始"))
        .append(" · 新增字节 ").append(fileLog.optLong("bytesRead"))
        .append(" · 相关行 ").append(fileLog.optLong("relevantLines"))
        .append("\n本进程全部日志来源：有效新鲜样本 ").append(acceptedLogs)
        .append(" · 未知格式/过期/重复/无映射 ").append(rejectedLogs)
        .append("\n轮转 ").append(fileLog.optLong("rotations"))
        .append(" · 跳过积压/超长行 ").append(fileLog.optLong("skipped"));
    if (fileLog.optString("backend").equals("carota")) {
      text.append("\n最近一次非空读取：").append(fileLog.optLong("lastBatchBytes"))
          .append("字节 / ").append(fileLog.optLong("lastBatchRelevantLines")).append("条车况")
          .append(" · 距上次非空读取 ").append(fileLog.optLong("lastGrowthGapMillis")).append("ms")
          .append("\n").append(carotaTiming.summary(System.currentTimeMillis()));
    }
    if (!fileLog.optString("error").isEmpty()) text.append("\n错误：").append(fileLog.optString("error"));
    return text.toString();
  }

  public synchronized void beginStandardCarProbe(JSONObject report) {
    try {
      standardCarProbe = new JSONObject(report.toString());
      if (appContext != null) appContext.getSharedPreferences("standard_car_probe", 0).edit()
          .putString("latest", standardCarProbe.toString()).apply();
    } catch (Exception ignored) {}
  }

  public synchronized void standardCarProbe(JSONObject report) {
    if (report.optLong("sessionId") == standardCarProbe.optLong("sessionId"))
      beginStandardCarProbe(report);
  }

  public synchronized JSONObject standardCarProbeSnapshot() {
    try { return new JSONObject(standardCarProbe.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public static JSONObject latestStandardCarProbe(android.content.Context context) throws Exception {
    try {
      return new JSONObject(context.getSharedPreferences("standard_car_probe", 0).getString("latest", "{}"));
    } catch (Exception error) { return new JSONObject().put("loadError", error.toString()); }
  }

  public synchronized String standardCarProbeSummary() {
    if (!standardCarProbe.has("sessionId")) return "尚未开始 · 原App身份，只读，不接入音效";
    JSONObject probe = standardCarProbe;
    StringBuilder result = new StringBuilder("状态：").append(probe.optString("phase"))
        .append(" · UID=").append(probe.optInt("uid"));
    JSONObject properties = probe.optJSONObject("properties");
    for (int property : StandardCarProbe.PROPERTIES) {
      JSONObject item = properties == null ? null : properties.optJSONObject(Integer.toString(property));
      if (item == null) continue;
      result.append("\n\n").append(StandardCarProbe.name(property))
          .append(" · 注册返回=").append(item.optString("registerResult", "未返回"))
          .append(" · 当前订阅=").append(item.optBoolean("registered"))
          .append("\n读取成功/尝试=").append(item.optLong("reads")).append("/").append(item.optLong("readAttempts"))
          .append(" · 定时读取=").append(item.optLong("pollReads"))
          .append(" · 读取失败=").append(item.optLong("readFailures"))
          .append("\n读取原值变化=").append(item.optLong("readChanges"))
          .append(" · 回调=").append(item.optLong("callbacks"))
          .append(" · 回调有效变化=").append(item.optLong("changes"))
          .append("\n读取源时间推进=").append(item.optLong("readTimestampAdvances"))
          .append(" · 原值变但源时间未推进=").append(item.optLong("readChangesWithoutTimestampAdvance"));
      for (String key : new String[] {"lastRead", "lastCallback"}) {
        JSONObject sample = item.optJSONObject(key);
        if (sample != null) result.append("\n").append("lastRead".equals(key) ? "读取" : "回调")
            .append("：原值=").append(sample.optString("raw", "null"))
            .append(" / ").append(sample.optString("label"))
            .append(" · ").append(sample.optString("statusLabel"))
            .append(" · 收到于 ").append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
                .format(new Date(sample.optLong("receivedAt"))))
            .append("\n源时间(ns)=").append(sample.optLong("sourceTimestampNanos"))
            .append(" · age(ms)=").append(sample.optString("ageMillis"));
      }
      if (item.optBoolean("readDisabled")) result.append("\n轮询已暂停：").append(item.optString("readDisabledReason"));
      else if (item.has("lastReadSucceeded") && !item.optBoolean("lastReadSucceeded"))
        result.append("\n最新读取失败；上述读取值为上次成功结果，并非新值");
      for (String key : new String[] {"registerError", "readError", "errorCallback", "unregisterError"})
        if (item.has(key)) result.append("\n").append(key).append(": ").append(item.optString(key));
    }
    for (String key : new String[] {"error", "lateError", "failedPhase", "disconnectError", "stopDetail"})
      if (probe.has(key)) result.append("\n").append(key).append(": ").append(probe.optString(key));
    result.append("\n清理：").append(probe.optBoolean("cleanupPending") ? "等待调用返回" :
        probe.optBoolean("cleanupUnknown") ? "上次进程结束，结果未知" : probe.optString("cleanupConfirmed", "未开始"));
    return result.append("\n选择挡位与实际挡位语义不同；注册成功、固定0或有时间戳都不代表实车有效。0次/报错也请导出。 ").toString();
  }

  public synchronized String standardCarProbeLiveSummary() {
    JSONObject probe = standardCarProbe;
    if (!probe.has("sessionId")) return "尚未开始 · 启动后每秒读取，实时对照两路结果";
    boolean active = probe.optBoolean("active");
    long elapsed = active ? Math.max(0, android.os.SystemClock.elapsedRealtime()
        - probe.optLong("startedElapsedMillis", android.os.SystemClock.elapsedRealtime()))
        : probe.optLong("durationMillis");
    long duration = probe.optLong("sessionTimeoutMillis", StandardCarProbe.SESSION_TIMEOUT);
    StringBuilder result = new StringBuilder(active ? "检测中" : "已停止")
        .append(" · 已运行 ").append(elapsed / 1000).append("秒");
    if (active) result.append(" · 剩余 ").append(Math.max(0, duration - elapsed + 999) / 1000).append("秒");
    result.append(probe.has("pollIntervalMillis") ? " · 每1秒读取" : " · 旧会话未启用定时读取");
    JSONObject properties = probe.optJSONObject("properties");
    for (int property : StandardCarProbe.PROPERTIES) {
      JSONObject item = properties == null ? null : properties.optJSONObject(Integer.toString(property));
      if (item == null) continue;
      result.append("\n").append(property == StandardCarProbe.SELECTION ? "选定挡位属性" : "实际挡位属性")
          .append("：读取 ").append(standardCarValue(item.optJSONObject("lastRead")))
          .append(" / 回调 ").append(standardCarValue(item.optJSONObject("lastCallback")))
          .append("\n读取原值变化 ").append(item.optLong("readChanges"))
          .append(" · 回调有效变化 ").append(item.optLong("changes"));
      if (item.optBoolean("readDisabled")) result.append(" · 轮询已暂停，值可能为旧值");
      else if (item.has("lastReadSucceeded") && !item.optBoolean("lastReadSucceeded"))
        result.append(" · 最新读取失败，显示上次值");
    }
    return result.toString();
  }

  private static String standardCarValue(JSONObject sample) {
    if (sample == null) return "等待数据";
    return sample.optString("label") + "（原值" + sample.optString("raw", "null") + "）"
        + (sample.optBoolean("valid") ? "" : "[无效/不可用]");
  }

  public synchronized void beginMessageCenterProbe(JSONObject report) {
    try {
      messageCenterProbe = new JSONObject(report.toString());
      if (appContext != null)
        appContext.getSharedPreferences("message_center_probe", 0).edit()
            .putString("latest", messageCenterProbe.toString()).apply();
    } catch (Exception ignored) {}
  }

  public synchronized void messageCenterProbe(JSONObject report) {
    if (report.optLong("sessionId") == messageCenterProbe.optLong("sessionId"))
      beginMessageCenterProbe(report);
  }

  public synchronized JSONObject messageCenterProbeSnapshot() {
    try { return new JSONObject(messageCenterProbe.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public static JSONObject latestMessageCenterProbe(android.content.Context context) throws Exception {
    try {
      return new JSONObject(context.getSharedPreferences("message_center_probe", 0)
          .getString("latest", "{}"));
    } catch (Exception error) { return new JSONObject().put("loadError", error.toString()); }
  }

  public synchronized String messageCenterProbeSummary() {
    if (!messageCenterProbe.has("sessionId")) return "尚未开始 · 手动订阅，不接入音效";
    JSONObject probe = messageCenterProbe;
    StringBuilder result = new StringBuilder("状态：").append(probe.optString("phase"))
        .append(" · App UID=").append(probe.optInt("uid"))
        .append("\n服务发现：").append(probe.optBoolean("serviceFound") ? "已发现" : "未确认")
        .append(" · 接口核对：").append(probe.optBoolean("interfaceVerified") ? "通过" : "未通过")
        .append("\n订阅返回：").append(probe.optString("subscribeResult", "未返回"))
        .append(" · 回调：").append(probe.optLong("callbackCount"))
        .append(" · 有效标记：").append(probe.optLong("validCallbackCount"))
        .append(" · 变化：").append(probe.optLong("changes"))
        .append("\n无效/解析失败：").append(probe.optLong("invalidCallbackCount"))
        .append(" / ").append(probe.optLong("malformedCallbackCount"))
        .append(" · 丢弃：").append(probe.optLong("droppedCallbacks"));
    JSONObject samples = probe.optJSONObject("samples");
    if (samples != null) for (String topic : MessageCenterProbe.topics()) {
      JSONObject sample = samples.optJSONObject(topic);
      if (sample != null) result.append("\n").append(topic).append("：")
          .append(sample.optBoolean("valid") ? sample.optString("value", "--") : "无效/解析失败")
          .append(" · 回调=").append(sample.optLong("callbackCount"))
          .append(" · 变化=").append(sample.optLong("changes"));
    }
    result.append("\n注销：").append(probe.optString("unsubscribeResult", "未返回/未尝试"))
        .append(probe.optBoolean("cleanupPending") ? "（等待调用结束）" : "");
    if (probe.optBoolean("cleanupUnknown")) result.append("\n上次进程已结束，注销是否完成未知");
    for (String key : new String[] {"failedPhase", "error", "lateError", "unsubscribeError", "stopDetail"})
      if (probe.has(key)) result.append("\n").append(key).append("：").append(probe.optString(key));
    return result.append("\nvalid仅为上游标记；无源时间戳，编码及E0X车型适配未确认。详情随JSON导出。" ).toString();
  }

  public synchronized void beginPlatformProbe(JSONObject report) {
    try {
      platformProbe = new JSONObject(report.toString());
      if (appContext != null)
        appContext.getSharedPreferences("platform_vehicle_probe", 0).edit()
            .putString("latest", platformProbe.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  public synchronized void platformProbe(JSONObject report) {
    if (report.optLong("sessionId") != platformProbe.optLong("sessionId")) return;
    beginPlatformProbe(report);
  }

  public synchronized JSONObject platformProbeSnapshot() {
    try {
      return new JSONObject(platformProbe.toString());
    } catch (Exception ignored) {
      return new JSONObject();
    }
  }

  public static JSONObject latestPlatformProbe(android.content.Context context) throws Exception {
    try {
      return new JSONObject(context.getSharedPreferences("platform_vehicle_probe", 0)
          .getString("latest", "{}"));
    } catch (Exception error) {
      return new JSONObject().put("loadError", error.toString());
    }
  }

  public synchronized String platformProbeSummary() {
    if (!platformProbe.has("sessionId")) return "尚未开始 · 手动启用，只检测挡位，不接入音效";
    return "状态：" + platformProbe.optString("phase")
        + " · App UID=" + platformProbe.optInt("uid")
        + "\n宿主：" + platformProbe.optString("hostPackage", "待确认")
        + " · 服务UID=" + platformProbe.optInt("serviceUid", -1)
        + "\n注册结果：" + platformProbe.optString("registerResult", "未返回")
        + " · ready=" + platformProbe.optLong("readyCount")
        + "\n查询：" + platformProbe.optString("readRaw", "未返回")
        + " / " + platformProbe.optString("readLabel", "") + "（可能为缓存）"
        + "\n挡位回调：" + platformProbe.optLong("gearCallbackCount")
        + " · 变化：" + platformProbe.optLong("callbackChanges")
        + " · 最近值：" + platformProbe.optString("callbackRaw", "无")
        + " / " + platformProbe.optString("callbackLabel", "")
        + "\n注销：" + platformProbe.optString("unregisterResult", "未返回")
        + (platformProbe.optBoolean("cleanupPending") ? "（等待远端调用结束）" : "")
        + (platformProbe.has("stopDetail") ? "\n结束原因：" + platformProbe.optString("stopDetail") : "")
        + (platformProbe.has("readError") ? "\n读取错误：" + platformProbe.optString("readError") : "")
        + (platformProbe.has("registerError") ? "\n注册错误：" + platformProbe.optString("registerError") : "")
        + (platformProbe.has("unregisterError") ? "\n注销错误：" + platformProbe.optString("unregisterError") : "")
        + "\n回调无源时间戳；以上结果不证明四类事件全部可用。错误详情随检测记录导出。";
  }

  private JSONObject nativeProperty(int id, int area) {
    String key = id + ":" + area;
    JSONObject item = nativeProperties.get(key);
    if (item == null) {
      if (nativeProperties.size() >= 512)
        nativeProperties.remove(nativeProperties.keySet().iterator().next());
      item = new JSONObject();
      try {
        item.put("propertyId", id)
            .put("propertyHex", "0x" + Integer.toHexString(id))
            .put("area", area);
      } catch (Exception ignored) {
      }
      nativeProperties.put(key, item);
    }
    return item;
  }

  public synchronized void resetNativeProperties() {
    nativeProperties.clear();
  }

  public synchronized void nativeSubscription(int id, boolean registered, String detail) {
    JSONObject item = nativeProperty(id, -1);
    try {
      item.put("registered", registered).put("subscription", detail);
    } catch (Exception ignored) {
    }
  }

  public synchronized void nativeSample(
      int id, int area, long timestamp, boolean callback, int status, String raw) {
    JSONObject item = nativeProperty(id, area);
    try {
      item.put("timestamp", timestamp)
          .put("status", status)
          .put("raw", raw.length() > 500 ? raw.substring(0, 500) : raw)
          .put("receivedUptimeMillis", android.os.SystemClock.uptimeMillis())
          .put("receivedElapsedNanos", android.os.SystemClock.elapsedRealtimeNanos())
          .put("readError", "")
          .put("connected", true);
      if (callback) item.put("callbackCount", item.optLong("callbackCount") + 1);
      else item.put("readCount", item.optLong("readCount") + 1);
    } catch (Exception ignored) {
    }
  }

  public synchronized void nativePropertyError(int id, int area, String detail) {
    try {
      nativeProperty(id, area).put("readError", detail);
    } catch (Exception ignored) {
    }
  }

  public synchronized void nativeDisconnected() {
    for (JSONObject item : nativeProperties.values())
      try {
        item.put("connected", false).put("registered", false);
      } catch (Exception ignored) {
      }
  }

  public synchronized String nativeSummary() {
    StringBuilder text =
        new StringBuilder(nativeSdkStatus).append('\n').append(nativeManagerStatus);
    for (JSONObject item : nativeProperties.values()) {
      text.append("\n").append(item.optString("propertyHex"));
      if (item.optInt("area") == -1) text.append(" · ").append(item.optString("subscription"));
      else
        text.append(" / ")
            .append(item.optInt("area"))
            .append(" · 回调 ")
            .append(item.optLong("callbackCount"))
            .append(" · 读取 ")
            .append(item.optLong("readCount"))
            .append(" · status=")
            .append(item.optInt("status", -1))
            .append(" · 值=")
            .append(item.optString("raw", "未知"))
            .append(
                item.optString("readError").isEmpty() ? "" : " · " + item.optString("readError"));
    }
    return text.toString();
  }

  public synchronized void attach(android.content.Context context) {
    appContext = context.getApplicationContext();
    try {
      iccHubProbe = new JSONObject(appContext.getSharedPreferences("icc_hub_probe", 0).getString("latest", "{}"));
      if (iccHubProbe.optBoolean("active")) {
        iccHubProbe.put("active", false).put("inFlight", false)
            .put("phase", "previous-session-interrupted").put("detail", "上次定位被中断；不会自动恢复或重试");
        appContext.getSharedPreferences("icc_hub_probe", 0).edit()
            .putString("latest", iccHubProbe.toString()).apply();
      }
    } catch (Exception ignored) { iccHubProbe = new JSONObject(); }
    try {
      iccVehicleSource = new JSONObject(appContext.getSharedPreferences("icc_vehicle_source", 0).getString("latest", "{}"));
      if (iccVehicleSource.optBoolean("active")) {
        iccVehicleSource.put("active", false).put("inFlight", false)
            .put("phase", "previous-session-interrupted").put("detail", "上次ICC监听中断；历史快照不恢复为实时状态");
        appContext.getSharedPreferences("icc_vehicle_source", 0).edit()
            .putString("latest", iccVehicleSource.toString()).apply();
      }
      iccVehicleProbe = new JSONObject(appContext.getSharedPreferences("icc_vehicle_probe", 0).getString("latest", "{}"));
      if (iccVehicleProbe.optBoolean("active")) {
        iccVehicleProbe.put("active", false).put("inFlight", false)
            .put("phase", "previous-session-interrupted").put("detail", "上次检测被中断；未自动恢复请求");
        appContext.getSharedPreferences("icc_vehicle_probe", 0).edit()
            .putString("latest", iccVehicleProbe.toString()).apply();
      }
      fileLog = new JSONObject(appContext.getSharedPreferences("file_log", 0).getString("latest", "{}"));
      if (fileLog.has("phase") && !fileLog.optString("phase").equals("failed")
          && !fileLog.optString("phase").equals("stopped"))
        fileLog.put("phase", "previous-session-interrupted");
      platformProbe = latestPlatformProbe(appContext);
      messageCenterProbe = latestMessageCenterProbe(appContext);
      standardCarProbe = latestStandardCarProbe(appContext);
      if (standardCarProbe.optBoolean("active") || standardCarProbe.optBoolean("cleanupPending")) {
        standardCarProbe.put("active", false).put("phase", "previous-session-interrupted")
            .put("cleanupPending", false).put("cleanupUnknown", true).put("cleanupConfirmed", false)
            .put("pollInFlight", false);
        JSONObject properties = standardCarProbe.optJSONObject("properties");
        if (properties != null) for (int property : StandardCarProbe.PROPERTIES) {
          JSONObject item = properties.optJSONObject(Integer.toString(property));
          if (item != null) item.put("registered", false);
        }
        appContext.getSharedPreferences("standard_car_probe", 0).edit()
            .putString("latest", standardCarProbe.toString()).apply();
      }
      if (messageCenterProbe.optBoolean("active") || messageCenterProbe.optBoolean("cleanupPending")) {
        messageCenterProbe.put("active", false).put("registered", false)
            .put("phase", "previous-session-interrupted").put("cleanupUnknown", true)
            .put("cleanupPending", false);
        appContext.getSharedPreferences("message_center_probe", 0).edit()
            .putString("latest", messageCenterProbe.toString()).apply();
      }
      if (platformProbe.optBoolean("active")) {
        platformProbe.put("active", false).put("connected", false).put("registered", false)
            .put("phase", "previous-session-interrupted").put("cleanupUnknown", true);
        appContext.getSharedPreferences("platform_vehicle_probe", 0).edit()
            .putString("latest", platformProbe.toString()).apply();
      }
    } catch (Exception ignored) {
    }
    journal = new SessionJournal(context);
  }

  public synchronized void lockDecision(String message) {
    if (message.equals(lockTriggerStatus)) return;
    lockTriggerStatus = message;
    line("锁车触发", message);
  }

  public synchronized void autoHoldDecision(String message) {
    if (message.equals(autoHoldTriggerStatus)) return;
    autoHoldTriggerStatus = message;
    line("AutoHold触发", message);
  }

  public synchronized void line(String type, String message) {
    if (journal != null
        && (type.equals("生命周期")
            || type.equals("日志通道")
            || type.equals("锁车触发")
            || type.equals("ICC锁对照")
            || type.equals("ICC重连")
            || type.equals("车外播放")
            || type.equals("播放失败")
            || type.equals("播放跳过"))) journal.record(type, message);
    if (message.length() > 3000) message = message.substring(0, 3000);
    lines.addLast(
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(new Date())
            + "  ["
            + type
            + "] "
            + message);
    while (lines.size() > 600) lines.removeFirst();
    revision++;
  }

  public synchronized void sample(Protocol.Sample s) {
    Candidate c = candidates.get(s.identity());
    Protocol.Sample previous = c == null ? null : c.sample;
    if (previous == null || previous.status != s.status || !previous.raw.equals(s.raw)) {
      SignalChange change = new SignalChange(s, previous);
      history.addLast(change);
      if (s.key != null && !s.key.equals("speed")) {
        events.addLast(change);
        if (events.size() > 400) {
          events.removeFirst();
          eventsDropped++;
        }
      }
      if (history.size() > 1200) {
        history.removeFirst();
        historyDropped++;
      }
    }
    if (c == null) {
      if (candidates.size() >= 180) candidates.remove(candidates.keySet().iterator().next());
      c = new Candidate(s);
      candidates.put(s.identity(), c);
    } else if (!c.sample.raw.equals(s.raw)) c.changes++;
    c.sample = s;
    c.count++;
    c.values.add(s.raw);
    while (c.values.size() > 12) c.values.remove(c.values.iterator().next());
    revision++;
  }

  public synchronized void live(String key, String value, String source) {
    live.put(key, new Live(key, value, source, System.currentTimeMillis()));
    revision++;
  }

  public synchronized List<Candidate> candidates() {
    List<Candidate> a = new ArrayList<>();
    for (Candidate c : candidates.values()) a.add(new Candidate(c));
    return a;
  }

  public synchronized Map<String, Live> live() {
    return new LinkedHashMap<>(live);
  }

  public synchronized String lines(String filter) {
    StringBuilder b = new StringBuilder();
    String f = filter.toLowerCase(Locale.ROOT);
    for (String s : lines)
      if (f.isEmpty() || s.toLowerCase(Locale.ROOT).contains(f)) b.append(s).append('\n');
    return b.toString();
  }

  public synchronized void marker(String text) {
    String s =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(new Date())
            + "  "
            + text;
    markers.add(s);
    while (markers.size() > 60) markers.remove(0);
    line("操作标记", text);
  }

  public synchronized void clear() {
    iccHubProbe = new JSONObject();
    if (appContext != null) appContext.getSharedPreferences("icc_hub_probe", 0).edit().clear().apply();
    iccVehicleSource = new JSONObject();
    iccSourceSavedAt = 0;
    if (appContext != null) appContext.getSharedPreferences("icc_vehicle_source", 0).edit().clear().apply();
    iccVehicleProbe = new JSONObject();
    if (appContext != null) appContext.getSharedPreferences("icc_vehicle_probe", 0).edit().clear().apply();
    if (journal != null) journal.clear();
    lockTriggerStatus = "记录已清空，等待中控锁状态";
    autoHoldTriggerStatus = "记录已清空，等待 AutoHold 状态";
    lockFocusResult = "记录已清空，尚未申请";
    lockFocusAt = 0;
    lines.clear();
    candidates.clear();
    stateReadings.clear();
    markers.clear();
    history.clear();
    events.clear();
    engineHistory.clear();
    engineMode = "paused";
    filteredLogs = 0;
    acceptedLogs = 0;
    rejectedLogs = 0;
    resetCarotaTiming();
    logQueueDropped = 0;
    historyDropped = 0;
    eventsDropped = 0;
    revision++;
  }

  public synchronized void resetLive() {
    live.clear();
    revision++;
  }

  public synchronized void removeLive(String key) {
    live.remove(key);
    revision++;
  }

  public synchronized void filteredLog() {
    filteredLogs++;
  }

  public synchronized void droppedLog() {
    logQueueDropped++;
  }

  public synchronized String recordingStatus() {
    return "已过滤 "
        + filteredLogs
        + " 条噪声 · 队列丢弃 "
        + logQueueDropped
        + " 条 · 变化记录 "
        + history.size()
        + " 条 · 关键记录 "
        + events.size()
        + " 条 · 已淘汰 "
        + historyDropped
        + "/"
        + eventsDropped
        + " 条";
  }

  public synchronized void property(String source, int id, int[] areas, String type) {
    String key = source + "|" + id;
    if (!properties.containsKey(key) && properties.size() >= 2048) {
      properties.remove(properties.keySet().iterator().next());
      propertiesDropped++;
    }
    properties.put(key, new PropertyInfo(source, id, areas, type));
  }

  public synchronized String export(boolean granted, Store store) throws Exception {
    JSONObject root = new JSONObject();
    root.put("schemaVersion", 2);
    root.put("application", "cn.es.soundlab");
    root.put("version", "0.3.35");
    root.put("vehicleProfile", "星纪元 ES / 原 APK 星途 ES 路径");
    root.put("exportedAt", System.currentTimeMillis());
    root.put("manufacturer", Build.MANUFACTURER);
    root.put("model", Build.MODEL);
    root.put("android", Build.VERSION.RELEASE);
    root.put("sdk", Build.VERSION.SDK_INT);
    root.put("readLogsGranted", granted);
    root.put("nativeStatus", nativeStatus);
    root.put("platformVehicleProbe", platformProbeSnapshot());
    root.put("messageCenterProbe", messageCenterProbeSnapshot());
    root.put("standardCarProbe", standardCarProbeSnapshot());
    root.put("iccVehicleProbe", iccVehicleProbeSnapshot());
    root.put("iccHubProbe", iccHubProbeSnapshot());
    root.put("iccVehicleSource", iccVehicleSourceSnapshot());
    root.put("engineSimulation", new JSONObject().put("enabled", store.enabled("engine"))
        .put("profile", store.engineProfile().json()).put("live", engineReading())
        .put("demo", engineDemoReading)
        .put("mode", store.engineSynth() ? "multilayer-synth" : "custom-loop")
        .put("renderer", store.engineSynth() ? "es-independent-seven-layer-pcm-v1" : "continuous-pcm48k-stereo-interpolated-phase-loop")
        .put("synthesis", store.engineTuning().json()).put("powertrain", enginePowertrain)
        .put("masterVolumePercent", store.volume("engine")).put("idleVolumePercent", store.engineIdleVolume())
        .put("selectedOutputId", store.prefs.getInt("output", -1))
        .put("history", new JSONArray(engineHistory))
        .put("sourceCredits", store.engineSynth() ? "ES independent synthesis; no third-party audio or website code bundled"
            : "CC0: domasx2 / Racing car engine sound loops / loop_0.wav")
        .put("loopSpliceMaxMillis", store.engineSynth() ? 0 : 30)
        .put("grainBlendMillis", store.engineSynth() ? 100 : 0)
        .put("previewAttackMillis", store.engineSynth() ? 60 : 25).put("previewReleaseMillis", store.engineSynth() ? 160 : 80)
        .put("previewSilentTailMillis", 100).put("parameterSmoothingMillis", store.engineSynth() ? 45 : 35)
        .put("speedMaxAgeMillis", EngineMotion.MAX_AGE).put("requiresGear", "D")
        .put("idlePolicy", "synth-only-fresh-ICC-power-on-or-start-P-N-D-and-zero-or-confirmed-omitted-speed")
        .put("outputPolicy", "normal-selected-output-not-factory-ASD-or-AVAS"));
    root.put("iccConfiguration", new JSONObject().put("intervalMillis", store.iccIntervalMillis())
        .put("lockConfirmed", store.iccTriggerConfirmed("lock"))
        .put("lockRequiresPriorParkBaseline", true)
        .put("lockGearMaxAgeMillis", 5000)
        .put("lockUsage", ExteriorAudio.USAGE)
        .put("lockBusAddress", ExteriorAudio.ADDRESS)
        .put("lockPlayerGainPercent", store.volume("lock"))
        .put("lockSystemVolumePolicy", "share-factory-usage2007-group-no-global-write")
        .put("chargeConfirmed", store.iccTriggerConfirmed("charge"))
        .put("speedUnitConfirmed", true).put("speedUnit", "km/h").put("speedConfirmation", "user-verified-this-vehicle")
        .put("handbrakeIsAutoHold", false));
    root.put("logStatus", logStatus);
    root.put("fileLog", fileLogSnapshot());
    root.put("carotaTiming", carotaTimingSnapshot());
    root.put("acceptedLogSamples", acceptedLogs);
    root.put("rejectedLogSamples", rejectedLogs);
    if (appContext != null) {
      try {
        JSONObject adb = AdbDiagnostics.latest(appContext);
        if (adb != null) root.put("adbDiagnostics", adb);
      } catch (Exception error) { root.put("adbDiagnosticsError", error.toString()); }
    }
    root.put("eventBackend", store.logsEnabled() ? "icc+authorized-log" : "icc");
    root.put("authorizedLogEnabled", store.logsEnabled());
    JSONArray interfaceKeys = new JSONArray().put("gear").put("speed").put("handbrake").put("power").put("charge");
    JSONArray logKeys = new JSONArray().put("door.front_left").put("door.front_right").put("door.rear_left").put("door.rear_right").put("tailgate").put("autohold");
    if (store.logLockSelected()) logKeys.put("lock"); else interfaceKeys.put("lock");
    root.put("sourcePolicy", new JSONObject()
        .put("interface", interfaceKeys).put("authorizedLog", logKeys)
        .put("lockTriggerSource", store.logLockSelected() ? "authorized-log" : "icc")
        .put("automaticFallback", false));
    root.put("calibrating", calibrating);
    JSONObject calibrations = new JSONObject();
    JSONArray currentStates = new JSONArray();
    for (VehicleStateSpec spec : VehicleStateSpec.ALL) {
      JSONObject configuration = store.stateCalibration(spec.key);
      if (configuration.length() != 0) calibrations.put(spec.key, configuration);
      StateReading reading = stateReadings.get(spec.key);
      if (reading != null) {
        Protocol.Sample sample = reading.sample;
        currentStates.put(new JSONObject().put("key", spec.key).put("raw", sample.raw)
            .put("value", sample.value == null ? JSONObject.NULL : sample.value)
            .put("status", sample.status).put("source", sample.source).put("topic", sample.topic)
            .put("propertyId", sample.propertyId).put("area", sample.area).put("sourceTime", sample.time)
            .put("receivedAt", reading.receivedAt).put("count", reading.count).put("changes", reading.changes)
            .put("live", live.containsKey(spec.key)));
      }
    }
    root.put("stateCalibrations", calibrations);
    root.put("currentStates", currentStates);
    StateReading speedReading = stateReadings.get("speed");
    Live speedLive = live.get("speed");
    Protocol.Sample speedSample = speedReading == null ? null : speedReading.sample;
    boolean freshSpeed = speedReading != null && IccSpeedReading.fresh(speedSample,
        monitoring && speedLive != null && speedLive.source.equals("ICC"),
        speedReading.receivedAt, System.currentTimeMillis());
    JSONObject speedComparison = IccSpeedReading.export(speedSample, freshSpeed);
    speedComparison.put("automaticAudio", store.enabled("engine"));
    if (speedReading != null) speedComparison.put("replyReceivedAt", speedSample.time)
        .put("processedAt", speedReading.receivedAt);
    root.put("iccSpeedComparison", speedComparison);
    root.put("diagnosticLogs", diagnosticLogs);
    root.put(
        "megaGearEncoding",
        store.prefs.getBoolean("mega.gear.sdkEnums", false) ? "sdk" : "es-observed");
    root.put("nativeSdkStatus", nativeSdkStatus);
    root.put("nativeManagerStatus", nativeManagerStatus);
    JSONArray nativeItems = new JSONArray();
    for (JSONObject item : nativeProperties.values())
      nativeItems.put(new JSONObject(item.toString()));
    root.put("nativeSubscriptions", nativeItems);
    root.put("monitoring", monitoring);
    root.put("discovery", scanning);
    JSONArray cs = new JSONArray();
    for (Candidate c : candidates.values()) {
      JSONObject j = new JSONObject();
      j.put("source", c.sample.source);
      j.put("topic", c.sample.topic);
      j.put("propertyId", c.sample.propertyId);
      j.put("propertyHex", "0x" + Long.toHexString(c.sample.propertyId));
      j.put("area", c.sample.area);
      j.put("status", c.sample.status);
      j.put("lastValue", c.sample.raw);
      j.put("lastSeen", c.sample.time);
      j.put("count", c.count);
      j.put("changes", c.changes);
      j.put("observedValues", new JSONArray(c.values));
      j.put("mappedKey", c.sample.key);
      cs.put(j);
    }
    root.put("candidates", cs);
    root.put("markers", new JSONArray(markers));
    root.put("lines", new JSONArray(lines));
    root.put("filteredLogs", filteredLogs);
    root.put("logQueueDropped", logQueueDropped);
    root.put("historyDropped", historyDropped);
    root.put("eventHistoryDropped", eventsDropped);
    root.put("propertyCatalogDropped", propertiesDropped);
    root.put("lockOutputStatus", lockOutputStatus);
    root.put("lockAudioFocus", new JSONObject().put("lastResult", lockFocusResult)
        .put("requestedAt", lockFocusAt).put("usage", ExteriorAudio.USAGE)
        .put("gain", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK));
    root.put("lockTriggerStatus", lockTriggerStatus);
    root.put("autoHoldTriggerStatus", autoHoldTriggerStatus);
    root.put(
        "autoHoldConfiguration",
        new JSONObject()
            .put("calibrated", store.autoHoldCalibrated())
            .put("holdingValue", store.prefs.getString("autohold.holding", "2"))
            .put("releasedValue", store.prefs.getString("autohold.released", "1"))
            .put("enabled", store.enabled("autohold"))
            .put("volume", store.volume("autohold"))
            .put("holdingSource", store.source(EventSpec.find("autohold.holding")))
            .put("releasedSource", store.source(EventSpec.find("autohold.released"))));
    root.put("lockReadiness", store.lockReadiness());
    if (journal != null) {
      root.put("runtime", journal.snapshot());
      root.put("sessionHistory", journal.export());
    }
    JSONArray outputs = new JSONArray();
    try {
      for (AudioDeviceInfo device : store.audioOutputs())
        outputs.put(
            new JSONObject()
                .put("id", device.getId())
                .put("type", device.getType())
                .put("name", String.valueOf(device.getProductName()))
                .put("address", Build.VERSION.SDK_INT >= 28 ? device.getAddress() : ""));
    } catch (Exception error) {
      root.put("audioOutputError", error.toString());
    }
    root.put("audioOutputs", outputs);
    root.put(
        "lockConfiguration",
        new JSONObject()
            .put("enabled", store.enabled("lock"))
            .put("triggerSource", store.logLockSelected() ? "authorized-log" : "icc")
            .put("triggerCalibrated", store.lockTriggerCalibrated())
            .put("volume", store.volume("lock"))
            .put("lockSource", store.source(EventSpec.find("lock.lock")))
            .put("unlockSource", store.source(EventSpec.find("lock.unlock")))
            .put("routingPolicy", "factory-bus-exact-address-no-cabin-fallback")
            .put("targetUsage", ExteriorAudio.USAGE)
            .put("targetAddress", ExteriorAudio.ADDRESS)
            .put("requiresOutputConfirmation", false)
            .put("requiresPriorParkBaseline", true)
            .put("legacyOutputSettingsIgnored", true)
            .put("lockedValue", store.prefs.getString("lock.locked", ""))
            .put("unlockedValue", store.prefs.getString("lock.unlocked", ""))
            .put("outputId", store.prefs.getInt("lock.output.id", -1))
            .put("outputType", store.prefs.getInt("lock.output.type", -1))
            .put("outputName", store.prefs.getString("lock.output.name", ""))
            .put("outputAddress", store.prefs.getString("lock.output.address", ""))
            .put("outputConfirmed", store.prefs.getBoolean("lock.output.confirmed", false)));
    root.put("signalHistory", exportHistory(history));
    root.put("eventHistory", exportHistory(events));
    JSONArray catalog = new JSONArray();
    for (PropertyInfo property : properties.values()) {
      JSONArray areas = new JSONArray();
      for (int area : property.areas) areas.put(area);
      catalog.put(
          new JSONObject()
              .put("source", property.source)
              .put("propertyId", property.id)
              .put("areas", areas)
              .put("type", property.type));
    }
    root.put("propertyCatalog", catalog);
    return root.toString(2);
  }

  private JSONArray exportHistory(Iterable<SignalChange> entries) throws Exception {
    JSONArray changes = new JSONArray();
    for (SignalChange change : entries) {
      Protocol.Sample sample = change.sample, previous = change.previous;
      changes.put(
          new JSONObject()
              .put("time", sample.time)
              .put("source", sample.source)
              .put("topic", sample.topic)
              .put("propertyId", sample.propertyId)
              .put("area", sample.area)
              .put("status", sample.status)
              .put("raw", sample.raw)
              .put("mappedKey", sample.key == null ? JSONObject.NULL : sample.key)
              .put("value", sample.value == null ? JSONObject.NULL : sample.value)
              .put("baseline", previous == null)
              .put("previousRaw", previous == null ? JSONObject.NULL : previous.raw)
              .put("previousStatus", previous == null ? JSONObject.NULL : previous.status));
    }
    return changes;
  }
}
