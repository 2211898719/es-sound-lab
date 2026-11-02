package cn.es.soundlab;

public final class AssetNames {
  private AssetNames() {}

  public static String display(String filename) {
    switch (filename) {
      case "engine.wav":
        return "赛车循环 · CC0 / domasx2.wav";
      case "option_princess.mp3":
        return "公主请上车.mp3";
      case "option_young_master.mp3":
        return "少爷请上车.mp3";
      case "option_left_belt.mp3":
        return "左后门已关闭，请系好安全带.mp3";
      case "option_right_belt.mp3":
        return "右后门已关闭，请系好安全带.mp3";
      case "option_p_goodluck.mp3":
        return "P档一按好运不断.mp3";
      case "option_d_goodfortune.mp3":
        return "D档一响黄金万两.mp3";
      case "gear_s.mp3":
        return "S 挡提示.mp3";
      default:
        for (EventSpec e : EventSpec.ALL)
          if (e.asset.equals(filename))
            return e.title + filename.substring(filename.lastIndexOf('.'));
        return filename;
    }
  }
}
