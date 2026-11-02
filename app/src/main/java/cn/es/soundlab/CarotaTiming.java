package cn.es.soundlab;

import java.util.Locale;
import org.json.JSONObject;

final class CarotaTiming {
  private long samples, lastSourceTime, lastReceivedAt, lastAgeMillis, maxAgeMillis;
  private long expiredAtRead, beforeBaseline, futureAtRead;
  private long processed, lastQueueMillis, maxQueueMillis, expiredOnMain;

  void observed(Protocol.Sample sample, long baseline, long received) {
    if (!CarotaLog.gear(sample)) return;
    long age = received - sample.time;
    samples++;
    lastSourceTime = sample.time;
    lastReceivedAt = received;
    lastAgeMillis = age;
    if (samples == 1 || age > maxAgeMillis) maxAgeMillis = age;
    if (age > 5000) expiredAtRead++;
    if (sample.time < baseline) beforeBaseline++;
    if (age < -500) futureAtRead++;
  }

  void processed(long received, long now, boolean expired) {
    processed++;
    lastQueueMillis = Math.max(0, now - received);
    maxQueueMillis = Math.max(maxQueueMillis, lastQueueMillis);
    if (expired) expiredOnMain++;
  }

  JSONObject snapshot() {
    JSONObject result = new JSONObject();
    try {
      result.put("samples", samples).put("lastSourceTime", lastSourceTime)
          .put("lastReceivedAt", lastReceivedAt).put("lastAgeMillis", lastAgeMillis)
          .put("maxAgeMillis", maxAgeMillis).put("expiredAtRead", expiredAtRead)
          .put("beforeBaseline", beforeBaseline).put("futureAtRead", futureAtRead)
          .put("processed", processed).put("lastQueueMillis", lastQueueMillis)
          .put("maxQueueMillis", maxQueueMillis).put("expiredOnMain", expiredOnMain);
    } catch (Exception ignored) {}
    return result;
  }

  String summary(long now) {
    if (samples == 0) return "Carota延迟尚未测到：等待新车况行";
    String age = lastAgeMillis < -500 ? "时间超前，检查设备时钟"
        : String.format(Locale.CHINA, "日志到达时已晚 %.1f 秒", Math.max(0, lastAgeMillis) / 1000.0);
    return age + "（" + Math.max(0, now - lastReceivedAt) / 1000 + "秒前读到） · 读取时超5秒弃用 "
        + expiredAtRead + " 条 · App队列最近/最大 " + lastQueueMillis + "/" + maxQueueMillis
        + "ms · 队列过期 " + expiredOnMain + " 条\n日志年龄不包含上游采样/查询延迟，不证明实时或存在缓冲。";
  }
}
