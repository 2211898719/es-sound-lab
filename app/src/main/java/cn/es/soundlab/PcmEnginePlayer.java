package cn.es.soundlab;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Locale;

final class PcmEnginePlayer implements EngineVoice {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Object outputLock = new Object();
  private final Listener listener;
  private volatile boolean closed;
  private volatile String status = "PCM音源加载中";
  private volatile float rate = 1, gain, load;
  private volatile EngineRenderer render;
  private AudioTrack track;
  private final Thread worker;

  PcmEnginePlayer(Context context, String source, boolean preview, AudioDeviceInfo device, Listener listener) {
    this.listener = listener;
    worker = new Thread(() -> run(context.getApplicationContext(), source, preview, device), "ES-Engine-PCM");
    worker.setDaemon(true);
    worker.start();
  }

  private void run(Context context, String source, boolean preview, AudioDeviceInfo device) {
    AudioTrack output = null;
    try {
      if (source.startsWith("synth:")) {
        status = "正在准备七层合成音色";
        EngineTuning tuning = EngineTuning.source(source);
        render = new EngineSynth.Render(EngineSynth.bank(tuning.style, () -> closed), tuning, preview);
      } else {
        render = new EnginePcm.Render(EngineDecoder.decode(context, source, () -> closed), preview);
      }
      if (closed) return;
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
      int minimum = AudioTrack.getMinBufferSize(EnginePcm.OUTPUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("系统不支持48kHz立体声PCM输出");
      int bufferBytes = Math.max(minimum * 2, EnginePcm.OUTPUT_RATE * 4 / 20);
      output = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
          .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(EnginePcm.OUTPUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
          .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build();
      if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("PCM播放器初始化失败");
      if (device != null && !output.setPreferredDevice(device)) throw new IOException("所选声浪输出设备拒绝路由，请重新选择");
      synchronized (outputLock) {
        if (closed) return;
        track = output;
      }
      short[] block = new short[960];
      long written = 0;
      long reported = -EnginePcm.OUTPUT_RATE;
      boolean started = false;
      while (!closed) {
        render.target(preview ? 1 : rate, gain, load);
        int frames = render.render(block, block.length / 2);
        if (frames == 0) break;
        int offset = 0;
        while (!closed && offset < frames * 2) {
          int count = output.write(block, offset, frames * 2 - offset, AudioTrack.WRITE_BLOCKING);
          if (count <= 0) throw new IOException("PCM写入失败：" + count);
          offset += count;
          written += count / 2;
        }
        if (!started && written >= bufferBytes / 8) {
          synchronized (outputLock) {
            if (closed) return;
            output.play();
          }
          started = true;
        }
        if (written - reported >= EnginePcm.OUTPUT_RATE / 2) {
          reported = written;
          status = String.format(Locale.CHINA, "%s · 虚拟RPM %.0f · 缓冲欠载 %d（非物理出声证明）",
              preview ? "PCM包络试听" : source.startsWith("synth:") ? "七层合成声浪" : "PCM连续声浪", render.rpm(), output.getUnderrunCount());
        }
      }
      if (!closed && preview) {
        if (!started) {
          synchronized (outputLock) { if (closed) return; output.play(); }
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
        while (!closed && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) < written) {
          if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("PCM试听尾部排空超时");
          Thread.sleep(10);
        }
        if (!closed) main.post(() -> { if (!closed) listener.finished(); });
      }
    } catch (Exception error) {
      if (!closed) {
        status = "PCM播放失败：" + error.getMessage();
        main.post(() -> { if (!closed) listener.failed(error); });
      }
    } finally {
      synchronized (outputLock) {
        track = null;
        if (output != null) {
          try { output.pause(); output.flush(); } catch (Exception ignored) {}
          output.release();
        }
      }
    }
  }

  @Override public void target(float rate, float gain, float load) {
    this.rate = rate; this.gain = gain; this.load = load;
  }

  @Override public String status() { return status; }

  @Override public void close() {
    closed = true;
    worker.interrupt();
    synchronized (outputLock) {
      if (track != null) try { track.pause(); track.flush(); } catch (Exception ignored) {}
    }
  }
}
