package cn.es.soundlab;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import java.util.Locale;

/** Only the Mega split calls documented in docs/split-screen-protocol.md. */
public final class SplitController {
  public static final String NAVI_PKG = "com.lion.appfwk.navi";
  public static final ComponentName NAVI =
      new ComponentName(NAVI_PKG, "com.lion.appfwk.navi.activity.StartupActivity");
  public static final String MEDIA_PKG = "com.lion.media";
  public static final ComponentName MEDIA =
      new ComponentName(MEDIA_PKG, "com.lion.business.home.ui.HomeActivity");
  public static final String METHOD = "mega.intent.extra.split.METHOD";
  public static final String HANDLE_LEFT = "mega.intent.extra.split.IS_HANDLE_LEFT";
  public static final String COMPONENT = "mega.intent.extra.split.COMPONENT";
  private final Context context;

  public SplitController(Context owner) {
    context = owner;
  }

  public ComponentName self() {
    return new ComponentName(context.getPackageName(), SplitProbeActivity.class.getName());
  }

  public void enterWithNavi(boolean launchNavi, boolean handleOnLeft) {
    enter(launchNavi ? NAVI : self(), launchNavi ? self() : NAVI, handleOnLeft);
  }

  public void enterWithMedia() {
    enter(MEDIA, self(), true);
  }

  private void enter(ComponentName launched, ComponentName other, boolean handleOnLeft) {
    Intent intent = new Intent();
    intent.setComponent(launched);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.putExtra(METHOD, "enter_split");
    intent.putExtra(HANDLE_LEFT, handleOnLeft);
    intent.putExtra(COMPONENT, other);
    context.startActivity(intent);
  }

  public Bundle exitByProvider(String pkg) {
    if (pkg == null || pkg.trim().isEmpty()) throw new IllegalArgumentException("退出包名为空");
    return context
        .getContentResolver()
        .call(Uri.parse("content://com.mega.systemui.chery.Split"), "exit_spit", pkg, null);
  }

  public void exitByIntent(ComponentName target, boolean invalidCategory) {
    Intent intent = new Intent();
    intent.setComponent(target);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.putExtra(METHOD, "exit_split");
    if (invalidCategory) intent.addCategory("split_invalid_intent");
    context.startActivity(intent);
  }

  /** Exceptions intentionally propagate to the probe UI, including settings access failures. */
  public String readSplitPackages() {
    return Settings.Global.getString(context.getContentResolver(), "system_split_pkg");
  }

  public static String otherPackage(String raw, String self) {
    String[] pair = raw == null ? new String[0] : raw.split(":", -1);
    if (pair.length != 2 || pair[0].isEmpty() || pair[1].isEmpty() || pair[0].equals(pair[1]))
      return null;
    if (pair[0].equals(self)) return pair[1];
    return pair[1].equals(self) ? pair[0] : null;
  }

  public static boolean hasPair(String raw, String self, String partner) {
    return partner != null && partner.equals(otherPackage(raw, self));
  }

  public static String pixelMode(int width) {
    if (Math.abs(width - 870) <= 100) return "1/3";
    if (Math.abs(width - 1682) <= 100) return "2/3";
    if (Math.abs(width - 2560) <= 100) return "全屏";
    return "未知";
  }

  public static String dpMode(float width) {
    if (width >= 400 && width <= 600) return "1/3";
    if (width >= 1000 && width <= 1200) return "2/3";
    return "全屏";
  }

  public static Measurement measure(Activity activity) {
    DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
    // Use this Activity's window, not the physical display, after split/configuration changes.
    int laidOutWidth = activity.getWindow().getDecorView().getWidth();
    return new Measurement(laidOutWidth > 0 ? laidOutWidth : metrics.widthPixels, metrics.density);
  }

  public static final class Measurement {
    public final int widthPixels;
    public final float density, widthDp;
    public final String pixels, dp;

    public Measurement(int width, float scale) {
      widthPixels = width;
      density = scale;
      widthDp = scale > 0 ? width / scale : 0;
      pixels = pixelMode(width);
      dp = dpMode(widthDp);
    }

    public boolean isSplitWidth() {
      return pixels.equals("1/3") || pixels.equals("2/3");
    }

    @Override
    public String toString() {
      return String.format(
          Locale.CHINA,
          "widthPixels：%d px\ndensity：%.3f\n宽度：%.1f dp\n像素档：%s（容差 100px）\ndp 档：%s",
          widthPixels,
          density,
          widthDp,
          pixels,
          dp);
    }
  }
}
