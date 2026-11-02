package cn.es.soundlab;

import android.os.Handler;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class IccVehicleSource {
  private static final int MAX_RETRY_MILLIS = 10000;
  interface Listener {
    void sample(Protocol.Sample sample);
    void reset();
  }

  private final Handler main;
  private final Diagnostics diagnostics;
  private final Listener listener;
  private final IccVehicleProbe.Transport transport;
  private final ExecutorService executor;
  private final LongSupplier elapsed, wall;
  private final int interval;
  private final JSONObject report = new JSONObject();
  private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
  private final Runnable tick = this::poll, stale = () -> invalidate("超过5秒未取得新快照，清除触发基线");
  private boolean active, started, inFlight;
  private int generation, epoch;
  private long attempts, responses, validGears, changes, failures, consecutiveFailures, recoveries;
  private Integer previousGear;

  IccVehicleSource(Handler main, Diagnostics diagnostics, Listener listener, int interval) {
    this(main, diagnostics, listener, interval, new IccVehicleTransport(),
        Executors.newSingleThreadExecutor(task -> {
          Thread thread = new Thread(task, "icc-trigger-query");
          thread.setDaemon(true);
          return thread;
        }), SystemClock::elapsedRealtime, System::currentTimeMillis);
  }

  IccVehicleSource(Handler main, Diagnostics diagnostics, Listener listener, int interval,
      IccVehicleProbe.Transport transport, ExecutorService executor, LongSupplier elapsed, LongSupplier wall) {
    this.main = main;
    this.diagnostics = diagnostics;
    this.listener = listener;
    this.interval = Store.validIccInterval(interval) ? interval : 250;
    this.transport = transport;
    this.executor = executor;
    this.elapsed = elapsed;
    this.wall = wall;
  }

  boolean active() { return active; }

  void start() {
    if (started) return;
    started = true;
    active = true;
    put("sessionId", UUID.randomUUID().toString());
    put("startedAt", wall.getAsLong());
    put("uid", android.os.Process.myUid());
    put("proxy", IccVehicleTransport.PROXY_HOST + ":" + IccVehicleTransport.PROXY_PORT);
    put("logicalService", IccVehicleTransport.LOGICAL_HOST);
    put("path", "/cdt");
    put("method", "POST");
    put("sourceApkVersion", "1.7.0.2609r");
    put("readOnly", true);
    put("audioConnected", true);
    put("triggerKeys", new JSONArray().put("gear").put("lock").put("charge"));
    put("displayKeys", new JSONArray().put("gear").put("speed").put("handbrake").put("power").put("charge").put("lock"));
    put("triggerPolicy", "挡位按音效开关；锁默认原厂车外通道，仅先有新鲜P挡基线后的锁变化触发；充电仍需核对确认");
    put("upstreamTimestampAvailable", false);
    put("minIntervalMillis", interval);
    put("historyLimit", 30);
    put("automaticRetry", true);
    put("retryLimit", "until-user-stop-or-source-replaced");
    put("maxRetryDelayMillis", MAX_RETRY_MILLIS);
    listener.reset();
    publish("querying", "ICC六项状态；锁默认P挡车外提示，充电需核对确认；首样本静默，不自动启用持续循环音");
    poll();
  }

  private void poll() {
    if (!active || inFlight) return;
    main.removeCallbacks(tick);
    inFlight = true;
    put("retryScheduled", false);
    put("nextRequestAt", JSONObject.NULL);
    long requested = elapsed.getAsLong();
    int token = generation, requestedEpoch = epoch;
    attempts++;
    put("lastRequestAt", wall.getAsLong());
    put("inFlight", true);
    publish("querying", "ICC串行只读查询；回复时间不是车辆源时间");
    try {
      executor.execute(() -> {
        IccVehicleProbe.Reply reply = null;
        Exception failure = null;
        try { reply = transport.query(); } catch (Exception error) { failure = error; }
        IccVehicleProbe.Reply result = reply;
        Exception error = failure;
        long received = elapsed.getAsLong(), receivedAt = wall.getAsLong();
        main.post(() -> accept(token, requestedEpoch, result, error, requested, received, receivedAt));
      });
    } catch (RuntimeException failure) { retry("worker-unavailable"); }
  }

  private void accept(int token, int requestedEpoch, IccVehicleProbe.Reply reply, Exception error,
      long requested, long received, long receivedAt) {
    if (!active || token != generation) return;
    inFlight = false;
    put("inFlight", false);
    put("lastDurationMillis", Math.max(0, received - requested));
    if (error != null) { retry(IccVehicleProbe.failureName(error)); return; }
    if (reply == null) { retry("empty-reply"); return; }
    put("lastHttpStatus", reply.status);
    if (reply.status != 200) {
      retry("http-" + reply.status);
      return;
    }
    responses++;
    if (requestedEpoch != epoch) {
      put("epochDropped", report.optLong("epochDropped") + 1);
      publish("baseline-reset", "校准或基线改变，丢弃此前发起的查询");
    } else if (elapsed.getAsLong() - requested > 5000 || elapsed.getAsLong() - received > 5000) {
      put("expired", report.optLong("expired") + 1);
      invalidate("请求/主线程排队过期，不补播旧快照");
    } else {
      IccVehicleWire.Condition condition;
      try { condition = IccVehicleWire.decode(reply.body); }
      catch (Exception failure) { retry("invalid-response"); return; }
      put("lastSuccessAt", receivedAt);
      if (consecutiveFailures != 0) {
        recoveries++;
        diagnostics.line("ICC重连", "只读查询恢复；此前连续失败 " + consecutiveFailures
            + " 次，恢复首样本仅建立静默基线，不补播");
        consecutiveFailures = 0;
      }
      put("retryDelayMillis", 0);
      if (condition.knownGear()) {
        validGears++;
        if (previousGear != null && !previousGear.equals(condition.gear)) changes++;
        previousGear = condition.gear;
      } else previousGear = null;
      try {
        JSONObject sample = new JSONObject().put("receivedAt", receivedAt)
            .put("requestMillis", Math.max(0, received - requested))
            .put("gear", condition.gearName()).put("knownGear", condition.knownGear())
            .put("gearPresent", condition.gear != null).put("fields", condition.fields);
        samples.addLast(sample);
        while (samples.size() > 30) samples.removeFirst();
        put("lastSample", sample);
      } catch (Exception ignored) {}
      main.removeCallbacks(stale);
      int deliveryEpoch = epoch;
      for (Protocol.Sample reading : condition.stateSamples(receivedAt)) {
        if (!active || epoch != deliveryEpoch) break;
        listener.sample(reading);
      }
      if (!active) return;
      if (epoch != deliveryEpoch) { schedule(interval); return; }
      publish(condition.knownGear() ? "snapshot" : "unknown-gear",
          condition.knownGear() ? "挡位/手刹/电源/充电/锁；车速原值即km/h，本车成功回复且挡位有效时车速缺失按静止0，回复不保证车辆源实时性"
              : "挡位缺失/未知，只清除挡位基线；其余字段独立处理，恢复首样本不播音");
      if (active) main.postDelayed(stale, 5000);
    }
    schedule(interval);
  }

  private void schedule(long delay) {
    if (!active) return;
    main.removeCallbacks(tick);
    put("nextRequestAt", wall.getAsLong() + delay);
    main.postDelayed(tick, delay);
  }

  private void retry(String code) {
    if (!active) return;
    inFlight = false;
    put("inFlight", false);
    failures++;
    consecutiveFailures++;
    epoch++;
    previousGear = null;
    main.removeCallbacks(stale);
    listener.reset();
    if (!active) return;
    long delay = Math.min(MAX_RETRY_MILLIS, 1000L << (int) Math.min(4, consecutiveFailures - 1));
    put("lastErrorCode", code);
    put("lastErrorAt", wall.getAsLong());
    put("retryScheduled", true);
    put("retryDelayMillis", delay);
    schedule(delay);
    String detail = code + "；" + delay + "ms后自动重试，监听未停止；状态已失效，恢复首样本静默";
    diagnostics.line("ICC重连", "连续失败 " + consecutiveFailures + " 次 · " + detail);
    publish("retry-wait", detail);
  }

  void invalidate(String detail) {
    if (!active) return;
    epoch++;
    previousGear = null;
    main.removeCallbacks(stale);
    listener.reset();
    publish("baseline-reset", detail);
  }

  void stop(String phase, String detail) {
    if (!active) return;
    active = false;
    inFlight = false;
    generation++;
    previousGear = null;
    main.removeCallbacks(tick);
    main.removeCallbacks(stale);
    transport.close();
    executor.shutdownNow();
    listener.reset();
    put("inFlight", false);
    put("retryScheduled", false);
    put("nextRequestAt", JSONObject.NULL);
    put("stoppedAt", wall.getAsLong());
    publish(phase, detail);
  }

  private void publish(String phase, String detail) {
    put("active", active);
    put("phase", phase);
    put("detail", detail);
    put("attempts", attempts);
    put("http200", responses);
    put("knownGearSamples", validGears);
    put("gearChanges", changes);
    put("failures", failures);
    put("consecutiveFailures", consecutiveFailures);
    put("recoveries", recoveries);
    put("samples", new JSONArray(samples));
    diagnostics.iccVehicleSource(report);
  }

  private void put(String key, Object value) {
    try { report.put(key, value); } catch (Exception ignored) {}
  }
}
