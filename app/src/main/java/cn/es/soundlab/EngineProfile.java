package cn.es.soundlab;

import org.json.JSONArray;
import org.json.JSONObject;

final class EngineProfile {
  static final String[] NAMES = {"舒适巡航", "运动响应", "电感平顺"};
  static final float[] SPEED_POINTS = {0, 20, 40, 60, 90, 120};
  static final float[] ACCEL_POINTS = {0, .5f, 1, 2, 3};
  final String name;
  final float[] pitch, volume, acceleration;
  final int responseMillis;

  EngineProfile(String name, float[] pitch, float[] volume, float[] acceleration, int response) {
    this.name = name;
    this.pitch = checked(pitch, SPEED_POINTS.length, .5f, 2);
    this.volume = checked(volume, SPEED_POINTS.length, 0, 1);
    this.acceleration = checked(acceleration, ACCEL_POINTS.length, 0, 1);
    this.responseMillis = Math.max(200, Math.min(2000, response));
  }

  private static float[] checked(float[] values, int count, float min, float max) {
    if (values == null || values.length != count) throw new IllegalArgumentException("曲线点数不正确");
    float[] result = values.clone();
    for (int index = 0; index < count; index++) {
      if (Float.isNaN(result[index]) || Float.isInfinite(result[index]))
        throw new IllegalArgumentException("曲线含无效数值");
      result[index] = Math.max(min, Math.min(max, result[index]));
    }
    return result;
  }

  static EngineProfile preset(int index) {
    switch (index) {
      case 1: return tuned(NAMES[1],
          new int[] {1500, 2550, 3450, 4350, 5400, 5850},
          new float[] {-30, -20, -16, -13, -10, -8},
          new float[] {0, .2f, .4f, .75f, 1}, 450);
      case 2: return tuned(NAMES[2],
          new int[] {1800, 2400, 3000, 3450, 3900, 4500},
          new float[] {-34, -25, -22, -19, -16, -14},
          new float[] {0, .08f, .18f, .32f, .5f}, 1000);
      default: return tuned(NAMES[0],
          new int[] {1500, 2100, 2850, 3600, 4500, 5400},
          new float[] {-32, -23, -19, -16, -13, -11},
          new float[] {0, .1f, .22f, .4f, .6f}, 800);
    }
  }

  private static EngineProfile tuned(String name, int[] rpm, float[] decibels, float[] load, int response) {
    float[] pitch = new float[rpm.length], gain = new float[decibels.length];
    for (int index = 0; index < pitch.length; index++) {
      pitch[index] = rpm[index] / 3000f;
      gain[index] = (float) Math.pow(10, decibels[index] / 20f);
    }
    return new EngineProfile(name, pitch, gain, load, response);
  }

  static float interpolate(float value, float[] points, float[] values) {
    if (value <= points[0]) return values[0];
    for (int index = 1; index < points.length; index++)
      if (value <= points[index]) {
        float weight = (value - points[index - 1]) / (points[index] - points[index - 1]);
        return values[index - 1] + weight * (values[index] - values[index - 1]);
      }
    return values[values.length - 1];
  }

  float load(float accelerationValue) {
    return interpolate(Math.max(0, accelerationValue), ACCEL_POINTS, acceleration);
  }

  float pitch(float speed, float load) {
    return Math.min(2, interpolate(speed, SPEED_POINTS, pitch) + .12f * load);
  }

  float gain(float speed, float load) {
    float base = interpolate(speed, SPEED_POINTS, volume);
    return Math.max(0, Math.min(1, base + .5f * load * (1 - base)));
  }

  EngineProfile edit(int curve, int point, float value) {
    float[] pitches = pitch.clone(), volumes = volume.clone(), accelerations = acceleration.clone();
    (curve == 0 ? pitches : curve == 1 ? volumes : accelerations)[point] = value;
    return new EngineProfile("自定义", pitches, volumes, accelerations, responseMillis);
  }

  EngineProfile response(int millis) {
    return new EngineProfile("自定义", pitch, volume, acceleration, millis);
  }

  JSONObject json() throws Exception {
    return new JSONObject().put("name", name).put("pitch", array(pitch)).put("volume", array(volume))
        .put("acceleration", array(acceleration)).put("responseMillis", responseMillis);
  }

  private static JSONArray array(float[] values) throws Exception {
    JSONArray result = new JSONArray();
    for (float value : values) result.put((double) value);
    return result;
  }

  private static float[] floats(JSONArray values, int count) throws Exception {
    if (values.length() != count) throw new IllegalArgumentException("曲线点数不正确");
    float[] result = new float[count];
    for (int index = 0; index < count; index++) result[index] = (float) values.getDouble(index);
    return result;
  }

  static EngineProfile parse(String text) {
    try {
      JSONObject value = new JSONObject(text);
      return new EngineProfile(value.optString("name", "自定义"),
          floats(value.getJSONArray("pitch"), 6), floats(value.getJSONArray("volume"), 6),
          floats(value.getJSONArray("acceleration"), 5), value.getInt("responseMillis"));
    } catch (Exception error) { return preset(0); }
  }
}
