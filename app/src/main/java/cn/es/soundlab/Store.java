package cn.es.soundlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Store {
  public static final String ES_STATE_PRESET = "es-known-log-v1";

  public static boolean validIccInterval(int millis) { return millis == 250 || millis == 500 || millis == 1000; }

  public int iccIntervalMillis() {
    int millis = prefs.getInt("icc.intervalMillis", 250);
    return validIccInterval(millis) ? millis : 250;
  }

  public void iccIntervalMillis(int millis) {
    if (validIccInterval(millis)) prefs.edit().putInt("icc.intervalMillis", millis).apply();
  }

  public boolean logsEnabled() { return prefs.getBoolean("log.enabled", false); }

  public void logsEnabled(boolean enabled) { prefs.edit().putBoolean("log.enabled", enabled).apply(); }

  public boolean logLockSelected() {
    return prefs.getString("lock.trigger.source", "icc").equals("authorized-log");
  }

  public void logLockSelected(boolean selected) {
    prefs.edit().putString("lock.trigger.source", selected ? "authorized-log" : "icc").apply();
  }

  public boolean acceptsLogKey(String key) {
    return VehicleStateSpec.logKey(key) || logLockSelected() && "lock".equals(key);
  }

  public boolean iccTriggerConfirmed(String key) {
    return key.equals("lock") || key.equals("charge") && prefs.getBoolean("icc.charge.confirmed", false);
  }

  public void iccTriggerConfirmed(String key, boolean confirmed) {
    if (key.equals("charge")) prefs.edit().putBoolean("icc.charge.confirmed", confirmed).apply();
  }

  public boolean lockTriggerCalibrated() { return !logLockSelected() || lockCalibrated(); }
  public final SharedPreferences prefs;
  private final Context context;

  public Store(Context c) {
    context = c.getApplicationContext();
    prefs = context.getSharedPreferences("es_sound_lab", 0);
    if (!prefs.getBoolean("lock.factory-defaults.v1", false))
      prefs.edit().putBoolean("enabled.lock", true).putInt("volume.lock", 100)
          .putBoolean("lock.factory-defaults.v1", true).apply();
    try {
      JSONArray a = new JSONArray(prefs.getString("custom.events", "[]"));
      synchronized (EventSpec.ALL) {
        for (int i = 0; i < a.length(); i++) {
          JSONObject j = a.getJSONObject(i);
          if (EventSpec.find(j.getString("id")) == null)
            EventSpec.ALL.add(
                new EventSpec(
                    j.getString("id"), "custom", j.getString("title"), "custom.wav", false, 2));
        }
      }
    } catch (Exception ignored) {
    }
  }

  public EventSpec custom(String title) {
    String id = "custom." + java.util.UUID.randomUUID();
    EventSpec spec = new EventSpec(id, "custom", title, "custom.wav", false, 2);
    synchronized (EventSpec.ALL) {
      EventSpec.ALL.add(spec);
    }
    try {
      JSONArray a = new JSONArray(prefs.getString("custom.events", "[]"));
      a.put(new JSONObject().put("id", id).put("title", title));
      prefs.edit().putString("custom.events", a.toString()).apply();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return spec;
  }

  public boolean enabled(String group) {
    if (group.equals("engine")) return prefs.getBoolean("engine.simulation.enabled", false);
    return prefs.getBoolean("enabled." + group, group.equals("lock"));
  }

  public void enable(String group, boolean enabled) {
    prefs.edit().putBoolean(group.equals("engine") ? "engine.simulation.enabled" : "enabled." + group, enabled).apply();
  }

  EngineProfile engineProfile() { return EngineProfile.parse(prefs.getString("engine.profile", "")); }

  boolean engineSynth() { return prefs.getBoolean("engine.synth.enabled", false); }

  int engineIdleVolume() { return Math.max(0, Math.min(40, prefs.getInt("engine.idle.volume", 18))); }

  EngineTuning engineTuning() {
    return new EngineTuning(prefs.getInt("engine.synth.style", 0), prefs.getInt("engine.synth.tone", 100),
        prefs.getInt("engine.synth.intensity", 70), prefs.getInt("engine.synth.shifting", 55));
  }

  int engineHorsepower() { return Math.max(80, Math.min(800, prefs.getInt("engine.demo.horsepower", 280))); }

  float engineZeroHundred() { return Math.max(30, Math.min(180, prefs.getInt("engine.demo.zeroHundredTenths", 70))) / 10f; }

  String engineSource() { return engineSynth() ? engineTuning().source() : source(EventSpec.find("engine.loop")); }

  void engineProfile(EngineProfile profile) {
    try { prefs.edit().putString("engine.profile", profile.json().toString()).apply(); }
    catch (Exception error) { throw new IllegalArgumentException("无法保存曲线", error); }
  }

  public int volume(String group) {
    return Math.max(0, Math.min(100, prefs.getInt("volume." + group, group.equals("lock") ? 100 : 65)));
  }

  public void volume(String group, int v) {
    prefs.edit().putInt("volume." + group, Math.max(0, Math.min(100, v))).apply();
  }

  public float speed(String event) {
    return Math.max(.5f, Math.min(2f, prefs.getInt("speed." + event, 100) / 100f));
  }

  public String selected(String group) {
    String id = prefs.getString("selected." + group, EventSpec.group(group).get(0).id);
    return EventSpec.find(id) != null ? id : EventSpec.group(group).get(0).id;
  }

  public void select(String group, String id) {
    prefs.edit().putString("selected." + group, id).apply();
  }

  public String source(EventSpec spec) {
    return prefs.getString("audio." + spec.id, "asset:audio/" + spec.asset);
  }

  public String audioName(EventSpec spec) {
    return prefs.getString(
        "audio.name." + spec.id, spec.title + spec.asset.substring(spec.asset.lastIndexOf('.')));
  }

  public void asset(EventSpec spec, String path, String name) {
    replace(spec, "asset:" + path, name);
  }

  public void file(EventSpec spec, File file, String name) {
    replace(spec, "file:" + file.getAbsolutePath(), name);
  }

  private void replace(EventSpec spec, String source, String name) {
    String old = source(spec);
    prefs
        .edit()
        .putString("audio." + spec.id, source)
        .putString("audio.name." + spec.id, name)
        .apply();
    if (old.startsWith("file:") && !old.equals(source)) {
      File f = new File(old.substring(5));
      try {
        File dir = new File(context.getFilesDir(), "audio");
        if (f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) f.delete();
      } catch (Exception ignored) {
      }
    }
  }

  public JSONArray rules() {
    try {
      return new JSONArray(prefs.getString("rules", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  public void addRule(JSONObject rule) {
    JSONArray a = rules();
    a.put(rule);
    prefs.edit().putString("rules", a.toString()).apply();
  }

  public void deleteRule(int at) {
    JSONArray a = rules(), b = new JSONArray();
    for (int i = 0; i < a.length(); i++) if (i != at) b.put(a.opt(i));
    prefs.edit().putString("rules", b.toString()).apply();
  }

  public boolean mapped(String event) {
    if (event.startsWith("charge.")) return iccTriggerConfirmed("charge");
    if (event.startsWith("lock.")) return lockTriggerCalibrated();
    if (event.startsWith("autohold.")) return autoHoldCalibrated();
    EventSpec s = EventSpec.find(event);
    if (s != null && s.mapped) return true;
    return false;
  }

  public boolean lockCalibrated() {
    return Protocol.lockState(
            prefs.getString("lock.locked", ""),
            prefs.getString("lock.locked", ""),
            prefs.getString("lock.unlocked", ""))
        != null;
  }

  public boolean autoHoldCalibrated() {
    return autoHoldState(prefs.getString("autohold.holding", "2")) != null;
  }

  public String autoHoldState(String raw) {
    return Protocol.autoHoldState(
        raw, prefs.getString("autohold.holding", "2"), prefs.getString("autohold.released", "1"));
  }

  public void calibrateAutoHold(String holding, String released) {
    if (Protocol.autoHoldState(holding, holding, released) == null)
      throw new IllegalArgumentException("保持和释放须分别填写 1 与 2，含义请对照实车确认");
    prefs
        .edit()
        .putString("autohold.holding", holding)
        .putString("autohold.released", released)
        .apply();
  }

  public String lockReadiness() {
    if (!enabled("lock")) return "解闭锁提示音开关已关闭";
    if (logLockSelected() && !logsEnabled()) return "已选择日志锁事件，请先自行启用并授权日志";
    if (!lockTriggerCalibrated()) return "日志锁原始值尚未校准，请核对历史映射";
    try {
      for (AudioDeviceInfo device : audioOutputs())
        if (lockOutputMatches(device)) return "原厂车外通道就绪；先有新鲜P挡基线，后续锁变化才播放";
    } catch (Exception ignored) {
    }
    return "原厂车外总线BUS2007_VENDOR_CONTEXT2不可用，不回退车内输出";
  }

  public String lockState(String raw) {
    return Protocol.lockState(
        raw, prefs.getString("lock.locked", ""), prefs.getString("lock.unlocked", ""));
  }

  public void calibrateLock(String locked, String unlocked) {
    if (Protocol.lockState(locked, locked, unlocked) == null)
      throw new IllegalArgumentException("请输入两个不同的非负整数，含义必须经实车确认");
    prefs.edit().putString("lock.locked", locked).putString("lock.unlocked", unlocked).apply();
  }

  public JSONObject stateCalibration(String key) {
    try { return new JSONObject(prefs.getString("state.calibration." + key, "{}")); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  public boolean esStatePreset(String key) {
    VehicleStateSpec spec = VehicleStateSpec.find(key);
    return spec != null && spec.customMapping()
        && ES_STATE_PRESET.equals(stateCalibration(key).optString("preset"));
  }

  public void applyEsStatePreset(String[] keys) {
    if (keys == null || keys.length == 0) throw new IllegalArgumentException("请至少选择一项预设");
    Set<String> selected = new LinkedHashSet<>();
    for (String key : keys) {
      VehicleStateSpec spec = VehicleStateSpec.find(key);
      if (spec == null || !spec.customMapping())
        throw new IllegalArgumentException("该状态没有已确认的ES预设");
      selected.add(key);
    }
    SharedPreferences.Editor editor = prefs.edit();
    try {
      long now = System.currentTimeMillis();
      for (VehicleStateSpec spec : VehicleStateSpec.ALL) {
        String preference = "state.calibration." + spec.key;
        if (selected.contains(spec.key))
          editor.putString(preference, new JSONObject().put("preset", ES_STATE_PRESET)
              .put("source", "日志").put("savedAt", now).put("verifiedOnVehicle", false)
              .put("description", spec.esPresetDescription()).toString());
        else if (esStatePreset(spec.key)) editor.remove(preference);
      }
      editor.apply();
    } catch (org.json.JSONException error) { throw new IllegalArgumentException("无法保存预设", error); }
  }

  public void clearEsStatePreset() {
    SharedPreferences.Editor editor = prefs.edit();
    for (VehicleStateSpec spec : VehicleStateSpec.ALL)
      if (esStatePreset(spec.key)) editor.remove("state.calibration." + spec.key);
    editor.apply();
  }

  public void calibrateState(Protocol.Sample sample, String[] rawValues, boolean metersPerSecond) {
    VehicleStateSpec spec = sample == null ? null : VehicleStateSpec.find(sample.key);
    if (spec == null || !spec.customMapping() || !sample.source.equals("日志") || sample.status != 0
        || !knownLogIdentity(sample))
      throw new IllegalArgumentException("请先收到该状态的有效日志，再对照实车校准");
    double observed = Protocol.number(sample.raw);
    if (spec.key.equals("speed") ? !Double.isFinite(observed) || observed < 0 || observed > 300
        : !Protocol.lockValue(sample.raw))
      throw new IllegalArgumentException("当前原始值无效，请等待有效样本再校准");
    try {
      JSONObject config = new JSONObject().put("source", sample.source).put("topic", sample.topic)
          .put("propertyId", sample.propertyId).put("area", sample.area)
          .put("savedAt", System.currentTimeMillis());
      if (spec.key.equals("speed")) config.put("metersPerSecond", metersPerSecond);
      else {
        if (rawValues == null || rawValues.length != spec.values.length)
          throw new IllegalArgumentException("请填写完整映射");
        JSONObject mapping = new JSONObject();
        for (int index = 0; index < rawValues.length; index++) {
          String raw = rawValues[index] == null ? "" : rawValues[index].trim();
          if (spec.key.equals("gear") && index == 4 && raw.isEmpty()) continue;
          if (!Protocol.lockValue(raw)) throw new IllegalArgumentException("请填写非负整数原始值，M挡可留空");
          if (mapping.has(raw)) throw new IllegalArgumentException("不同状态不能使用同一个原始值");
          mapping.put(raw, spec.values[index]);
        }
        config.put("mapping", mapping);
      }
      prefs.edit().putString("state.calibration." + spec.key, config.toString()).apply();
    } catch (org.json.JSONException error) { throw new IllegalArgumentException("无法保存校准", error); }
  }

  static boolean knownLogIdentity(Protocol.Sample sample) {
    if (sample == null || sample.key == null || !sample.source.equals("日志")) return false;
    String raw = sample.key.equals("speed") ? "0" : "1";
    String line = "1.000 1 1 I MessageProperty: receive " + sample.topic
        + ", value: CarPropertyValue{id=0x" + Long.toHexString(sample.propertyId)
        + ", area=" + sample.area + ", status=0, value=" + raw + "}";
    Protocol.Sample decoded = Protocol.decode(line);
    return decoded != null && sample.key.equals(decoded.key) && sample.area.equals("0x0");
  }

  public Protocol.Sample calibratedLogSample(Protocol.Sample sample) {
    if (sample == null || !sample.source.equals("日志") || sample.key == null) return sample;
    JSONObject config = stateCalibration(sample.key);
    if (esStatePreset(sample.key)) return sample;
    if (!sample.source.equals(config.optString("source")) || !sample.topic.equals(config.optString("topic"))
        || sample.propertyId != config.optLong("propertyId") || !sample.area.equals(config.optString("area")))
      return sample;
    String value = null;
    if (sample.status == 0 && knownLogIdentity(sample)) {
      if (sample.key.equals("speed")) {
        double speed = Protocol.number(sample.raw) * (config.optBoolean("metersPerSecond") ? 3.6 : 1);
        if (Double.isFinite(speed) && speed >= 0 && speed <= 300) value = Double.toString(speed);
      } else if (Protocol.lockValue(sample.raw)) {
        JSONObject mapping = config.optJSONObject("mapping");
        if (mapping != null) value = mapping.optString(sample.raw, null);
        VehicleStateSpec spec = VehicleStateSpec.find(sample.key);
        boolean allowed = false;
        if (spec != null && spec.customMapping())
          for (String expected : spec.values) if (expected.equals(value)) allowed = true;
        if (!allowed) value = null;
      }
    }
    return new Protocol.Sample(sample.topic, sample.propertyId, sample.area, sample.status,
        sample.raw, sample.time, sample.source, sample.key, value);
  }

  public void clearStateCalibration(String key) {
    prefs.edit().remove("state.calibration." + key).apply();
  }

  private String address(AudioDeviceInfo device) {
    return Build.VERSION.SDK_INT >= 28 ? device.getAddress() : "";
  }

  public void lockOutput(AudioDeviceInfo device) {
    prefs.edit()
        .putInt("lock.output.id", device.getId())
        .putInt("lock.output.type", device.getType())
        .putString("lock.output.name", String.valueOf(device.getProductName()))
        .putString("lock.output.address", address(device))
        .putBoolean("lock.output.confirmed", false)
        .apply();
  }

  public boolean lockOutputMatches(AudioDeviceInfo device) {
    return ExteriorAudio.matches(device);
  }

  public AudioDeviceInfo[] audioOutputs() {
    AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    return manager == null ? new AudioDeviceInfo[0] : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
  }

  public boolean watches(Protocol.Sample sample) {
    if (sample == null) return false;
    JSONArray entries = rules();
    for (int index = 0; index < entries.length(); index++) {
      JSONObject rule = entries.optJSONObject(index);
      if (rule != null && rule.optString("source").equals(sample.source)
          && rule.optString("topic").equals(sample.topic)
          && rule.optLong("propertyId") == sample.propertyId
          && rule.optString("area").equals(sample.area)) return true;
    }
    return false;
  }
}
