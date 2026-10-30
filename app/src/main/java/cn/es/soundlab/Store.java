package cn.es.soundlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Store {
  public final SharedPreferences prefs;
  private final Context context;

  public Store(Context c) {
    context = c.getApplicationContext();
    prefs = context.getSharedPreferences("es_sound_lab", 0);
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
    return prefs.getBoolean("enabled." + group, false);
  }

  public void enable(String group, boolean enabled) {
    prefs.edit().putBoolean("enabled." + group, enabled).apply();
  }

  public int volume(String group) {
    return Math.max(0, Math.min(100, prefs.getInt("volume." + group, 65)));
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
    if (event.startsWith("autohold.")) return autoHoldCalibrated();
    if (event.startsWith("lock.") && lockCalibrated()) return true;
    EventSpec s = EventSpec.find(event);
    if (s != null && s.mapped) return true;
    JSONArray a = rules();
    for (int i = 0; i < a.length(); i++)
      if (event.equals(a.optJSONObject(i).optString("event"))) return true;
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
    return autoHoldState(prefs.getString("autohold.holding", "")) != null;
  }

  public String autoHoldState(String raw) {
    return Protocol.autoHoldState(
        raw, prefs.getString("autohold.holding", ""), prefs.getString("autohold.released", ""));
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
    if (!lockCalibrated()) return "中控锁未校准，不会自动播放；选择音源或确认车外设备不能代替状态校准";
    if (!enabled("lock")) return "解闭锁提示音开关已关闭";
    if (!prefs.getBoolean("lock.output.confirmed", false)) return "车外设备未人工确认，不会自动播放";
    try {
      for (AudioDeviceInfo device : audioOutputs())
        if (lockOutputMatches(device)) return "解闭锁配置就绪；首样本仅建立基线，后续有效变化才播放";
    } catch (Exception ignored) {
    }
    return "所选车外设备不可用，请重新选择、试听并确认";
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
    return device != null
        && device.getId() == prefs.getInt("lock.output.id", -1)
        && device.getType() == prefs.getInt("lock.output.type", -1)
        && String.valueOf(device.getProductName()).equals(prefs.getString("lock.output.name", ""))
        && address(device).equals(prefs.getString("lock.output.address", ""));
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
