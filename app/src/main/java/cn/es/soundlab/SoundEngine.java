package cn.es.soundlab;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
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
  private String loopId, desiredLoop;
  private float loopRate = 1;
  private AudioFocusRequest focus;
  private boolean focused, closed, focusBlocked;

  private static final class Job {
    final EventSpec spec;
    final long at = android.os.SystemClock.elapsedRealtime();

    Job(EventSpec s) {
      spec = s;
    }
  }

  public SoundEngine(Context c, Store s, Diagnostics d) {
    context = c;
    store = s;
    diag = d;
    audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
  }

  public void play(String id, boolean preview) {
    EventSpec spec = EventSpec.find(id);
    if (spec == null || closed || (!preview && !store.enabled(spec.group))) return;
    focusBlocked = false;
    Job j = new Job(spec);
    for (Iterator<Job> it = queue.iterator(); it.hasNext(); )
      if (it.next().spec.id.equals(id)) it.remove();
    if (preview || spec.priority >= 3) {
      queue.clear();
      releaseShot();
    }
    queue.offer(j);
    if (shot == null) next();
  }

  /** A new gear invalidates the entire preceding gear request, even during prepareAsync. */
  public void gearChanged(String event) {
    queue.clear();
    desiredLoop = null;
    releaseShot();
    releaseLoop();
    focusBlocked = false;
    play(event, false);
  }

  private void next() {
    if (closed) return;
    releaseShot();
    Job j;
    do {
      j = queue.poll();
    } while (j != null && android.os.SystemClock.elapsedRealtime() - j.at > 8000);
    if (j == null) {
      applyLoop();
      return;
    }
    releaseLoop();
    if (!requestFocus()) {
      diag.line("播放", "未获得音频焦点");
      queue.clear();
      return;
    }
    final Job job = j;
    try {
      final MediaPlayer p = create(job.spec);
      shot = p;
      p.setOnPreparedListener(
          m -> {
            if (shot != m || closed) return;
            try {
              rate(m, store.speed(job.spec.id));
              m.start();
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

  private MediaPlayer create(EventSpec s) throws Exception {
    MediaPlayer p = new MediaPlayer();
    try {
      p.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
      float volume = store.volume(s.group) / 100f;
      p.setVolume(volume, volume);
      AudioDeviceInfo device = selectedDevice();
      if (device != null && !p.setPreferredDevice(device)) diag.line("音频输出", "所选设备未接受路由，使用系统输出");
      return p;
    } catch (Exception e) {
      p.release();
      throw e;
    }
  }

  public void loop(String id, float rate) {
    desiredLoop = id;
    loopRate = rate;
    if (shot == null) applyLoop();
  }

  private void applyLoop() {
    if (closed) return;
    if (focusBlocked) return;
    if (desiredLoop == null) {
      releaseLoop();
      diag.playing = "待命";
      abandon();
      return;
    }
    EventSpec s = EventSpec.find(desiredLoop);
    if (s == null || !store.enabled(s.group)) {
      releaseLoop();
      diag.playing = "待命";
      abandon();
      return;
    }
    if (loop != null && desiredLoop.equals(loopId)) {
      try {
        rate(loop, loopRate * store.speed(s.id));
      } catch (Exception ignored) {
      }
      return;
    }
    releaseLoop();
    if (!requestFocus()) return;
    try {
      final MediaPlayer p = create(s);
      loop = p;
      loopId = s.id;
      p.setLooping(true);
      p.setOnPreparedListener(
          m -> {
            if (loop == m && !closed)
              try {
                rate(m, loopRate * store.speed(s.id));
                m.start();
                diag.playing = s.title;
              } catch (Exception e) {
                fail(e);
                releaseLoop();
                abandon();
              }
          });
      p.setOnErrorListener(
          (m, a, b) -> {
            if (loop == m) {
              fail(new IllegalStateException("循环音频错误 " + a + "/" + b));
              releaseLoop();
              abandon();
            }
            return true;
          });
      p.prepareAsync();
    } catch (Exception e) {
      fail(e);
      releaseLoop();
      abandon();
    }
  }

  private static void rate(MediaPlayer p, float v) {
    p.setPlaybackParams(p.getPlaybackParams().setSpeed(Math.max(.5f, Math.min(2f, v))).setPitch(1));
  }

  private boolean requestFocus() {
    if (audio == null) return true;
    if (focused) return true;
    if (focus == null)
      focus =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
              .setAudioAttributes(new AudioAttributes.Builder().setUsage(13).build())
              .setOnAudioFocusChangeListener(
                  change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                      stop();
                      focusBlocked = true;
                      diag.line("播放", "音频焦点被其他应用接管");
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                      focusBlocked = false;
                      if (shot == null) applyLoop();
                    }
                  },
                  main)
              .build();
    focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
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
    if (shot != null) {
      MediaPlayer p = shot;
      shot = null;
      p.setOnPreparedListener(null);
      p.setOnCompletionListener(null);
      p.setOnErrorListener(null);
      try {
        p.release();
      } catch (Exception ignored) {
      }
    }
  }

  private void releaseLoop() {
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
    queue.clear();
    desiredLoop = null;
    releaseShot();
    releaseLoop();
    abandon();
    diag.playing = "待命";
  }

  public void refresh() {
    stop();
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
}
