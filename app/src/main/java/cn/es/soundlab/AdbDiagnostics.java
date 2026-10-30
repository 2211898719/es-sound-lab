package cn.es.soundlab;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Manual read-only collection. Does not grant permissions or change the vehicle. */
public final class AdbDiagnostics {
  private static final java.util.concurrent.atomic.AtomicBoolean COLLECTING =
      new java.util.concurrent.atomic.AtomicBoolean();
  public static final int COMMAND_LIMIT = 2 * 1024 * 1024, TOTAL_LIMIT = 12 * 1024 * 1024;

  public static synchronized KeyPair key(Context context) throws Exception {
    File file = new File(context.getFilesDir(), "adb-diagnostics-client-key.json");
    if (!file.exists()) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair generated = generator.generateKeyPair();
      JSONObject pair =
          new JSONObject()
              .put(
                  "private",
                  Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()))
              .put(
                  "public", Base64.getEncoder().encodeToString(generated.getPublic().getEncoded()));
      write(file, pair.toString().getBytes(StandardCharsets.UTF_8));
      return generated;
    }
    JSONObject pair = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
    KeyFactory factory = KeyFactory.getInstance("RSA");
    return new KeyPair(
        factory.generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(pair.getString("public")))),
        factory.generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pair.getString("private")))));
  }

  static File reportFile(Context context) {
    return new File(context.getFilesDir(), "adb-diagnostics-report.json");
  }

  public static synchronized JSONObject latest(Context context) throws Exception {
    File file = reportFile(context);
    return file.exists() ? new JSONObject(new String(read(file), StandardCharsets.UTF_8)) : null;
  }

  public static JSONObject collect(
      Context context, AdbClient client, String host, int port, AdbClient.Progress progress)
      throws Exception {
    JSONObject report =
        new JSONObject()
            .put("schemaVersion", 1)
            .put("source", "authenticated-adb-shell")
            .put("startedAt", System.currentTimeMillis())
            .put("host", host)
            .put("port", port)
            .put("complete", false)
            .put("commandExitCodesAvailable", false)
            .put("note", "只读元数据和定量日志；传统shell不提供退出码。命令输出中的拒绝/缺失需结合内容判断，ADB身份不代表应用已获车辆权限。");
    JSONArray results = new JSONArray();
    report.put("commands", results);
    List<AdbDiagnosticCommands.Command> commands = AdbDiagnosticCommands.metadata();
    int total = 0;
    if (!COLLECTING.compareAndSet(false, true)) {
      client.close();
      throw new IllegalStateException("另一次ADB采集正在结束，请稍后重试");
    }
    try {
      checkpoint(context, report);
      progress.message("正在连接 ADB…");
      client.connect(host, port, key(context), progress);
      for (int index = 0; index < commands.size(); index++) {
        AdbDiagnosticCommands.Command command = commands.get(index);
        progress.message("采集 " + (index + 1) + "/" + commands.size() + " · " + command.name);
        JSONObject item =
            new JSONObject()
                .put("name", command.name)
                .put("shell", command.shell)
                .put("startedAt", System.currentTimeMillis());
        results.put(item);
        try {
          int room = TOTAL_LIMIT - total;
          if (room <= 0) throw new IllegalStateException("诊断达到12MiB上限，已保留部分结果");
          AdbClient.Result output = client.shell(command.shell, Math.min(COMMAND_LIMIT, room));
          total += output.text.getBytes(StandardCharsets.UTF_8).length;
          item.put("output", output.text)
              .put("truncated", output.truncated)
              .put("transportCompleted", true)
              .put("exitCode", JSONObject.NULL);
          if (command.name.equals("car-service-package")) {
            String pkg = output.text.trim();
            if (!Arrays.asList(AdbDiagnosticCommands.PACKAGES).contains(pkg))
              AdbDiagnosticCommands.addPackage(commands, pkg);
          }
          if (command.name.equals("home-activity")) {
            for (String line : output.text.split("\\r?\\n")) {
              int slash = line.indexOf('/');
              if (slash > 0)
                AdbDiagnosticCommands.addPackage(commands, line.substring(0, slash).trim());
            }
          }
        } catch (Exception error) {
          item.put("transportCompleted", false).put("error", error.toString());
          if (error instanceof AdbClient.ShellException)
            item.put("output", ((AdbClient.ShellException) error).partialOutput);
          throw error;
        } finally {
          item.put("finishedAt", System.currentTimeMillis());
          checkpoint(context, report);
        }
      }
      AdbDiagnosticCommands.Command logs = AdbDiagnosticCommands.logs();
      progress.message("采集近期车辆接口和权限相关日志…");
      JSONObject logResult = new JSONObject().put("name", logs.name).put("shell", logs.shell);
      results.put(logResult);
      try {
        if (total >= TOTAL_LIMIT) throw new IllegalStateException("达到总大小上限，未采集日志");
        AdbClient.Result output =
            client.shell(logs.shell, Math.min(COMMAND_LIMIT, TOTAL_LIMIT - total));
        logResult
            .put("output", output.text)
            .put("truncated", output.truncated)
            .put("transportCompleted", true)
            .put("exitCode", JSONObject.NULL);
      } catch (Exception error) {
        logResult.put("transportCompleted", false).put("error", error.toString());
        if (error instanceof AdbClient.ShellException)
          logResult.put("output", ((AdbClient.ShellException) error).partialOutput);
        throw error;
      }
      report.put("complete", true);
    } catch (Exception error) {
      report.put("error", error.toString());
      progress.message("采集未完整完成，已保存部分结果：" + error.getMessage());
    } finally {
      client.close();
      report.put("finishedAt", System.currentTimeMillis());
      try {
        checkpoint(context, report);
      } finally {
        COLLECTING.set(false);
      }
    }
    return report;
  }

  private static synchronized void checkpoint(Context context, JSONObject report) throws Exception {
    write(reportFile(context), report.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] read(File file) throws Exception {
    try (FileInputStream input = new AtomicFile(file).openRead();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
      return bytes.toByteArray();
    }
  }

  private static void write(File file, byte[] bytes) throws Exception {
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream output = null;
    try {
      output = atomic.startWrite();
      output.write(bytes);
      atomic.finishWrite(output);
    } catch (Exception error) {
      atomic.failWrite(output);
      throw error;
    }
  }

  private AdbDiagnostics() {}
}
