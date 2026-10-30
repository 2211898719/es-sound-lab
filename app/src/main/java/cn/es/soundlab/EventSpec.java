package cn.es.soundlab;

import java.util.ArrayList;
import java.util.List;

public final class EventSpec {
  public final String id, group, title, asset;
  public final boolean mapped;
  public final int priority;

  public EventSpec(
      String id, String group, String title, String asset, boolean mapped, int priority) {
    this.id = id;
    this.group = group;
    this.title = title;
    this.asset = asset;
    this.mapped = mapped;
    this.priority = priority;
  }

  public static final List<EventSpec> ALL = new ArrayList<>();

  static {
    String[] gears = {"p", "r", "n", "d", "m"};
    for (int i = 0; i < gears.length; i++)
      add(
          "gear." + gears[i],
          "gear",
          gears[i].toUpperCase() + " 挡",
          "gear_" + gears[i] + ".mp3",
          true,
          3);
    String[] doors = {"front_left", "front_right", "rear_left", "rear_right"},
        names = {"主驾", "副驾", "左后门", "右后门"};
    for (int i = 0; i < doors.length; i++) {
      add(
          "door." + doors[i] + ".open",
          "door",
          names[i] + " · 开门",
          "door_" + doors[i] + "_open.mp3",
          true,
          1);
      add(
          "door." + doors[i] + ".close",
          "door",
          names[i] + " · 关门",
          "door_" + doors[i] + "_closed.mp3",
          true,
          1);
    }
    add("lock.unlock", "lock", "车辆解锁", "unlock.wav", false, 4);
    add("lock.lock", "lock", "车辆闭锁", "lock.wav", false, 4);
    add("autohold.holding", "autohold", "AutoHold · 保持", "autohold_holding.wav", false, 2);
    add("autohold.released", "autohold", "AutoHold · 释放", "autohold_released.wav", false, 2);
    add("engine.loop", "engine", "行驶声浪", "engine.wav", true, 0);
    add("reverse.loop", "reverse", "倒车提醒", "reverse_alert.mp3", true, 0);
    add("shout.clip", "shout", "车外语音 · 手动播放", "welcome.mp3", false, 2);
    add("charge.start", "charge", "开始充电", "charge_start.wav", false, 2);
    add("charge.stop", "charge", "结束充电", "charge_stop.wav", false, 2);
    add("tailgate.open", "tailgate", "尾门开启", "tailgate_open.mp3", true, 1);
    add("tailgate.close", "tailgate", "尾门关闭", "tailgate_close.mp3", true, 1);
    add("pedestrian.loop", "pedestrian", "低速行人提醒", "pedestrian.wav", true, 0);
  }

  private static void add(
      String id, String group, String title, String asset, boolean mapped, int priority) {
    ALL.add(new EventSpec(id, group, title, asset, mapped, priority));
  }

  public static EventSpec find(String id) {
    for (EventSpec s : ALL) if (s.id.equals(id)) return s;
    return null;
  }

  public static List<EventSpec> group(String group) {
    List<EventSpec> r = new ArrayList<>();
    for (EventSpec s : ALL) if (s.group.equals(group)) r.add(s);
    return r;
  }

  public static String groupName(String id) {
    switch (id) {
      case "gear":
        return "挂挡音效";
      case "door":
        return "开关门提示音";
      case "lock":
        return "解闭锁提示音";
      case "autohold":
        return "AutoHold 音效";
      case "engine":
        return "模拟声浪";
      case "reverse":
        return "倒车提示音";
      case "shout":
        return "车外喊话";
      case "charge":
        return "充电提示音";
      case "tailgate":
        return "尾门提示音";
      case "pedestrian":
        return "低速行人提醒";
      default:
        return "自定义事件";
    }
  }
}
