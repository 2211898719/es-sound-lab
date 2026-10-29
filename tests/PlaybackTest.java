package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import org.robolectric.util.ReflectionHelpers;

/** Playback lifecycle tests. Audio decoding and real vehicle outputs still need device testing. */
@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    shadows = PlaybackTest.PlayerShadow.class)
public final class PlaybackTest {
  @Implements(MediaPlayer.class)
  public static class PlayerShadow extends ShadowMediaPlayer {
    private PlaybackParams params = new PlaybackParams().setSpeed(1).setPitch(1);

    @Implementation
    protected PlaybackParams getPlaybackParams() {
      return params;
    }

    @Implementation
    protected void setPlaybackParams(PlaybackParams p) {
      params = p;
    }
  }

  private Store store;
  private MonitorService service;
  private ServiceController<MonitorService> controller;
  private final List<MediaPlayer> players = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    store = new Store(c);
    store.prefs.edit().clear().commit();
    store.enable("gear", true);
    store.enable("reverse", true);
    shadowOf((AudioManager) c.getSystemService(Context.AUDIO_SERVICE))
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    for (String id :
        new String[] {"gear.p", "gear.r", "gear.d", "reverse.loop", "door.front_left.close"}) {
      File dir = new File(c.getFilesDir(), "audio");
      dir.mkdirs();
      File f = File.createTempFile("playback-", ".wav", dir);
      Files.copy(
          new File("app/src/main/assets/audio/engine.wav").toPath(),
          f.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      store.file(EventSpec.find(id), f, id + ".wav");
      ShadowMediaPlayer.addMediaInfo(
          DataSource.toDataSource(f.getAbsolutePath()), new ShadowMediaPlayer.MediaInfo(2000, 10));
    }
    ShadowMediaPlayer.setCreateListener((player, shadow) -> players.add(player));
    controller = Robolectric.buildService(MonitorService.class).create();
    service = controller.get();
    ReflectionHelpers.setField(service, "running", true);
    gear(0); // P baseline: no sound at startup.
    assertEquals(0, players.size());
  }

  @After
  public void cleanup() {
    if (controller != null) controller.destroy();
  }

  private void gear(int raw) {
    accept(Protocol.property(true, 67108874, 0, 0, raw, System.currentTimeMillis()));
  }

  private void accept(Protocol.Sample sample) {
    ReflectionHelpers.callInstanceMethod(
        service, "accept", ReflectionHelpers.ClassParameter.from(Protocol.Sample.class, sample));
  }

  private void advance(long ms) {
    shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS);
  }

  @Test
  public void playingRIsReleasedImmediatelyWhenDArises() {
    gear(1);
    advance(20);
    MediaPlayer r = players.get(0);
    assertTrue(r.isPlaying());
    MediaPlayer.OnCompletionListener stale = shadowOf(r).getOnCompletionListener();
    gear(3);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    advance(20);
    MediaPlayer d = players.get(1);
    assertTrue(d.isPlaying());
    assertEquals("D 挡", service.diagnostics.playing);
    stale.onCompletion(r);
    assertEquals(2, players.size());
    assertTrue(d.isPlaying());
  }

  @Test
  public void staleRPreparationCannotRestartAfterSwitchingToD() {
    String path = store.source(EventSpec.find("gear.r")).substring(5);
    ShadowMediaPlayer.addMediaInfo(
        DataSource.toDataSource(path), new ShadowMediaPlayer.MediaInfo(2000, 1000));
    gear(1);
    MediaPlayer r = players.get(0);
    MediaPlayer.OnPreparedListener stale = shadowOf(r).getOnPreparedListener();
    assertEquals(ShadowMediaPlayer.State.PREPARING, shadowOf(r).getState());
    gear(3);
    advance(20);
    MediaPlayer d = players.get(1);
    assertTrue(d.isPlaying());
    stale.onPrepared(r);
    advance(1100);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    assertTrue(d.isPlaying());
    assertEquals(2, players.size());
    assertEquals("D 挡", service.diagnostics.playing);
  }

  @Test
  public void rapidRDPLeavesOnlyTheLatestGear() {
    gear(1);
    MediaPlayer r = players.get(0);
    gear(3);
    MediaPlayer d = players.get(1);
    gear(0);
    advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(d).getState());
    assertEquals(3, players.size());
    assertTrue(players.get(2).isPlaying());
    assertEquals("P 挡", service.diagnostics.playing);
  }

  @Test
  public void leavingReverseStopsTheReverseLoopEvenWithoutGearVoice() {
    store.enable("gear", false);
    gear(1);
    advance(20);
    MediaPlayer reverse = players.get(0);
    assertTrue(reverse.isPlaying());
    assertTrue(reverse.isLooping());
    gear(3);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(reverse).getState());
    advance(50);
    assertEquals(1, players.size());
    assertEquals("待命", service.diagnostics.playing);
  }

  @Test
  public void pendingReverseLoopCannotStartAfterLeavingR() {
    store.enable("gear", false);
    String path = store.source(EventSpec.find("reverse.loop")).substring(5);
    ShadowMediaPlayer.addMediaInfo(
        DataSource.toDataSource(path), new ShadowMediaPlayer.MediaInfo(2000, 1000));
    gear(1);
    MediaPlayer reverse = players.get(0);
    MediaPlayer.OnPreparedListener stale = shadowOf(reverse).getOnPreparedListener();
    gear(3);
    stale.onPrepared(reverse);
    advance(1100);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(reverse).getState());
    assertEquals(1, players.size());
    assertEquals("待命", service.diagnostics.playing);
  }

  @Test
  public void missingDAudioStillCancelsReverse() {
    store.file(EventSpec.find("gear.d"), new File("/missing-d-clip.wav"), "missing.wav");
    gear(1);
    advance(20);
    MediaPlayer r = players.get(0);
    gear(3);
    advance(2100);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    // One R player and one failed D player; no reverse loop can restart.
    assertEquals(2, players.size());
    for (MediaPlayer p : players) assertEquals(ShadowMediaPlayer.State.END, shadowOf(p).getState());
  }

  @Test
  public void invalidGearStopsPlaybackAndRecoverySetsSilentBaseline() {
    gear(1);
    advance(20);
    MediaPlayer r = players.get(0);
    accept(Protocol.property(true, 67108874, 0, 1, 1, System.currentTimeMillis()));
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    gear(3);
    advance(50);
    assertEquals(1, players.size());
    assertEquals("待命", service.diagnostics.playing);
  }

  @Test
  public void aggregateDoorBroadcastIsSilentButIndividualChangesStillPlay() {
    store.enable("door", true);
    int[] areas = {16, 64, 256, 1024};
    for (int area : areas)
      accept(Protocol.property(true, 100663305, area, 0, 0, System.currentTimeMillis()));
    advance(100);
    for (int area : areas)
      accept(Protocol.property(true, 100663305, area, 0, 1, System.currentTimeMillis()));
    advance(100);
    assertEquals(0, players.size());
    assertEquals("1", service.diagnostics.live().get("door.front_left").value);
    accept(Protocol.property(true, 100663305, 16, 0, 0, System.currentTimeMillis()));
    advance(100);
    assertEquals(1, players.size());
    assertTrue(players.get(0).isPlaying());
    assertEquals("主驾 · 关门", service.diagnostics.playing);
  }
}
