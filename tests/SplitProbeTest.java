package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    qualifiers = "w580dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SplitProbeTest {
  private Context context;

  @Before
  public void setup() {
    context = RuntimeEnvironment.getApplication();
    context.getSharedPreferences("session_journal", 0).edit().clear().commit();
    Settings.Global.putString(context.getContentResolver(), "system_split_pkg", "");
  }

  @Test
  public void allFourMapRequestsUseOnlyDocumentedComponentsFlagsAndTypedExtras() {
    SplitController controller = new SplitController(context);
    for (boolean launchMap : new boolean[] {true, false})
      for (boolean left : new boolean[] {true, false}) {
        controller.enterWithNavi(launchMap, left);
        Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals(launchMap ? SplitController.NAVI : controller.self(), intent.getComponent());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.getFlags());
        assertEquals("enter_split", intent.getStringExtra(SplitController.METHOD));
        assertEquals(left, intent.getBooleanExtra(SplitController.HANDLE_LEFT, !left));
        assertEquals(
            launchMap ? controller.self() : SplitController.NAVI,
            intent.getParcelableExtra(SplitController.COMPONENT));
        assertTrue(intent.getExtras().get(SplitController.COMPONENT) instanceof ComponentName);
        assertEquals(3, intent.getExtras().size());
        assertNull(intent.getCategories());
      }
    controller.enterWithMedia();
    Intent media = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    assertEquals(SplitController.MEDIA, media.getComponent());
    assertEquals(controller.self(), media.getParcelableExtra(SplitController.COMPONENT));
    assertTrue(media.getBooleanExtra(SplitController.HANDLE_LEFT, false));
    assertNull(media.getCategories());
  }

  @Test
  public void intentExitDoesNotInheritEnterExtrasAndCategoryIsOptional() {
    SplitController controller = new SplitController(context);
    for (ComponentName target : new ComponentName[] {SplitController.NAVI, controller.self()})
      for (boolean category : new boolean[] {false, true}) {
        controller.exitByIntent(target, category);
        Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals(target, intent.getComponent());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.getFlags());
        assertEquals("exit_split", intent.getStringExtra(SplitController.METHOD));
        assertEquals(1, intent.getExtras().size());
        assertEquals(category, intent.hasCategory("split_invalid_intent"));
        if (!category) assertNull(intent.getCategories());
      }
  }

  @Test
  public void providerUsesExactSpellingAndPackageAndDoesNotHidePermissionErrors() {
    ProbeProvider provider = provider();
    SplitController controller = new SplitController(context);
    controller.exitByProvider(context.getPackageName());
    assertEquals("exit_spit", provider.method);
    assertEquals(context.getPackageName(), provider.argument);
    assertNull(provider.extras);
    provider.reject = true;
    try {
      controller.exitByProvider(SplitController.NAVI_PKG);
      fail("permission failure was hidden");
    } catch (SecurityException expected) {
      assertEquals("provider permission denied", expected.getMessage());
    }
  }

  @Test
  public void pairingIsExactAndWindowClassificationDoesNotUseMultiWindowAsSuccess() {
    String self = "cn.es.soundlab", map = SplitController.NAVI_PKG;
    assertEquals(map, SplitController.otherPackage(self + ":" + map, self));
    assertEquals(map, SplitController.otherPackage(map + ":" + self, self));
    for (String raw :
        new String[] {
          "",
          ":",
          self,
          self + ":",
          self + ":" + self,
          "prefix." + self + ":" + map,
          self + ":" + map + ":third"
        }) assertFalse(raw, SplitController.hasPair(raw, self, map));
    assertFalse(SplitController.hasPair(null, self, map));
    assertEquals("1/3", SplitController.pixelMode(770));
    assertEquals("1/3", SplitController.pixelMode(970));
    assertEquals("未知", SplitController.pixelMode(971));
    assertEquals("2/3", SplitController.pixelMode(1730));
    assertEquals("全屏", SplitController.pixelMode(2610));
    assertEquals("1/3", SplitController.dpMode(580));
    assertEquals("2/3", SplitController.dpMode(1121));
    assertEquals("全屏", SplitController.dpMode(700));
    SplitController.Measurement measurement = new SplitController.Measurement(870, 1.5f);
    assertEquals(580, measurement.widthDp, 0.01f);
    assertTrue(measurement.isSplitWidth());
    assertFalse(new SplitController.Measurement(2560, 1.5f).isSplitWidth());
  }

  @Test
  public void activityOnlyLaunchesFromClicksAndPreservesProviderFailureBeforeIntentFallback() {
    ProbeProvider provider = provider();
    provider.reject = true;
    try (ActivityController<SplitProbeActivity> lifecycle =
        Robolectric.buildActivity(SplitProbeActivity.class).setup()) {
      SplitProbeActivity activity = lifecycle.get();
      View root = activity.findViewById(android.R.id.content);
      assertNull(shadowOf(activity).getNextStartedActivity());
      click(root, "Provider · 退出自己");
      assertTrue(allText(root).contains("java.lang.SecurityException"));
      assertTrue(allText(root).contains("provider permission denied"));
      click(root, "Intent · 启动地图退出");
      Intent fallback = shadowOf(activity).getNextStartedActivity();
      assertEquals("exit_split", fallback.getStringExtra(SplitController.METHOD));
      assertEquals(SplitController.NAVI, fallback.getComponent());
      Settings.Global.putString(
          context.getContentResolver(), "system_split_pkg", "not.self:" + SplitController.NAVI_PKG);
      click(root, "Provider · 退出当前配对另一侧");
      assertTrue(allText(root).contains("没有包含本应用"));
      JSONArray journal = new SessionJournal(context, "测试读取").export();
      assertTrue(journal.toString().contains("分屏探测"));
      assertTrue(journal.toString().contains("SecurityException"));
      assertFalse(journal.toString().contains("监听服务创建"));
    }
  }

  @Test
  public void missingActivityIsVisibleAndPlainLaunchIsNotReportedAsSplitSuccess() throws Exception {
    try (ActivityController<SplitProbeActivity> lifecycle =
        Robolectric.buildActivity(SplitProbeActivity.class).setup()) {
      SplitProbeActivity activity = lifecycle.get();
      Context rejecting =
          new ContextWrapper(activity) {
            @Override
            public void startActivity(Intent intent) {
              throw new ActivityNotFoundException("navigation entry unavailable");
            }
          };
      ReflectionHelpers.setField(activity, "controller", new SplitController(rejecting));
      View root = activity.findViewById(android.R.id.content);
      click(root, "启动地图 · 自己在 extra · 把手左");
      assertTrue(allText(root).contains("ActivityNotFoundException"));
      assertTrue(allText(root).contains("navigation entry unavailable"));
      ReflectionHelpers.setField(activity, "controller", new SplitController(activity));
      click(root, "启动地图 · 自己在 extra · 把手右");
      assertNotNull(shadowOf(activity).getNextStartedActivity());
      assertTrue(allText(root).contains("尚未确认进入"));
      assertEquals("", Settings.Global.getString(context.getContentResolver(), "system_split_pkg"));
      activity.onConfigurationChanged(
          new Configuration(activity.getResources().getConfiguration()));
      activity.onNewIntent(new Intent().putExtra(SplitController.METHOD, "enter_split"));
      shadowOf(Looper.getMainLooper()).idleFor(650, TimeUnit.MILLISECONDS);
      assertNull(shadowOf(activity).getNextStartedActivity());
      screenshot(activity, 580, 720, "split-probe-narrow");
      screenshot(activity, 1121, 800, "split-probe-wide");
    }
  }

  @Test
  public void pairingAndMeasuredActivityWidthMustBothMatchAndMainEntryOpensProbe() {
    try (ActivityController<SplitProbeActivity> lifecycle =
        Robolectric.buildActivity(SplitProbeActivity.class).setup()) {
      SplitProbeActivity activity = lifecycle.get();
      View decor = activity.getWindow().getDecorView();
      decor.layout(0, 0, 870, 720);
      View root = activity.findViewById(android.R.id.content);
      Settings.Global.putString(
          context.getContentResolver(),
          "system_split_pkg",
          context.getPackageName() + ":" + SplitController.NAVI_PKG);
      click(root, "刷新状态");
      assertTrue(allText(root).contains("已观测到配对与分屏宽度匹配"));
      decor.layout(0, 0, 2560, 720);
      click(root, "刷新状态");
      assertTrue(allText(root).contains("尚未确认进入"));
    }
    try (ActivityController<MainActivity> lifecycle =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity main = lifecycle.get();
      assertNull(find(main.findViewById(android.R.id.content), "分屏探测"));
      assertNull(shadowOf(main).getNextStartedActivity());
    }
  }

  private static View find(View view, String label) {
    if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View match = find(((ViewGroup) view).getChildAt(i), label);
        if (match != null) return match;
      }
    return null;
  }

  private static void click(View root, String label) {
    View button = find(root, label);
    assertNotNull(label, button);
    button.performClick();
  }

  private static String allText(View view) {
    StringBuilder text = new StringBuilder();
    if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        text.append(allText(((ViewGroup) view).getChildAt(i)));
    return text.toString();
  }

  private static void screenshot(Activity activity, int width, int height, String name)
      throws Exception {
    activity.getWindow().getDecorView().layout(0, 0, width, height);
    View view = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    click(activity.findViewById(android.R.id.content), "刷新状态");
    assertEquals(width, SplitController.measure(activity).widthPixels);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File file = new File("dist/screenshots", name + ".png");
    try (FileOutputStream out = new FileOutputStream(file)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  private ProbeProvider provider() {
    ProbeProvider provider = new ProbeProvider();
    ProviderInfo info = new ProviderInfo();
    info.authority = "com.mega.systemui.chery.Split";
    provider.attachInfo(context, info);
    ShadowContentResolver.registerProviderInternal(info.authority, provider);
    return provider;
  }

  public static final class ProbeProvider extends ContentProvider {
    String method, argument;
    Bundle extras;
    boolean reject;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      if (reject) throw new SecurityException("provider permission denied");
      this.method = method;
      argument = arg;
      this.extras = extras;
      return null;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] args, String sort) {
      return null;
    }

    @Override
    public String getType(Uri uri) {
      return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
      return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      return 0;
    }
  }
}
