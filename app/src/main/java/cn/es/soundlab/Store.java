package cn.es.soundlab;

import android.content.Context;
import android.content.SharedPreferences;
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
    EventSpec s = EventSpec.find(event);
    if (s != null && s.mapped) return true;
    JSONArray a = rules();
    for (int i = 0; i < a.length(); i++)
      if (event.equals(a.optJSONObject(i).optString("event"))) return true;
    return false;
  }
}
