package cn.es.soundlab;

import org.json.JSONObject;

final class EngineTuning {
  static final String[] NAMES = {"V8 · 低沉巡航", "V10 · 高转运动", "未来 · 电感涡流"};
  final int style, tone, intensity, shifting;

  EngineTuning(int style, int tone, int intensity, int shifting) {
    this.style = Math.max(0, Math.min(2, style));
    this.tone = Math.max(75, Math.min(125, tone));
    this.intensity = Math.max(0, Math.min(100, intensity));
    this.shifting = Math.max(0, Math.min(100, shifting));
  }

  float idle() { return style == 1 ? 950 : 800; }
  float redline() { return style == 1 ? 8000 : 6500; }
  int cylinders() { return style == 1 ? 10 : 8; }
  float cruiseShift() { return 1900 + shifting * 19; }
  float powerShift() { return redline() * (.82f + shifting * .0012f); }
  String source() { return "synth:1:" + style + ":" + tone + ":" + intensity; }

  static EngineTuning source(String source) {
    try {
      String[] fields = source.split(":");
      if (fields.length != 5 || !fields[0].equals("synth") || !fields[1].equals("1"))
        throw new IllegalArgumentException();
      return new EngineTuning(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), 55);
    } catch (RuntimeException error) { throw new IllegalArgumentException("无效的合成声浪配置", error); }
  }

  JSONObject json() {
    JSONObject result = new JSONObject();
    try {
      result.put("name", NAMES[style]).put("style", style).put("tonePercent", tone)
          .put("intensityPercent", intensity).put("shiftCharacterPercent", shifting)
          .put("idleRpm", idle()).put("redlineRpm", redline()).put("layers", 7)
          .put("implementation", "es-independent-periodic-combustion-v1")
          .put("actualRpmMeasurement", false).put("actualPedalMeasurement", false);
    } catch (Exception ignored) {}
    return result;
  }
}
