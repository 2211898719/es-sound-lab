package cn.es.soundlab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class AdbPackageCatalog {
  static final int MAX_TARGETS = 64, MAX_PACKAGES = 4096;

  static boolean validPackage(String value) {
    return value != null && value.length() <= 255
        && value.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
  }

  static String[] selection(String manual, String[]... groups) {
    Set<String> selected = new LinkedHashSet<>();
    for (String[] group : groups)
      for (String pkg : group) add(selected, pkg);
    if (manual.length() > 32768) throw new IllegalArgumentException("包名输入过长");
    for (String pkg : manual.trim().split("[\\s,，;；]+"))
      if (!pkg.isEmpty()) add(selected, pkg);
    return selected.toArray(new String[0]);
  }

  private static void add(Set<String> selected, String pkg) {
    if (!validPackage(pkg)) throw new IllegalArgumentException("包名格式无效：" + pkg);
    selected.add(pkg);
    if (selected.size() > MAX_TARGETS)
      throw new IllegalArgumentException("每次最多选择" + MAX_TARGETS + "个包，请分批提取");
  }

  static List<String> parse(String output, boolean truncated) throws IOException {
    if (truncated) throw new IOException("包列表被截断，不能作为完整扫描结果");
    Set<String> packages = new TreeSet<>();
    for (String line : output.split("\\r?\\n")) {
      if (line.trim().isEmpty()) continue;
      if (!line.startsWith("package:")) throw new IOException("包扫描返回异常：" + line);
      int equals = line.lastIndexOf('=');
      if (equals < 8) throw new IOException("包列表缺少安装路径");
      String pkg = line.substring(equals + 1).trim();
      if (!validPackage(pkg)) throw new IOException("包列表含无效包名");
      try {
        AdbApkExtractor.paths("package:" + line.substring(8, equals));
      } catch (IOException unsupported) {
        continue;
      }
      packages.add(pkg);
      if (packages.size() > MAX_PACKAGES) throw new IOException("包扫描超过4096项上限");
    }
    if (packages.isEmpty()) throw new IOException("未取得可识别的已安装包，不代表车机没有应用");
    return new ArrayList<>(packages);
  }

  static List<String> scan(AdbClient client) throws IOException {
    AdbClient.Result result = client.shell("pm list packages -f", AdbDiagnostics.COMMAND_LIMIT);
    return parse(result.text, result.truncated);
  }

  private AdbPackageCatalog() {}
}
