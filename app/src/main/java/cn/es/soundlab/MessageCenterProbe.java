package cn.es.soundlab;

import android.os.Handler;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MessageCenterProbe {
  static final int MAX_PAYLOAD = 8192;
  static final long OPERATION_TIMEOUT = 8000, SESSION_TIMEOUT = 60000;
  private static final String[] TOPICS = {"DrivingInfo/Gear", "VehicleDoor/CurrentPosition",
      "VehicleDoor/CentralLock", "VehicleDoor/reevLHFdoors", "T1CF1/AutoHold"};
  private static final AtomicLong sessions = new AtomicLong();
  private static final AtomicBoolean remoteBusy = new AtomicBoolean();
  private static final ExecutorService remoteCalls = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "ES-MessageCenterReadOnly");
    thread.setDaemon(true);
    return thread;
  });

  interface Sink { void receive(String topic, byte[] payload); }
  interface Transport {
    boolean discover() throws Exception;
    void verify() throws Exception;
    int subscribe(ArrayList<String> topics, Sink sink) throws Exception;
    int unsubscribe(ArrayList<String> topics) throws Exception;
    default boolean subscriptionMayExist() { return true; }
  }

  private final Handler main;
  private final Diagnostics diagnostics;
  private final ExecutorService worker;
  private Transport transport;
  private final JSONObject report = new JSONObject(), samples = new JSONObject();
  private final JSONArray events = new JSONArray();
  private final HashMap<String, String> previousValues = new HashMap<>();
  private final AtomicInteger queued = new AtomicInteger(), dropped = new AtomicInteger();
  private final AtomicBoolean cleanupStarted = new AtomicBoolean();
  private final Runnable deadline = () -> stop("timeout", "阶段或会话超时；同步HIDL调用不能强制取消，清理结果另记");
  private final Runnable flush = () -> {
    synchronized (this) { flushScheduled = false; publish(); }
  };
  private volatile boolean started, closed, attempted;
  private boolean leased;
  private boolean flushScheduled;
  private boolean discoveryOnly;
  private long startedElapsed;

  public MessageCenterProbe(Handler main, Diagnostics diagnostics) {
    this(main, diagnostics, remoteCalls, null);
  }

  MessageCenterProbe(Handler main, Diagnostics diagnostics, ExecutorService worker,
      Transport transport) {
    this.main = main;
    this.diagnostics = diagnostics;
    this.worker = worker;
    this.transport = transport;
  }

  static ArrayList<String> topics() { return new ArrayList<>(Arrays.asList(TOPICS)); }

  static boolean accepts(String topic) { return topic != null && topics().contains(topic); }

  public void start() { start(false); }

  public void startDiscoveryOnly() { start(true); }

  private synchronized void start(boolean onlyDiscover) {
    if (started || closed) return;
    started = true;
    discoveryOnly = onlyDiscover;
    startedElapsed = SystemClock.elapsedRealtime();
    put("sessionId", sessions.incrementAndGet());
    put("startedAt", System.currentTimeMillis());
    put("source", "app-uid-message-center-read-only");
    put("uid", android.os.Process.myUid());
    put("pid", android.os.Process.myPid());
    put("discoveryOnly", discoveryOnly);
    put("descriptor", "vendor.mega.message_center@1.0::IMessageCenter");
    put("instance", "default");
    put("topics", new JSONArray(topics()));
    put("samples", samples);
    put("events", events);
    put("active", true);
    put("sourceTimestampAvailable", false);
    put("freshness", "unverified: callback arrival is not a source timestamp");
    put("topicCompatibility", "candidates from supplied APKs; T1CF1 is not verified for E0X");
    put("audioConnected", false);
    diagnostics.beginMessageCenterProbe(snapshot());
    if (!remoteBusy.compareAndSet(false, true)) {
      stop("busy", "上一会话的HIDL调用或清理仍在进行；不会叠加调用");
      return;
    }
    leased = true;
    phase("discovering");
    worker.execute(this::connect);
  }

  private void connect() {
    try {
      if (closed) return;
      if (transport == null) transport = new MessageCenterTransport();
      boolean found = transport.discover();
      put("serviceFound", found);
      if (closed) return;
      if (!found) { stop("service-unavailable", "未发现服务或当前身份不可见；不显式启动组件或重试"); return; }
      phase("verifying-interface");
      transport.verify();
      put("interfaceVerified", true);
      if (closed) return;
      if (discoveryOnly) {
        stop("discovery-complete", "已发现并核对接口；本轮未订阅，不代表车辆回调可用");
        return;
      }
      phase("subscribing");
      attempted = true;
      put("subscriptionAttempted", true);
      int result = transport.subscribe(topics(), this::receive);
      put("subscribeResult", result);
      if (closed) return;
      put("registered", result == 0);
      if (result != 0) { stop("subscribe-rejected", "订阅返回非0，不重试；无有效回调不能判定可用"); return; }
      phase("listening");
    } catch (Exception | LinkageError error) {
      synchronized (this) {
        if (!report.has("failedPhase")) put("failedPhase", report.optString("phase"));
        put(closed ? "lateError" : "error", describe(error));
        put("errorType", error.getClass().getName());
        java.io.StringWriter trace = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(trace));
        put("errorStack", clip(trace.toString(), 4096));
      }
      stop(error instanceof SecurityException ? "permission-denied"
          : error instanceof java.util.NoSuchElementException
              && "discovering".equals(snapshot().optString("failedPhase")) ? "service-unavailable"
          : error instanceof LinkageError ? "hidden-api-unavailable" : "operation-error",
          "只读访问失败，不切换身份、不修改权限或调试属性");
    } finally {
      if (attempted) put("remoteSubscriptionAttempted", transport.subscriptionMayExist());
      if (closed) cleanup();
    }
  }

  private synchronized void phase(String value) {
    if (closed) return;
    put("phase", value);
    event(value, "listening".equals(value) ? "注册已返回，等待有效回调；不要求切挡或行驶" : value);
    main.post(() -> {
      if (closed) return;
      main.removeCallbacks(deadline);
      long remaining = SESSION_TIMEOUT - (SystemClock.elapsedRealtime() - startedElapsed);
      main.postDelayed(deadline, Math.max(1, "listening".equals(value)
          ? remaining : Math.min(OPERATION_TIMEOUT, remaining)));
    });
    publish();
  }

  void receive(String topic, byte[] payload) {
    if (closed || !accepts(topic)) return;
    if (queued.incrementAndGet() > 64) {
      queued.decrementAndGet();
      dropped.incrementAndGet();
      return;
    }
    byte[] copy = payload == null || payload.length > MAX_PAYLOAD ? null : payload.clone();
    long receivedAt = System.currentTimeMillis();
    main.post(() -> {
      try {
        synchronized (MessageCenterProbe.this) {
          if (closed) return;
          increment(report, "callbackCount");
          JSONObject sample = samples.optJSONObject(topic);
          if (sample == null) { sample = new JSONObject(); putSample(topic, sample); }
          increment(sample, "callbackCount");
          sample.put("receivedAt", receivedAt);
          sample.put("payloadBytes", copy == null ? -1 : copy.length);
          sample.put("valid", false);
          for (String key : new String[] {"raw", "rawTruncated", "value", "valueTruncated", "validFlag", "parseError"})
            sample.remove(key);
          try {
            if (copy == null) throw new IllegalArgumentException("payload missing or exceeds 8192 bytes");
            String raw = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(copy)).toString();
            sample.put("raw", clip(raw, 2048));
            sample.put("rawTruncated", raw.length() > 2048);
            JSONObject data = new JSONObject(raw);
            Object valid = data.opt("valid"), value = data.opt("value");
            boolean usable = Boolean.TRUE.equals(valid) && value != null && value != JSONObject.NULL;
            sample.put("valid", usable);
            sample.put("validFlag", valid == null ? JSONObject.NULL : valid);
            sample.remove("parseError");
            if (usable) {
              String encoded = value instanceof String ? JSONObject.quote((String) value) : value.toString();
              sample.put("value", clip(encoded, 2048));
              sample.put("valueTruncated", encoded.length() > 2048);
              increment(report, "validCallbackCount");
              increment(sample, "validCallbackCount");
              if (encoded.length() <= 2048) {
                String previous = previousValues.put(topic, encoded);
                if (previous != null && !previous.equals(encoded)) {
                  increment(report, "changes");
                  increment(sample, "changes");
                }
              } else previousValues.remove(topic);
            } else {
              increment(report, "invalidCallbackCount");
              previousValues.remove(topic);
              sample.remove("value");
            }
          } catch (Exception error) {
            increment(report, "malformedCallbackCount");
            sample.put("parseError", describe(error));
            sample.remove("value");
            previousValues.remove(topic);
          }
          event("callback", topic + " valid=" + sample.optBoolean("valid"));
          if (!flushScheduled) {
            flushScheduled = true;
            main.postDelayed(flush, 250);
          }
        }
      } catch (Exception ignored) {
      } finally { queued.decrementAndGet(); }
    });
  }

  public boolean active() { return started && !closed; }

  public synchronized void stop(String reason, String detail) {
    if (closed) return;
    closed = true;
    main.removeCallbacks(deadline);
    main.removeCallbacks(flush);
    flushScheduled = false;
    put("active", false);
    put("registered", false);
    if ("timeout".equals(reason)) put("failedPhase", report.optString("phase"));
    put("phase", reason);
    put("stopDetail", detail);
    put("finishedAt", System.currentTimeMillis());
    put("cleanupPending", leased);
    event("stop", detail);
    publish();
    if (leased) worker.execute(this::cleanup);
  }

  private void cleanup() {
    if (!cleanupStarted.compareAndSet(false, true)) return;
    try {
      if (attempted && transport.subscriptionMayExist()) {
        int result = transport.unsubscribe(topics());
        put("unsubscribeResult", result);
        put("cleanupConfirmed", result == 0);
      } else {
        put("cleanupConfirmed", true);
        put("unsubscribeSkipped", "no subscription transaction dispatched");
      }
    } catch (Exception | LinkageError error) {
      put("unsubscribeError", describe(error));
      put("cleanupConfirmed", false);
    } finally {
      put("cleanupPending", false);
      event("cleanup", "清理已返回；非0结果/异常不代表成功注销");
      publish();
      if (leased) { leased = false; remoteBusy.set(false); }
    }
  }

  private synchronized void put(String key, Object value) {
    try { report.put(key, value); } catch (Exception ignored) {}
  }

  private void putSample(String topic, JSONObject sample) throws Exception { samples.put(topic, sample); }

  private static void increment(JSONObject target, String key) {
    try { target.put(key, target.optLong(key) + 1); } catch (Exception ignored) {}
  }

  private synchronized void event(String kind, String detail) {
    try {
      if (events.length() >= 96) { events.remove(0); increment(report, "eventsDropped"); }
      events.put(new JSONObject().put("at", System.currentTimeMillis()).put("kind", kind)
          .put("detail", clip(detail, 512)));
    } catch (Exception ignored) {}
  }

  synchronized JSONObject snapshot() {
    try {
      report.put("droppedCallbacks", dropped.get());
      return new JSONObject(report.toString());
    } catch (Exception ignored) { return new JSONObject(); }
  }

  private synchronized void publish() { diagnostics.messageCenterProbe(snapshot()); }

  private static String clip(String value, int max) { return value.substring(0, Math.min(value.length(), max)); }

  private static String describe(Throwable error) {
    return clip(error.getClass().getName() + ": " + String.valueOf(error.getMessage()), 1024);
  }
}
