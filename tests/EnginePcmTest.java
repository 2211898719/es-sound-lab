package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.AudioTrack;
import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioTrack;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class EnginePcmTest {
  private EnginePcm fixture(int rate, int frames) throws Exception {
    float[] values = new float[frames];
    for (int index = 0; index < frames; index++) values[index] = .4f * (float) Math.sin(index * 2 * Math.PI * 137 / rate);
    return new EnginePcm(values, null, rate);
  }

  @Test public void loopSpliceIsContinuousAcrossManyPeriodsAndRates() throws Exception {
    for (float rate : new float[] {.5f, .8f, 1, 1.3f, 2}) {
      EnginePcm.Render render = new EnginePcm.Render(fixture(48000, 12345), false);
      render.target(rate, .65f, 1);
      short[] block = new short[960];
      int previous = 0, maximum = 0;
      for (int batch = 0; batch < 1000; batch++) {
        assertEquals(480, render.render(block, 480));
        for (int index = 0; index < block.length; index += 2) {
          if (batch > 30) maximum = Math.max(maximum, Math.abs(block[index] - previous));
          previous = block[index];
        }
      }
      assertTrue("unexpected loop discontinuity " + maximum, maximum < 500);
      assertEquals(480000, render.frames());
    }
  }

  @Test public void previewHasSilentEndpointsReleaseAndTail() throws Exception {
    EnginePcm pcm = fixture(24000, 48000);
    EnginePcm.Render render = new EnginePcm.Render(pcm, true);
    render.target(1, 1, 1);
    short[] block = new short[960];
    ByteArrayOutputStream collected = new ByteArrayOutputStream();
    int frames;
    while ((frames = render.render(block, 480)) != 0)
      for (int index = 0; index < frames * 2; index++) { collected.write(block[index] & 255); collected.write((block[index] >> 8) & 255); }
    ByteBuffer result = ByteBuffer.wrap(collected.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0, result.getShort(0));
    assertEquals(100800, render.frames());
    for (int index = result.limit() - 4800 * 4; index < result.limit(); index += 2) assertEquals(0, result.getShort(index));
    for (int index = 96000 * 4 - 32; index < 96000 * 4; index += 2) assertTrue(Math.abs(result.getShort(index)) < 25);
  }

  @Test public void longPreviewIsCappedAtFiveSecondsAndStereoIsPreserved() throws Exception {
    float[] left = new float[48000 * 6], right = new float[left.length];
    for (int index = 0; index < left.length; index++) {
      left[index] = .5f * (float) Math.sin(index * .04);
      right[index] = .2f * (float) Math.sin(index * .08);
    }
    EnginePcm.Render render = new EnginePcm.Render(new EnginePcm(left, right, 48000), true);
    render.target(1, 1, 0);
    short[] buffer = new short[960];
    boolean different = false;
    while (render.render(buffer, 480) > 0) if (buffer[200] != buffer[201]) different = true;
    assertTrue(different);
    assertEquals(244800, render.frames());
  }

  @Test public void abruptControlChangesAreSmoothedAndZeroVolumeConverges() throws Exception {
    EnginePcm.Render render = new EnginePcm.Render(fixture(48000, 20000), false);
    short[] block = new short[960];
    render.target(1, 0, 0); render.render(block, 480);
    render.target(2, 1, 1); render.render(block, 480);
    assertTrue(render.rpm() < 4500);
    assertTrue(render.gain() < .3f);
    for (int index = 0; index < 100; index++) render.render(block, 480);
    assertEquals(6000, render.rpm(), 10);
    render.target(Float.NaN, Float.NaN, Float.NaN);
    for (int index = 0; index < 100; index++) render.render(block, 480);
    assertTrue(render.rpm() >= 1500 && render.rpm() < 1510);
    assertTrue(render.gain() < .0001f);
  }

  @Test public void loadChangesTimbreWithoutIntroducingAControlStep() throws Exception {
    float[] source = new float[48000];
    for (int index = 0; index < source.length; index++) source[index] = .4f * (float) Math.sin(index * 2 * Math.PI * 6000 / 48000);
    EnginePcm pcm = new EnginePcm(source, null, 48000);
    EnginePcm.Render coast = new EnginePcm.Render(pcm, false), drive = new EnginePcm.Render(pcm, false);
    coast.target(1, .7f, 0); drive.target(1, .7f, 1);
    short[] quiet = new short[960], bright = new short[960];
    for (int index = 0; index < 100; index++) { coast.render(quiet, 480); drive.render(bright, 480); }
    long quietEnergy = 0, brightEnergy = 0;
    for (int index = 0; index < quiet.length; index++) { quietEnergy += (long) quiet[index] * quiet[index]; brightEnergy += (long) bright[index] * bright[index]; }
    assertTrue(brightEnergy > quietEnergy * 2);
  }

  @Test public void dcOffsetIsRemovedAndOverloadDoesNotClip() throws Exception {
    float[] source = new float[4800];
    for (int index = 0; index < source.length; index++) source[index] = 1 + (float) Math.sin(index * .1);
    EnginePcm pcm = new EnginePcm(source, null, 48000);
    double sum = 0;
    for (float value : pcm.left) { sum += value; assertTrue(Math.abs(value) <= .85001f); }
    assertEquals(0, sum / pcm.left.length, .00001);
  }

  @Test public void userProfileSurvivesNewPresetTuningAndDbAnchorsAreReproducible() throws Exception {
    EngineProfile sport = EngineProfile.preset(1);
    assertEquals(1500, sport.pitch[0] * 3000, .001);
    assertEquals(Math.pow(10, -8 / 20.0), sport.volume[5], .00001);
    String saved = sport.edit(0, 1, 1.37f).edit(1, 2, .77f).json().toString();
    EngineProfile restored = EngineProfile.parse(saved);
    assertEquals(1.37f, restored.pitch[1], 0);
    assertEquals(.77f, restored.volume[2], 0);
  }

  @Test public void wavParserHandlesPreparedSourceAndRejectsMalformedOrLongAudio() throws Exception {
    try (FileInputStream input = new FileInputStream("app/src/main/assets/audio/engine.wav")) {
      EnginePcm pcm = EngineDecoder.wav(input);
      assertNotNull(pcm); assertEquals(48000, pcm.rate);
    }
    assertNull(EngineDecoder.wav(new ByteArrayInputStream(new byte[] {1, 2, 3})));
    byte[] broken = new byte[20];
    System.arraycopy("RIFF".getBytes("US-ASCII"), 0, broken, 0, 4);
    System.arraycopy("WAVEfmt ".getBytes("US-ASCII"), 0, broken, 8, 8);
    ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 99999);
    try { EngineDecoder.wav(new ByteArrayInputStream(broken)); fail(); } catch (java.io.IOException expected) {}
    try { new EnginePcm(new float[48000 * 31], null, 48000); fail(); } catch (java.io.IOException expected) {}
    try { new EnginePcm(new float[] {Float.NaN}, null, 48000); fail(); } catch (java.io.IOException expected) {}
  }

  @Test public void writesAnOfflineAuditionWithMultipleLoopWrapsAndSmoothControls() throws Exception {
    EnginePcm pcm;
    try (FileInputStream input = new FileInputStream("app/src/main/assets/audio/engine.wav")) { pcm = EngineDecoder.wav(input); }
    EnginePcm.Render render = new EnginePcm.Render(pcm, false);
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    short[] output = new short[960];
    for (int index = 0; index < 1200; index++) {
      float phase = index / 1200f;
      render.target(.65f + 1.2f * (float) Math.sin(Math.PI * phase), phase > .95f ? 0 : .3f,
          phase < .6f ? .85f : .1f);
      render.render(output, 480);
      for (short value : output) { result.write(value & 255); result.write((value >> 8) & 255); }
    }
    File directory = new File("dist/audio-check"); directory.mkdirs();
    byte[] audio = result.toByteArray();
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes("US-ASCII")).putInt(audio.length + 36).put("WAVEfmt ".getBytes("US-ASCII"))
        .putInt(16).putShort((short) 1).putShort((short) 2).putInt(48000).putInt(48000 * 4)
        .putShort((short) 4).putShort((short) 16).put("data".getBytes("US-ASCII")).putInt(audio.length);
    try (FileOutputStream file = new FileOutputStream(new File(directory, "engine-continuous.wav"))) { file.write(header.array()); file.write(audio); }
  }

  @Implements(AudioTrack.class)
  public static class TrackShadow extends ShadowAudioTrack {
    static volatile int writes, plays, releases;
    static volatile boolean rejectWrite;
    private int head;
    @Implementation protected int write(short[] audio, int offset, int count, int mode) {
      writes++; head += count / 2;
      try { Thread.sleep(1); } catch (InterruptedException ignored) {}
      return rejectWrite ? AudioTrack.ERROR_INVALID_OPERATION : count;
    }
    @Implementation protected void play() { plays++; }
    @Implementation protected int getPlaybackHeadPosition() { return head; }
    @Implementation protected int getUnderrunCount() { return 0; }
    @Implementation protected void release() { releases++; }
  }

  @Before public void reset() { TrackShadow.writes = TrackShadow.plays = TrackShadow.releases = 0; TrackShadow.rejectWrite = false; }

  private void await(BooleanSupplier ready) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!ready.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle(); }
    assertTrue("worker did not finish in time", ready.getAsBoolean());
  }

  @Test @Config(shadows = TrackShadow.class)
  public void synthesizedWorkerFinishesPreviewAndReleasesOutput() throws Exception {
    AtomicInteger completed = new AtomicInteger(), errors = new AtomicInteger(), started = new AtomicInteger();
    EngineVoice voice = new PcmEnginePlayer(RuntimeEnvironment.getApplication(),
        new EngineTuning(0, 100, 70, 55).source(), true, null, new EngineVoice.Listener() {
          public void started(String output) { assertTrue(output.contains("route=")); started.incrementAndGet(); }
          public void finished() { completed.incrementAndGet(); }
          public void failed(Exception error) { errors.incrementAndGet(); }
        });
    voice.target(1, .3f, .5f);
    try {
      await(() -> completed.get() + errors.get() > 0);
      assertEquals(0, errors.get());
      assertEquals(1, completed.get());
      assertEquals(1, started.get());
      await(() -> TrackShadow.releases > 0);
      assertEquals(1, TrackShadow.plays);
    } finally { voice.close(); }
  }

  @Test @Config(shadows = TrackShadow.class)
  public void synthesizedWorkerCancelAndFailureCannotReviveAudio() throws Exception {
    AtomicInteger callbacks = new AtomicInteger();
    EngineVoice.Listener listener = new EngineVoice.Listener() {
      public void finished() { callbacks.incrementAndGet(); }
      public void failed(Exception error) { callbacks.incrementAndGet(); }
    };
    String source = new EngineTuning(1, 100, 70, 55).source();
    EngineVoice voice = new PcmEnginePlayer(RuntimeEnvironment.getApplication(), source, false, null, listener);
    voice.target(1.2f, .4f, .7f);
    try { await(() -> TrackShadow.writes > 2); } finally { voice.close(); }
    await(() -> TrackShadow.releases > 0);
    assertEquals(0, callbacks.get());
    TrackShadow.rejectWrite = true;
    EngineVoice failed = new PcmEnginePlayer(RuntimeEnvironment.getApplication(), source, false, null, listener);
    try {
      await(() -> callbacks.get() > 0);
      assertEquals(1, callbacks.get());
      await(() -> TrackShadow.releases > 1);
    } finally { failed.close(); }
  }

  @Test @Config(shadows = TrackShadow.class)
  public void pcmWorkerFinishesPreviewAndReleasesOutput() throws Exception {
    AtomicInteger completed = new AtomicInteger(), errors = new AtomicInteger();
    EngineVoice voice = new PcmEnginePlayer(RuntimeEnvironment.getApplication(),
        "file:" + new File("app/src/main/assets/audio/engine.wav").getAbsolutePath(), true, null,
        new EngineVoice.Listener() {
          public void finished() { completed.incrementAndGet(); }
          public void failed(Exception error) { errors.incrementAndGet(); }
        });
    voice.target(1, .3f, 0);
    try { await(() -> completed.get() + errors.get() > 0); assertEquals(0, errors.get()); await(() -> TrackShadow.releases > 0); assertEquals(1, TrackShadow.plays); }
    finally { voice.close(); }
  }

  @Test @Config(shadows = TrackShadow.class)
  public void pcmWorkerCancellationAndWriteFailureDoNotReviveOutput() throws Exception {
    AtomicInteger callbacks = new AtomicInteger();
    EngineVoice voice = new PcmEnginePlayer(RuntimeEnvironment.getApplication(),
        "file:" + new File("app/src/main/assets/audio/engine.wav").getAbsolutePath(), false, null,
        new EngineVoice.Listener() {
          public void finished() { callbacks.incrementAndGet(); }
          public void failed(Exception error) { callbacks.incrementAndGet(); }
        });
    try { await(() -> TrackShadow.writes > 2); } finally { voice.close(); }
    await(() -> TrackShadow.releases > 0);
    assertEquals(0, callbacks.get());
    TrackShadow.rejectWrite = true;
    EngineVoice failed = new PcmEnginePlayer(RuntimeEnvironment.getApplication(),
        "file:" + new File("app/src/main/assets/audio/engine.wav").getAbsolutePath(), false, null,
        new EngineVoice.Listener() {
          public void finished() { fail(); }
          public void failed(Exception error) { callbacks.incrementAndGet(); }
        });
    try { await(() -> callbacks.get() > 0); assertEquals(1, callbacks.get()); }
    finally { failed.close(); }
  }
}
