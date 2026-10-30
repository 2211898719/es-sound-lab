package cn.es.soundlab;

import java.util.ArrayList;
import java.util.List;

/** Fixed read-only diagnostic commands, shared by the built-in ADB collector. */
public final class AdbDiagnosticCommands {
  public static final String[] PACKAGES = {
    "cn.es.soundlab",
    "com.chery.aiot",
    "com.mega.car",
    "com.iflytek.autofly.carcontroladapter",
    "com.mega.app.engineeringmode",
    "com.android.car",
    "com.mega.carsettings",
    "com.mega.systemui"
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
    commands.add(new Command("android-version", "getprop ro.build.version.release"));
    commands.add(new Command("sdk-version", "getprop ro.build.version.sdk"));
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
    if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) return;
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

  private AdbDiagnosticCommands() {}
}
