package cn.es.soundlab;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Iterator;

/** Main-thread playback: gear interrupts, door events queue, loops yield to speech. */
public final class SoundEngine {
  private final Context context;
  private final Store store;
  private final Diagnostics diag;
  private final AudioManager audio;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayDeque<Job> queue = new ArrayDeque<>();
  private MediaPlayer shot, loop;
  private EventSpec shotSpec;
  private String loopId, desiredLoop;
  private float loopRate = 1;
  private float engineGain = 1;
  private boolean engineBlocked;
  private boolean engineDemo;
  private EngineVoice engineVoice;
  private boolean enginePreview;
  private float engineLoad;
  EngineVoice.Factory engineFactory = PcmEnginePlayer::new;
  private boolean loopPrepared;
  private AudioFocusRequest focus;
  private boolean focused, closed, focusBlocked, shotPreview;
  private int focusUsage = -1;
  private java.util.function.BooleanSupplier lockPlaybackAllowed = () -> false;
  private int lockTarget = -1;
  private float lockVolume;
  private long lockDeadline;
  private boolean lockAudible, lockSeeking;
  private final Runnable lockRouteCheck = this::checkLockRoute;
  private final AudioRouting.OnRoutingChangedListener lockRouting =
      routing -> {
        if (routing == shot) checkLockRoute();
      };

  private static final class Job {
    final EventSpec spec;
    final boolean preview;
    final long at = android.os.SystemClock.elapsedRealtime();

    Job(EventSpec s, boolean test) {
      spec = s;
      preview = test;
    }
  }

  public SoundEngine(Context c, Store s, Diagnostics d) {
    context = c;
    store = s;
    diag = d;
    audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
  }

  public void lockPlaybackAllowed(java.util.function.BooleanSupplier allowed) {
    lockPlaybackAllowed = allowed;
  }

  public void limitEnginePreview() {
    final EngineVoice preview = engineVoice;
    main.postDelayed(() -> {
      if (engineVoice == preview && enginePreview) next();
    }, 8000);
  }

  public void play(String id, boolean preview) {
    EventSpec spec = EventSpec.find(id);
    if (spec != null && spec.group.equals("lock"))
      diag.line("车外播放", (preview ? "手动试听" : "自动事件") + " · " + id
          + (closed ? " · 播放器已关闭" : !preview && !store.enabled("lock") ? " · 开关关闭，跳过" : " · 请求播放"));
    if (!playable(spec, preview)) return;
    focusBlocked = false;
    if (spec.group.equals("engine") && preview) {
      engineBlocked = false;
      queue.clear();
      releaseShot();
      releaseLoop();
      if (!requestFocus(false, true)) return;
      startEngineVoice(true);
      return;
    }
    Job j = new Job(spec, preview);
    for (Iterator<Job> it = queue.iterator(); it.hasNext(); )
      if (it.next().spec.id.equals(id)) it.remove();
    if (preview || spec.priority >= 3) {
      queue.clear();
      releaseShot();
    }
    queue.offer(j);
    if (shot == null) next();
  }

  /** Only playable gear speech preempts a short clip; driving loops always follow current state. */
  public void gearChanged(String event) {
    invalidateGear();
    play(event, false);
  }

  public void invalidateGear() {
    for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); )
      if (jobs.next().spec.group.equals("gear")) jobs.remove();
    desiredLoop = null;
    releaseLoop();
  }

  private boolean playable(EventSpec spec, boolean preview) {
    if (spec == null || closed || (!preview && !store.enabled(spec.group))) return false;
    if (!preview && spec.group.equals("charge")
        && !store.iccTriggerConfirmed(spec.group)) return false;
    if (!preview && spec.group.equals("lock") && !lockPlaybackAllowed.getAsBoolean()) {
      diag.lockDecision("未处于新鲜P挡，取消自动解闭锁音效，不补播");
      return false;
    }
    try {
      if (store.volume(spec.group) == 0) throw new IllegalStateException("音量为 0");
      if (spec.group.equals("engine") && store.engineSynth()) {
        EngineTuning.source(store.engineSource());
        return true;
      }
      if (spec.group.equals("lock") && selectedLockDevice() == null) {
        diag.lockOutputStatus = "未找到原厂车外总线，已阻止解闭锁播放，不回退车内";
        throw new IllegalStateException(diag.lockOutputStatus);
      }
      String source = store.source(spec);
      if (source.startsWith("file:")) {
        if (!new File(source.substring(5)).isFile())
          throw new IllegalStateException("自选音频文件已丢失，请重新导入");
      } else if (source.startsWith("asset:")) {
        try (AssetFileDescriptor asset = context.getAssets().openFd(source.substring(6))) {
          if (asset.getLength() == 0) throw new IllegalStateException("音源为空");
        }
      } else throw new IllegalStateException("未知音源类型");
      return true;
    } catch (Exception error) {
      diag.line("播放跳过", spec.id + " · " + error.getMessage() + "；保留正在播放的短音效");
      return false;
    }
  }

  private void next() {
    if (closed) return;
    releaseShot();
    Job j;
    do {
      j = queue.poll();
    } while (j != null && (android.os.SystemClock.elapsedRealtime() - j.at > 8000
        || !playable(j.spec, j.preview)));
    if (j == null) {
      applyLoop();
      return;
    }
    releaseLoop();
    if (!requestFocus(j.spec.group.equals("lock"), j.spec.group.equals("engine"))) {
      diag.line("播放", "未获得音频焦点");
      if (j.spec.group.equals("lock")) {
        diag.line("车外播放", "未获得音频焦点，取消 · " + j.spec.id + " · " + diag.lockFocusResult);
      }
      queue.clear();
      return;
    }
    final Job job = j;
    try {
      final MediaPlayer p = create(job.spec, job.preview);
      shot = p;
      shotSpec = job.spec;
      shotPreview = job.preview;
      p.setOnPreparedListener(
          m -> {
            if (shot != m || closed) return;
            try {
              rate(m, job.spec.group.equals("engine") ? 1 : store.speed(job.spec.id));
              if (job.spec.group.equals("lock")) {
                AudioDeviceInfo device = selectedLockDevice();
                if (device == null)
                  throw new IllegalStateException("原厂车外通道已失效，取消解闭锁音效");
                requireParkedLock();
                lockTarget = device.getId();
                lockVolume = store.volume("lock") / 100f;
                lockDeadline = android.os.SystemClock.uptimeMillis() + 1500;
                diag.lockOutputStatus = "正在静音核验车外设备 " + lockTarget;
                m.addOnRoutingChangedListener(lockRouting, main);
                m.setLooping(true);
                m.start();
                checkLockRoute();
                if (shot != m) return;
              } else m.start();
              diag.playing = job.spec.title;
              diag.line("播放", job.spec.title);
            } catch (Exception e) {
              fail(e);
              next();
            }
          });
      p.setOnCompletionListener(
          m -> {
            if (shot == m) next();
          });
      p.setOnErrorListener(
          (m, a, b) -> {
            if (shot == m) {
              fail(new IllegalStateException("音频错误 " + a + "/" + b));
              next();
            }
            return true;
          });
      p.prepareAsync();
    } catch (Exception e) {
      fail(e);
      next();
    }
  }

  private MediaPlayer create(EventSpec s, boolean preview) throws Exception {
    boolean external = s.group.equals("lock");
    AudioDeviceInfo device = external ? selectedLockDevice() : selectedDevice();
    if (external && device == null) {
      diag.lockOutputStatus = "解闭锁已阻止：原厂车外通道不可用，不回退普通输出";
      throw new IllegalStateException(diag.lockOutputStatus);
    }
    MediaPlayer p = new MediaPlayer();
    try {
      p.setAudioAttributes(external ? ExteriorAudio.attributes() : new AudioAttributes.Builder()
              .setUsage(s.group.equals("engine") ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
              .setContentType(s.group.equals("engine") ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SPEECH)
              .build());
      String path = store.source(s);
      if (path.startsWith("file:")) {
        File f = new File(path.substring(5));
        if (!f.isFile()) throw new IllegalStateException("自选音频文件已丢失，请重新导入");
        p.setDataSource(f.getAbsolutePath());
      } else {
        try (AssetFileDescriptor a = context.getAssets().openFd(path.substring(6))) {
          p.setDataSource(a.getFileDescriptor(), a.getStartOffset(), a.getLength());
        }
      }
      float volume = external ? 0 : store.volume(s.group) / 100f;
      if (s.group.equals("engine") && !preview) volume *= engineGain;
      p.setVolume(volume, volume);
      if (device != null && !p.setPreferredDevice(device)) {
        if (external) {
          diag.lockOutputStatus = "车外设备拒绝路由，已取消解闭锁音效";
          throw new IllegalStateException(diag.lockOutputStatus);
        }
        diag.line("音频输出", "所选设备未接受路由，使用系统输出");
      }
      return p;
    } catch (Exception e) {
      p.release();
      throw e;
    }
  }

  private void checkLockRoute() {
    main.removeCallbacks(lockRouteCheck);
    if (shot == null || lockTarget < 0) return;
    try {
      requireParkedLock();
      AudioDeviceInfo selected = selectedLockDevice(), routed = shot.getRoutedDevice();
      if (selected == null || selected.getId() != lockTarget)
        throw new IllegalStateException("车外设备已断开或改变，停止解闭锁音效");
      if (!lockAudible && android.os.SystemClock.uptimeMillis() >= lockDeadline)
        throw new IllegalStateException("车外路由或音频定位超时，取消放音");
      if (routed != null && routed.getId() == lockTarget && store.lockOutputMatches(routed)) {
        if (!lockAudible && !lockSeeking) {
          lockSeeking = true;
          shot.setOnSeekCompleteListener(this::finishLockSeek);
          shot.seekTo(0);
        }
      } else if (lockAudible || lockSeeking || android.os.SystemClock.uptimeMillis() >= lockDeadline) {
        throw new IllegalStateException("系统未保持所选车外路由，取消放音，不回退普通输出");
      }
      main.postDelayed(lockRouteCheck, 100);
    } catch (Exception e) {
      failLock(e);
    }
  }

  private void finishLockSeek(MediaPlayer player) {
    if (shot != player || lockTarget < 0 || closed) return;
    try {
      requireParkedLock();
      if (android.os.SystemClock.uptimeMillis() >= lockDeadline)
        throw new IllegalStateException("车外音频定位超时，取消放音");
      player.setOnSeekCompleteListener(null);
      AudioDeviceInfo selected = selectedLockDevice(), routed = player.getRoutedDevice();
      if (selected == null || selected.getId() != lockTarget || routed == null
          || routed.getId() != lockTarget || !store.lockOutputMatches(routed))
        throw new IllegalStateException("音频定位后车外路由已改变，取消放音");
      player.setLooping(false);
      player.setVolume(lockVolume, lockVolume);
      lockAudible = true;
      lockSeeking = false;
      diag.lockOutputStatus = "系统已报告所选车外路由；实际扬声器需在车外确认";
      diag.line("车外播放", "设备 " + lockTarget + " 路由匹配，开始放音 · " + shotSpec.id
          + " · 音量 " + store.volume("lock") + "%（实际扬声器仍须车外确认）");
    } catch (Exception error) {
      failLock(error);
    }
  }

  private void failLock(Exception error) {
    try {
      if (shot != null) shot.setVolume(0, 0);
    } catch (Exception ignored) {
    }
    diag.lockOutputStatus = error.getMessage();
    fail(error);
    next();
  }

  public void loop(String id, float rate) {
    if (id == null) engineBlocked = false;
    desiredLoop = id;
    loopRate = rate;
    if (shot == null) applyLoop();
  }

  public void engine(float pitch, float gain) {
    engine(pitch, gain, 0);
  }

  public void engine(float pitch, float gain, float load) {
    engineLoad = load;
    engineGain = Math.max(0, Math.min(1, gain));
    if (engineBlocked) return;
    loop("engine.loop", pitch);
  }

  void beginEngineDemo() {
    stop();
    focusBlocked = false;
    engineBlocked = false;
    engineDemo = true;
  }

  boolean engineDemoActive() { return engineDemo && !closed && !engineBlocked && !focusBlocked; }

  void endEngineDemo() {
    if (!engineDemo) return;
    engineDemo = false;
    desiredLoop = null;
    releaseLoop();
    if (shot == null) abandon();
  }

  String engineStatus() {
    if (engineBlocked) return "音频请求或播放失败；安全驻车后重新启用 / 更换音源";
    if (focusBlocked) return "音频焦点被系统接管，声浪已暂停";
    if (engineVoice != null) return engineVoice.status();
    if (shot != null) return shotSpec != null && shotSpec.group.equals("engine")
        ? "原音试听中" : "短音效播放中，声浪让行";
    if ("engine.loop".equals(loopId)) return loopPrepared ? "已提交循环播放（未验证物理输出）" : "准备音频中";
    return "声浪未播放";
  }

  private void applyLoop() {
    if (closed) return;
    if (focusBlocked) return;
    if (enginePreview) return;
    if (desiredLoop == null) {
      releaseLoop();
      diag.playing = "待命";
      abandon();
      return;
    }
    EventSpec s = EventSpec.find(desiredLoop);
    if (s == null || !store.enabled(s.group) && !(engineDemo && s.group.equals("engine"))) {
      releaseLoop();
      diag.playing = "待命";
      abandon();
      return;
    }
    if (s.group.equals("engine")) {
      if (engineVoice != null) {
        engineVoice.target(loopRate, engineGain * store.volume("engine") / 100f, engineLoad);
        return;
      }
      releaseLoop();
      if (!requestFocus(false, true)) { engineBlocked = true; return; }
      startEngineVoice(false);
      return;
    }
    if (loop != null && desiredLoop.equals(loopId)) {
      if (!loopPrepared) return;
      try {
        loopParameters(loop, s);
      } catch (Exception error) {
        engineBlocked = s.group.equals("engine");
        fail(error);
        releaseLoop();
        abandon();
      }
      return;
    }
    releaseLoop();
    if (!requestFocus(false, s.group.equals("engine"))) {
      engineBlocked = s.group.equals("engine");
      return;
    }
    try {
      final MediaPlayer p = create(s, false);
      loop = p;
      loopId = s.id;
      p.setLooping(true);
      p.setOnPreparedListener(
          m -> {
            if (loop == m && !closed)
              try {
                loopPrepared = true;
                loopParameters(m, s);
                m.start();
                diag.playing = s.title;
              } catch (Exception e) {
                engineBlocked = s.group.equals("engine");
                fail(e);
                releaseLoop();
                abandon();
              }
          });
      p.setOnErrorListener(
          (m, a, b) -> {
            if (loop == m) {
              engineBlocked = s.group.equals("engine");
              fail(new IllegalStateException("循环音频错误 " + a + "/" + b));
              releaseLoop();
              abandon();
            }
            return true;
          });
      p.prepareAsync();
    } catch (Exception e) {
      engineBlocked = s.group.equals("engine");
      fail(e);
      releaseLoop();
      abandon();
    }
  }

  private void loopParameters(MediaPlayer player, EventSpec spec) {
    rate(player, loopRate * store.speed(spec.id));
  }

  private void startEngineVoice(boolean preview) {
    enginePreview = preview;
    final EngineVoice[] expected = new EngineVoice[1];
    try {
      EngineVoice created = engineFactory.create(context, store.engineSource(), preview,
          selectedDevice(), new EngineVoice.Listener() {
            @Override public void started(String output) {
              if (engineVoice == expected[0] && !closed) diag.engineEvent("PCM输出启动", output);
            }
            @Override public void finished() {
              if (engineVoice != expected[0] || closed) return;
              if (enginePreview) next();
              else { engineBlocked = true; releaseLoop(); abandon(); }
            }
            @Override public void failed(Exception error) {
              if (engineVoice != expected[0] || closed) return;
              diag.engineEvent("播放失败", error.toString());
              engineBlocked = true;
              fail(error);
              if (enginePreview) { releaseShot(); abandon(); }
              else { releaseLoop(); abandon(); }
            }
          });
      expected[0] = created;
      engineVoice = created;
      recordEngineRequest(preview);
      if (!preview) loopId = "engine.loop";
      created.target(preview ? 1 : loopRate, (preview ? 1 : engineGain) * store.volume("engine") / 100f,
          preview ? store.engineSynth() ? .5f : 0 : engineLoad);
      diag.playing = preview ? "声浪PCM原音试听" : "声浪PCM连续播放";
    } catch (Exception error) {
      enginePreview = false;
      engineBlocked = true;
      diag.engineEvent("播放失败", error.toString());
      fail(error);
      abandon();
    }
  }

  private void recordEngineRequest(boolean preview) {
    try {
      AudioDeviceInfo device = selectedDevice();
      diag.engineEvent("播放请求", (preview ? "试听" : engineDemo ? "虚拟驾驶" : "实车声浪")
          + " · source=" + store.engineSource() + " · master=" + store.volume("engine")
          + "% · output=" + (device == null ? "系统媒体输出" : device.getId() + "/" + device.getAddress())
          + " · mediaVolume=" + (audio == null ? "unknown" : audio.getStreamVolume(AudioManager.STREAM_MUSIC)
              + "/" + audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
    } catch (RuntimeException error) {
      diag.engineEvent("播放请求", "音频输出元数据不可读：" + error);
    }
  }

  private static void rate(MediaPlayer p, float v) {
    p.setPlaybackParams(p.getPlaybackParams().setSpeed(Math.max(.5f, Math.min(2f, v))).setPitch(1));
  }

  private boolean requestFocus(boolean external) {
    return requestFocus(external, false);
  }

  private boolean requestFocus(boolean external, boolean continuous) {
    if (audio == null) return true;
    int usage = external ? ExteriorAudio.USAGE : continuous ? AudioAttributes.USAGE_MEDIA
        : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
    AudioAttributes attributes;
    try {
      attributes = external ? ExteriorAudio.attributes() : new AudioAttributes.Builder().setUsage(usage).build();
    } catch (Exception error) {
      diag.lockOutputStatus = error.getMessage();
      if (external) {
        diag.lockFocusAt = System.currentTimeMillis();
        diag.lockFocusResult = "attributes-unsupported";
      }
      fail(error);
      return false;
    }
    if (focusUsage != usage) {
      abandon();
      focus = null;
      focusUsage = usage;
    }
    if (focused) return true;
    if (focus == null)
      focus =
          new AudioFocusRequest.Builder(continuous ? AudioManager.AUDIOFOCUS_GAIN : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
              .setAudioAttributes(attributes)
              .setOnAudioFocusChangeListener(
                  change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        || continuous && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                      stop();
                      focusBlocked = true;
                      if (continuous) diag.engineEvent("音频焦点丢失", "change=" + change + "；停止播放，等待用户重新启用");
                      diag.line("播放", "音频焦点被其他应用接管");
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                      focusBlocked = false;
                      if (shot == null) applyLoop();
                    }
                  },
                  main)
              .build();
    try {
      int result = audio.requestAudioFocus(focus);
      focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
      if (continuous) diag.engineEvent("音频焦点申请", "usage=" + usage + " · result=" + result
          + (focused ? " · 已授予" : " · 未授予，不强播"));
      if (external) {
        diag.lockFocusAt = System.currentTimeMillis();
        diag.lockFocusResult = "result=" + result + (focused ? "（已授予）" : "（未授予）");
        if (!focused) diag.lockOutputStatus = "系统未授予usage2007音频焦点；已取消，不强播、不回退车内";
        diag.line("车外播放", "音频焦点 " + diag.lockFocusResult + " · usage=" + usage
            + " · gain=" + AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
      }
    } catch (RuntimeException error) {
      focused = false;
      if (continuous) diag.engineEvent("音频焦点异常", error.toString());
      if (external) {
        diag.lockFocusAt = System.currentTimeMillis();
        diag.lockFocusResult = "request-exception:" + error.getClass().getSimpleName();
        diag.lockOutputStatus = "系统焦点申请异常，取消车外放音，不回退车内";
      }
      diag.line("音频焦点", "系统拒绝usage=" + usage + "，取消放音，不回退其他通道");
    }
    return focused;
  }

  private void abandon() {
    if (audio != null && focus != null && focused) audio.abandonAudioFocusRequest(focus);
    focused = false;
  }

  private void fail(Exception e) {
    diag.line("播放失败", e.getMessage() == null ? e.toString() : e.getMessage());
    diag.playing = "音频播放失败";
  }

  private void releaseShot() {
    if (enginePreview) {
      enginePreview = false;
      if (engineVoice != null) engineVoice.close();
      engineVoice = null;
    }
    main.removeCallbacks(lockRouteCheck);
    if (shot != null) {
      MediaPlayer p = shot;
      shot = null;
      if (lockTarget >= 0)
        try {
          p.removeOnRoutingChangedListener(lockRouting);
        } catch (Exception ignored) {
        }
      p.setOnPreparedListener(null);
      p.setOnCompletionListener(null);
      p.setOnSeekCompleteListener(null);
      p.setOnErrorListener(null);
      try {
        p.release();
      } catch (Exception ignored) {
      }
    }
    lockTarget = -1;
    lockAudible = false;
    lockSeeking = false;
    shotSpec = null;
    shotPreview = false;
  }

  private void requireParkedLock() {
    if (!shotPreview && !lockPlaybackAllowed.getAsBoolean())
      throw new IllegalStateException("自动解闭锁放音前P挡已失效，取消音效，不补播");
  }

  public void cancelAutomaticLock() {
    for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); ) {
      Job job = jobs.next();
      if (job.spec.group.equals("lock") && !job.preview) jobs.remove();
    }
    if (shotSpec != null && shotSpec.group.equals("lock") && !shotPreview) next();
  }

  public void cancelLock() {
    for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); )
      if (jobs.next().spec.group.equals("lock")) jobs.remove();
    if (shotSpec != null && shotSpec.group.equals("lock")) next();
  }

  /** Keep only the latest AutoHold state, without interrupting unrelated short clips. */
  public void autoHoldChanged(String event) {
    cancelAutoHold();
    play(event, false);
  }

  public void cancelAutoHold() {
    for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); )
      if (jobs.next().spec.group.equals("autohold")) jobs.remove();
    if (shotSpec != null && shotSpec.group.equals("autohold")) next();
  }

  public void chargeChanged(String event) {
    cancelCharge();
    play(event, false);
  }

  public void cancelCharge() {
    for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); )
      if (jobs.next().spec.group.equals("charge")) jobs.remove();
    if (shotSpec != null && shotSpec.group.equals("charge")) next();
  }

  private void releaseLoop() {
    if (!enginePreview && engineVoice != null) {
      engineVoice.close();
      engineVoice = null;
      loopId = null;
    }
    loopPrepared = false;
    if (loop != null) {
      MediaPlayer p = loop;
      loop = null;
      loopId = null;
      p.setOnPreparedListener(null);
      p.setOnErrorListener(null);
      try {
        p.release();
      } catch (Exception ignored) {
      }
    }
  }

  public void stop() {
    engineDemo = false;
    queue.clear();
    desiredLoop = null;
    releaseShot();
    releaseLoop();
    abandon();
    diag.playing = "待命";
  }

  public void refresh(String key) {
    if (key.equals("engine.simulation.enabled") && store.enabled("engine")) focusBlocked = false;
    if (key.equals("audio.engine.loop") || key.equals("engine.profile") || key.equals("output") || key.startsWith("engine.synth.")) {
      engineBlocked = false;
      if (enginePreview && !key.equals("engine.profile")) { releaseShot(); abandon(); }
      if ("engine.loop".equals(loopId)) releaseLoop();
    }
    if (key.startsWith("autohold.")) {
      cancelAutoHold();
      return;
    }
    if (key.startsWith("lock.")) {
      cancelLock();
      return;
    }
    if (key.startsWith("enabled.")) {
      String group = key.substring(8);
      if (!store.enabled(group)) {
        for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); )
          if (jobs.next().spec.group.equals(group)) jobs.remove();
        if (loopId != null && EventSpec.find(loopId).group.equals(group)) releaseLoop();
        if (shotSpec != null && shotSpec.group.equals(group)) next();
      }
    } else if (key.startsWith("volume.") && shotSpec != null
        && shotSpec.group.equals(key.substring(7))) {
      lockVolume = store.volume(shotSpec.group) / 100f;
      if (!shotSpec.group.equals("lock") || lockAudible) shot.setVolume(lockVolume, lockVolume);
    } else if (key.startsWith("speed.") && shotSpec != null
        && shotSpec.id.equals(key.substring(6))) {
      try {
        rate(shot, store.speed(shotSpec.id));
      } catch (Exception error) {
        diag.line("播放", "语速将在下一次播放生效");
      }
    } else if (key.equals("output")) {
      for (Iterator<Job> jobs = queue.iterator(); jobs.hasNext(); )
        if (!jobs.next().spec.group.equals("lock")) jobs.remove();
      releaseLoop();
      if (shotSpec != null && !shotSpec.group.equals("lock")) next();
    }
    if (key.startsWith("volume.") && loopId != null
        && EventSpec.find(loopId).group.equals(key.substring(7))) {
      float volume = store.volume(EventSpec.find(loopId).group) / 100f;
      if (loopId.equals("engine.loop")) volume *= engineGain;
      if (engineVoice != null) engineVoice.target(loopRate, volume, engineLoad);
      else if (loop != null) loop.setVolume(volume, volume);
    }
    if (key.equals("volume.engine") && enginePreview && engineVoice != null)
      engineVoice.target(1, store.volume("engine") / 100f, store.engineSynth() ? .5f : 0);
  }

  public void close() {
    stop();
    closed = true;
    main.removeCallbacksAndMessages(null);
  }

  public AudioDeviceInfo selectedDevice() {
    if (audio == null) return null;
    int id = store.prefs.getInt("output", -1);
    for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
      if (d.getId() == id) return d;
    return null;
  }

  private AudioDeviceInfo selectedLockDevice() {
    if (audio == null) return null;
    for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
      if (store.lockOutputMatches(device)) return device;
    return null;
  }
}
