package cn.es.soundlab;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.os.Build;

final class ExteriorAudio {
  static final int USAGE = 2007;
  static final String ADDRESS = "BUS2007_VENDOR_CONTEXT2";

  static boolean matches(AudioDeviceInfo device) {
    return Build.VERSION.SDK_INT >= 28 && device != null && device.isSink()
        && device.getType() == AudioDeviceInfo.TYPE_BUS
        && ADDRESS.equals(device.getAddress());
  }

  static AudioAttributes attributes() {
    AudioAttributes attributes = new AudioAttributes.Builder().setUsage(USAGE)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    if (attributes.getUsage() != USAGE)
      throw new IllegalStateException("系统不支持原厂车外usage2007，取消放音，不回退车内");
    return attributes;
  }
}
