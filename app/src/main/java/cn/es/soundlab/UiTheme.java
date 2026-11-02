package cn.es.soundlab;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;

final class UiTheme {
  final boolean dark;
  final int background, surface, raised, field, border, text, muted, secondaryText;
  final int accent, primary, inactive, good, goodFill, warning, warningFill, neutralFill, thumb;

  UiTheme(Context context) {
    dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
    background = color(0xfff3f7fc, 0xff101722);
    surface = color(0xffffffff, 0xff182231);
    raised = color(0xfffafdff, 0xff202d40);
    field = color(0xffffffff, 0xff121d2b);
    border = color(0xffe0e9f5, 0xff33465e);
    text = color(0xff203553, 0xffe3ecf7);
    muted = color(0xff72839a, 0xffa1b3ca);
    secondaryText = color(0xff60718c, 0xffb2c2d7);
    accent = color(0xff2475f5, 0xff91baff);
    primary = color(0xff2475f5, 0xff2365cc);
    inactive = color(0xffb4c3d8, 0xff71839b);
    good = color(0xff176d67, 0xff7ddac5);
    goodFill = color(0xffe9f7f4, 0xff173b36);
    warning = color(0xffa96b12, 0xfff3c06e);
    warningFill = color(0xfffff6e7, 0xff3a2e1d);
    neutralFill = color(0xfff4f7fb, 0xff1e2939);
    thumb = color(0xffffffff, 0xffd7e8ff);
  }

  int color(int light, int night) { return dark ? night : light; }

  void systemBars(Activity activity) {
    activity.getWindow().setStatusBarColor(background);
    activity.getWindow().setNavigationBarColor(background);
    View decor = activity.getWindow().getDecorView();
    int flags = decor.getSystemUiVisibility()
        & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    decor.setSystemUiVisibility(flags);
  }
}
