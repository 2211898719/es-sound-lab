package cn.es.soundlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
  private boolean running, destroyed, silenced;
  private int generation;

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
    diagnostics.line("检测", enabled ? "开始扩展检测，属性和原始日志将保存在本次记录中" : "结束扩展检测，恢复已知事件监听");
    restartSources();
  }

  public void reconnect() {
    if (running) restartSources();
    else begin();
  }

  private void restartSources() {
    stopSources();
    if (!running) return;
    states.clear();
    logTimes.clear();
    gate.reset();
    ruleGate.reset();
    diagnostics.resetLive();
    audio.stop();
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
    logs =
        new LogReader(
            this,
            diagnostics,
            new LogReader.Listener() {
              public void line(String line, long start) {
                Protocol.Sample s = Protocol.decode(line);
                long at = Protocol.logTime(line);
                if (at < start || at > System.currentTimeMillis() + 500) return;
                if (pendingLogs.incrementAndGet() > 128) {
                  pendingLogs.decrementAndGet();
                  return;
                }
                main.post(
                    () -> {
                      try {
                        if (token != generation || destroyed) return;
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
                      if (token == generation) resetSource("日志");
                    });
              }
            },
            diagnostics.scanning);
    logs.start();
  }

  private void stopSources() {
    generation++;
    main.removeCallbacks(flushLegacy);
    legacyDoors.clear();
    if (logs != null) {
      logs.stop();
      logs = null;
    }
    if (vehicles != null) {
      VehicleAccess old = vehicles;
      vehicles = null;
      io.post(() -> old.stop());
    }
  }

  private void resetSource(String source) {
    main.removeCallbacks(flushLegacy);
    legacyDoors.clear();
    for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext(); )
      if (it.next().getValue().source.startsWith(source + "|")) it.remove();
    gate.reset();
    ruleGate.reset();
    diagnostics.resetLive();
    for (Map.Entry<String, State> e : states.entrySet())
      diagnostics.live(e.getKey(), e.getValue().value, e.getValue().source.split("\\|")[0]);
    audio.stop();
    updateLoops();
  }

  private int rank(Protocol.Sample s) {
    if (s.source.equals("日志")) return s.topic.equals("VehicleDoor/TrunkSts") ? 95 : 100;
    if (s.source.equals("Desay")) return 90;
    if (s.source.equals("Mega")) return 80;
    return s.propertyId == Protocol.CURRENT_GEAR ? 65 : 70;
  }

  private void accept(Protocol.Sample s) {
    if (!running) return;
    diagnostics.sample(s);
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
    String source = s.source + "|" + s.topic + "|" + s.area;
    State previous = states.get(s.key);
    if (previous != null && previous.rank > rank(s)) return;
    TransitionGate.Change change = gate.accept(s.key, s.value, source);
    if (s.key.equals("gear")
        && previous != null
        && (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE))
      audio.stop();
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
        else audio.play(event, false);
      }
      diagnostics.line("事件", s.key + " → " + s.value + " · " + s.source);
    }
    updateLoops();
  }

  private void applyRules(Protocol.Sample s) {
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
      if (running) restartSources();
      return;
    }
    if (key.equals("custom.events")) return;
    audio.refresh();
    updateLoops();
  }

  private final Runnable watchdog =
      new Runnable() {
        @Override
        public void run() {
          if (destroyed) return;
          updateLoops();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public void onDestroy() {
    destroyed = true;
    store.prefs.unregisterOnSharedPreferenceChangeListener(this);
    stopSources();
    audio.close();
    microphone.stop();
    main.removeCallbacksAndMessages(null);
    dataThread.quitSafely();
    super.onDestroy();
  }
}
