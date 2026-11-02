package cn.es.soundlab;

import org.json.JSONObject;

final class EngineDemo {
  static final int[] HORSEPOWER = {180, 280, 400, 600};
  static final float[] ZERO_HUNDRED = {9, 7, 4.5f, 3.2f};
  static final String[] NAMES = {"轻快 · 180匹 / 9.0秒", "ES · 280匹 / 7.0秒", "性能 · 400匹 / 4.5秒", "高性能 · 600匹 / 3.2秒"};
  static final float MAX_SECONDS = 60;
  final int horsepower;
  final float zeroHundred;
  private final float knee, launch;
  boolean active = true;
  String status = "虚拟油门演示中";
  float throttle, speed, acceleration, elapsed, distance, load, pitch = 1, gain;
  private float smoothedSpeed, smoothedAcceleration;
  private EnginePowertrain powertrain;

  void synth(EngineTuning tuning) { powertrain = new EnginePowertrain(tuning, horsepower, zeroHundred); }

  EnginePowertrain powertrain() { return powertrain; }

  EngineDemo(int horsepower, float zeroHundred) {
    if (horsepower < 80 || horsepower > 800 || !Float.isFinite(zeroHundred)
        || zeroHundred < 3 || zeroHundred > 18) throw new IllegalArgumentException("无效演示车型参数");
    this.horsepower = horsepower;
    this.zeroHundred = zeroHundred;
    knee = 20 * (float) Math.sqrt(horsepower / 280f);
    float target = 100 / 3.6f;
    launch = (target + target * target * target / (3 * knee * knee)) / zeroHundred;
  }

  void throttle(float value) {
    throttle = Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
  }

  void step(float seconds, EngineProfile profile) {
    if (!active) return;
    if (!Float.isFinite(seconds) || seconds <= 0 || seconds > .5f) {
      stop("演示计时中断，已停止");
      return;
    }
    float duration = Math.min(seconds, MAX_SECONDS - elapsed);
    float velocity = speed / 3.6f;
    float previous = velocity;
    int steps = Math.max(1, (int) Math.ceil(duration / .01f));
    float interval = duration / steps;
    for (int index = 0; index < steps; index++) {
      float drive = throttle * launch / (1 + velocity * velocity / (knee * knee));
      float coast = (1 - throttle) * (.3f + .0005f * velocity * velocity);
      velocity = Math.max(0, Math.min(200 / 3.6f, velocity + (drive - coast) * interval));
    }
    speed = velocity * 3.6f;
    distance += (velocity + previous) * .5f * duration;
    acceleration = duration > 0 ? (velocity - previous) / duration : 0;
    elapsed += duration;
    float blend = 1 - (float) Math.exp(-duration * 1000 / profile.responseMillis);
    smoothedSpeed += blend * (speed - smoothedSpeed);
    smoothedAcceleration += blend * (acceleration - smoothedAcceleration);
    load = profile.load(smoothedAcceleration);
    pitch += blend * (profile.pitch(smoothedSpeed, load) - pitch);
    gain += blend * (profile.gain(smoothedSpeed, load) - gain);
    if (powertrain != null) {
      powertrain.step(speed, acceleration, throttle, duration);
      pitch = powertrain.rpm / 3000f;
      load = powertrain.load;
    }
    if (elapsed >= MAX_SECONDS) stop("已达60秒，演示自动停止");
  }

  void stop(String reason) { active = false; status = reason; }

  JSONObject json(String audio, int masterVolume) {
    JSONObject result = new JSONObject();
    try {
      result.put("virtual", true).put("actualPowerMeasurement", false).put("active", active)
          .put("status", status).put("horsepower", horsepower).put("zeroToHundredSeconds", zeroHundred)
          .put("throttle", throttle).put("speedKmh", speed).put("accelerationMps2", acceleration)
          .put("elapsedSeconds", elapsed).put("load", load).put("pitch", pitch).put("gain", gain)
          .put("virtualRpm", pitch * 3000).put("actualRpmMeasurement", false)
          .put("finalGain", active ? gain * masterVolume / 100f : 0).put("audio", audio);
      if (powertrain != null) result.put("powertrain", powertrain.json());
    } catch (Exception ignored) {}
    return result;
  }
}
