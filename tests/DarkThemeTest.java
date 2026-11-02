package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
    qualifiers = "w1280dp-h1080dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class DarkThemeTest {
  @Before public void reset() {
    new Store(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit();
  }

  private View find(View view, String text) {
    if (view instanceof TextView && text.equals(((TextView) view).getText().toString())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        View found = find(group.getChildAt(index), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private View root(Activity activity) {
    return ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
  }

  private void image(View view, String name, int width, int height) throws Exception {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File directory = new File("dist/screenshots");
    directory.mkdirs();
    try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
    } finally { bitmap.recycle(); }
  }

  private double luminance(int color) {
    double[] channels = {Color.red(color) / 255d, Color.green(color) / 255d, Color.blue(color) / 255d};
    for (int index = 0; index < channels.length; index++)
      channels[index] = channels[index] <= .04045 ? channels[index] / 12.92
          : Math.pow((channels[index] + .055) / 1.055, 2.4);
    return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
  }

  private void readable(int foreground, int background) {
    double first = luminance(foreground), second = luminance(background);
    assertTrue("Insufficient contrast: " + Integer.toHexString(foreground) + "/"
        + Integer.toHexString(background), (Math.max(first, second) + .05) / (Math.min(first, second) + .05) >= 4.5);
  }

  private ActivityController<MainActivity> main() {
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    controller.get().setTheme(android.R.style.Theme_Material_NoActionBar);
    controller.create().start().resume().visible();
    shadowOf(Looper.getMainLooper()).idle();
    return controller;
  }

  @Test public void darkPaletteKeepsTextAndStateColorsReadable() {
    UiTheme theme = new UiTheme(RuntimeEnvironment.getApplication());
    assertTrue(theme.dark);
    for (int background : new int[] {theme.background, theme.surface, theme.raised, theme.field}) {
      readable(theme.text, background);
      readable(theme.muted, background);
      readable(theme.accent, background);
    }
    readable(Color.WHITE, theme.primary);
    readable(theme.good, theme.goodFill);
    readable(theme.warning, theme.warningFill);
    readable(theme.muted, theme.neutralFill);
    assertNotEquals(theme.goodFill, theme.warningFill);
    assertNotEquals(theme.warningFill, theme.neutralFill);
  }

  @Test public void homeDashboardAndNativeDialogFollowNightMode() throws Exception {
    try (ActivityController<MainActivity> controller = main()) {
      MainActivity activity = controller.get();
      UiTheme theme = new UiTheme(activity);
      View root = root(activity);
      assertEquals(theme.background, ((ColorDrawable) root.getBackground()).getColor());
      assertEquals(theme.background, activity.getWindow().getStatusBarColor());
      assertEquals(theme.background, activity.getWindow().getNavigationBarColor());
      assertEquals(0, activity.getWindow().getDecorView().getSystemUiVisibility()
          & (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
      assertNull(find(root, "查看实车信号 →"));
      assertNull(find(root, "♬ 音频输出"));
      image(root, "home-landscape-dark", 1280, 1080);
      find(root, "车辆检测").performClick();
      image(root(activity), "detection-landscape-dark", 1280, 1400);
      ReflectionHelpers.callInstanceMethod(activity, "iccStateSettings",
          ReflectionHelpers.ClassParameter.from(String.class, "lock"));
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(dialog.isShowing());
      TextView message = dialog.findViewById(android.R.id.message);
      assertTrue(luminance(message.getCurrentTextColor()) > .5);
      dialog.dismiss();
      Store store = new Store(activity);
      assertTrue(store.prefs.getBoolean("monitor", false));
      assertFalse(store.logsEnabled());
    }
  }

  @Test public void dropdownRowsUseDarkSurfaceAndBrightText() {
    try (ActivityController<MainActivity> controller = main()) {
      MainActivity activity = controller.get();
      UiTheme theme = new UiTheme(activity);
      Spinner spinner = ReflectionHelpers.callInstanceMethod(activity, "spinner",
          ReflectionHelpers.ClassParameter.from(List.class, Arrays.asList("P挡", "D挡")));
      TextView selected = (TextView) spinner.getAdapter().getView(0, null, spinner);
      TextView dropdown = (TextView) spinner.getAdapter().getDropDownView(0, null, spinner);
      assertEquals(theme.text, selected.getCurrentTextColor());
      assertEquals(theme.text, dropdown.getCurrentTextColor());
      assertEquals(theme.surface, ((ColorDrawable) dropdown.getBackground()).getColor());
    }
  }

  @Test public void engineSimulationRemainsReadableInDarkMode() throws Exception {
    try (ActivityController<MainActivity> controller = main()) {
      View card = root(controller.get()).findViewWithTag("engine.simulation");
      assertNotNull(card);
      TextView waiting = (TextView) find(card, "尚未启用 · 默认关闭");
      assertNotNull(waiting);
      readable(waiting.getCurrentTextColor(), new UiTheme(controller.get()).surface);
      assertNull(find(card, "试听"));
      assertNull(find(card, "更多设置"));
      image(card, "engine-simulation-dark", 404, 338);
      find(card, "选择预设 / 曲线详情 →").performClick();
      View settings = root(controller.get()).findViewWithTag("engine.settings");
      assertNotNull(settings);
      image(settings, "engine-settings-dark", 1000, 1600);
      image(settings.findViewWithTag("engine.demo.panel"), "engine-demo-dark", 900, 1250);
    }
  }

  @Test public void packagedNightThemeUsesDarkNativeWidgetsAndMatchesThePalette() throws Exception {
    UiTheme theme = new UiTheme(RuntimeEnvironment.getApplication());
    org.w3c.dom.Document document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .newDocumentBuilder().parse(new File("app/src/main/res/values-night/styles.xml"));
    org.w3c.dom.Element style = (org.w3c.dom.Element) document.getElementsByTagName("style").item(0);
    assertEquals("AppTheme", style.getAttribute("name"));
    assertEquals("android:style/Theme.Material.NoActionBar", style.getAttribute("parent"));
    java.util.Map<String, String> items = new java.util.HashMap<>();
    org.w3c.dom.NodeList nodes = style.getElementsByTagName("item");
    for (int index = 0; index < nodes.getLength(); index++) {
      org.w3c.dom.Element item = (org.w3c.dom.Element) nodes.item(index);
      items.put(item.getAttribute("name"), item.getTextContent());
    }
    assertEquals("false", items.get("android:windowLightStatusBar"));
    assertEquals(String.format("#%06X", theme.background & 0xffffff), items.get("android:windowBackground"));
    assertEquals(String.format("#%06X", theme.text & 0xffffff), items.get("android:textColorPrimary"));
    assertEquals(String.format("#%06X", theme.accent & 0xffffff), items.get("android:colorAccent"));
  }

  @Test public void stateTilesKeepFreshUnknownAndStaleSemanticsInDarkMode() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    UiTheme theme = new UiTheme(context);
    StateDashboardView dashboard = new StateDashboardView(context, key -> {});
    Diagnostics diagnostics = new Diagnostics();
    Store store = new Store(context);
    long now = System.currentTimeMillis();
    for (Protocol.Sample sample : IccVehicleWire.decode(IccVehicleStateTest.body(9, 1, 2, 88, 6, 0,
        7, 3, 8, 1, 13, 2)).stateSamples(now)) {
      diagnostics.stateReading(sample);
      diagnostics.live(sample.key, sample.value == null ? "不可用" : sample.value, "ICC");
    }
    // stateReading records its own receive time; render after all those receives.
    now = System.currentTimeMillis();
    dashboard.update(diagnostics, store, true, now);
    TextView gear = dashboard.findViewWithTag("state.value.gear");
    TextView speed = dashboard.findViewWithTag("state.value.speed");
    TextView handbrake = dashboard.findViewWithTag("state.value.handbrake");
    assertEquals(theme.good, gear.getCurrentTextColor());
    assertEquals(theme.good, speed.getCurrentTextColor());
    assertEquals(theme.warning, handbrake.getCurrentTextColor());
    TextView speedCandidates = dashboard.findViewWithTag("state.speed.candidates");
    assertTrue(speedCandidates.getText().toString().contains("88 km/h"));
    assertEquals(theme.secondaryText, speedCandidates.getCurrentTextColor());
    image((View) speed.getParent(), "icc-speed-confirmed-dark", 302, 350);
    assertEquals(theme.goodFill, ((GradientDrawable) ((View) gear.getParent()).getBackground()).getColor().getDefaultColor());
    assertEquals(theme.goodFill, ((GradientDrawable) ((View) speed.getParent()).getBackground()).getColor().getDefaultColor());
    image(dashboard, "state-dashboard-dark", 1280, 1100);
    dashboard.update(diagnostics, store, true, now + 6000);
    assertFalse(speedCandidates.getText().toString().contains("A ×1"));
    assertEquals(theme.muted, gear.getCurrentTextColor());
    assertEquals(theme.neutralFill, ((GradientDrawable) ((View) gear.getParent()).getBackground()).getColor().getDefaultColor());
  }

  @Test public void adbInputsAndPackageDialogFollowNightModeWithoutConnecting() throws Exception {
    try (ActivityController<AdbDiagnosticsActivity> controller = Robolectric.buildActivity(AdbDiagnosticsActivity.class)) {
      controller.get().setTheme(android.R.style.Theme_Material_NoActionBar);
      controller.create().start().resume().visible();
      AdbDiagnosticsActivity activity = controller.get();
      UiTheme theme = new UiTheme(activity);
      for (String field : new String[] {"host", "port", "packageNames"}) {
        EditText input = ReflectionHelpers.getField(activity, field);
        assertEquals(theme.text, input.getCurrentTextColor());
        assertEquals(theme.secondaryText, input.getCurrentHintTextColor());
      }
      image(root(activity), "adb-package-extraction-dark", 1280, 1800);
      ReflectionHelpers.setField(activity, "installedPackages", Arrays.asList("com.example.vehicle"));
      ReflectionHelpers.callInstanceMethod(activity, "choosePackages");
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(dialog.isShowing());
      dialog.dismiss();
      assertNull(ReflectionHelpers.getField(activity, "active"));
    }
  }

  @Test public void switchingBackToLightRebuildsPaletteWithoutResettingSettings() {
    try (ActivityController<MainActivity> controller = main()) {
      Store store = new Store(controller.get());
      store.enable("lock", false);
      store.volume("lock", 37);
      store.select("gear", "gear.d");
      RuntimeEnvironment.setQualifiers("w1280dp-h1080dp-land-notnight-mdpi");
      controller.recreate();
      UiTheme theme = new UiTheme(controller.get());
      assertFalse(theme.dark);
      assertEquals(0xfff3f7fc, ((ColorDrawable) root(controller.get()).getBackground()).getColor());
      assertEquals(theme.text, ((TextView) find(root(controller.get()), "星纪元 ES")).getCurrentTextColor());
      Store restored = new Store(controller.get());
      assertFalse(restored.enabled("lock"));
      assertEquals(37, restored.volume("lock"));
      assertEquals("gear.d", restored.selected("gear"));
    }
  }

  @Test @Config(qualifiers = "w640dp-h900dp-port-notnight-mdpi")
  public void lightPaletteKeepsExistingColorsAndDarkSystemIcons() {
    Context context = RuntimeEnvironment.getApplication();
    UiTheme theme = new UiTheme(context);
    assertFalse(theme.dark);
    assertEquals(0xfff3f7fc, theme.background);
    assertEquals(0xff203553, theme.text);
    try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
      controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
      controller.create().start().resume();
      assertTrue((controller.get().getWindow().getDecorView().getSystemUiVisibility()
          & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0);
    }
  }
}
