package cn.es.soundlab;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AdbApkExtractor {
  static final String[] PACKAGES = {"com.mega.car", "com.android.systemui"};
  static final String[] NAMES = {"RedwoodCarService", "RockySystemUI"};
  static final String[] INTERFACE_PACKAGES = {"com.mega.ecology", "com.iflytek.autofly.sceneengine",
      "com.android.launcher3", "com.lion.scenemode"};
  static final String[] INTERFACE_NAMES = {"RockyEcologyCenter", "RockySceneEngine",
      "RockyLauncher", "RockyLionSceneModeService"};
  static final long FILE_LIMIT = 2L * 1024 * 1024 * 1024,
      TOTAL_LIMIT = 4L * 1024 * 1024 * 1024,
      MIN_FREE_SPACE = 64L * 1024 * 1024;

  static String[] targets(boolean legacy, boolean interfaces) {
    List<String> selected = new ArrayList<>();
    if (interfaces) java.util.Collections.addAll(selected, INTERFACE_PACKAGES);
    if (legacy) java.util.Collections.addAll(selected, PACKAGES);
    return selected.toArray(new String[0]);
  }

  private static String archiveName(String pkg) {
    for (int index = 0; index < INTERFACE_PACKAGES.length; index++)
      if (INTERFACE_PACKAGES[index].equals(pkg)) return INTERFACE_NAMES[index];
    for (int index = 0; index < PACKAGES.length; index++)
      if (PACKAGES[index].equals(pkg)) return NAMES[index];
    if (!AdbPackageCatalog.validPackage(pkg))
      throw new IllegalArgumentException("APK包名格式无效：" + pkg);
    return "packages/" + pkg;
  }

  static String[] validateTargets(String[] targets) {
    if (targets == null) throw new IllegalArgumentException("未指定APK提取目标");
    String[] selected = targets.clone();
    if (selected.length > AdbPackageCatalog.MAX_TARGETS)
      throw new IllegalArgumentException("每次最多选择64个包，请分批提取");
    List<String> seen = new ArrayList<>();
    for (String pkg : selected) {
      archiveName(pkg);
      if (seen.contains(pkg)) throw new IllegalArgumentException("重复的APK提取目标：" + pkg);
      seen.add(pkg);
    }
    return selected;
  }

  static long transferLimit(long total) throws IOException {
    if (total < 0 || total >= TOTAL_LIMIT) throw new IOException("APK提取合计超过4GiB上限");
    return Math.min(FILE_LIMIT, TOTAL_LIMIT - total);
  }

  static void requireSpace(File directory) throws IOException {
    long available = directory.getUsableSpace();
    if (available < MIN_FREE_SPACE)
      throw new IOException("App存储空间不足：剩余" + available / (1024 * 1024)
          + "MiB，提取时需保留至少64MiB空闲空间，请清理后重试");
  }

  private static final class ApkOutputStream extends FilterOutputStream {
    private final File directory;
    private long checkedAt;
    long bytes;

    ApkOutputStream(OutputStream output, File directory) throws IOException {
      super(output);
      this.directory = directory;
      requireSpace(directory);
    }

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      if (bytes - checkedAt >= 1024 * 1024) {
        requireSpace(directory);
        checkedAt = bytes;
      }
      out.write(buffer, offset, length);
      bytes += length;
    }
  }

  static List<String> paths(String output) throws IOException {
    List<String> paths = new ArrayList<>();
    for (String line : output.split("\\r?\\n")) {
      if (!line.startsWith("package:")) continue;
      String path = line.substring(8).trim();
      if (!path.matches("/(?:system|system_ext|product|vendor|data/app|apex)/[A-Za-z0-9_./+=~@-]+\\.apk")
          || path.contains("/../") || path.contains("/./"))
        throw new IOException("APK 路径不符合只读白名单");
      if (!paths.contains(path)) paths.add(path);
      if (paths.size() > 16) throw new IOException("APK 分包数量超过16个");
    }
    if (paths.isEmpty()) throw new IOException("pm path 未返回已安装APK路径");
    return paths;
  }

  static void extract(Context context, AdbClient client, JSONObject result, long collectionId,
      AdbClient.Progress progress) throws Exception {
    extract(context, client, result, collectionId, PACKAGES, progress);
  }

  static void extract(Context context, AdbClient client, JSONObject result, long collectionId,
      String[] targets, AdbClient.Progress progress) throws Exception {
    String[] selected = validateTargets(targets);
    if (selected.length == 0) throw new IllegalArgumentException("未选择APK提取目标");
    result.put("complete", false).put("collectionId", collectionId)
        .put("packages", new JSONArray(java.util.Arrays.asList(selected)))
        .put("fileLimitBytes", FILE_LIMIT).put("totalLimitBytes", TOTAL_LIMIT)
        .put("minFreeSpaceBytes", MIN_FREE_SPACE);
    JSONArray files = new JSONArray();
    result.put("files", files);
    JSONArray packageResults = new JSONArray();
    result.put("packageResults", packageResults);
    JSONObject currentPackage = null;
    File archive = new File(context.getFilesDir(), "adb-apks-" + collectionId + ".zip");
    File partial = new File(archive.getPath() + ".part");
    File[] leftovers = context.getFilesDir().listFiles();
    if (leftovers != null)
      for (File file : leftovers)
        if (file.getName().matches("adb-apks-[0-9]+\\.zip\\.part")) file.delete();
    long total = 0;
    try {
      requireSpace(context.getFilesDir());
      try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(partial))) {
        for (String pkg : selected) {
          currentPackage = new JSONObject().put("package", pkg).put("complete", false);
          packageResults.put(currentPackage);
          String name = archiveName(pkg);
          progress.message("定位 APK · " + pkg);
          AdbClient.Result located = client.shell("pm path " + pkg, 32768);
          if (located.truncated) throw new IOException("APK路径输出被截断");
          List<String> paths = paths(located.text);
          for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            String entry = index == 0 ? name + ".apk"
                : name + "/" + index + "-" + new File(path).getName();
            JSONObject item = new JSONObject().put("package", pkg)
                .put("remotePath", path).put("entry", entry).put("complete", false);
            files.put(item);
            try {
              long limit = transferLimit(total);
              String limitKind = limit == FILE_LIMIT ? "单文件2GiB上限" : "合计4GiB剩余额度";
              item.put("limitBytes", limit).put("limitKind", limitKind);
              progress.message("读取 " + entry);
              zip.putNextEntry(new ZipEntry(entry));
              MessageDigest digest = MessageDigest.getInstance("SHA-256");
              ApkOutputStream destination = new ApkOutputStream(
                  new DigestOutputStream(zip, digest), context.getFilesDir());
              long bytes;
              try {
                bytes = client.pull(path, destination, limit,
                    message -> progress.message(entry + " · " + message));
              } finally {
                item.put("bytes", destination.bytes);
                result.put("bytes", total + destination.bytes);
              }
              if (bytes == 0) throw new IOException("APK为空，不能作为提取成功");
              zip.closeEntry();
              total += bytes;
              item.put("complete", true).put("sha256", hex(digest.digest()));
            } catch (Exception error) {
              String detail = entry + "：" + error.getMessage();
              if (error instanceof AdbClient.TransferLimitException)
                detail += "（" + item.getString("limitKind") + "）";
              item.put("error", detail);
              throw new IOException(detail, error);
            }
          }
          currentPackage.put("complete", true).put("fileCount", paths.size());
        }
        result.put("bytes", total);
        zip.putNextEntry(new ZipEntry("extraction-info.json"));
        zip.write(new JSONObject(result.toString()).put("complete", true)
            .put("archiveFile", archive.getName()).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      if (!partial.renameTo(archive)) throw new IOException("无法保存APK归档");
      result.put("archiveFile", archive.getName()).put("complete", true);
      File[] old = context.getFilesDir().listFiles();
      if (old != null)
        for (File file : old)
          if (file.getName().matches("adb-apks-[0-9]+\\.zip(?:\\.part)?") && !file.equals(archive))
            file.delete();
    } catch (Exception error) {
      partial.delete();
      archive.delete();
      if (currentPackage != null && !currentPackage.optBoolean("complete")) {
        currentPackage.put("error", error.toString());
        result.put("failedPackage", currentPackage.getString("package"));
      }
      result.put("error", error.toString());
      throw error;
    }
  }

  static File archive(Context context, JSONObject report) throws Exception {
    JSONObject result = report == null ? null : report.optJSONObject("apkExtraction");
    if (result == null || !result.optBoolean("complete"))
      throw new IOException("本次没有完整提取所选APK，请勾选提取后重新采集");
    String name = "adb-apks-" + report.getLong("startedAt") + ".zip";
    if (!name.equals(result.optString("archiveFile"))) throw new IOException("APK归档不属于本次采集");
    File file = new File(context.getFilesDir(), name);
    if (!file.isFile()) throw new IOException("APK归档已不存在，请重新提取");
    return file;
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte value : bytes) text.append(String.format(java.util.Locale.US, "%02x", value & 255));
    return text.toString();
  }

  private AdbApkExtractor() {}
}
