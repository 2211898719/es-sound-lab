package cn.es.soundlab;

import java.util.Locale;

public final class VehicleStateSpec {
  public final String key, title, events;
  public final String[] values, labels;
  public static final VehicleStateSpec[] ALL = {
    new VehicleStateSpec("gear", "挡位", "P / R / N / D / M 挂挡提示",
        new String[] {"1", "2", "3", "4", "5"}, new String[] {"P 挡", "R 挡", "N 挡", "D 挡", "M 挡"}),
    new VehicleStateSpec("speed", "车速", "本车原值即km/h · 仅展示，不驱动自动音效", new String[0], new String[0]),
    binary("door.front_left", "主驾车门"), binary("door.front_right", "副驾车门"),
    binary("door.rear_left", "左后车门"), binary("door.rear_right", "右后车门"),
    binary("tailgate", "后尾门"),
    new VehicleStateSpec("lock", "中控锁", "P挡后解锁 / 闭锁提示 · 默认原厂车外通道",
        new String[] {"locked", "unlocked"}, new String[] {"已闭锁", "已解锁"}),
    new VehicleStateSpec("autohold", "AutoHold", "保持 / 释放提示",
        new String[] {"holding", "released"}, new String[] {"正在保持", "已释放保持"}),
    new VehicleStateSpec("charge", "充电状态", "ICC协议 · 核对后可启用开始 / 结束提示",
        new String[] {"inactive", "charging"}, new String[] {"未在充电", "正在充电"}),
    new VehicleStateSpec("handbrake", "手刹 / EPB", "ICC只读展示 · 不是AutoHold",
        new String[] {"released", "engaged"}, new String[] {"已释放", "已拉起"}),
    new VehicleStateSpec("power", "电源状态", "ICC只读展示 · 不推断车辆可行驶",
        new String[] {"off", "acc", "on", "start"}, new String[] {"OFF", "ACC", "ON", "START"})
  };

  private VehicleStateSpec(String key, String title, String events, String[] values, String[] labels) {
    this.key = key;
    this.title = title;
    this.events = events;
    this.values = values;
    this.labels = labels;
  }

  private static VehicleStateSpec binary(String key, String title) {
    return new VehicleStateSpec(key, title, "打开 / 关闭提示",
        new String[] {"0", "1"}, new String[] {"关闭", "打开"});
  }

  public static boolean interfaceKey(String key) {
    return "gear".equals(key) || "speed".equals(key) || "handbrake".equals(key)
        || "power".equals(key) || "charge".equals(key) || "lock".equals(key);
  }

  public static boolean logKey(String key) {
    return "tailgate".equals(key) || "autohold".equals(key)
        || "door.front_left".equals(key) || "door.front_right".equals(key)
        || "door.rear_left".equals(key) || "door.rear_right".equals(key);
  }

  public static VehicleStateSpec find(String key) {
    for (VehicleStateSpec spec : ALL) if (spec.key.equals(key)) return spec;
    return null;
  }

  public boolean customMapping() {
    return key.equals("gear") || key.equals("speed") || key.equals("tailgate") || key.startsWith("door.");
  }

  public String esPresetDescription() {
    if (key.equals("gear"))
      return "P=1/101，R=2/102，N=3/103/300，D=4/113；AVM仅1～4，M未确认";
    if (key.equals("speed")) return "沿用现有日志 km/h 单位，仍需实车核对";
    if (key.equals("tailgate") || key.startsWith("door."))
      return "0=关闭，1=打开；带color时保留原有已知判定";
    return "含义尚未确认，不提供预设";
  }

  public String[] esPresetRawValues() {
    if (key.equals("gear")) return new String[] {"1", "2", "3", "4", ""};
    if (key.equals("tailgate") || key.startsWith("door.")) return new String[] {"0", "1"};
    return new String[0];
  }

  public String label(String value) {
    if (value == null) return "未映射";
    if (key.equals("speed")) return String.format(Locale.CHINA, "%.1f km/h", Protocol.number(value));
    for (int index = 0; index < values.length; index++)
      if (values[index].equals(value)) return labels[index];
    return "不可用";
  }
}
