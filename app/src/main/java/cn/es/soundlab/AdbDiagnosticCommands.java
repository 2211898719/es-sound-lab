package cn.es.soundlab;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Fixed read-only diagnostic commands, shared by the built-in ADB collector. */
public final class AdbDiagnosticCommands {
  public static final String[] PACKAGES = {
    "cn.es.soundlab",
    "com.chery.aiot",
    "com.mega.car",
    "com.iflytek.autofly.carcontroladapter",
    "com.iflytek.autofly.platformservice",
    "com.mega.app.engineeringmode",
    "com.mega.engineeringmode",
    "com.android.car",
    "com.mega.carsettings",
    "com.android.systemui",
    "com.android.shell"
  };

  public static final class Command {
    public final String name, shell;

    public Command(String name, String shell) {
      this.name = name;
      this.shell = shell;
    }
  }

  public static List<Command> metadata() {
    List<Command> commands = new ArrayList<>();
    commands.add(new Command("shell-identity", "id"));
    commands.add(new Command("selinux", "getenforce"));
    commands.add(new Command("current-user", "am get-current-user"));
    commands.add(new Command("packages-with-paths", "pm list packages -f"));
    commands.add(
        new Command(
            "home-activity",
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c"
                + " android.intent.category.HOME"));
    commands.add(new Command("shared-libraries", "pm list libraries"));
    commands.add(new Command("binder-services", "service list"));
    commands.add(new Command("permission-definitions", "dumpsys -t 10 package permissions"));
    commands.add(new Command("car-service-package", "getprop ro.android.car.carservice.package"));
    commands.add(new Command("platform-system-route", "getprop ro.autofly.service.system"));
    commands.add(new Command("platform-running-services",
        "dumpsys -t 10 activity services com.iflytek.autofly.platformservice"));
    commands.add(new Command("android-version", "getprop ro.build.version.release"));
    commands.add(new Command("sdk-version", "getprop ro.build.version.sdk"));
    commands.add(new Command("vehicle-debug-property", "getprop persist.mega.car.userdebug"));
    commands.add(new Command("adb-tcp-disabled", "getprop vendor.adbd.disable_tcp"));
    commands.add(new Command("adb-service-port", "getprop service.adb.tcp.port"));
    commands.add(new Command("adb-persistent-port", "getprop persist.adb.tcp.port"));
    commands.add(new Command("adb-tls-port", "getprop service.adb.tls.port"));
    commands.add(new Command("build-fingerprint", "getprop ro.build.fingerprint"));
    commands.add(new Command("build-security-patch", "getprop ro.build.version.security_patch"));
    commands.add(new Command("vendor-security-patch", "getprop ro.vendor.build.security_patch"));
    commands.add(new Command("network-addresses", "ip addr show"));
    commands.add(new Command("network-routes", "ip route show"));
    commands.add(new Command("network-tcp-listeners", "ss -ltn"));
    commands.add(new Command("vehicle-model", "getprop ro.build.vehicle_model"));
    commands.add(new Command("vendor-vehicle-model", "getprop ro.vendor.build.vehicle_model"));
    commands.add(
        new Command("mega-running-services", "dumpsys -t 10 activity services com.mega.car"));
    commands.add(new Command("car-service-dump", "dumpsys -t 10 car_service"));
    commands.add(new Command("mega-service-dump", "dumpsys -t 10 com.mega.car.CarService"));
    for (String pkg : PACKAGES) addPackage(commands, pkg);
    return commands;
  }

  public static void addPackage(List<Command> commands, String pkg) {
    if (!AdbPackageCatalog.validPackage(pkg)) return;
    for (Command command : commands) if (command.name.equals("package-" + pkg)) return;
    commands.add(new Command("package-" + pkg, "dumpsys -t 10 package " + pkg));
    commands.add(new Command("path-" + pkg, "pm path " + pkg));
    commands.add(new Command("appops-" + pkg, "cmd appops get " + pkg));
  }

  public static Command logs() {
    return new Command(
        "vehicle-permission-logs",
        "logcat -d -t 2000 -v threadtime MessageProperty:I MegaCar:V CarPropertyManager:V"
            + " CarPropertyService:V MegaCarService:V AndroidRuntime:E SELinux:W auditd:W '*:S'");
  }

  static void addApkHashes(List<Command> commands, String name, String output, boolean truncated) {
    if (truncated || !(name.equals("path-com.mega.car")
        || name.equals("path-com.mega.app.engineeringmode")
        || name.equals("path-com.mega.engineeringmode"))) return;
    try {
      List<String> paths = AdbApkExtractor.paths(output);
      for (int index = 0; index < paths.size(); index++)
        commands.add(new Command("apk-sha256-" + name.substring(5) + "-" + index,
            "sha256sum " + paths.get(index)));
    } catch (java.io.IOException unavailable) {
      return;
    }
  }

  static Command nativeProbe(String apkPath) {
    try {
      AdbApkExtractor.paths("package:" + apkPath);
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("本应用APK路径不符合验证白名单", error);
    }
    return new Command("shell-native-read-probe",
        "CLASSPATH='" + apkPath + "' /system/bin/app_process /system/bin cn.es.soundlab.AdbVehicleProbe");
  }

  static JSONObject probeResult(String output, boolean truncated) {
    try {
      if (truncated) throw new IllegalArgumentException("验证输出被截断");
      for (String line : output.split("\\r?\\n")) {
        if (!line.startsWith(AdbVehicleProbe.MARKER)) continue;
        JSONObject result = new JSONObject(line.substring(AdbVehicleProbe.MARKER.length()));
        if (result.getInt("uid") != 2000
            || !"adb-shell-native-read-probe".equals(result.getString("source")))
          throw new IllegalArgumentException("验证进程身份不符合预期");
        AdbVehicleProbe.summarize(result);
        result.put("processVerified", true).put("subscriptionVerified", false);
        return result;
      }
      throw new IllegalArgumentException("验证进程没有返回结构化结果；检查原始输出中的启动/SDK/SELinux错误");
    } catch (Exception error) {
      try {
        return new JSONObject().put("outcome", "not-verified")
            .put("processVerified", false).put("readAttempted", false)
            .put("readVerified", false).put("changeObserved", false)
            .put("subscriptionVerified", false).put("error", error.toString());
      } catch (org.json.JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }


  private AdbDiagnosticCommands() {}
}
