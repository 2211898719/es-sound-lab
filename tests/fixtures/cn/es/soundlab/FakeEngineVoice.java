package cn.es.soundlab;

import android.os.Handler;
import android.os.Looper;

final class FakeEngineVoice implements EngineVoice {
  final String source;
  final boolean preview;
  final Listener listener;
  boolean closed;
  float rate, gain, load;

  FakeEngineVoice(String source, boolean preview, Listener listener) {
    this.source = source; this.preview = preview; this.listener = listener;
    if (preview) new Handler(Looper.getMainLooper()).postDelayed(() -> { if (!closed) listener.finished(); }, 5000);
  }

  @Override public void target(float rate, float gain, float load) {
    this.rate = rate; this.gain = gain; this.load = load;
  }
  @Override public void close() { closed = true; }
  @Override public String status() { return "PCM测试输出（非实车验证）"; }
}
