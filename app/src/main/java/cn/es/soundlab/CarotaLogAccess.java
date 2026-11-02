package cn.es.soundlab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class CarotaLogAccess implements FileLogReader.Access {
  static final class WaitingForShard extends IOException {
    WaitingForShard(String message) { super(message); }
  }
  static final String PACKAGE = "com.carota.chery.v4";
  static final File ROOT = new File(android.os.Environment.getExternalStorageDirectory(), "carota");
  private static final Pattern NAME = Pattern.compile(
      "com\\.carota\\.chery\\.v4\\[\\d+T(\\d{4}-\\d{4}-\\d{4}-\\d{2})\\]\\.log");
  private final File root;
  private File selected;
  private FileLogReader.AndroidAccess current;
  private long selectedTime = Long.MIN_VALUE;

  CarotaLogAccess() { this(ROOT); }
  CarotaLogAccess(File root) { this.root = root; }

  static long fileTime(String name) {
    Matcher match = NAME.matcher(name);
    if (!match.matches()) return Long.MIN_VALUE;
    try {
      SimpleDateFormat format = new SimpleDateFormat("yyyy-MMdd-HHmm-ss", Locale.ROOT);
      format.setLenient(false);
      return format.parse(match.group(1)).getTime();
    } catch (Exception invalid) { return Long.MIN_VALUE; }
  }

  static File latest(File root) throws IOException {
    if (!root.isDirectory()) throw new IOException("Carota目录不存在或不可读：" + root);
    File newest = null;
    long newestTime = Long.MIN_VALUE;
    int entries = 0;
    for (File directory : new File[] {root, new File(root, PACKAGE)}) {
      if (!directory.equals(root) && !directory.exists()) continue;
      if (Files.isSymbolicLink(directory.toPath())) throw new IOException("拒绝跟随Carota目录符号链接：" + directory);
      File[] files = directory.listFiles();
      if (files == null) throw new IOException("无法列出Carota目录，请检查存储权限：" + directory);
      entries += files.length;
      if (entries > 4096) throw new IOException("Carota目录条目超过4096，请导出记录后检查");
      for (File file : files) {
        long time = fileTime(file.getName());
        if (time == Long.MIN_VALUE || !file.isFile() || Files.isSymbolicLink(file.toPath())) continue;
        if (newest == null || time > newestTime || time == newestTime
            && (file.lastModified() > newest.lastModified() || file.lastModified() == newest.lastModified()
                && file.getAbsolutePath().compareTo(newest.getAbsolutePath()) > 0)) {
          newest = file;
          newestTime = time;
        }
      }
    }
    if (newest == null) throw new WaitingForShard("等待Carota目录产生匹配的当前.log分片：" + root);
    return newest;
  }

  File select() throws IOException {
    File newest = latest(root);
    long time = fileTime(newest.getName());
    if (selected != null && time < selectedTime && Files.notExists(selected.toPath()))
      throw new WaitingForShard("原分片已移除，等待更晚的Carota分片，不回退旧文件");
    if (selected == null || time > selectedTime
        || time == selectedTime && Files.notExists(selected.toPath())) {
      selected = newest;
      selectedTime = time;
      current = new FileLogReader.AndroidAccess(selected.getAbsolutePath());
    }
    return selected;
  }

  public String identity() throws Exception {
    select();
    return selected.getAbsolutePath() + "|" + current.identity();
  }

  public FileLogReader.Handle open() throws Exception {
    if (current == null) throw new IOException("尚未选中Carota分片");
    return current.open();
  }

  public JSONArray evidence() {
    JSONArray evidence = current == null
        ? new FileLogReader.AndroidAccess(new File(root, PACKAGE).getAbsolutePath()).evidence() : current.evidence();
    try {
      evidence.put(new JSONObject().put("directory", root.getAbsolutePath())
          .put("packageDirectory", new File(root, PACKAGE).getAbsolutePath())
          .put("selection", "filename timestamp; active shard never rolls back")
          .put("selected", selected == null ? JSONObject.NULL : selected.getAbsolutePath()));
    } catch (Exception ignored) {}
    return evidence;
  }

  public String path() { return selected == null ? root.getAbsolutePath() : selected.getAbsolutePath(); }
  public String backend() { return "carota"; }
  public boolean relevant(String line) { return CarotaLog.relevant(line); }
}
