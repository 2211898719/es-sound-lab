package cn.es.soundlab;

import android.content.Context;
import android.media.AudioDeviceInfo;

interface EngineVoice {
  interface Listener {
    default void started(String output) {}
    void finished();
    void failed(Exception error);
  }
  interface Factory {
    EngineVoice create(Context context, String source, boolean preview, AudioDeviceInfo device, Listener listener);
  }
  void target(float rate, float gain, float load);
  void close();
  String status();
}
