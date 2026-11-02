package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class EngineSynthTest {
  private EngineTuning tuning(int style) { return new EngineTuning(style, 100, 70, 55); }
  private EngineSynth.Render render(int style, boolean preview) throws Exception {
    return new EngineSynth.Render(EngineSynth.bank(style, () -> false), tuning(style), preview);
  }

  @Test public void migrationKeepsCustomSourceCurvesAndDisabledAutomaticSound() throws Exception {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().putString("audio.engine.loop", "file:/private/user-loop.wav").commit();
    EngineProfile profile = EngineProfile.preset(1).edit(0, 2, 1.42f);
    store.engineProfile(profile);
    String curve = profile.json().toString();
    assertFalse(store.engineSynth());
    assertFalse(store.enabled("engine"));
    store.prefs.edit().putBoolean("engine.synth.enabled", true).commit();
    assertTrue(store.engineSource().startsWith("synth:1:"));
    assertEquals("file:/private/user-loop.wav", store.source(EventSpec.find("engine.loop")));
    assertEquals(curve, store.engineProfile().json().toString());
    assertFalse(store.enabled("engine"));
    store.prefs.edit().putBoolean("engine.synth.enabled", false).commit();
    assertEquals("file:/private/user-loop.wav", store.engineSource());
  }

  @Test public void tuningBoundsAndSourceRoundTripAreExplicit() {
    EngineTuning settings = new EngineTuning(99, 400, -5, 300);
    assertEquals(2, settings.style);
    assertEquals(125, settings.tone);
    assertEquals(0, settings.intensity);
    assertEquals(100, settings.shifting);
    assertEquals(settings.source(), EngineTuning.source(settings.source()).source());
    for (String source : new String[] {"asset:audio/engine.wav", "synth:99:0:100:70", "synth:1:bad:100:70"}) {
      try { EngineTuning.source(source); fail(source); } catch (IllegalArgumentException expected) {}
    }
  }

  @Test public void allBanksContainSevenFinitePeriodicAndDistinctLayers() throws Exception {
    for (int style = 0; style < 3; style++) {
      EngineSynth.Bank bank = EngineSynth.bank(style, () -> false);
      assertSame(bank, EngineSynth.bank(style, () -> false));
      assertEquals(7, bank.layers.length);
      for (EngineSynth.Layer layer : bank.layers) {
        assertEquals(0, layer.samples.length % layer.cycle);
        assertTrue(layer.samples.length >= 40000 && layer.samples.length < 60000);
        double sum = 0, energy = 0;
        for (float sample : layer.samples) {
          assertTrue(Float.isFinite(sample));
          assertTrue(Math.abs(sample) <= .801f);
          sum += sample;
          energy += sample * sample;
        }
        assertEquals(0, sum / layer.samples.length, .00001);
        assertTrue(Math.sqrt(energy / layer.samples.length) > .02);
        assertTrue("periodic boundary", Math.abs(layer.samples[0] - layer.samples[layer.samples.length - 1]) < .08);
      }
      assertFalse(Arrays.equals(bank.layers[1].samples, bank.layers[2].samples));
    }
  }

  @Test public void cancelledLoadsDoNotUseEvenCachedBanks() throws Exception {
    EngineSynth.bank(0, () -> false);
    try { EngineSynth.bank(0, () -> true); fail(); } catch (IOException expected) {}
    Thread.currentThread().interrupt();
    try { EngineSynth.bank(1, () -> false); fail(); }
    catch (IOException expected) {}
    finally { Thread.interrupted(); }
  }

  @Test public void gearboxUpshiftsUnloadsAndBlipsOnCoastDownshifts() {
    EnginePowertrain engine = new EnginePowertrain(tuning(0), 280, 7);
    boolean unloaded = false, blipped = false;
    int maximumGear = 0;
    for (int frame = 0; frame < 1900; frame++) {
      float seconds = frame * .02f;
      boolean driving = seconds < 14;
      float speed = driving ? seconds * 10.5f : Math.max(0, 147 - (seconds - 14) * 7);
      assertTrue(engine.step(speed, driving ? 2.92f : -1.95f, driving ? 1 : 0, .02f));
      maximumGear = Math.max(maximumGear, engine.gear());
      unloaded |= engine.shiftDirection > 0 && engine.throttle > .9f && engine.load < -.1f;
      blipped |= engine.shiftDirection < 0 && engine.throttle < .1f && engine.load > .2f;
      assertTrue(engine.rpm >= 800 && engine.rpm <= 6500);
      assertTrue(engine.load >= -1 && engine.load <= 1);
    }
    assertTrue("upshifts", maximumGear >= 3);
    assertTrue("upshift unload", unloaded);
    assertTrue("downshift blip", blipped);
    assertEquals(1, engine.gear());
  }

  @Test public void cruisingAndCoastingHaveDifferentSignedLoadsWithoutPedalAccess() {
    EnginePowertrain engine = new EnginePowertrain(tuning(0), 280, 7);
    for (int frame = 0; frame < 100; frame++) engine.step(80, 0, Float.NaN, .1f);
    float cruise = engine.load;
    assertTrue(engine.throttle > 0);
    for (int frame = 0; frame < 30; frame++) engine.step(80 - frame, -2, Float.NaN, .1f);
    assertTrue(engine.load < -.1f);
    assertTrue(cruise > engine.load);
  }

  @Test public void invalidInputsResetModelAndRejoiningDoesNotStartInAnOverrevvingGear() {
    EnginePowertrain engine = new EnginePowertrain(tuning(1), 280, 7);
    assertTrue(engine.step(120, 0, Float.NaN, .1f));
    assertTrue(engine.gear() > 1);
    assertTrue(engine.rpm < 4000);
    for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 300}) {
      assertFalse(engine.step(bad, 0, Float.NaN, .1f));
      assertEquals(0, engine.gear());
      assertEquals(0, engine.load, 0);
    }
    assertFalse(engine.step(20, Float.NaN, Float.NaN, .1f));
    assertFalse(engine.step(20, 0, Float.NaN, 2));
    assertFalse(engine.step(20, 0, 2, .1f));
  }

  @Test public void futureStyleDoesNotShiftAndCruiseNoiseDoesNotHuntGears() {
    for (int style = 0; style < 3; style++) {
      EnginePowertrain engine = new EnginePowertrain(tuning(style), 280, 7);
      int transitions = 0, previous = 0;
      for (int frame = 0; frame < 500; frame++) {
        engine.step(80 + .1f * (float) Math.sin(frame), 0, Float.NaN, .1f);
        if (previous > 0 && previous != engine.gear()) transitions++;
        previous = engine.gear();
        if (style == 2) assertEquals(0, engine.shiftDirection);
      }
      assertTrue(transitions <= 1);
    }
  }

  @Test public void previewIsBoundedAndHasZeroStartAndSilentTail() throws Exception {
    EngineSynth.Render render = render(0, true);
    render.target(1, 1, .8f);
    short[] block = new short[960];
    boolean first = true, audible = false;
    while (render.render(block, 480) > 0) {
      if (first) { assertEquals(0, block[0]); first = false; }
      for (short sample : block) { assertTrue(Math.abs((int) sample) < 31800); audible |= sample != 0; }
      if (render.frames() > 240000) for (short sample : block) assertEquals(0, sample);
    }
    assertTrue(audible);
    assertEquals(244800, render.frames());
  }

  @Test public void controlsRemainFiniteSmoothAndReturnToExactSilence() throws Exception {
    EngineSynth.Render render = render(1, false);
    short[] block = new short[960];
    render.target(2.5f, 1, 1);
    render.render(block, 480);
    assertTrue(render.rpm() < 3000);
    for (int frame = 0; frame < 500; frame++) {
      render.target(frame % 2 == 0 ? 2.5f : .5f, .9f, frame % 2 == 0 ? 1 : -1);
      render.render(block, 480);
      for (short sample : block) assertTrue(Math.abs((int) sample) < 31800);
    }
    render.target(Float.NaN, Float.NaN, Float.NaN);
    for (int frame = 0; frame < 200; frame++) render.render(block, 480);
    for (short sample : block) assertEquals(0, sample);
  }

  @Test public void sampleContinuityDoesNotDependOnCallerBlockSize() throws Exception {
    EngineSynth.Render small = render(0, false), large = render(0, false);
    small.target(1.2f, .65f, -.4f); large.target(1.2f, .65f, -.4f);
    short[] first = new short[960], second = new short[960], frame = new short[2];
    for (int batch = 0; batch < 160; batch++) {
      large.render(first, 480);
      for (int index = 0; index < 480; index++) {
        small.render(frame, 1);
        second[index * 2] = frame[0]; second[index * 2 + 1] = frame[1];
      }
      assertArrayEquals(first, second);
    }
  }

  @Test public void signedLoadChangesTheToneAndVirtualDemoExportsTheModel() throws Exception {
    EngineSynth.Render drive = render(0, false), coast = render(0, false);
    drive.target(1, .8f, 1); coast.target(1, .8f, -1);
    short[] driving = new short[960], coasting = new short[960];
    long difference = 0;
    for (int frame = 0; frame < 100; frame++) {
      drive.render(driving, 480); coast.render(coasting, 480);
      for (int index = 0; index < driving.length; index++) difference += Math.abs(driving[index] - coasting[index]);
    }
    assertTrue(difference > 1000000);
    EngineDemo demo = new EngineDemo(280, 7);
    demo.synth(tuning(0)); demo.throttle(1);
    for (int frame = 0; frame < 100; frame++) demo.step(.1f, EngineProfile.preset(1));
    assertTrue(demo.powertrain().gear() >= 2);
    assertTrue(demo.json("test", 65).getJSONObject("powertrain").getBoolean("virtual"));
    assertEquals(demo.powertrain().rpm, demo.pitch * 3000, 1);
  }

  @Test public void independentDrivingAuditionsAreGeneratedForAllThreeStyles() throws Exception {
    for (int style = 0; style < 3; style++) {
      EngineDemo demo = new EngineDemo(280, 7);
      demo.synth(tuning(style));
      EngineSynth.Render render = render(style, false);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      short[] block = new short[960];
      for (int batch = 0; batch < 1800; batch++) {
        demo.throttle(batch < 950 ? .9f : 0);
        demo.step(.01f, EngineProfile.preset(1));
        render.target(demo.pitch, batch > 1740 ? 0 : .8f, demo.load);
        render.render(block, 480);
        for (short sample : block) { bytes.write(sample & 255); bytes.write((sample >> 8) & 255); }
      }
      byte[] audio = bytes.toByteArray();
      ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      header.put("RIFF".getBytes("US-ASCII")).putInt(audio.length + 36).put("WAVEfmt ".getBytes("US-ASCII"))
          .putInt(16).putShort((short) 1).putShort((short) 2).putInt(48000).putInt(192000)
          .putShort((short) 4).putShort((short) 16).put("data".getBytes("US-ASCII")).putInt(audio.length);
      File directory = new File("dist/audio-check"); directory.mkdirs();
      try (FileOutputStream output = new FileOutputStream(new File(directory,
          new String[] {"engine-v8-drive.wav", "engine-v10-drive.wav", "engine-future-drive.wav"}[style]))) {
        output.write(header.array()); output.write(audio);
      }
    }
  }
}
