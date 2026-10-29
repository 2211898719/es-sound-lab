package cn.es.soundlab;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class LogReader {
  public interface Listener {
    void line(String line, long sessionStart);

    void reset();
  }

  private final Context context;
  private final Diagnostics diag;
  private final Listener listener;
  private final boolean discovery;
  private volatile boolean closed;
  private volatile Process process;
  private Thread thread;

  public LogReader(Context c, Diagnostics d, Listener l, boolean scan) {
    context = c;
    diag = d;
    listener = l;
    discovery = scan;
  }

  public void start() {
    thread = new Thread(() -> run(), "ES-LogReader");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    while (!closed) {
      if (context.checkSelfPermission("android.permission.READ_LOGS")
          != PackageManager.PERMISSION_GRANTED) {
        diag.logStatus = "日志权限未授予 · 请在应用管家授权本应用";
        pause();
        continue;
      }
      long started = System.currentTimeMillis();
      try {
        String[] command =
            discovery
                ? new String[] {
                  "logcat", "-b", "main", "-b", "system", "-v", "epoch", "-T", "1", "*:V"
                }
                : new String[] {
                  "logcat",
                  "-b",
                  "main",
                  "-b",
                  "system",
                  "-v",
                  "epoch",
                  "-T",
                  "1",
                  "MessageProperty:V",
                  "VCU_PRNDGearAct:V",
                  "CarCabinPropertyService:V",
                  "VehicleStateMonitorB561:V",
                  "Welcome:V",
                  "*:S"
                };
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        process = p;
        if (closed) {
          p.destroy();
          break;
        }
        diag.line("日志通道", "启动 logcat，扩展检测=" + discovery + "；权限已授予不代表读取已获系统批准");
        diag.logStatus = "已启动日志请求 · 等待系统批准或车辆数据";
        boolean received = false;
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while (!closed && (line = reader.readLine()) != null) {
            if (line.contains("Permission denied") || line.contains("not permitted"))
              throw new SecurityException("logcat 读取被系统拒绝");
            if (Protocol.relevant(line)) {
              if (!received) {
                received = true;
                diag.logStatus = discovery ? "扩展检测中 · 已收到车辆日志" : "日志监听中 · 已收到车辆日志";
                diag.line("日志通道", "收到本次请求的首条车辆相关日志");
              }
              listener.line(line, started);
            }
          }
        }
      } catch (Exception e) {
        if (!closed) diag.line("日志读取", e.toString());
      } finally {
        Process p = process;
        process = null;
        if (p != null) p.destroy();
        diag.line("日志通道", closed ? "主动关闭日志请求" : "日志请求结束，将重试并重新建立基线");
        listener.reset();
      }
      if (!closed) {
        diag.logStatus = "日志通道已断开，5 秒后重试";
        pause();
      }
    }
  }

  private void pause() {
    try {
      Thread.sleep(5000);
    } catch (InterruptedException ignored) {
    }
  }

  public void stop() {
    closed = true;
    Process p = process;
    if (p != null) p.destroy();
    if (thread != null) thread.interrupt();
  }
}
