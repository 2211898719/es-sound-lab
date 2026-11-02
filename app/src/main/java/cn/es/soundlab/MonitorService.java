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
import android.os.IBinder;
import android.os.Looper;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class MonitorService extends Service
    implements SharedPreferences.OnSharedPreferenceChangeListener {
  public static final String START = "cn.es.soundlab.START", STOP = "cn.es.soundlab.STOP";
  public final Diagnostics diagnostics = new Diagnostics();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Store store;
  private SoundEngine audio;
  private MicrophoneBridge microphone;
  private IccVehicleSource iccSource;
  interface IccSourceFactory { IccVehicleSource create(IccVehicleSource.Listener listener); }
  private IccSourceFactory iccSourceFactory = listener ->
      new IccVehicleSource(main, diagnostics, listener, store.iccIntervalMillis());
  private LogReader logs;
  private boolean logEventsActive;
  private final TransitionGate gate = new TransitionGate();
  private final Map<String, State> states = new HashMap<>();
  private final Map<String, Long> logTimes = new HashMap<>();
  private final AtomicInteger pendingLogs = new AtomicInteger();
  private final AtomicInteger logEpoch = new AtomicInteger();
  private int acceptedLogEpoch;
  private java.util.function.LongSupplier logClock = System::currentTimeMillis;
  private boolean running, destroyed, silenced, diagnosticLogs, lockParkedBaseline;
  private volatile int logGeneration;
  private long previousElapsed, previousUptime;
  private String observedIccLock;
  private final EngineMotion engineMotion = new EngineMotion();
  private EngineProfile engineProfile;
  private EnginePowertrain enginePowertrain;
  private float enginePitch = 1, engineGain;
  private long engineFrameAt;
  private boolean engineMuted;
  private EngineDemo engineDemo;
  private long demoFrameAt, demoHeartbeat;
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
    final long elapsed;

    State(String v, String s, int r) {
      value = v;
      source = s;
      rank = r;
      at = System.currentTimeMillis();
      elapsed = android.os.SystemClock.elapsedRealtime();
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
    engineProfile = store.engineProfile();
    enginePowertrain = new EnginePowertrain(store.engineTuning(), store.engineHorsepower(), store.engineZeroHundred());
    diagnostics.attach(this);
    IntentFilter powerFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
    powerFilter.addAction(Intent.ACTION_SCREEN_OFF);
    powerFilter.addAction(Intent.ACTION_USER_PRESENT);
    registerReceiver(powerEvents, powerFilter);
    audio = new SoundEngine(this, store, diagnostics);
    audio.lockPlaybackAllowed(() -> running && !diagnostics.calibrating && parkedGear());
    microphone = new MicrophoneBridge(diagnostics);
    store.prefs.registerOnSharedPreferenceChangeListener(this);
    main.post(watchdog);
    main.post(engineTicker);
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
    stopEngineDemo();
    running = false;
    diagnostics.monitoring = false;
    diagnostics.calibrating = false;
    diagnostics.scanning = false;
    store.prefs.edit().putBoolean("monitor", false).apply();
    diagnosticLogs = false;
    diagnostics.diagnosticLogs = false;
    stopSources();
    states.clear();
    gate.reset();
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
    diagnostics.line("检测", enabled ? "扩展日志观察（不连接旧车辆探针）" : "恢复已知日志观察");
    if (logs != null) logs.discovery(enabled);
  }

  public void reconnect() {
    if (running) {
      stopIccSource();
      stopLogs();
      resetSource("日志");
      logTimes.clear();
      restartSources();
    } else begin();
  }

  private void restartSources() {
    diagnostics.nativeStatus = "ICC接口 + 用户授权日志；已移除无效读取路线";
    if (!running) return;
    startIccSource();
    if (store.logsEnabled()) startLogs();
    else diagnostics.logStatus = "日志未启用；车门、尾门和AutoHold等待用户授权";
  }

  public boolean logsEnabled() { return store.logsEnabled(); }

  public void logsEnabled(boolean enabled) { store.logsEnabled(enabled); }

  public boolean diagnosticLogs() { return diagnosticLogs; }

  public void diagnosticLogs(boolean enabled) { logsEnabled(enabled); }

  private void startLogs() {
    if (destroyed || !running || !store.logsEnabled() || logs != null) return;
    final int token = logGeneration;
    logEventsActive = true;
    diagnosticLogs = true;
    diagnostics.diagnosticLogs = true;
    logTimes.clear();
    logs = new LogReader(this, diagnostics, logListener(token), true);
    logs.start();
  }

  LogReader.Listener logListener(final int token) {
    return new LogReader.Listener() {
      public void line(String line, long start) {
        long received = logClock.getAsLong();
        String normalized = LogFormat.normalize(line, received);
        Protocol.Sample s = normalized == null ? null : Protocol.decode(normalized);
        long at = normalized == null ? -1 : Protocol.logTime(normalized);
        if (token != logGeneration || destroyed) return;
        if (!LogFormat.fresh(at, start, received) || s == null) {
          diagnostics.logSample(false);
          if (s != null) logEpoch.incrementAndGet();
          String reason = normalized == null ? "未知时间格式" : s == null ? "未匹配已支持结构" : "过期/早于连接/超前";
          if (Protocol.relevant(line))
            diagnostics.line("日志未采用", reason + " · " + line);
          return;
        }
        if (!store.acceptsLogKey(s.key)) {
          diagnostics.filteredLog();
          return;
        }
        int epoch = logEpoch.get();
        if (pendingLogs.incrementAndGet() > 128) {
          pendingLogs.decrementAndGet();
          logEpoch.incrementAndGet();
          diagnostics.droppedLog();
          return;
        }
        main.post(
            () -> {
              try {
                if (token != logGeneration || destroyed || !running) return;
                if (epoch != logEpoch.get()) return;
                if (acceptedLogEpoch != epoch) {
                  resetSource("日志");
                  logTimes.clear();
                  acceptedLogEpoch = epoch;
                }
                long processedAt = logClock.getAsLong();
                boolean fresh = LogFormat.fresh(at, start, processedAt);
                if (!fresh) {
                  diagnostics.logSample(false);
                  resetSource("日志");
                  return;
                }
                diagnostics.line("原始", line);
                if (s != null) {
                  Long last = logTimes.get(s.identity());
                  if (last != null && s.time <= last) {
                    diagnostics.logSample(false);
                    return;
                  }
                  if (logTimes.size() >= 512 && last == null)
                    logTimes.remove(logTimes.keySet().iterator().next());
                  logTimes.put(s.identity(), s.time);
                  Protocol.Sample calibrated = store.calibratedLogSample(s);
                  diagnostics.logSample(calibrated.key != null && calibrated.value != null);
                  accept(s);
                }
              } finally {
                pendingLogs.decrementAndGet();
              }
            });
      }

      public void reset() {
        if (token != logGeneration || destroyed) return;
        logEpoch.incrementAndGet();
        main.post(
            () -> {
              if (token == logGeneration && !destroyed) {
                resetSource("日志");
                logTimes.clear();
              }
            });
      }
    };
  }

  private void stopSources() {
    stopIccSource();
    stopLogs();
  }

  private void startIccSource() {
    if (destroyed || !running || iccSource != null && iccSource.active()) return;
    iccSource = iccSourceFactory.create(new IccVehicleSource.Listener() {
      public void sample(Protocol.Sample sample) {
        if (!destroyed && running) accept(sample);
      }
      public void reset() { resetSource("ICC"); }
    });
    iccSource.start();
  }

  private void stopIccSource() {
    if (iccSource != null) {
      iccSource.stop("stopped", "停止监听、切换来源或手动重连；清除ICC基线");
      iccSource = null;
    }
    resetSource("ICC");
  }

  private void stopLogs() {
    logGeneration++;
    logEventsActive = false;
    diagnosticLogs = false;
    diagnostics.diagnosticLogs = false;
    if (logs != null) {
      logs.stop();
      logs = null;
    }
  }

  private void resetSource(String source) {
    if (source.equals("ICC")) {
      engineMotion.reset();
      lockParkedBaseline = false;
      audio.cancelLock();
      diagnostics.lockDecision("ICC来源中断；P挡依据失效，取消自动锁声，不沿用历史P挡");
      observedIccLock = null;
    }
    for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, State> entry = it.next();
      if (!entry.getValue().source.startsWith(source + "|")) continue;
      diagnostics.removeLive(entry.getKey());
      if (entry.getKey().equals("gear")) audio.invalidateGear();
      if (entry.getKey().equals("charge")) audio.cancelCharge();
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
    if (source.equals("ICC")) for (Map.Entry<String, Diagnostics.Live> entry : diagnostics.live().entrySet())
      if (entry.getValue().source.equals("ICC")) diagnostics.removeLive(entry.getKey());
    updateLoops();
  }

  private int rank(Protocol.Sample sample) {
    return sample.source.equals("ICC") ? 100 : 50;
  }

  private void accept(Protocol.Sample sample) {
    if (!running) return;
    boolean icc = IccVehicleWire.state(sample) && iccSource != null && iccSource.active();
    boolean log = sample != null && sample.source.equals("日志") && logEventsActive
        && store.logsEnabled() && store.acceptsLogKey(sample.key);
    if (!icc && !log) return;
    if (icc && sample.key.equals("lock") && store.logLockSelected()) {
      String observation = sample.raw + "/" + sample.value;
      if (!observation.equals(observedIccLock)) {
        diagnostics.line("ICC锁对照", "原始值=" + sample.raw + " · 仅观察；当前锁触发来源=授权日志");
        observedIccLock = observation;
      }
      return;
    }
    sample = store.calibratedLogSample(sample);
    diagnostics.sample(sample);
    acceptKnown(sample, diagnostics.calibrating);
  }

  private void acceptKnown(Protocol.Sample s, boolean silent) {
    if (s.key.equals("gear") && !"4".equals(s.value)) engineMotion.reset();
    boolean parkedLock = !s.key.equals("lock") || lockParkedBaseline && parkedGear();
    if (s.key.equals("gear") && !"1".equals(s.value)) {
      if (lockParkedBaseline)
        diagnostics.lockDecision("ICC挡位=" + s.raw + "，P挡依据失效，取消自动锁声，不补播");
      lockParkedBaseline = false;
      audio.cancelAutomaticLock();
    }
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
    if (s.key.equals("lock") && !IccVehicleWire.state(s))
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
    if (s.key.equals("lock")) lockParkedBaseline = s.value != null && parkedGear();
    State previous = states.get(s.key);
    if (previous != null && previous.rank > rank(s)) return;
    diagnostics.stateReading(s);
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
                          : silent ? "校准/静默样本，不触发"
                          : !store.enabled("autohold") ? "音效开关关闭" : "状态变化，交由音效队列处理";
      diagnostics.autoHoldDecision("原始值=" + autoHoldRaw + " · " + reason);
      if (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE)
        audio.cancelAutoHold();
    }
    if (lockRaw != null) {
      String reason =
          !store.lockTriggerCalibrated()
              ? "未校准，不触发"
              : s.value == null
                  ? "状态无效或不在校准值中，不触发"
                  : change == TransitionGate.Change.BASELINE
                      ? "首样本/重连基线，不触发"
                      : change != TransitionGate.Change.CHANGED
                          ? "状态未变化，不触发"
                          : silent ? "静默样本，不触发"
                          : !parkedLock ? "未先建立新鲜P挡基线，不触发、不补播" : store.lockReadiness();
      State gear = states.get("gear");
      diagnostics.lockDecision("来源=" + s.source + " · 原始值=" + lockRaw + " · " + reason
          + " · 新鲜P挡=" + parkedGear() + " · ICC挡位=" + (gear == null ? "不可用" : gear.value)
          + (change == TransitionGate.Change.SAME || gear == null ? ""
              : " · 挡位回复距今=" + (System.currentTimeMillis() - gear.at) + "ms"));
    }
    if (s.key.equals("lock")
        && (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE))
      audio.cancelLock();
    if (s.key.equals("gear")
        && previous != null
        && (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE))
      audio.invalidateGear();
    if (s.key.equals("charge") && (change == TransitionGate.Change.INVALID || change == TransitionGate.Change.BASELINE))
      audio.cancelCharge();
    if (s.value == null) {
      if (s.key.equals("speed")) engineMotion.reset();
      states.remove(s.key);
      diagnostics.live(s.key, "不可用", s.source);
      updateLoops();
      return;
    }
    states.put(s.key, new State(s.value, source, rank(s)));
    if (s.key.equals("speed") && s.source.equals("ICC")) {
      Integer kmh = IccSpeedReading.kmh(s);
      if (kmh == null || IccSpeedReading.omittedZero(s)) engineMotion.reset();
      else engineMotion.sample(kmh, android.os.SystemClock.elapsedRealtime(), engineProfile);
    }
    diagnostics.live(s.key, s.value, s.source);
    boolean confirmed = !IccVehicleWire.state(s) || !s.key.equals("lock") && !s.key.equals("charge")
        || store.iccTriggerConfirmed(s.key);
    if (change == TransitionGate.Change.CHANGED && !silent && confirmed && parkedLock) {
      silenced = false;
      String event = Protocol.event(s.key, s.value);
      if (event != null) {
        if (s.key.equals("gear")) audio.gearChanged(event);
        else if (s.key.equals("charge")) audio.chargeChanged(event);
        else if (s.key.equals("autohold")) audio.autoHoldChanged(event);
        else audio.play(event, false);
      }
      diagnostics.line("事件", s.key + " → " + s.value + " · " + s.source);
    }
    updateLoops();
  }

  private void updateLoops() {
    long now = android.os.SystemClock.elapsedRealtime();
    if (engineDemo != null && engineDemo.active) {
      if (demoUnsafe() || diagnostics.calibrating || microphone.active()) finishEngineDemo("实车非驻车状态或正在校准/喊话，演示已停止");
      else if (now - demoHeartbeat > 750 || now < demoHeartbeat) finishEngineDemo("演示页面失去响应，已停止");
      else if (!audio.engineDemoActive()) finishEngineDemo("音频焦点或播放不可用，演示已停止");
      else {
        if (now > demoFrameAt) engineDemo.step((now - demoFrameAt) / 1000f, engineProfile);
        demoFrameAt = now;
        if (engineDemo.active) audio.engine(engineDemo.pitch, engineDemo.gain, engineDemo.load);
        else audio.endEngineDemo();
        diagnostics.engineDemoReading = engineDemo.json(audio.engineStatus(), store.volume("engine"));
        diagnostics.enginePowertrain = engineDemo.powertrain() == null ? new org.json.JSONObject() : engineDemo.powertrain().json();
        diagnostics.engineReading("虚拟视听演示中，实车声浪暂让行", false, engineMotion, 1, 0, 0, audio.engineStatus());
        diagnostics.engineMode("demo");
        return;
      }
    }
    State gear = states.get("gear"), power = states.get("power"), speed = states.get("speed");
    boolean drive = freshIcc(gear, now) && gear.value.equals("4");
    boolean powered = freshIcc(power, now) && (power.value.equals("on") || power.value.equals("start"));
    boolean powerOff = freshIcc(power, now) && !powered;
    // A fresh successful ICC reply may omit speed at standstill on this vehicle.
    // Use that convention only for idle; never feed an omitted value to the acceleration fit.
    boolean idle = store.engineSynth() && powered && freshIcc(gear, now)
        && (gear.value.equals("1") || gear.value.equals("3") || drive)
        && freshIcc(speed, now) && speed.value.equals("0");
    boolean moving = drive && !powerOff && engineMotion.ready(now)
        && engineMotion.raw() > 1 && engineMotion.speed() > 1;
    String status = !store.enabled("engine") ? "声浪已关闭" : !running ? "车辆监听已停止"
        : diagnostics.calibrating ? "校准中，声浪暂停" : silenced || engineMuted ? "已停止播放；重新启用声浪以恢复"
        : microphone.active() ? "喊话中，声浪暂停" : powerOff ? "车辆未上电，声浪暂停"
        : idle ? "车辆已上电，怠速声浪运行中（虚拟）" : !drive ? "等待新鲜ICC上电/挡位/静止状态；行驶声浪仅D挡"
        : !engineMotion.ready(now) ? "等待连续有效车速；缺失字段不用于拟合"
        : engineMotion.raw() <= 1 || engineMotion.speed() <= 1 ? "静止或低于起声速度" : "车速拟合运行中（非真实油门）";
    boolean active = store.enabled("engine") && running && !diagnostics.calibrating && !silenced && !engineMuted
        && !microphone.active() && (idle || moving);
    if (!active) {
      enginePowertrain.reset();
      engineGain = 0;
      enginePitch = 1;
      engineFrameAt = now;
      if (!drive || powerOff || !running || !store.enabled("engine") || silenced || engineMuted || diagnostics.calibrating)
        engineMotion.reset();
      audio.loop(null, 1);
    } else if (idle) {
      enginePowertrain.reset();
      engineMotion.reset();
      engineFrameAt = now;
      enginePitch = store.engineTuning().idle() / 3000f;
      engineGain = store.engineIdleVolume() / 100f;
      audio.engine(enginePitch, engineGain, 0);
    } else {
      float load = engineProfile.load(engineMotion.acceleration());
      long frameMillis = Math.min(200, Math.max(0, now - engineFrameAt));
      float blend = 1 - (float) Math.exp(-frameMillis / (double) engineProfile.responseMillis);
      enginePitch += blend * (engineProfile.pitch(engineMotion.speed(), load) - enginePitch);
      engineGain += blend * (engineProfile.gain(engineMotion.speed(), load) - engineGain);
      engineFrameAt = now;
      if (store.engineSynth()) {
        if (frameMillis > 0) enginePowertrain.step(engineMotion.speed(), engineMotion.acceleration(), Float.NaN, frameMillis / 1000f);
        enginePitch = enginePowertrain.rpm / 3000f;
        engineGain = Math.max(engineGain, store.engineIdleVolume() / 100f);
        audio.engine(enginePitch, engineGain, enginePowertrain.load);
      } else audio.engine(enginePitch, engineGain, load);
    }
    diagnostics.engineReading(status, active && !idle, engineMotion, enginePitch, engineGain,
        store.engineSynth() ? enginePowertrain.load : engineProfile.load(engineMotion.acceleration()), audio.engineStatus());
    diagnostics.engineMode(idle && active ? "idle" : moving && active ? "drive" : "paused");
    diagnostics.enginePowertrain = store.engineSynth() ? enginePowertrain.json() : new org.json.JSONObject();
  }

  private boolean freshIcc(State state, long now) {
    return state != null && state.source.startsWith("ICC|") && now >= state.elapsed
        && now - state.elapsed <= EngineMotion.MAX_AGE;
  }

  private final Runnable engineTicker = new Runnable() {
    @Override public void run() {
      if (destroyed) return;
      updateLoops();
      main.postDelayed(this, 100);
    }
  };

  boolean startEngineDemo(int horsepower, float zeroHundred) {
    if (destroyed || diagnostics.calibrating || microphone.active() || demoUnsafe()) return false;
    engineDemo = new EngineDemo(horsepower, zeroHundred);
    if (store.engineSynth()) engineDemo.synth(store.engineTuning());
    demoFrameAt = demoHeartbeat = android.os.SystemClock.elapsedRealtime();
    engineMotion.reset();
    audio.beginEngineDemo();
    diagnostics.line("模拟声浪", "开始虚拟油门视听 · " + horsepower + "匹 / " + zeroHundred + "秒；不写入实车状态");
    return true;
  }

  void engineDemoThrottle(float value) {
    if (engineDemo != null && engineDemo.active) {
      engineDemo.throttle(value);
      demoHeartbeat = android.os.SystemClock.elapsedRealtime();
    }
  }

  EngineDemo engineDemo() { return engineDemo; }

  void stopEngineDemo() { finishEngineDemo("虚拟视听演示已停止"); }

  private void finishEngineDemo(String reason) {
    if (engineDemo == null || !engineDemo.active) return;
    engineDemo.stop(reason);
    audio.endEngineDemo();
    engineMotion.reset();
    engineGain = 0;
    enginePitch = 1;
    engineFrameAt = android.os.SystemClock.elapsedRealtime();
    diagnostics.engineDemoReading = engineDemo.json(audio.engineStatus(), store.volume("engine"));
    diagnostics.line("模拟声浪", reason);
  }

  private boolean demoUnsafe() {
    long now = android.os.SystemClock.elapsedRealtime();
    State gear = states.get("gear"), speed = states.get("speed");
    if (gear != null && gear.source.startsWith("ICC|") && now >= gear.elapsed
        && now - gear.elapsed <= EngineMotion.MAX_AGE && !"1".equals(gear.value)) return true;
    if (speed != null && speed.source.startsWith("ICC|") && now >= speed.elapsed
        && now - speed.elapsed <= EngineMotion.MAX_AGE) {
      try { return Float.parseFloat(speed.value) > 1; }
      catch (Exception ignored) {}
    }
    return false;
  }

  private boolean parkedGear() {
    State gear = states.get("gear");
    long now = System.currentTimeMillis();
    return gear != null && gear.source.startsWith("ICC|") && gear.value.equals("1")
        && now >= gear.at && now - gear.at <= 5000;
  }

  public void preview(String id) {
    stopEngineDemo();
    microphone.stop();
    silenced = false;
    audio.play(id, true);
    if (id.equals("engine.loop")) audio.limitEnginePreview();
  }

  public void stopAudio() {
    stopEngineDemo();
    silenced = true;
    engineMuted = true;
    microphone.stop();
    audio.stop();
    updateLoops();
  }

  public void calibrationMode(boolean enabled) {
    if (destroyed || diagnostics.calibrating == enabled) return;
    stopEngineDemo();
    diagnostics.calibrating = enabled;
    lockParkedBaseline = false;
    if (iccSource != null) iccSource.invalidate("校准模式改变，旧查询丢弃；等待新样本静默建基线");
    logEpoch.incrementAndGet();
    gate.reset();
    states.clear();
    diagnostics.resetLive();
    audio.stop();
    microphone.stop();
    diagnostics.line("状态校准", enabled ? "进入统一校准，自动音效暂停；只修改本地映射"
        : "结束统一校准，等待各状态首个新样本建立静默基线");
  }

  public void startMicrophone() {
    stopEngineDemo();
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
    if (key.startsWith("engine.synth.") || key.startsWith("engine.demo.")) {
      stopEngineDemo();
      enginePowertrain = new EnginePowertrain(store.engineTuning(), store.engineHorsepower(), store.engineZeroHundred());
      engineMotion.reset();
      engineGain = 0;
      audio.loop(null, 1);
    }
    if (key.equals("engine.profile") || key.equals("engine.simulation.enabled")) {
      if (key.equals("engine.simulation.enabled") && store.enabled("engine")) {
        engineMuted = false;
        silenced = false;
      }
      engineProfile = store.engineProfile();
      engineMotion.reset();
      engineGain = 0;
      audio.loop(null, 1);
    }
    if (destroyed || key.equals("monitor") || key.startsWith("selected.")) return;
    if (key.equals("lock.trigger.source")) {
      lockParkedBaseline = false;
      observedIccLock = null;
      audio.cancelLock();
      gate.forget("lock");
      states.remove("lock");
      diagnostics.removeLive("lock");
      logEpoch.incrementAndGet();
      diagnostics.lockDecision("锁来源已切换为" + (store.logLockSelected() ? "授权日志" : "ICC")
          + "，等待新鲜P挡与新来源锁基线；不自动启用或授权日志");
      return;
    }
    if (key.equals("log.enabled")) {
      stopLogs();
      resetSource("日志");
      logTimes.clear();
      if (running && store.logsEnabled()) startLogs();
      else diagnostics.logStatus = store.logsEnabled() ? "已启用日志；开始监听后请求系统批准" : "日志未启用；接口继续独立读取";
      return;
    }
    if (key.equals("icc.intervalMillis")) {
      if (running) { stopIccSource(); startIccSource(); }
      return;
    }
    if (key.equals("icc.lock.confirmed") || key.equals("icc.charge.confirmed")) {
      audio.cancelLock();
      audio.cancelCharge();
      if (iccSource != null) iccSource.invalidate("ICC触发确认改变，丢弃旧查询；等待新样本静默建基线");
      updateLoops();
      return;
    }
    if (key.startsWith("state.calibration.")) {
      String stateKey = key.substring("state.calibration.".length());
      gate.forget(stateKey);
      states.remove(stateKey);
      diagnostics.removeLive(stateKey);
      logEpoch.incrementAndGet();
      audio.stop();
      diagnostics.line("状态校准", stateKey + "映射已更新，等待新样本建立基线");
      updateLoops();
      return;
    }
    if (key.equals("autohold.holding") || key.equals("autohold.released")) {
      logEpoch.incrementAndGet();
      audio.cancelAutoHold();
      states.remove("autohold");
      gate.forget("autohold");
      diagnostics.removeLive("autohold");
      diagnostics.autoHoldDecision("校准已改变，等待新的有效状态建立基线");
    }
    if (key.equals("lock.locked") || key.equals("lock.unlocked")) {
      logEpoch.incrementAndGet();
      audio.cancelLock();
      diagnostics.lockDecision("校准已改变，等待新的有效锁状态建立基线");
      states.remove("lock");
      gate.forget("lock");
      diagnostics.removeLive("lock");
    }
    if (key.startsWith("lock.output."))
      diagnostics.lockOutputStatus = "自动匹配原厂车外总线，历史手动输出配置不参与选路";
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
          if (!parkedGear()) {
            lockParkedBaseline = false;
            audio.cancelAutomaticLock();
          }
          updateLoops();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public void onDestroy() {
    stopEngineDemo();
    diagnostics.calibrating = false;
    diagnostics.line("生命周期", "服务销毁；非正常进程终止可能没有此记录");
    destroyed = true;
    unregisterReceiver(powerEvents);
    store.prefs.unregisterOnSharedPreferenceChangeListener(this);
    stopSources();
    audio.close();
    microphone.stop();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
