package cn.es.soundlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SessionJournal {
  private static final String PROCESS_ID = UUID.randomUUID().toString();
  private static final Object STORAGE_LOCK = new Object();
  private final String sessionId = UUID.randomUUID().toString();
  private final Context context;
  private final SharedPreferences prefs;
  private final ArrayDeque<JSONObject> entries = new ArrayDeque<>();
  private final long startedAt = System.currentTimeMillis();
  private final String sessionOwner;

  public SessionJournal(Context owner) {
    this(owner, "监听服务");
  }

  public SessionJournal(Context owner, String name) {
    sessionOwner = name;
    context = owner.getApplicationContext();
    prefs = context.getSharedPreferences("session_journal", Context.MODE_PRIVATE);
    record("生命周期", name + "创建");
  }

  private void reload() {
    entries.clear();
    try {
      JSONArray saved = new JSONArray(prefs.getString("entries", "[]"));
      for (int index = Math.max(0, saved.length() - 160); index < saved.length(); index++)
        entries.addLast(saved.getJSONObject(index));
    } catch (Exception ignored) {
      entries.clear();
    }
  }

  public synchronized JSONObject snapshot() throws Exception {
    PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    return new JSONObject()
        .put("time", System.currentTimeMillis())
        .put("processId", PROCESS_ID)
        .put("pid", android.os.Process.myPid())
        .put("serviceSessionId", sessionId)
        .put("sessionOwner", sessionOwner)
        .put("serviceStartedAt", startedAt)
        .put("bootCount", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1))
        .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        .put("uptimeMs", SystemClock.uptimeMillis())
        .put("interactive", power != null && power.isInteractive())
        .put("readLogsGranted", context.checkSelfPermission("android.permission.READ_LOGS")
            == android.content.pm.PackageManager.PERMISSION_GRANTED);
  }

  public synchronized void record(String type, String message) {
    synchronized (STORAGE_LOCK) {
      reload();
      try {
        entries.addLast(snapshot().put("type", type)
            .put("message", message.length() > 500 ? message.substring(0, 500) : message));
        while (entries.size() > 160) entries.removeFirst();
        prefs.edit().putString("entries", new JSONArray(entries).toString()).apply();
      } catch (Exception ignored) {
      }
    }
  }

  public synchronized JSONArray export() {
    synchronized (STORAGE_LOCK) {
      reload();
      return new JSONArray(entries);
    }
  }

  public synchronized void clear() {
    synchronized (STORAGE_LOCK) {
      entries.clear();
      prefs.edit().remove("entries").apply();
      record("生命周期", "用户清空诊断记录");
    }
  }
}
