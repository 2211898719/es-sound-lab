package cn.es.soundlab;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

public final class MicrophoneBridge {
  private volatile boolean running;
  private volatile AudioRecord record;
  private volatile Thread thread;
  private final Diagnostics diag;

  public MicrophoneBridge(Diagnostics d) {
    diag = d;
  }

  public synchronized void start(AudioDeviceInfo device, int volume) {
    if (thread != null) return;
    running = true;
    thread =
        new Thread(
            () -> {
              AudioTrack track = null;
              AudioRecord mic = null;
              try {
                int rate = 16000,
                    size =
                        Math.max(
                            3200,
                            AudioRecord.getMinBufferSize(
                                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
                mic =
                    new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        size * 2);
                record = mic;
                track =
                    new AudioTrack.Builder()
                        .setAudioAttributes(
                            new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(
                            new AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(size * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (device != null && !track.setPreferredDevice(device))
                  throw new IllegalStateException("所选喊话输出不可用");
                track.setVolume(volume / 100f);
                if (mic.getState() != AudioRecord.STATE_INITIALIZED
                    || track.getState() != AudioTrack.STATE_INITIALIZED)
                  throw new IllegalStateException("麦克风或输出通道不可用");
                if (!running) return;
                track.play();
                mic.startRecording();
                diag.playing = "按住喊话中";
                short[] data = new short[320];
                while (running) {
                  int n = mic.read(data, 0, data.length);
                  if (n < 0) throw new IllegalStateException("麦克风读取错误 " + n);
                  if (n > 0) {
                    int at = 0;
                    while (running && at < n) {
                      int written = track.write(data, at, n - at);
                      if (written <= 0) throw new IllegalStateException("输出通道写入失败");
                      at += written;
                    }
                  }
                }
              } catch (Exception e) {
                diag.line("喊话", e.toString());
              } finally {
                running = false;
                record = null;
                if (mic != null) {
                  try {
                    mic.stop();
                  } catch (Exception ignored) {
                  }
                  mic.release();
                }
                if (track != null) {
                  try {
                    track.stop();
                  } catch (Exception ignored) {
                  }
                  track.release();
                }
                diag.playing = "待命";
                synchronized (MicrophoneBridge.this) {
                  thread = null;
                }
              }
            },
            "ES-Microphone");
    thread.start();
  }

  public void stop() {
    running = false;
    AudioRecord r = record;
    if (r != null)
      try {
        r.stop();
      } catch (Exception ignored) {
      }
  }

  public boolean active() {
    return running;
  }
}
