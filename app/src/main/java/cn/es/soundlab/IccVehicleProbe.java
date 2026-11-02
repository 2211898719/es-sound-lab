package cn.es.soundlab;

import android.os.Handler;
import android.os.SystemClock;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class IccVehicleProbe {
  interface Transport { Reply query() throws Exception; void close(); }
  static final class Reply {
    final int status;
    final byte[] body;
    Reply(int status, byte[] body) { this.status = status; this.body = body; }
  }

  private final Handler main;
  private final Diagnostics diagnostics;
  private final Transport transport;
  private final ExecutorService executor;
  private final LongSupplier elapsed, wall;
  private final JSONObject report = new JSONObject();
  private final JSONArray samples = new JSONArray();
  private final Runnable tick = this::poll, deadline = () -> stop("complete", "达到60秒上限");
  private boolean active, started;
  private int generation, attempts, responses, validGears, changes;
  private Integer previousGear;
  private long startedElapsed;

  IccVehicleProbe(Handler main, Diagnostics diagnostics) {
    this(main, diagnostics, new IccVehicleTransport(), Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "icc-condition-query");
      thread.setDaemon(true);
      return thread;
    }), SystemClock::elapsedRealtime, System::currentTimeMillis);
  }

  IccVehicleProbe(Handler main, Diagnostics diagnostics, Transport transport,
      ExecutorService executor, LongSupplier elapsed, LongSupplier wall) {
    this.main = main;
    this.diagnostics = diagnostics;
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
    startedElapsed = elapsed.getAsLong();
    put("sessionId", UUID.randomUUID().toString());
    put("startedAt", wall.getAsLong());
    put("uid", android.os.Process.myUid());
    put("proxy", IccVehicleTransport.PROXY_HOST + ":" + IccVehicleTransport.PROXY_PORT);
    put("logicalService", IccVehicleTransport.LOGICAL_HOST);
    put("path", "/cdt");
    put("method", "POST");
    put("sourceApkVersion", "1.7.0.2609r");
    put("readOnly", true);
    put("audioConnected", false);
    put("upstreamTimestampAvailable", false);
    put("maxAttempts", 30);
    put("maxDurationMillis", 60000);
    put("minIntervalMillis", 1000);
    put("samples", samples);
    publish("querying", "只读车况查询；不调用/event、/sys、升级或控制接口");
    main.postDelayed(deadline, 60000);
    poll();
  }

  private void poll() {
    if (!active) return;
    if (attempts >= 30 || elapsed.getAsLong() - startedElapsed >= 60000) {
      stop("complete", "达到次数或时长上限");
      return;
    }
    attempts++;
    int token = generation;
    long requested = elapsed.getAsLong();
    put("lastRequestAt", wall.getAsLong());
    put("inFlight", true);
    publish("querying", "正在查询ICC快照；不代表已取得实时信号");
    try {
      executor.execute(() -> {
        Reply reply = null;
        Exception failure = null;
        try { reply = transport.query(); } catch (Exception error) { failure = error; }
        Reply result = reply;
        Exception error = failure;
        long received = elapsed.getAsLong(), receivedAt = wall.getAsLong();
        main.post(() -> accept(token, result, error, requested, received, receivedAt));
      });
    } catch (RuntimeException failure) { stop("failed", "查询线程不可用"); }
  }

  private void accept(int token, Reply reply, Exception error, long requested, long received, long receivedAt) {
    if (!active || token != generation) return;
    put("inFlight", false);
    put("lastDurationMillis", Math.max(0, received - requested));
    if (elapsed.getAsLong() - startedElapsed >= 60000) { stop("complete", "达到60秒上限"); return; }
    if (error != null) { stop("failed", failureName(error)); return; }
    if (reply == null) { stop("failed", "empty-reply"); return; }
    put("lastHttpStatus", reply.status);
    if (reply.status != 200) {
      stop("http-" + reply.status, reply.status == 401 || reply.status == 403
          ? "访问被拒绝；不绕过鉴权、不切换其他接口" : "HTTP未成功；不跟随重定向、不自动重试");
      return;
    }
    responses++;
    if (elapsed.getAsLong() - received > 5000) {
      previousGear = null;
      put("queueExpired", report.optInt("queueExpired") + 1);
      publish("queue-expired", "主线程积压，丢弃旧快照");
    } else {
      try {
        IccVehicleWire.Condition condition = IccVehicleWire.decode(reply.body);
        if (condition.knownGear()) {
          validGears++;
          if (previousGear != null && !previousGear.equals(condition.gear)) changes++;
          previousGear = condition.gear;
        } else previousGear = null;
        JSONObject sample = new JSONObject().put("receivedAt", receivedAt)
            .put("requestMillis", Math.max(0, received - requested))
            .put("gear", condition.gearName()).put("gearPresent", condition.gear != null)
            .put("knownGear", condition.knownGear()).put("fields", condition.fields);
        samples.put(sample);
        put("lastSample", sample);
        publish(condition.knownGear() ? "snapshot" : "unknown-gear",
            "回复时间不是车辆信号时间；快照仍需实车对照，不接入音效");
      } catch (Exception failure) { stop("invalid-response", "响应不是可接受的车况结构；不猜测为P挡"); return; }
    }
    if (attempts >= 30) stop("complete", "已完成30次查询");
    else main.postDelayed(tick, 1000);
  }

  void stop(String phase, String detail) {
    if (!active) return;
    active = false;
    generation++;
    previousGear = null;
    main.removeCallbacks(tick);
    main.removeCallbacks(deadline);
    transport.close();
    executor.shutdownNow();
    put("inFlight", false);
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
    diagnostics.iccVehicleProbe(report);
  }

  private void put(String name, Object value) {
    try { report.put(name, value); } catch (Exception ignored) {}
  }

  static String failureName(Exception error) {
    if (error instanceof SocketTimeoutException) return "timeout：ICC查询超时";
    if (error instanceof ConnectException) return "connection-refused：目标拒绝连接";
    if (error instanceof NoRouteToHostException || error instanceof UnknownHostException)
      return "unreachable：当前网络无法到达原厂路由";
    if (error instanceof SecurityException) return "permission-denied：系统禁止该访问";
    String code = error.getMessage();
    if (code != null && code.matches("[a-z-]{1,48}")) return code;
    return "transport-failed：" + error.getClass().getSimpleName();
  }
}
