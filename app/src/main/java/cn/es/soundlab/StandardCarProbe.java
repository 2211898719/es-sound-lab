package cn.es.soundlab;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

public final class StandardCarProbe {
  static final int SELECTION = 0x11400400, CURRENT = 0x11400401;
  static final int[] PROPERTIES = {SELECTION, CURRENT};
  static final long OPERATION_TIMEOUT = 8000, SESSION_TIMEOUT = 60000, POLL_INTERVAL = 1000;
  static final int MAX_SAMPLES = 256;
  private static final AtomicLong sessions = new AtomicLong(System.currentTimeMillis());
  private static final AtomicBoolean remoteBusy = new AtomicBoolean();
  private static final ExecutorService calls = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "ES-StandardCarReadOnly");
    thread.setDaemon(true);
    return thread;
  });

  interface Sink {
    void ready(boolean connected);
    void value(Value value);
    void error(int property, String detail);
  }
  interface Transport {
    void connect(Sink sink) throws Exception;
    void prepare(Sink sink) throws Exception;
    boolean register(int property) throws Exception;
    Value read(int property) throws Exception;
    void unregister(int property) throws Exception;
    void disconnect() throws Exception;
  }
  static final class Value {
    final int property, area, status;
    final long timestamp;
    final long receivedElapsed = SystemClock.elapsedRealtimeNanos(), receivedAt = System.currentTimeMillis();
    final Object raw;
    Value(int property, int area, int status, long timestamp, Object raw) {
      this.property = property;
      this.area = area;
      this.status = status;
      this.timestamp = timestamp;
      this.raw = raw;
    }
  }

  private final Handler main;
  private final Diagnostics diagnostics;
  private final ExecutorService worker;
  private final Transport transport;
  private final JSONObject report = new JSONObject(), properties = new JSONObject();
  private final JSONArray events = new JSONArray(), samples = new JSONArray();
  private final HashMap<Integer, Integer> previous = new HashMap<>();
  private final HashMap<Integer, Long> timestamps = new HashMap<>();
  private final HashMap<Integer, Integer> previousReads = new HashMap<>();
  private final AtomicBoolean preparing = new AtomicBoolean(), cleanupStarted = new AtomicBoolean();
  private final AtomicInteger queued = new AtomicInteger(), dropped = new AtomicInteger();
  private final boolean[] attempted = new boolean[2];
  private volatile boolean started, closed;
  private boolean leased, flushScheduled, pollInFlight;
  private long startedElapsed;
  private final Runnable deadline = () -> {
    if (SystemClock.elapsedRealtime() - startedElapsed >= SESSION_TIMEOUT)
      stop("completed", "60秒检测结束，注销回调并断开；已派发的同步调用不能强制取消");
    else stop("timeout", "阶段8秒超时；同步调用不能强制取消");
  };
  private final Runnable pollTick = this::poll;
  private final Runnable flush = () -> {
    synchronized (this) { flushScheduled = false; publish(); }
  };
  private final Sink sink = new Sink() {
    @Override public void ready(boolean connected) {
      if (closed) return;
      if (!connected) { stop("disconnected", "Car服务连接丢失/未就绪"); return; }
      if (preparing.compareAndSet(false, true)) worker.execute(StandardCarProbe.this::inspect);
    }
    @Override public void value(Value value) { enqueue(() -> receive(value, true, false)); }
    @Override public void error(int property, String detail) {
      enqueue(() -> {
        synchronized (StandardCarProbe.this) {
          increment(report, "errorCallbacks");
          JSONObject item = item(property);
          if (item != null) {
            previous.remove(property);
            set(item, "errorCallback", detail);
            increment(item, "errorCallbacks");
          }
          event("error-callback", property + ": " + detail);
          scheduleFlush();
        }
      });
    }
  };

  public StandardCarProbe(Context context, Handler main, Diagnostics diagnostics) {
    this(main, diagnostics, calls, new StandardCarTransport(context, main));
    set(report, "carPowertrainGranted", context.checkSelfPermission("android.car.permission.CAR_POWERTRAIN") == 0);
  }

  StandardCarProbe(Handler main, Diagnostics diagnostics, ExecutorService worker, Transport transport) {
    this.main = main;
    this.diagnostics = diagnostics;
    this.worker = worker;
    this.transport = transport;
  }

  public synchronized void start() {
    if (started || closed) return;
    started = true;
    startedElapsed = SystemClock.elapsedRealtime();
    set(report, "startedElapsedMillis", startedElapsed);
    set(report, "pollIntervalMillis", POLL_INTERVAL);
    set(report, "sessionTimeoutMillis", SESSION_TIMEOUT);
    set(report, "sessionId", sessions.incrementAndGet());
    set(report, "startedAt", System.currentTimeMillis());
    set(report, "uid", android.os.Process.myUid());
    set(report, "pid", android.os.Process.myPid());
    set(report, "source", "app-uid-standard-android-car-read-only");
    set(report, "audioConnected", false);
    set(report, "active", true);
    set(report, "properties", properties);
    set(report, "events", events);
    set(report, "samples", samples);
    set(report, "freshness", "unverified until compared with physical gear; no automatic audio");
    for (int property : PROPERTIES) {
      JSONObject item = new JSONObject();
      set(item, "id", property);
      set(item, "hex", String.format("0x%08x", property));
      set(item, "name", name(property));
      set(item, "registered", false);
      set(properties, Integer.toString(property), item);
    }
    diagnostics.beginStandardCarProbe(snapshot());
    if (!remoteBusy.compareAndSet(false, true)) {
      stop("busy", "上一标准Car调用/清理未返回，不叠加调用");
      return;
    }
    leased = true;
    phase("connecting", OPERATION_TIMEOUT);
    worker.execute(() -> {
      try { if (!closed) transport.connect(sink); }
      catch (Exception | LinkageError error) { fail(error); }
    });
  }

  private void inspect() {
    try {
      if (closed) return;
      phase("property-manager", OPERATION_TIMEOUT);
      transport.prepare(sink);
      for (int index = 0; index < PROPERTIES.length && !closed; index++) {
        int property = PROPERTIES[index];
        JSONObject item = item(property);
        phase("register-" + name(property), OPERATION_TIMEOUT);
        attempted[index] = true;
        try {
          boolean result = transport.register(property);
          synchronized (this) {
            set(item, "registerResult", result);
            set(item, "registered", result && !closed);
          }
        } catch (Exception | LinkageError error) { setError(item, "registerError", error); }
        if (closed) break;
        read(property, false);
      }
      resumePolling();
    } catch (Exception | LinkageError error) { fail(error); }
  }

  private void read(int property, boolean polled) {
    JSONObject item = item(property);
    phase((polled ? "poll-" : "read-") + name(property), OPERATION_TIMEOUT);
    synchronized (this) {
      if (closed) return;
      increment(item, "readAttempts");
    }
    try {
      Value value = transport.read(property);
      if (value == null || value.property != property)
        throw new IllegalStateException("getProperty returned null or mismatched property");
      receive(value, false, polled);
    } catch (Exception | LinkageError error) {
      synchronized (this) {
        previousReads.remove(property);
        increment(item, "readFailures");
        set(item, "lastReadSucceeded", false);
        if (error instanceof SecurityException || error instanceof ReflectiveOperationException
            || error instanceof LinkageError) {
          set(item, "readDisabled", true);
          set(item, "readDisabledReason", "权限/接口不可用；本会话不重复读取");
        }
        setError(item, "readError", error);
      }
    }
  }

  private synchronized void poll() {
    if (closed || pollInFlight) return;
    pollInFlight = true;
    worker.execute(() -> {
      try {
        synchronized (this) {
          if (closed) return;
          increment(report, "pollCycles");
        }
        for (int property : PROPERTIES) {
          synchronized (this) { if (closed) break; }
          if (!item(property).optBoolean("readDisabled")) read(property, true);
        }
      } finally {
        synchronized (this) { pollInFlight = false; resumePolling(); }
      }
    });
  }

  private synchronized void resumePolling() {
    if (closed) return;
    phase("listening", SESSION_TIMEOUT);
    main.removeCallbacks(pollTick);
    for (int property : PROPERTIES) if (!item(property).optBoolean("readDisabled")) {
      main.postDelayed(pollTick, POLL_INTERVAL);
      break;
    }
  }

  private void enqueue(Runnable task) {
    if (closed) return;
    if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); dropped.incrementAndGet(); return; }
    main.post(() -> {
      try { if (!closed) task.run(); }
      finally { queued.decrementAndGet(); }
    });
  }

  private synchronized void receive(Value value, boolean callback, boolean polled) {
    if (closed || value == null) return;
    JSONObject item = item(value.property);
    if (item == null) { increment(report, "unexpectedCallbacks"); return; }
    long received = value.receivedElapsed;
    JSONObject sample = new JSONObject();
    set(sample, "propertyId", value.property);
    set(sample, "areaId", value.area);
    set(sample, "status", value.status);
    set(sample, "statusLabel", value.status == 0 ? "AVAILABLE" : value.status == 1 ? "UNAVAILABLE" : value.status == 2 ? "ERROR" : "UNKNOWN");
    set(sample, "sourceTimestampNanos", value.timestamp);
    set(sample, "receivedElapsedNanos", received);
    set(sample, "receivedAt", value.receivedAt);
    set(sample, "source", callback ? "callback" : "read");
    if (!callback) set(sample, "readKind", polled ? "poll" : "initial");
    set(sample, "ageMillis", (received - value.timestamp) / 1000000.0);
    set(sample, "raw", value.raw == null ? JSONObject.NULL : value.raw instanceof Integer ? value.raw : clip(String.valueOf(value.raw), 256));
    set(sample, "valueClass", value.raw == null ? "null" : value.raw.getClass().getName());
    String label = value.raw instanceof Integer ? label((Integer) value.raw) : "非整数";
    set(sample, "label", label);
    boolean valid = value.status == 0 && value.area == 0 && value.raw instanceof Integer
        && !"UNKNOWN/未识别".equals(label);
    boolean timestampValid = value.timestamp > 0 && value.timestamp <= received;
    set(sample, "valid", valid);
    set(sample, "timestampPlausible", timestampValid);
    increment(item, callback ? "callbacks" : "reads");
    if (callback) {
      Long last = timestamps.get(value.property);
      boolean ordered = timestampValid && (last == null || value.timestamp > last);
      set(sample, "ordered", ordered);
      if (valid && ordered) {
        timestamps.put(value.property, value.timestamp);
        Integer before = previous.put(value.property, (Integer) value.raw);
        increment(item, "validCallbacks");
        if (before != null && !before.equals(value.raw)) increment(item, "changes");
      } else {
        previous.remove(value.property);
        increment(item, "invalidOrUnorderedCallbacks");
        if (timestampValid && (last == null || value.timestamp > last)) timestamps.put(value.property, value.timestamp);
      }
      set(item, "lastCallback", sample);
    } else {
      set(item, "lastReadSucceeded", true);
      if (polled) increment(item, "pollReads");
      JSONObject last = item.optJSONObject("lastRead");
      if (last != null) {
        long lastTimestamp = last.optLong("sourceTimestampNanos");
        set(sample, "sourceTimestampAdvanced", timestampValid && value.timestamp > lastTimestamp);
        if (timestampValid && value.timestamp > lastTimestamp) increment(item, "readTimestampAdvances");
      }
      if (valid) {
        Integer before = previousReads.put(value.property, (Integer) value.raw);
        if (before != null && !before.equals(value.raw)) {
          increment(item, "readChanges");
          if (!sample.optBoolean("sourceTimestampAdvanced")) increment(item, "readChangesWithoutTimestampAdvance");
        }
      } else previousReads.remove(value.property);
      set(item, "lastRead", sample);
    }
    if (samples.length() >= MAX_SAMPLES) { samples.remove(0); increment(report, "samplesDropped"); }
    samples.put(sample);
    event(callback ? "callback" : "read", name(value.property) + " raw=" + sample.opt("raw")
        + " status=" + value.status + " ts=" + value.timestamp);
    scheduleFlush();
  }

  public boolean active() { return started && !closed; }

  public synchronized void stop(String reason, String detail) {
    if (closed) return;
    closed = true;
    main.removeCallbacks(deadline);
    main.removeCallbacks(pollTick);
    main.removeCallbacks(flush);
    flushScheduled = false;
    if ("timeout".equals(reason)) set(report, "failedPhase", report.optString("phase"));
    set(report, "phase", reason);
    set(report, "active", false);
    for (int property : PROPERTIES) set(item(property), "registered", false);
    set(report, "stopDetail", detail);
    set(report, "finishedAt", System.currentTimeMillis());
    set(report, "durationMillis", SystemClock.elapsedRealtime() - startedElapsed);
    set(report, "cleanupPending", leased);
    event("stop", detail);
    publish();
    if (leased) worker.execute(this::cleanup);
  }

  private void cleanup() {
    if (!cleanupStarted.compareAndSet(false, true)) return;
    boolean confirmed = true;
    try {
      for (int index = 0; index < PROPERTIES.length; index++) if (attempted[index]) {
        JSONObject item = item(PROPERTIES[index]);
        try { transport.unregister(PROPERTIES[index]); synchronized (this) { set(item, "unregisterReturned", true); } }
        catch (Exception | LinkageError error) { confirmed = false; setError(item, "unregisterError", error); }
      }
      try { transport.disconnect(); synchronized (this) { set(report, "disconnectReturned", true); } }
      catch (Exception | LinkageError error) { confirmed = false; setError(report, "disconnectError", error); }
    } finally {
      synchronized (this) {
        set(report, "cleanupConfirmed", confirmed);
        set(report, "cleanupPending", false);
        publish();
        leased = false;
        remoteBusy.set(false);
      }
    }
  }

  private synchronized void phase(String phase, long timeout) {
    if (closed) return;
    set(report, "phase", phase);
    main.removeCallbacks(deadline);
    main.postDelayed(deadline, Math.max(0, Math.min(timeout,
        SESSION_TIMEOUT - (SystemClock.elapsedRealtime() - startedElapsed))));
    publish();
  }

  private synchronized void fail(Throwable error) {
    setError(report, closed ? "lateError" : "error", error);
    if (!closed) {
      set(report, "failedPhase", report.optString("phase"));
      stop("failed", "标准Car接口不可用/调用失败；不重试、不回退厂商接口");
    }
  }

  private synchronized void setError(JSONObject target, String key, Throwable error) {
    set(target, key, describe(error));
    publish();
  }

  private void scheduleFlush() {
    if (!flushScheduled) { flushScheduled = true; main.postDelayed(flush, 250); }
  }
  private JSONObject item(int property) { return properties.optJSONObject(Integer.toString(property)); }
  private void event(String kind, String detail) {
    if (events.length() >= 120) { events.remove(0); increment(report, "eventsDropped"); }
    JSONObject event = new JSONObject();
    set(event, "at", System.currentTimeMillis());
    set(event, "kind", kind);
    set(event, "detail", clip(detail, 512));
    events.put(event);
  }
  synchronized JSONObject snapshot() {
    set(report, "droppedCallbacks", dropped.get());
    set(report, "pollInFlight", pollInFlight);
    if (started && !closed) set(report, "durationMillis", SystemClock.elapsedRealtime() - startedElapsed);
    try { return new JSONObject(report.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }
  private void publish() { diagnostics.standardCarProbe(snapshot()); }
  private static void set(JSONObject target, String key, Object value) {
    if (target != null) try { target.put(key, value); } catch (Exception ignored) {}
  }
  private static void increment(JSONObject target, String key) { set(target, key, target.optLong(key) + 1); }
  private static String clip(String value, int length) { return value.substring(0, Math.min(value.length(), length)); }
  static String describe(Throwable error) { return clip(error.getClass().getName() + ": " + error.getMessage(), 1024); }
  static String name(int property) { return property == SELECTION ? "GEAR_SELECTION" : "CURRENT_GEAR"; }
  static String label(int value) {
    switch (value) {
      case 1: return "N";
      case 2: return "R";
      case 4: return "P";
      case 8: return "D";
      case 16: case 32: case 64: case 128: case 256: case 512: case 1024: case 2048: case 4096:
        return "前进" + (Integer.numberOfTrailingZeros(value) - 3) + "挡";
      default: return "UNKNOWN/未识别";
    }
  }
}
