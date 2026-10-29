package cn.es.soundlab;

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

  private final ArrayDeque<String> lines = new ArrayDeque<>();
  private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
  private final LinkedHashMap<String, Live> live = new LinkedHashMap<>();
  private final ArrayList<String> markers = new ArrayList<>();
  public volatile String nativeStatus = "尚未开始车辆监听", logStatus = "尚未开始日志监听", playing = "待命";
  public volatile boolean scanning, monitoring;
  public volatile long revision;

  public synchronized void line(String type, String message) {
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
    lines.clear();
    candidates.clear();
    markers.clear();
    revision++;
  }

  public synchronized void resetLive() {
    live.clear();
    revision++;
  }

  public synchronized String export(boolean granted, Store store) throws Exception {
    JSONObject root = new JSONObject();
    root.put("schemaVersion", 1);
    root.put("application", "cn.es.soundlab");
    root.put("version", "0.1.0");
    root.put("vehicleProfile", "星纪元 ES / 原 APK 星途 ES 路径");
    root.put("exportedAt", System.currentTimeMillis());
    root.put("manufacturer", Build.MANUFACTURER);
    root.put("model", Build.MODEL);
    root.put("android", Build.VERSION.RELEASE);
    root.put("sdk", Build.VERSION.SDK_INT);
    root.put("readLogsGranted", granted);
    root.put("nativeStatus", nativeStatus);
    root.put("logStatus", logStatus);
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
    return root.toString(2);
  }
}
