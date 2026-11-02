package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccHubUiTest {
  @Test public void obsoleteHubAndStandaloneProbeActionsAreRemoved() {
    org.robolectric.android.controller.ServiceController<MonitorService> service = Robolectric.buildService(MonitorService.class).create();
    try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).create().start().resume()) {
      ReflectionHelpers.setField(activity.get(), "service", service.get());
      ReflectionHelpers.callInstanceMethod(activity.get(), "detectionOptions");
      shadowOf(Looper.getMainLooper()).idle();
      AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
      assertEquals(5, menu.getListView().getAdapter().getCount());
      for (int index = 0; index < menu.getListView().getAdapter().getCount(); index++) {
        String label = menu.getListView().getAdapter().getItem(index).toString();
        assertFalse(label.contains("ICC 服务定位"));
        assertFalse(label.contains("ICC 只读车况检测"));
      }
    } finally { service.destroy(); }
  }
}
