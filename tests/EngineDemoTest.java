package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class EngineDemoTest {
  @Test public void presetsReachHundredAtTheSelectedTime() {
    for (int index = 0; index < EngineDemo.HORSEPOWER.length; index++) {
      EngineDemo demo = new EngineDemo(EngineDemo.HORSEPOWER[index], EngineDemo.ZERO_HUNDRED[index]);
      demo.throttle(1);
      for (int step = 0; step < Math.round(demo.zeroHundred * 100); step++) demo.step(.01f, EngineProfile.preset(0));
      assertEquals(100, demo.speed, .2f);
      assertTrue(demo.active);
      assertTrue(demo.pitch >= .5f && demo.pitch <= 2);
      assertTrue(demo.gain >= 0 && demo.gain <= 1);
    }
  }

  @Test public void throttleAndCoastingAffectTheSimulationAndInvalidInputCannotRunAway() {
    EngineDemo full = new EngineDemo(280, 7), half = new EngineDemo(280, 7);
    full.throttle(1); half.throttle(.5f);
    for (int step = 0; step < 70; step++) { full.step(.1f, EngineProfile.preset(0)); half.step(.1f, EngineProfile.preset(0)); }
    assertEquals(100, full.speed, .3f);
    assertTrue(half.speed < full.speed);
    float previous = full.speed;
    full.throttle(0);
    full.step(.1f, EngineProfile.preset(0));
    assertTrue(full.speed < previous);
    assertTrue(full.acceleration < 0);
    full.throttle(Float.NaN);
    assertEquals(0, full.throttle, 0);
    full.step(1, EngineProfile.preset(0));
    assertFalse(full.active);
  }

  @Test public void respectsAllCurvesResponseMasterAndVirtualExport() throws Exception {
    EngineDemo demo = new EngineDemo(280, 7);
    EngineProfile quiet = new EngineProfile("静音", new float[] {1, 1, 1, 1, 1, 1},
        new float[] {0, 0, 0, 0, 0, 0}, new float[] {0, 0, 0, 0, 0}, 200);
    demo.throttle(1);
    for (int index = 0; index < 40; index++) demo.step(.1f, quiet);
    assertEquals(1, demo.pitch, .001f);
    assertEquals(0, demo.gain, .001f);
    demo.step(.1f, EngineProfile.preset(1));
    assertTrue(demo.gain > 0);
    assertEquals(demo.gain * .25f, demo.json("test", 25).getDouble("finalGain"), .001);
    assertTrue(demo.json("test", 25).getBoolean("virtual"));
    assertFalse(demo.json("test", 25).getBoolean("actualPowerMeasurement"));
    EngineDemo slow = new EngineDemo(280, 7), quick = new EngineDemo(280, 7);
    slow.throttle(1); quick.throttle(1);
    slow.step(.1f, EngineProfile.preset(1).response(2000));
    quick.step(.1f, EngineProfile.preset(1).response(200));
    assertTrue(quick.gain > slow.gain);
  }

  @Test public void demoIsTimeSpeedBoundedAndDoesNotRestartAfterStop() {
    EngineDemo demo = new EngineDemo(800, 3);
    demo.throttle(2);
    for (int index = 0; index < 610; index++) demo.step(.1f, EngineProfile.preset(0));
    assertFalse(demo.active);
    assertTrue(demo.speed <= 200);
    assertEquals(60, demo.elapsed, .001f);
    float previous = demo.speed;
    demo.step(.1f, EngineProfile.preset(0));
    assertEquals(previous, demo.speed, 0);
    assertEquals(0, demo.json("stopped", 100).optDouble("finalGain"), 0);
  }

  @Test public void rejectsInvalidVehicleParameters() {
    for (float seconds : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY, 19}) {
      try { new EngineDemo(280, seconds); fail(); } catch (IllegalArgumentException expected) {}
    }
    try { new EngineDemo(0, 7); fail(); } catch (IllegalArgumentException expected) {}
  }
}
