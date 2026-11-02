package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class EngineMotionTest {
  @Test public void presetsAreBoundedAndInterpolateAtAnchors() {
    for (int preset = 0; preset < 3; preset++) {
      EngineProfile profile = EngineProfile.preset(preset);
      for (int speed = 0; speed <= 240; speed++) {
        for (int acceleration = -6; acceleration <= 6; acceleration++) {
          float load = profile.load(acceleration);
          assertTrue(load >= 0 && load <= 1);
          assertTrue(profile.pitch(speed, load) >= .5f && profile.pitch(speed, load) <= 2);
          assertTrue(profile.gain(speed, load) >= 0 && profile.gain(speed, load) <= 1);
        }
      }
      assertEquals(0, profile.load(-2), 0);
      assertEquals(profile.pitch[2], EngineProfile.interpolate(40, EngineProfile.SPEED_POINTS, profile.pitch), 0);
      assertEquals(profile.pitch[5], EngineProfile.interpolate(200, EngineProfile.SPEED_POINTS, profile.pitch), 0);
    }
  }

  @Test public void customCurvesRoundTripAndInvalidProfilesFallBack() throws Exception {
    EngineProfile profile = EngineProfile.preset(1).edit(0, 2, 1.37f).edit(1, 4, .77f)
        .edit(2, 3, .81f).response(1200);
    EngineProfile reopened = EngineProfile.parse(profile.json().toString());
    assertEquals("自定义", reopened.name);
    assertArrayEquals(profile.pitch, reopened.pitch, .0001f);
    assertArrayEquals(profile.volume, reopened.volume, .0001f);
    assertArrayEquals(profile.acceleration, reopened.acceleration, .0001f);
    assertEquals(1200, reopened.responseMillis);
    assertEquals(EngineProfile.NAMES[0], EngineProfile.parse("broken").name);
    JSONObject invalid = profile.json().put("pitch", new org.json.JSONArray().put(1));
    assertEquals(EngineProfile.NAMES[0], EngineProfile.parse(invalid.toString()).name);
    assertEquals(2, profile.edit(0, 0, 90).pitch[0], 0);
    assertEquals(200, profile.response(0).responseMillis);
    try { profile.edit(0, 0, Float.NaN); fail(); } catch (IllegalArgumentException expected) {}
  }

  @Test public void oldHiddenEngineSwitchDoesNotOptIntoSimulation() {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().putBoolean("enabled.engine", true).commit();
    assertFalse(store.enabled("engine"));
    store.enable("engine", true);
    assertTrue(new Store(RuntimeEnvironment.getApplication()).enabled("engine"));
    EngineProfile profile = EngineProfile.preset(2).edit(1, 1, .8f);
    store.engineProfile(profile);
    assertEquals(.8f, new Store(RuntimeEnvironment.getApplication()).engineProfile().volume[1], 0);
    store.enable("engine", false);
    assertFalse(store.enabled("engine"));
  }

  @Test public void constantSpeedHasNoAccelerationAndNeedsTwoSeparatedSamples() {
    EngineMotion motion = new EngineMotion();
    EngineProfile profile = EngineProfile.preset(0);
    motion.sample(40, 1000, profile);
    assertFalse(motion.ready(1000));
    motion.sample(40, 1100, profile);
    assertFalse(motion.ready(1100));
    for (int index = 1; index <= 20; index++) motion.sample(40, 1100 + index * 250, profile);
    assertTrue(motion.ready(6100));
    assertEquals(40, motion.speed(), .001f);
    assertEquals(0, motion.acceleration(), .001f);
    assertFalse(motion.ready(8601));
  }

  @Test public void irregularSamplingFitsMetresPerSecondSquaredAndDecelerationIsNotLoad() {
    EngineMotion motion = new EngineMotion();
    EngineProfile profile = EngineProfile.preset(1).response(200);
    long[] times = {0, 250, 600, 1000, 1300, 1750, 2100, 2500, 2900};
    for (long time : times) motion.sample(20 + time / 1000f * 3.6f, time, profile);
    assertTrue(motion.ready(2900));
    assertEquals(1, motion.acceleration(), .01f);
    motion.reset();
    for (long time : times) motion.sample(50 - time / 1000f * 3.6f, time, profile);
    assertEquals(-1, motion.acceleration(), .01f);
    assertEquals(0, profile.load(motion.acceleration()), 0);
  }

  @Test public void staleReconnectAndImpossibleJumpReprimeInsteadOfSpiking() {
    EngineMotion motion = new EngineMotion();
    EngineProfile profile = EngineProfile.preset(0);
    motion.sample(20, 1000, profile);
    motion.sample(22, 1500, profile);
    assertTrue(motion.ready(1500));
    motion.sample(60, 5000, profile);
    assertFalse(motion.ready(5000));
    assertEquals(0, motion.acceleration(), 0);
    motion.sample(120, 5250, profile);
    assertFalse(motion.ready(5250));
    assertEquals(0, motion.acceleration(), 0);
    motion.sample(120, 5250, profile);
    motion.sample(120, 5200, profile);
    assertFalse(motion.ready(5250));
    motion.sample(Float.NaN, 5500, profile);
    assertFalse(motion.ready(5500));
    motion.sample(-1, 5750, profile);
    assertFalse(motion.ready(5750));
  }
}
