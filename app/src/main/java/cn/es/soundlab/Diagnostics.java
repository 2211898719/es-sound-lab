package cn.es.soundlab;

import android.media.AudioDeviceInfo;
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
  private final ArrayList<String> markers = new ArrayList<>();
  private final ArrayDeque<SignalChange> history = new ArrayDeque<>();
  private final ArrayDeque<SignalChange> events = new ArrayDeque<>();
  private final LinkedHashMap<String, PropertyInfo> properties = new LinkedHashMap<>();
  private long filteredLogs, historyDropped, eventsDropped, propertiesDropped, logQueueDropped;
  private SessionJournal journal;
  public volatile String lockTriggerStatus = "尚未收到中控锁状态";
  public volatile String autoHoldTriggerStatus = "尚未收到 AutoHold 状态";
  public volatile String nativeStatus = "尚未开始车辆监听", logStatus = "尚未开始日志监听", playing = "待命";
  public volatile String nativeSdkStatus = "Mega SDK：尚未加载";
  public volatile String nativeManagerStatus = "属性管理器：尚未连接";
  public volatile boolean diagnosticLogs;
  private final LinkedHashMap<String, JSONObject> nativeProperties = new LinkedHashMap<>();
  public volatile String lockOutputStatus = "车外通道尚未测试";
  public volatile boolean scanning, monitoring;
  public volatile long revision;

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
    if (journal != null) journal.clear();
    lockTriggerStatus = "记录已清空，等待中控锁状态";
    autoHoldTriggerStatus = "记录已清空，等待 AutoHold 状态";
    lines.clear();
    candidates.clear();
    markers.clear();
    history.clear();
    events.clear();
    filteredLogs = 0;
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
    root.put("version", "0.3.0");
    root.put("vehicleProfile", "星纪元 ES / 原 APK 星途 ES 路径");
    root.put("exportedAt", System.currentTimeMillis());
    root.put("manufacturer", Build.MANUFACTURER);
    root.put("model", Build.MODEL);
    root.put("android", Build.VERSION.RELEASE);
    root.put("sdk", Build.VERSION.SDK_INT);
    root.put("readLogsGranted", granted);
    root.put("nativeStatus", nativeStatus);
    root.put("logStatus", logStatus);
    root.put("eventBackend", "Mega native property subscription");
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
    root.put("rules", store.rules());
    root.put("filteredLogs", filteredLogs);
    root.put("logQueueDropped", logQueueDropped);
    root.put("historyDropped", historyDropped);
    root.put("eventHistoryDropped", eventsDropped);
    root.put("propertyCatalogDropped", propertiesDropped);
    root.put("lockOutputStatus", lockOutputStatus);
    root.put("lockTriggerStatus", lockTriggerStatus);
    root.put("autoHoldTriggerStatus", autoHoldTriggerStatus);
    root.put(
        "autoHoldConfiguration",
        new JSONObject()
            .put("calibrated", store.autoHoldCalibrated())
            .put("holdingValue", store.prefs.getString("autohold.holding", ""))
            .put("releasedValue", store.prefs.getString("autohold.released", ""))
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
            .put("volume", store.volume("lock"))
            .put("lockSource", store.source(EventSpec.find("lock.lock")))
            .put("unlockSource", store.source(EventSpec.find("lock.unlock")))
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
