package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    qualifiers = "w1280dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UiTest {
  private View find(View v, String text) {
    if (v instanceof TextView && text.equals(((TextView) v).getText().toString())) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View match = find(((ViewGroup) v).getChildAt(i), text);
        if (match != null) return match;
      }
    return null;
  }

  private int count(View v, Class<?> type) {
    int n = type.isInstance(v) ? 1 : 0;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++)
        n += count(((ViewGroup) v).getChildAt(i), type);
    return n;
  }

  private void image(MainActivity a, String name, int width, int height) throws Exception {
    View v = ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
    v.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    v.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    v.draw(new Canvas(bitmap));
    File dir = new File("dist/screenshots");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    bitmap.recycle();
  }

  @Test
  public void homeDetectionRulesAndPortraitRender() throws Exception {
    try (ActivityController<MainActivity> c =
        Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()) {
      MainActivity a = c.get();
      shadowOf(Looper.getMainLooper()).idle();
      View root = a.findViewById(android.R.id.content);
      assertNotNull(find(root, "挂挡音效"));
      assertNotNull(find(root, "解闭锁提示音"));
      assertNotNull(find(root, "充电提示音"));
      assertEquals(10, count(root, Switch.class));
      image(a, "home-landscape", 1280, 1080);
      find(root, "车辆检测").performClick();
      assertNotNull(find(root, "开始扩展检测"));
      assertEquals(1, count(root, EditText.class));
      image(a, "detection-landscape", 1280, 1080);
      find(root, "规则管理").performClick();
      assertNotNull(find(root, "让新事件拥有自己的声音"));
      image(a, "rules-landscape", 1280, 900);
      RuntimeEnvironment.setQualifiers("w400dp-h840dp-port-mdpi");
      find(root, "音效设置").performClick();
      image(a, "home-portrait", 400, 840);
    }
  }

  @Test
  public void importedSourcesAndRulesPersistIndependently() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    Store store = new Store(c);
    store.prefs.edit().clear().commit();
    EventSpec e = EventSpec.find("door.front_left.open");
    assertFalse(store.enabled("door"));
    store.enable("door", true);
    store.volume("door", 500);
    store.asset(e, "audio/option_princess.mp3", "公主请上车.mp3");
    Store reopened = new Store(c);
    assertTrue(reopened.enabled("door"));
    assertEquals(100, reopened.volume("door"));
    assertEquals("asset:audio/option_princess.mp3", reopened.source(e));
    assertEquals(
        "asset:audio/door_front_left_closed.mp3",
        reopened.source(EventSpec.find("door.front_left.close")));
    JSONObject rule =
        new JSONObject()
            .put("id", "lock1")
            .put("event", "lock.unlock")
            .put("topic", "observed/topic")
            .put("source", "日志")
            .put("value", "2");
    reopened.addRule(rule);
    assertTrue(reopened.mapped("lock.unlock"));
    assertFalse(reopened.mapped("lock.lock"));
    reopened.deleteRule(0);
    assertFalse(reopened.mapped("lock.unlock"));
    assertEquals(0, reopened.rules().length());
  }

  @Test
  public void diagnosticsAreBoundedAndExportable() throws Exception {
    Diagnostics d = new Diagnostics();
    for (int i = 0; i < 900; i++) d.line("test", "line-" + i);
    assertFalse(d.lines("").contains("line-0\n"));
    assertTrue(d.lines("").contains("line-899"));
    Protocol.Sample s =
        Protocol.decode(
            "1760000000.125 345 789 I MessageProperty: receive VehicleDoor/Door/FrontLeft, value:"
                + " CarPropertyValue{id=0x600002c, area=0x0, status=0, value=1}");
    d.sample(s);
    d.marker("主驾开门");
    JSONObject exported =
        new JSONObject(d.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertEquals(600, exported.getJSONArray("lines").length());
    assertEquals(1, exported.getJSONArray("candidates").length());
    assertFalse(exported.getBoolean("readLogsGranted"));
    assertEquals(
        "VehicleDoor/Door/FrontLeft",
        exported.getJSONArray("candidates").getJSONObject(0).getString("topic"));
  }
}
