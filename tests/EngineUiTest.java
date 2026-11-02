package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
    qualifiers = "w1280dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class EngineUiTest {
  @Implements(android.media.MediaMetadataRetriever.class)
  public static class MetadataShadow extends org.robolectric.shadows.ShadowMediaMetadataRetriever {
    @Implementation protected void setDataSource(String path) {
      super.setDataSource(path);
      try {
        byte[] header = Files.readAllBytes(new File(path).toPath());
        if (header.length > 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F')
          addMetadata(path, android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "2000");
      } catch (Exception ignored) {}
    }
  }
  @Before public void clear() {
    new Store(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit();
  }

  private View find(View view, String text) {
    if (view instanceof TextView && text.equals(((TextView) view).getText().toString())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup parent = (ViewGroup) view;
      for (int index = 0; index < parent.getChildCount(); index++) {
        View result = find(parent.getChildAt(index), text);
        if (result != null) return result;
      }
    }
    return null;
  }

  private ActivityController<MainActivity> activity() {
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);
    controller.create().start().resume().visible();
    View root = controller.get().findViewById(android.R.id.content);
    find(root, "选择预设 / 曲线详情 →").performClick();
    return controller;
  }

  @Test public void presetApplicationAndCurveEditsPersistWithoutReplacingTheAudio() {
    try (ActivityController<MainActivity> controller = activity()) {
      MainActivity activity = controller.get();
      Store store = new Store(activity);
      View root = activity.findViewById(android.R.id.content);
      String source = store.source(EventSpec.find("engine.loop"));
      Spinner preset = (Spinner) findSpinner(root);
      preset.setSelection(1);
      find(root, "应用预设（覆盖当前曲线）").performClick();
      assertEquals("运动响应", store.engineProfile().name);
      SeekBar response = (SeekBar) root.findViewWithTag("engine.response");
      assertEquals(250, response.getProgress());
      SeekBar pitch = (SeekBar) root.findViewWithTag("engine.point.2");
      pitch.setProgress(87);
      shadowOf(pitch).getOnSeekBarChangeListener().onStopTrackingTouch(pitch);
      assertEquals(1.37f, store.engineProfile().pitch[2], .001f);
      find(root, "加速→增强").performClick();
      SeekBar gain = (SeekBar) root.findViewWithTag("engine.point.3");
      gain.setProgress(81);
      shadowOf(gain).getOnSeekBarChangeListener().onStopTrackingTouch(gain);
      assertEquals(.81f, store.engineProfile().acceleration[3], .001f);
      assertEquals(source, store.source(EventSpec.find("engine.loop")));
      assertFalse(store.enabled("engine"));
      controller.recreate();
      assertNotNull(controller.get().findViewById(android.R.id.content).findViewWithTag("engine.settings"));
      assertEquals(.81f, new Store(controller.get()).engineProfile().acceleration[3], .001f);
    }
  }

  @Test public void synthesisControlsPersistWithoutReplacingUserAudioOrEnablingAutomaticSound() throws Exception {
    try (ActivityController<MainActivity> controller = activity()) {
      Store store = new Store(controller.get());
      String source = store.source(EventSpec.find("engine.loop"));
      String curve = store.engineProfile().json().toString();
      View root = controller.get().findViewById(android.R.id.content);
      ((Switch) root.findViewWithTag("engine.synth.mode")).setChecked(true);
      ((Spinner) root.findViewWithTag("engine.synth.styles")).setSelection(1);
      shadowOf(Looper.getMainLooper()).idle();
      SeekBar tone = (SeekBar) root.findViewWithTag("engine.synth.tone");
      tone.setProgress(37);
      shadowOf(tone).getOnSeekBarChangeListener().onStopTrackingTouch(tone);
      assertTrue(store.engineSynth());
      assertEquals(1, store.engineTuning().style);
      assertEquals(112, store.engineTuning().tone);
      assertEquals(source, store.source(EventSpec.find("engine.loop")));
      assertEquals(curve, store.engineProfile().json().toString());
      assertFalse(store.enabled("engine"));
      capture(root.findViewWithTag("engine.settings"), "engine-synth-light", 1000, 1600);
      capture(root.findViewWithTag("engine.settings"), "engine-synth-narrow", 440, 1400);
      controller.recreate();
      root = controller.get().findViewById(android.R.id.content);
      assertTrue(((Switch) root.findViewWithTag("engine.synth.mode")).isChecked());
      assertEquals(1, ((Spinner) root.findViewWithTag("engine.synth.styles")).getSelectedItemPosition());
      ((Switch) root.findViewWithTag("engine.synth.mode")).setChecked(false);
      assertEquals(source, store.engineSource());
      assertFalse(store.enabled("engine"));
    }
  }

  @Test @Config(qualifiers = "w1280dp-h1080dp-land-night-mdpi")
  public void synthesizedSettingsRenderInDarkMode() throws Exception {
    new Store(RuntimeEnvironment.getApplication()).prefs.edit().putBoolean("engine.synth.enabled", true).commit();
    try (ActivityController<MainActivity> controller = activity()) {
      View root = controller.get().findViewById(android.R.id.content);
      assertTrue(((Switch) root.findViewWithTag("engine.synth.mode")).isChecked());
      capture(root.findViewWithTag("engine.settings"), "engine-synth-dark", 1000, 1600);
      capture(root.findViewWithTag("engine.settings"), "engine-synth-dark-narrow", 440, 1400);
      assertFalse(new Store(controller.get()).enabled("engine"));
    }
  }

  private View findSpinner(View view) {
    if (view instanceof Spinner && "engine.response.presets".equals(view.getTag())) return view;
    if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
      View result = findSpinner(((ViewGroup) view).getChildAt(index));
      if (result != null) return result;
    }
    return null;
  }

  @Test @Config(shadows = {MetadataShadow.class})
  public void audioReplacementUsesPrivateValidatedCopyAndDoesNotEnableSimulation() throws Exception {
    try (ActivityController<MainActivity> controller = activity()) {
      MainActivity activity = controller.get();
      View root = activity.findViewById(android.R.id.content);
      find(root, "从文件 / U盘替换").performClick();
      org.robolectric.shadows.ShadowActivity.IntentForResult picker = shadowOf(activity).getNextStartedActivityForResult();
      assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.getAction());
      File source = new File(activity.getCacheDir(), "engine-import.wav");
      Files.copy(new File("app/src/main/assets/audio/engine.wav").toPath(), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      ReflectionHelpers.setField(activity, "pendingImport", "engine.loop");
      ReflectionHelpers.callInstanceMethod(activity, "onActivityResult",
          ReflectionHelpers.ClassParameter.from(int.class, 301),
          ReflectionHelpers.ClassParameter.from(int.class, android.app.Activity.RESULT_OK),
          ReflectionHelpers.ClassParameter.from(Intent.class, new Intent().setData(Uri.fromFile(source))));
      ExecutorService executor = ReflectionHelpers.getField(activity, "files");
      executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
      shadowOf(Looper.getMainLooper()).idle();
      Store store = new Store(activity);
      String copied = store.source(EventSpec.find("engine.loop"));
      assertTrue(copied, copied.startsWith("file:"));
      assertNotEquals("file:" + source.getAbsolutePath(), copied);
      assertFalse(store.enabled("engine"));
    }
  }

  @Test public void settingsAndCurveRenderOnLargeAndNarrowScreens() throws Exception {
    try (ActivityController<MainActivity> controller = activity()) {
      View view = controller.get().findViewById(android.R.id.content).findViewWithTag("engine.settings");
      capture(view, "engine-settings-light", 1000, 1600);
      capture(view.findViewWithTag("engine.curve-graph"), "engine-curve-light", 900, 220);
      capture(view, "engine-settings-narrow", 440, 1300);
      capture(view.findViewWithTag("engine.demo.gauge"), "engine-demo-gauge", 900, 220);
      capture(view.findViewWithTag("engine.demo.panel"), "engine-demo-light", 900, 1250);
      capture(view.findViewWithTag("engine.demo.panel"), "engine-demo-narrow", 440, 1400);
      assertNotNull(find(view, "车速→音高"));
      assertNotNull(find(view, "加速→增强"));
    }
  }

  @Test public void virtualCarPresetsAndCustomParametersPersistWithoutChangingCurvesOrEnablement() {
    try (ActivityController<MainActivity> controller = activity()) {
      MainActivity activity = controller.get();
      Store store = new Store(activity);
      View root = activity.findViewById(android.R.id.content);
      String profile = store.prefs.getString("engine.profile", "");
      Spinner cars = (Spinner) root.findViewWithTag("engine.demo.presets");
      assertEquals(1, cars.getSelectedItemPosition());
      cars.setSelection(2);
      find(root, "应用视听车型（不改声浪曲线）").performClick();
      assertEquals(400, store.prefs.getInt("engine.demo.horsepower", 0));
      assertEquals(45, store.prefs.getInt("engine.demo.zeroHundredTenths", 0));
      SeekBar horsepower = (SeekBar) root.findViewWithTag("engine.demo.horsepower");
      horsepower.setProgress(280 - 80);
      shadowOf(horsepower).getOnSeekBarChangeListener().onStopTrackingTouch(horsepower);
      SeekBar zeroHundred = (SeekBar) root.findViewWithTag("engine.demo.zero-hundred");
      zeroHundred.setProgress(70 - 30);
      shadowOf(zeroHundred).getOnSeekBarChangeListener().onStopTrackingTouch(zeroHundred);
      controller.recreate();
      assertEquals(280, store.prefs.getInt("engine.demo.horsepower", 0));
      assertEquals(70, store.prefs.getInt("engine.demo.zeroHundredTenths", 0));
      assertEquals(profile, store.prefs.getString("engine.profile", ""));
      assertFalse(store.enabled("engine"));
    }
  }

  @Test public void virtualDemoStopsWhenTheActivityPauses() {
    org.robolectric.android.controller.ServiceController<MonitorService> serviceController =
        Robolectric.buildService(MonitorService.class).create();
    try (ActivityController<MainActivity> controller = activity()) {
      MonitorService service = serviceController.get();
      ReflectionHelpers.setField(controller.get(), "service", service);
      View root = controller.get().findViewById(android.R.id.content);
      root.findViewWithTag("engine.demo.start").performClick();
      assertTrue(service.engineDemo().active);
      controller.pause();
      assertFalse(service.engineDemo().active);
      assertFalse(new Store(controller.get()).enabled("engine"));
    } finally { serviceController.destroy(); }
  }

  @Test @Config(shadows = {PlaybackTest.PlayerShadow.class})
  public void virtualThrottleDrivesTheLiveGaugeAndStopsOnLeavingThePage() throws Exception {
    org.robolectric.android.controller.ServiceController<MonitorService> serviceController =
        Robolectric.buildService(MonitorService.class).create();
    try (ActivityController<MainActivity> controller = activity()) {
      MainActivity activity = controller.get();
      MonitorService service = serviceController.get();
      ReflectionHelpers.setField(activity, "service", service);
      SoundEngine audio = ReflectionHelpers.getField(service, "audio");
      audio.engineFactory = (context, sourcePath, preview, output, listener) -> new FakeEngineVoice(sourcePath, preview, listener);
      android.media.AudioManager manager = (android.media.AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
      shadowOf(manager).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
      Store store = new Store(activity);
      File source = File.createTempFile("virtual-demo-", ".wav", activity.getFilesDir());
      Files.copy(new File("app/src/main/assets/audio/engine.wav").toPath(), source.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
          org.robolectric.shadows.util.DataSource.toDataSource(source.getAbsolutePath()),
          new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(2000, 10));
      store.file(EventSpec.find("engine.loop"), source, "engine.wav");
      View root = activity.findViewById(android.R.id.content);
      SeekBar throttle = (SeekBar) root.findViewWithTag("engine.demo.throttle");
      throttle.setProgress(75);
      shadowOf(throttle).getOnSeekBarChangeListener().onStopTrackingTouch(throttle);
      root.findViewWithTag("engine.demo.start").performClick();
      shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS);
      assertTrue(service.engineDemo().active);
      assertTrue(service.engineDemo().speed > 30);
      assertEquals(.75f, service.engineDemo().throttle, .001f);
      assertTrue(((TextView) root.findViewWithTag("engine.demo.reading")).getText().toString().contains("虚拟车速"));
      capture(root.findViewWithTag("engine.demo.panel"), "engine-demo-running", 900, 1250);
      find(root, "← 返回音效设置").performClick();
      assertFalse(service.engineDemo().active);
      assertFalse(store.enabled("engine"));
    } finally { serviceController.destroy(); }
  }

  @Test @Config(shadows = {MetadataShadow.class})
  public void invalidAudioReplacementKeepsTheOldAudioAndRemovesPartialCopy() throws Exception {
    try (ActivityController<MainActivity> controller = activity()) {
      MainActivity activity = controller.get();
      Store store = new Store(activity);
      String original = store.source(EventSpec.find("engine.loop"));
      File source = new File(activity.getCacheDir(), "invalid-engine.bin");
      Files.write(source.toPath(), new byte[] {1, 2, 3, 4});
      ReflectionHelpers.callInstanceMethod(activity, "copyAudio",
          ReflectionHelpers.ClassParameter.from(Uri.class, Uri.fromFile(source)),
          ReflectionHelpers.ClassParameter.from(EventSpec.class, EventSpec.find("engine.loop")));
      shadowOf(Looper.getMainLooper()).idle();
      assertEquals(original, store.source(EventSpec.find("engine.loop")));
      File[] saved = new File(activity.getFilesDir(), "audio").listFiles();
      assertTrue(saved == null || saved.length == 0);
      assertFalse(store.enabled("engine"));
    }
  }

  private void capture(View view, String name, int width, int height) throws Exception {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File target = new File("dist/screenshots/" + name + ".png");
    try (FileOutputStream output = new FileOutputStream(target)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
    bitmap.recycle();
  }
}
