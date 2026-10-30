package cn.es.soundlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MonitorService extends Service
    implements SharedPreferences.OnSharedPreferenceChangeListener {
  public static final String START = "cn.es.soundlab.START", STOP = "cn.es.soundlab.STOP";
  public final Diagnostics diagnostics = new Diagnostics();
  private final Handler main = new Handler(Looper.getMainLooper());
  private HandlerThread dataThread;
  private Handler io;
  private Store store;
  private SoundEngine audio;
  private MicrophoneBridge microphone;
  private VehicleAccess vehicles;
  private LogReader logs;
  private final TransitionGate gate = new TransitionGate(), ruleGate = new TransitionGate();
  private final Map<String, State> states = new HashMap<>();
  private final Map<String, Long> logTimes = new HashMap<>();
  private final AtomicInteger pendingLogs = new AtomicInteger();
  private final Map<String, Protocol.Sample> legacyDoors = new LinkedHashMap<>();
  private long legacyDoorAt;
  private String legacyDoorValue;
  private final Runnable flushLegacy = this::flushLegacyDoors;
  private boolean running, destroyed, silenced, diagnosticLogs;
  private int generation, logGeneration;
  private long previousElapsed, previousUptime;
  private final BroadcastReceiver powerEvents =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          diagnostics.line("生命周期", "屏幕/用户事件 " + intent.getAction());
        }
      };

  private static final class State {
    final String value, source;
    final int rank;
    final long at;

    State(String v, String s, int r) {
      value = v;
      source = s;
      rank = r;
      at = System.currentTimeMillis();
    }
  }

  public final class LocalBinder extends Binder {
    public MonitorService service() {
      return MonitorService.this;
    }
  }

  @Override
  public IBinder onBind(Intent i) {
    return new LocalBinder();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    store = new Store(this);
    diagnostics.attach(this);
    IntentFilter powerFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
    powerFilter.addAction(Intent.ACTION_SCREEN_OFF);
    powerFilter.addAction(Intent.ACTION_USER_PRESENT);
    registerReceiver(powerEvents, powerFilter);
    audio = new SoundEngine(this, store, diagnostics);
    microphone = new MicrophoneBridge(diagnostics);
    store.prefs.registerOnSharedPreferenceChangeListener(this);
    dataThread = new HandlerThread("ES-VehicleIO");
    dataThread.start();
    io = new Handler(dataThread.getLooper());
    main.post(watchdog);
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    diagnostics.line("生命周期", "服务启动 action=" + (i == null ? "系统恢复(null)" : i.getAction()));
    if (i != null && STOP.equals(i.getAction())) {
      end();
      return START_NOT_STICKY;
    }
    if ((i != null && START.equals(i.getAction())) || store.prefs.getBoolean("monitor", false))
      begin();
    else stopSelf();
    return running ? START_STICKY : START_NOT_STICKY;
  }

  public void begin() {
    if (destroyed) return;
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(
        new NotificationChannel("vehicle", "车辆音效监听", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            1,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            2,
            new Intent(this, MonitorService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        101,
        new Notification.Builder(this, "vehicle")
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("ES 音效实验室")
            .setContentText("正在监听车辆事件 · 点击打开检测页")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止监听", stop).build())
            .build());
    store.prefs.edit().putBoolean("monitor", true).apply();
    if (running) return;
    running = true;
    silenced = false;
    diagnostics.monitoring = true;
    restartSources();
  }

  public void end() {
    running = false;
    diagnostics.monitoring = false;
    diagnostics.scanning = false;
    store.prefs.edit().putBoolean("monitor", false).apply();
    diagnosticLogs = false;
    diagnostics.diagnosticLogs = false;
    stopSources();
    states.clear();
    gate.reset();
    ruleGate.reset();
    diagnostics.resetLive();
    audio.stop();
    microphone.stop();
    diagnostics.nativeStatus = "车辆监听已停止";
    diagnostics.logStatus = "日志监听已停止";
    stopForeground(true);
    stopSelf();
  }

  public boolean running() {
    return running;
  }

  public boolean readLogsGranted() {
    return checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED;
  }

  public void discovery(boolean enabled) {
    if (enabled && !running) begin();
    if (diagnostics.scanning == enabled) return;
    diagnostics.scanning = enabled;
    diagnostics.line("检测", enabled ? "开始原生属性扩展检测；日志采集由独立开关控制" : "结束扩展检测，保留原生事件基线");
    if (logs != null) logs.discovery(enabled);
    // The active native baseline survives toggling discovery; rebuild only on explicit reconnect.
    if (vehicles != null) {
      VehicleAccess active = vehicles;
      io.post(() -> active.discovery(enabled));
    }
  }

  public void reconnect() {
    if (running) {
      stopLogs();
      resetSource("日志");
      logTimes.clear();
      restartSources();
    } else begin();
  }

  private void restartSources() {
    restartVehicles();
    if (running && diagnosticLogs && logs == null) startLogs();
    if (!diagnosticLogs) diagnostics.logStatus = "日志采集已关闭 · 音效由原生属性触发";
  }

  private void restartVehicles() {
    diagnostics.line("生命周期", "重建 Mega 原生接口，扩展检测=" + diagnostics.scanning);
    stopVehicles();
    if (!running) return;
    resetSource("Mega");
    final int token = generation;
    vehicles =
        new VehicleAccess(
            this,
            io,
            diagnostics,
            store,
            new VehicleAccess.Listener() {
              public void sample(Protocol.Sample s) {
                main.post(
                    () -> {
                      if (token == generation && !destroyed) accept(s);
                    });
              }

              public void reset(String source) {
                main.post(
                    () -> {
                      if (token == generation) resetSource(source);
                    });
              }
            });
    vehicles.start(diagnostics.scanning);
  }

  public boolean diagnosticLogs() {
    return diagnosticLogs;
  }

  public void diagnosticLogs(boolean enabled) {
    diagnosticLogs = enabled;
    diagnostics.diagnosticLogs = enabled;
    if (enabled) {
      if (!running) begin();
      if (logs == null) startLogs();
    } else {
      stopLogs();
      diagnostics.logStatus = "日志采集已关闭 · 音效由原生属性触发";
    }
  }

  private void startLogs() {
    final int token = logGeneration;
    logs =
        new LogReader(
            this,
            diagnostics,
            new LogReader.Listener() {
              public void line(String line, long start) {
                Protocol.Sample s = Protocol.decode(line);
                long at = Protocol.logTime(line);
                if (at < start || at > System.currentTimeMillis() + 500) return;
                if (Protocol.noisy(line) && !store.watches(s)) {
                  diagnostics.filteredLog();
                  return;
                }
                if (pendingLogs.incrementAndGet() > 128) {
                  pendingLogs.decrementAndGet();
                  diagnostics.droppedLog();
                  return;
                }
                main.post(
                    () -> {
                      try {
                        if (token != logGeneration || destroyed || !running) return;
                        diagnostics.line("原始", line);
                        if (s != null) {
                          Long last = logTimes.get(s.identity());
                          if (last != null && s.time <= last) return;
                          if (logTimes.size() >= 512 && last == null)
                            logTimes.remove(logTimes.keySet().iterator().next());
                          logTimes.put(s.identity(), s.time);
                          accept(s);
                        }
                      } finally {
                        pendingLogs.decrementAndGet();
                      }
                    });
              }

              public void reset() {
                main.post(
                    () -> {
                      if (token == logGeneration && !destroyed) resetSource("日志");
                    });
              }
            },
            diagnostics.scanning);
    logs.start();
  }

  private void stopSources() {
    stopLogs();
    stopVehicles();
  }

  private void stopLogs() {
    logGeneration++;
    if (logs != null) {
      logs.stop();
      logs = null;
    }
  }

  private void stopVehicles() {
    generation++;
    main.removeCallbacks(flushLegacy);
    legacyDoors.clear();
    if (vehicles != null) {
      VehicleAccess old = vehicles;
      vehicles = null;
      io.post(() -> old.stop());
    }
  }

  private void resetSource(String source) {
    if (source.equals("Mega")) {
      main.removeCallbacks(flushLegacy);
      legacyDoors.clear();
    }
    for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, State> entry = it.next();
      if (!entry.getValue().source.startsWith(source + "|")) continue;
      diagnostics.removeLive(entry.getKey());
      if (entry.getKey().equals("gear")) audio.invalidateGear();
      if (entry.getKey().equals("autohold")) {
        audio.cancelAutoHold();
        diagnostics.autoHoldDecision(source + "来源中断，等待新样本建立基线");
      }
      if (entry.getKey().equals("lock")) {
        audio.cancelLock();
        diagnostics.lockDecision(source + "来源中断，等待新锁样本建立基线；不补播历史事件");
      }
      it.remove();
    }
    gate.forgetSource(source);
    ruleGate.forgetSource(source);
    updateLoops();
  }

  private int rank(Protocol.Sample s) {
    if (s.source.equals("日志")) return 0;
    if (s.source.equals("Mega")) return s.propertyId == 100663305 ? 75 : 100;
    return s.propertyId == Protocol.CURRENT_GEAR ? 65 : 70;
  }

  private void accept(Protocol.Sample s) {
    if (!running) return;
    diagnostics.sample(s);
    // All log/legacy samples are observational only; only Mega properties drive audio/rules.
    if (!s.source.equals("Mega")) return;
    applyRules(s);
    if (s.key == null) return;
    // The legacy property can broadcast a single aggregate value to all four areas.
    // Match the original adapter's 80 ms / three-door suppression without delaying gears.
    if (s.source.equals("Mega") && s.propertyId == 100663305 && s.value != null) {
      long now = android.os.SystemClock.uptimeMillis();
      if (!legacyDoors.isEmpty() && (!s.value.equals(legacyDoorValue) || now - legacyDoorAt > 80))
        flushLegacyDoors();
      if (legacyDoors.isEmpty()) {
        legacyDoorAt = now;
        legacyDoorValue = s.value;
      }
      legacyDoors.put(s.key, s);
      main.removeCallbacks(flushLegacy);
      main.postDelayed(flushLegacy, 80);
      return;
    }
    acceptKnown(s, false);
  }

  private void flushLegacyDoors() {
    main.removeCallbacks(flushLegacy);
    boolean aggregate = legacyDoors.size() >= 3;
    for (Protocol.Sample s : legacyDoors.values()) acceptKnown(s, aggregate);
    if (aggregate) diagnostics.line("车门过滤", "已抑制旧版接口的多车门同值广播，保留状态基线");
    legacyDoors.clear();
  }

  private void acceptKnown(Protocol.Sample s, boolean silent) {
    String autoHoldRaw = "autohold".equals(s.key) ? s.raw : null;
    if (autoHoldRaw != null)
      s =
          new Protocol.Sample(
              s.topic,
              s.propertyId,
              s.area,
              s.status,
              s.raw,
              s.time,
              s.source,
              s.key,
              store.autoHoldState(s.value));
    String lockRaw = "lock".equals(s.key) ? s.raw : null;
    if (s.key.equals("lock"))
      s =
          new Protocol.Sample(
              s.topic,
              s.propertyId,
              s.area,
              s.status,
              s.raw,
              s.time,
              s.source,
              s.key,
              store.lockState(s.value));
    String source = s.source + "|" + s.topic + "|" + s.area;
    State previous = states.get(s.key);
    if (previous != null && previous.rank > rank(s)) return;
    TransitionGate.Change change = gate.accept(s.key, s.value, source);
    if (autoHoldRaw != null) {
      String reason =
          !store.autoHoldCalibrated()
              ? "未校准，不触发"
              : s.value == null
                  ? "无效或未知状态，不触发"
                  : change == TransitionGate.Change.BASELINE
                      ? "首样本/重连基线，不触发"
                      : change != TransitionGate.Change.CHANGED
                          ? "状态未变化，不触发"
                          : !store.enabled("autohold") ? "音效开关关闭" : "状态变化，交由音效队列处理";
      diagnostics.autoHoldDecision("原始值=" + autoHoldRaw + " · " + reason);
      if (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE)
        audio.cancelAutoHold();
    }
    if (lockRaw != null) {
      String reason =
          !store.lockCalibrated()
              ? "未校准，不触发"
              : s.value == null
                  ? "状态无效或不在校准值中，不触发"
                  : change == TransitionGate.Change.BASELINE
                      ? "首样本/重连基线，不触发"
                      : change != TransitionGate.Change.CHANGED
                          ? "状态未变化，不触发"
                          : silent ? "静默样本，不触发" : store.lockReadiness();
      diagnostics.lockDecision("原始值=" + lockRaw + " · " + reason);
    }
    if (s.key.equals("lock")
        && (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE))
      audio.cancelLock();
    if (s.key.equals("gear")
        && previous != null
        && (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE))
      audio.invalidateGear();
    if (s.value == null) {
      states.remove(s.key);
      diagnostics.live(s.key, "不可用", s.source);
      updateLoops();
      return;
    }
    states.put(s.key, new State(s.value, source, rank(s)));
    diagnostics.live(s.key, s.value, s.source);
    if (change == TransitionGate.Change.CHANGED && !silent) {
      silenced = false;
      String event = Protocol.event(s.key, s.value);
      if (event != null) {
        if (s.key.equals("gear")) audio.gearChanged(event);
        else if (s.key.equals("autohold")) audio.autoHoldChanged(event);
        else audio.play(event, false);
      }
      diagnostics.line("事件", s.key + " → " + s.value + " · " + s.source);
    }
    updateLoops();
  }

  private void applyRules(Protocol.Sample s) {
    if ("lock".equals(s.key) || "autohold".equals(s.key)) return;
    JSONArray rules = store.rules();
    for (int i = 0; i < rules.length(); i++) {
      JSONObject r = rules.optJSONObject(i);
      if (r == null
          || !r.optString("source").equals(s.source)
          || !r.optString("topic").equals(s.topic)
          || r.optLong("propertyId") != s.propertyId
          || !r.optString("area").equals(s.area)) continue;
      String value = s.status == 0 ? s.raw : null;
      TransitionGate.Change change = ruleGate.accept(r.optString("id"), value, s.identity());
      if (change == TransitionGate.Change.CHANGED && r.optString("value").equals(value)) {
        String event = r.optString("event");
        silenced = false;
        audio.play(event, false);
        diagnostics.line("自定义规则", r.optString("name") + " → " + event);
      }
    }
  }

  private void updateLoops() {
    if (!running || microphone.active() || silenced) {
      audio.loop(null, 1);
      return;
    }
    State gear = states.get("gear"), speed = states.get("speed");
    String g = gear == null ? "" : gear.value;
    double v = speed == null ? Double.NaN : Protocol.number(speed.value);
    boolean fresh = speed != null && System.currentTimeMillis() - speed.at <= 5000;
    String id = null;
    float rate = 1;
    if (g.equals("2") && store.enabled("reverse")) id = "reverse.loop";
    else if (fresh && v > 0 && (g.equals("4") || g.equals("5"))) {
      if (store.enabled("pedestrian") && v <= store.prefs.getInt("lowSpeed", 20))
        id = "pedestrian.loop";
      else if (store.enabled("engine")) {
        id = "engine.loop";
        rate = (float) Math.min(1.8, .65 + v / 65);
      }
    }
    audio.loop(id, rate);
  }

  public void preview(String id) {
    microphone.stop();
    silenced = false;
    audio.play(id, true);
  }

  public void stopAudio() {
    silenced = true;
    microphone.stop();
    audio.stop();
  }

  public void startMicrophone() {
    if (checkSelfPermission("android.permission.RECORD_AUDIO")
        != PackageManager.PERMISSION_GRANTED) {
      diagnostics.line("喊话", "麦克风权限未授予");
      return;
    }
    audio.stop();
    microphone.start(audio.selectedDevice(), store.volume("shout"));
  }

  public void stopMicrophone() {
    microphone.stop();
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences p, String key) {
    if (destroyed || key.equals("monitor") || key.startsWith("selected.")) return;
    if (key.equals("rules")) {
      ruleGate.reset();
      if (running && vehicles != null) {
        VehicleAccess active = vehicles;
        boolean scan = diagnostics.scanning;
        io.post(() -> active.discovery(scan));
      }
      return;
    }
    if (key.equals("custom.events")) return;
    if (key.equals("mega.gear.sdkEnums")) {
      if (running) restartVehicles();
      return;
    }
    if (key.equals("autohold.holding") || key.equals("autohold.released")) {
      states.remove("autohold");
      gate.forget("autohold");
      diagnostics.removeLive("autohold");
      diagnostics.autoHoldDecision("校准已改变，等待新的有效状态建立基线");
    }
    if (key.equals("lock.locked") || key.equals("lock.unlocked")) {
      diagnostics.lockDecision("校准已改变，等待新的有效锁状态建立基线");
      states.remove("lock");
      gate.forget("lock");
      diagnostics.removeLive("lock");
    }
    if (key.startsWith("lock.output."))
      diagnostics.lockOutputStatus =
          p.getBoolean("lock.output.confirmed", false)
              ? "车外设备已人工确认，等待实际播放时核验路由"
              : "车外配置已改变，须重新测试并人工确认";
    audio.refresh(key);
    updateLoops();
  }

  private final Runnable watchdog =
      new Runnable() {
        @Override
        public void run() {
          if (destroyed) return;
          long elapsed = android.os.SystemClock.elapsedRealtime();
          long uptime = android.os.SystemClock.uptimeMillis();
          if (previousElapsed != 0) {
            long suspended = (elapsed - previousElapsed) - (uptime - previousUptime);
            if (suspended > 2000)
              diagnostics.line("生命周期", "检测到系统挂起间隔约 " + suspended + " ms（非重启证据）");
          }
          previousElapsed = elapsed;
          previousUptime = uptime;
          updateLoops();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public void onDestroy() {
    diagnostics.line("生命周期", "服务销毁；非正常进程终止可能没有此记录");
    destroyed = true;
    unregisterReceiver(powerEvents);
    store.prefs.unregisterOnSharedPreferenceChangeListener(this);
    stopSources();
    audio.close();
    microphone.stop();
    main.removeCallbacksAndMessages(null);
    dataThread.quitSafely();
    super.onDestroy();
  }
}
