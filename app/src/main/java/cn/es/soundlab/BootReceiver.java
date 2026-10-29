package cn.es.soundlab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if ((Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))
        && new Store(c).prefs.getBoolean("monitor", false))
      c.startForegroundService(new Intent(c, MonitorService.class).setAction(MonitorService.START));
  }
}
