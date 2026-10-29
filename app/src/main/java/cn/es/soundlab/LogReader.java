package cn.es.soundlab;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

public final class LogReader {
  public interface Listener {
    void line(String line, long sessionStart);

    void reset();
  }

  private final Context context;
  private final Diagnostics diag;
  private final Listener listener;
  interface ProcessFactory {
    Process start(String[] command) throws IOException;
  }

  private final ProcessFactory factory;
  private volatile boolean discovery;
  private volatile boolean closed;
  private volatile boolean disconnected;
  private volatile boolean received;
  private volatile Process process;
  private Thread thread;
  private Thread errorThread;

  public LogReader(Context c, Diagnostics d, Listener l, boolean scan) {
    this(c, d, l, scan, command -> new ProcessBuilder(command).start());
  }

  LogReader(Context c, Diagnostics d, Listener l, boolean scan, ProcessFactory launcher) {
    context = c;
    diag = d;
    listener = l;
    discovery = scan;
    factory = launcher;
  }

  public void discovery(boolean scan) {
    discovery = scan;
    if (!disconnected && received && process != null)
      diag.logStatus = scan ? "扩展检测中 · 复用现有日志连接" : "标准监听 · 复用现有日志连接";
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
        String[] command = {"logcat", "-b", "main", "-b", "system", "-v", "epoch", "-T", "1", "*:V"};
        Process p = factory.start(command);
        process = p;
        if (closed) {
          p.destroy();
          break;
        }
        diag.line("日志通道", "启动 logcat，扩展检测=" + discovery + "；权限已授予不代表读取已获系统批准");
        diag.logStatus = "已启动日志请求 · 等待系统批准或车辆数据";
        errorThread = new Thread(() -> readErrors(p), "ES-LogcatErrors");
        errorThread.setDaemon(true);
        errorThread.start();
        received = false;
        try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while (!closed && (line = reader.readLine()) != null) {
            if (Protocol.relevant(line) && (discovery || Protocol.standardLog(line))) {
              if (!received) {
                received = true;
                diag.logStatus = discovery ? "扩展检测中 · 已收到车辆日志" : "日志监听中 · 已收到车辆日志";
                diag.line("日志通道", "收到本次请求的首条车辆相关日志");
              }
              listener.line(line, started);
            }
          }
        }
        if (!closed) {
          String exit;
          try {
            exit = String.valueOf(p.exitValue());
          } catch (IllegalThreadStateException stillRunning) {
            exit = "未知（输出流结束时进程仍存活）";
          }
          diag.line("日志通道", "logcat 输出结束，退出码=" + exit);
        }
      } catch (Exception e) {
        if (!closed) diag.line("日志通道", "读取异常：" + e);
      } finally {
        Process p = process;
        process = null;
        if (p != null) p.destroy();
        disconnected = true;
        diag.line("日志通道", closed ? "主动关闭日志请求" : "日志请求结束；暂停自动重试，请在前台点击重新连接");
        listener.reset();
      }
      if (!closed) diag.logStatus = "日志通道已断开 · 请回到前台点击重新连接（不会反复请求授权）";
      break;
    }
  }

  private void readErrors(Process owner) {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(owner.getErrorStream(), StandardCharsets.UTF_8))) {
      String message;
      int count = 0;
      while (!closed && (message = reader.readLine()) != null) {
        if (count++ < 16) diag.line("日志通道", "logcat stderr：" + message);
      }
    } catch (Exception error) {
      if (!closed) diag.line("日志通道", "logcat stderr 结束：" + error);
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
