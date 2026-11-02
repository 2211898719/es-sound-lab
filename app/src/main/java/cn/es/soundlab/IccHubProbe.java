package cn.es.soundlab;

import android.os.Handler;
import android.os.SystemClock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

final class IccHubProbe {
  interface Transport { IccVehicleProbe.Reply query() throws Exception; void close(); }
  private final Handler main;
  private final Diagnostics diagnostics;
  private final Transport transport;
  private final ExecutorService executor;
  private final LongSupplier elapsed, wall;
  private final JSONObject report = new JSONObject();
  private final Runnable deadline = () -> stop("timeout", "服务定位超时；不自动重试");
  private boolean started, active;
  private long startedElapsed;

  IccHubProbe(Handler main, Diagnostics diagnostics) {
    this(main, diagnostics, new IccHubTransport(), Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "icc-hub-info");
      thread.setDaemon(true);
      return thread;
    }), SystemClock::elapsedRealtime, System::currentTimeMillis);
  }

  IccHubProbe(Handler main, Diagnostics diagnostics, Transport transport,
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
    put("proxy", IccVehicleTransport.PROXY_HOST + ":" + IccVehicleTransport.PROXY_PORT);
    put("logicalService", "ota_proxy");
    put("path", "/info");
    put("method", "GET");
    put("readOnly", true);
    put("audioConnected", false);
    put("maxAttempts", 1);
    put("attempts", 1);
    put("maxDurationMillis", 5000);
    put("routes", new JSONArray());
    put("inFlight", true);
    publish("querying", "仅单次读取Hub路由表；不扫描端口、不注册模块，不访问返回地址");
    main.postDelayed(deadline, 5000);
    try {
      executor.execute(() -> {
        IccVehicleProbe.Reply reply = null;
        Exception failure = null;
        try { reply = transport.query(); } catch (Exception error) { failure = error; }
        IccVehicleProbe.Reply result = reply;
        Exception error = failure;
        main.post(() -> accept(result, error));
      });
    } catch (RuntimeException error) { stop("failed", "检测线程不可用；不自动重试"); }
  }

  private void accept(IccVehicleProbe.Reply reply, Exception error) {
    if (!active) return;
    put("durationMillis", Math.max(0, elapsed.getAsLong() - startedElapsed));
    if (elapsed.getAsLong() - startedElapsed >= 5000) { deadline.run(); return; }
    if (error != null) {
      IccHubFailure failure = error instanceof IccHubFailure ? (IccHubFailure) error
          : new IccHubFailure("transport", error, null);
      JSONObject details = failure.snapshot();
      put("failure", details);
      if (details.has("httpStatus")) put("httpStatus", details.optInt("httpStatus"));
      stop("failed", failure.summary());
      return;
    }
    if (reply == null) { stop("failed", "empty-reply"); return; }
    put("httpStatus", reply.status);
    if (reply.status != 200) {
      stop("http-" + reply.status, reply.status == 401 || reply.status == 403
          ? "访问被拒绝；不绕过鉴权" : "HTTP未成功；不跟随重定向、不自动重试");
      return;
    }
    try {
      JSONArray routes = IccHubWire.decode(reply.body);
      int icc = 0, android = 0;
      for (int index = 0; index < routes.length(); index++) {
        if (routes.getJSONObject(index).getString("module").equals("ota_icc_vsi")) icc++;
        else android++;
      }
      put("routes", routes);
      put("iccRouteCount", icc);
      put("androidRouteCount", android);
      stop(icc > 0 ? "complete" : "target-not-listed", icc > 0
          ? "已读取ICC路由条目；空地址或未识别地址不能定位提供者，登记地址也不能单独证明进程、云端依赖或离线可用性"
          : "本次未取得ICC目标路由；不代表服务不存在，请导出记录");
    } catch (Exception decodeError) {
      IccHubFailure failure = new IccHubFailure("decode-routes", decodeError, null);
      put("failure", failure.snapshot());
      stop("invalid-response", failure.summary());
    }
  }

  void stop(String phase, String detail) {
    if (!active) return;
    active = false;
    main.removeCallbacks(deadline);
    transport.close();
    executor.shutdownNow();
    put("inFlight", false);
    put("finishedAt", wall.getAsLong());
    put("durationMillis", Math.max(0, elapsed.getAsLong() - startedElapsed));
    publish(phase, detail);
  }

  private void publish(String phase, String detail) {
    put("active", active);
    put("phase", phase);
    put("detail", detail);
    diagnostics.iccHubProbe(report);
  }

  private void put(String name, Object value) {
    try { report.put(name, value); } catch (Exception ignored) {}
  }
}
