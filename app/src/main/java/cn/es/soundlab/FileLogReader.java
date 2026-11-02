package cn.es.soundlab;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FileLogReader {
  public static final String PATH = "/log/android/logcat.log";
  static final int MAX_BACKLOG = 262144, MAX_LINE = 8192;

  interface Handle extends Closeable {
    long size() throws Exception;
    void seek(long position) throws Exception;
    int read(byte[] bytes, int count) throws Exception;
  }

  interface Access {
    String identity() throws Exception;
    Handle open() throws Exception;
    JSONArray evidence();
    default String path() { return PATH; }
    default String backend() { return "file"; }
    default boolean relevant(String line) { return Protocol.relevant(line); }
  }

  static final class AndroidAccess implements Access {
    private final String path;
    AndroidAccess() { this(PATH); }
    AndroidAccess(String path) { this.path = path; }
    public String path() { return path; }
    public String identity() throws Exception {
      StructStat stat = Os.lstat(path);
      if (!OsConstants.S_ISREG(stat.st_mode)) throw new IOException("不是普通文件，拒绝读取");
      return stat.st_dev + ":" + stat.st_ino;
    }

    public Handle open() throws Exception {
      FileDescriptor descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK
          | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
      try {
        StructStat stat = Os.fstat(descriptor);
        StructStat current = Os.lstat(path);
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev != current.st_dev || stat.st_ino != current.st_ino)
          throw new IOException("文件类型或身份已改变，请重新连接");
      } catch (Exception error) {
        Os.close(descriptor);
        throw error;
      }
      return new Handle() {
        public long size() throws Exception { return Os.fstat(descriptor).st_size; }
        public void seek(long position) throws Exception { Os.lseek(descriptor, position, OsConstants.SEEK_SET); }
        public int read(byte[] bytes, int count) throws Exception { return Os.read(descriptor, bytes, 0, count); }
        public void close() throws IOException {
          try { Os.close(descriptor); }
          catch (Exception failure) { throw new IOException(failure); }
        }
      };
    }

    public JSONArray evidence() {
      JSONArray result = new JSONArray();
      String[] paths = this.path.equals(PATH) ? new String[] {"/log", "/log/android", PATH}
          : new String[] {new java.io.File(this.path).getParent(), this.path};
      for (String path : paths) {
        JSONObject item = new JSONObject();
        try {
          item.put("path", path);
          StructStat stat = Os.lstat(path);
          item.put("uid", stat.st_uid).put("gid", stat.st_gid)
              .put("mode", Integer.toOctalString(stat.st_mode)).put("size", stat.st_size)
              .put("mtimeSeconds", stat.st_mtime).put("inode", stat.st_ino).put("device", stat.st_dev);
        } catch (Exception error) {
          try { item.put("error", error.toString()); } catch (Exception ignored) {}
        }
        result.put(item);
      }
      return result;
    }
  }

  private final Diagnostics diagnostics;
  private final LogReader.Listener listener;
  private final Access access;
  private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
  private final byte[] buffer = new byte[16384];
  private volatile boolean closed;
  private Thread thread;
  private Handle handle;
  private String identity;
  private boolean discard;
  private boolean openAttempted, opened;
  private byte[] anchor = new byte[0];
  private long position, start, bytes, lines, rotations, skipped, lastGrowth, lastRelevant;
  private long readBatches, lastBatchBytes, lastBatchRelevantLines, lastGrowthGapMillis;
  private JSONArray evidence = new JSONArray();
  private String phase = "idle", error = "";

  public FileLogReader(Diagnostics diagnostics, LogReader.Listener listener) {
    this(diagnostics, listener, new AndroidAccess());
  }

  FileLogReader(Diagnostics diagnostics, LogReader.Listener listener, Access access) {
    this.diagnostics = diagnostics;
    this.listener = listener;
    this.access = access;
  }

  public synchronized void start() {
    if (thread != null || closed) return;
    thread = new Thread(() -> {
      try {
        while (!closed) {
          poll(System.currentTimeMillis());
          Thread.sleep(250);
        }
      } catch (InterruptedException ignored) {
      } finally {
        stop();
      }
    }, "ES-FileLog");
    thread.setDaemon(true);
    thread.start();
  }

  synchronized void poll(long now) {
    if (closed) return;
    try {
      if (handle == null) {
        evidence = access.evidence();
        reopen(now, "waiting");
      } else if (!identity.equals(access.identity())) {
        reopen(now, "rotated");
      }
      long size = handle.size();
      boolean rewritten = size >= position && size - position <= MAX_BACKLOG && !anchorMatches();
      if (size < position || size - position > MAX_BACKLOG || rewritten) {
        skipped++;
        baseline(now, size < position ? "truncated" : rewritten ? "rewritten" : "backlog-skipped");
        size = position;
      }
      int remaining = (int) Math.min(65536, size - position);
      long previousBytes = bytes, previousLines = lines;
      while (remaining > 0 && !closed) {
        int count = handle.read(buffer, Math.min(buffer.length, remaining));
        if (count <= 0) break;
        position += count;
        bytes += count;
        remaining -= count;
        for (int index = 0; index < count && !closed; index++) {
          int value = buffer[index] & 255;
          if (value == '\n') {
            if (!discard && partial.size() > 0) {
              String line = new String(partial.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
              if (access.relevant(line)) {
                lines++;
                lastRelevant = now;
                listener.line(line, start);
              }
            }
            partial.reset();
            discard = false;
          } else if (!discard) {
            if (partial.size() >= MAX_LINE) {
              partial.reset();
              discard = true;
              skipped++;
              listener.reset();
            } else partial.write(value);
          }
        }
      }
      if (closed) return;
      if (bytes > previousBytes) {
        lastGrowthGapMillis = lastGrowth == 0 ? 0 : Math.max(0, now - lastGrowth);
        lastGrowth = now;
        readBatches++;
        phase = "tailing";
        lastBatchBytes = bytes - previousBytes;
        lastBatchRelevantLines = lines - previousLines;
      }
      captureAnchor();
      publish(now);
    } catch (CarotaLogAccess.WaitingForShard pending) {
      waitForShard(now, pending.getMessage());
    } catch (Exception failure) {
      if (closed) return;
      if (access.backend().equals("carota") && failure instanceof android.system.ErrnoException
          && ((android.system.ErrnoException) failure).errno == OsConstants.ENOENT) {
        waitForShard(now, "分片切换中，等待最新文件：" + failure.toString());
        return;
      }
      error = failure.toString();
      phase = "failed";
      diagnostics.line("文件日志", "原App身份读取失败：" + error + "；不自动改权限或回退logcat");
      closeHandle();
      closed = true;
      listener.reset();
      publish(now);
    }
  }

  private void waitForShard(long now, String message) {
    closeHandle();
    if (!phase.equals("waiting-shard")) listener.reset();
    phase = "waiting-shard";
    error = message;
    evidence = access.evidence();
    publish(now);
  }

  private void reopen(long now, String nextPhase) throws Exception {
    closeHandle();
    String nextIdentity = access.identity();
    if (identity != null && !identity.equals(nextIdentity)) rotations++;
    identity = nextIdentity;
    openAttempted = true;
    handle = access.open();
    opened = true;
    error = "";
    if (!identity.equals(access.identity())) throw new IOException("打开期间文件发生轮转，请重新连接");
    evidence = access.evidence();
    baseline(now, nextPhase);
  }

  private void baseline(long now, String nextPhase) throws Exception {
    start = now;
    lastGrowth = 0;
    lastRelevant = 0;
    lastBatchBytes = 0;
    lastBatchRelevantLines = 0;
    lastGrowthGapMillis = 0;
    position = handle.size();
    partial.reset();
    discard = false;
    if (position > 0) {
      handle.seek(position - 1);
      discard = handle.read(buffer, 1) == 1 && buffer[0] != '\n';
    }
    handle.seek(position);
    captureAnchor();
    phase = nextPhase;
    listener.reset();
  }

  private void closeHandle() {
    if (handle == null) return;
    try { handle.close(); } catch (Exception ignored) {}
    handle = null;
  }

  private byte[] readAnchor() throws Exception {
    int count = (int) Math.min(64, position);
    byte[] result = new byte[count];
    handle.seek(position - count);
    byte[] scratch = new byte[count];
    int offset = 0;
    while (offset < count) {
      int received = handle.read(scratch, count - offset);
      if (received <= 0) throw new IOException("文件在检查边界时被截断，请重新连接");
      System.arraycopy(scratch, 0, result, offset, received);
      offset += received;
    }
    handle.seek(position);
    return result;
  }

  private boolean anchorMatches() throws Exception {
    return java.util.Arrays.equals(anchor, readAnchor());
  }

  private void captureAnchor() throws Exception {
    anchor = readAnchor();
  }

  private void publish(long now) {
    try {
      JSONObject report = new JSONObject().put("path", access.path()).put("backend", access.backend())
          .put("identity", "App UID " + android.os.Process.myUid())
          .put("phase", phase).put("error", error).put("updatedAt", now).put("sessionStart", start)
          .put("openAttempted", openAttempted).put("opened", opened)
          .put("position", position).put("bytesRead", bytes).put("relevantLines", lines)
          .put("rotations", rotations).put("skipped", skipped).put("lastGrowthAt", lastGrowth)
          .put("lastRelevantAt", lastRelevant).put("metadata", evidence);
      report.put("readBatches", readBatches).put("lastBatchBytes", lastBatchBytes)
          .put("lastBatchRelevantLines", lastBatchRelevantLines).put("lastGrowthGapMillis", lastGrowthGapMillis);
      diagnostics.fileLog(report);
      diagnostics.logStatus = "failed".equals(phase) ? "文件不可读：" + error + " · 请导出记录"
          : "stopped".equals(phase) ? "文件日志已停止"
          : "waiting-shard".equals(phase) ? "Carota等待最新分片：" + error
          : (access.backend().equals("carota") ? "Carota · " + new java.io.File(access.path()).getName() + " · " : "")
              + "文件已打开 · 新增 " + bytes + " 字节 / 相关 " + lines + " 行 · "
              + (lastGrowth == 0 || now - lastGrowth > 5000 ? "等待新写入（尚未证明实时有效）" : "检测到新写入");
    } catch (Exception ignored) {}
  }

  public synchronized void stop() {
    if (closed) return;
    closed = true;
    closeHandle();
    if (thread != null) thread.interrupt();
    if (!"failed".equals(phase)) phase = "stopped";
    listener.reset();
    publish(System.currentTimeMillis());
  }
}
