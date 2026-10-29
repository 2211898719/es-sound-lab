package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioRouting;
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
import org.robolectric.shadow.api.Shadow;

/** Playback lifecycle tests. Audio decoding and real vehicle outputs still need device testing. */
@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    shadows = {PlaybackTest.PlayerShadow.class, PlaybackTest.DeviceShadow.class})
public final class PlaybackTest {
  @Implements(MediaPlayer.class)
  public static class PlayerShadow extends ShadowMediaPlayer {
    private PlaybackParams params = new PlaybackParams().setSpeed(1).setPitch(1);
    static boolean acceptsRoute = true;
    static AudioDeviceInfo initialRoute;
    AudioDeviceInfo preferred, routed;
    float volume;
    AudioRouting.OnRoutingChangedListener routingListener;

    @Implementation
    protected boolean setPreferredDevice(AudioDeviceInfo device) {
      preferred = device;
      routed = initialRoute;
      return acceptsRoute;
    }

    @Implementation
    protected AudioDeviceInfo getRoutedDevice() {
      return routed;
    }

    @Implementation
    protected void setVolume(float left, float right) {
      volume = left;
    }

    @Implementation
    protected void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener,
        android.os.Handler handler) {
      routingListener = listener;
    }

    @Implementation
    protected void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
      if (routingListener == listener) routingListener = null;
    }

    @Implementation
    protected PlaybackParams getPlaybackParams() {
      return params;
    }

    @Implementation
    protected void setPlaybackParams(PlaybackParams p) {
      params = p;
    }
  }

  @Implements(AudioDeviceInfo.class)
  public static class DeviceShadow {
    int id;

    @Implementation
    protected int getId() { return id; }

    @Implementation
    protected int getType() { return AudioDeviceInfo.TYPE_BUS; }

    @Implementation
    protected CharSequence getProductName() { return "test-vehicle-bus"; }

    @Implementation
    protected String getAddress() { return "test-output-" + id; }

    @Implementation
    protected boolean isSink() { return true; }
  }

  private AudioDeviceInfo device(int id) {
    AudioDeviceInfo device = Shadow.newInstanceOf(AudioDeviceInfo.class);
    ((DeviceShadow) Shadow.extract(device)).id = id;
    return device;
  }

  private AudioDeviceInfo externalOutput(boolean confirmed) {
    AudioDeviceInfo external = device(42), cabin = device(7);
    AudioManager manager = (AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setOutputDevices(java.util.Arrays.asList(external, cabin));
    store.prefs.edit().putInt("output", cabin.getId()).apply();
    store.lockOutput(external);
    store.prefs.edit().putBoolean("lock.output.confirmed", confirmed).apply();
    PlayerShadow.initialRoute = external;
    return external;
  }

  private void lock(int value, int status) {
    accept(Protocol.decode("1760000000.125 345 789 I MessageProperty: receive VehicleDoor/CentralLock,"
        + " value: CarPropertyValue{id=0x67000096, area=0x0, status=" + status + ", value=" + value + "}"));
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
    PlayerShadow.acceptsRoute = true;
    PlayerShadow.initialRoute = null;
    store.enable("gear", true);
    store.enable("reverse", true);
    shadowOf((AudioManager) c.getSystemService(Context.AUDIO_SERVICE))
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    for (String id :
        new String[] {"gear.p", "gear.r", "gear.d", "reverse.loop", "door.front_left.close", "lock.lock", "lock.unlock"}) {
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
  public void missingDAudioPreservesShortVoiceWithoutRestartingReverse() {
    store.file(EventSpec.find("gear.d"), new File("/missing-d-clip.wav"), "missing.wav");
    gear(1);
    advance(20);
    MediaPlayer r = players.get(0);
    gear(3);
    assertTrue(r.isPlaying());
    advance(2100);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    assertEquals(1, players.size());
    for (MediaPlayer p : players) assertEquals(ShadowMediaPlayer.State.END, shadowOf(p).getState());
  }

  @Test
  public void invalidGearPreservesShortVoiceAndRecoverySetsSilentBaseline() {
    gear(1);
    advance(20);
    MediaPlayer r = players.get(0);
    accept(Protocol.property(true, 67108874, 0, 1, 1, System.currentTimeMillis()));
    assertTrue(r.isPlaying());
    gear(3);
    advance(50);
    assertEquals(1, players.size());
    assertEquals("R 挡", service.diagnostics.playing);
    advance(2100);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    assertEquals(1, players.size());
  }

  @Test
  public void disabledGearEventsDoNotInterruptDoorVoice() {
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(0);
    store.enable("gear", false);
    gear(3);
    advance(20);
    assertTrue(door.isPlaying());
    assertEquals(1, players.size());
  }

  @Test
  public void mutedGearEventsDoNotInterruptDoorVoice() {
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(0);
    store.volume("gear", 0);
    gear(3);
    advance(20);
    assertTrue(door.isPlaying());
    assertEquals(1, players.size());
    assertTrue(service.diagnostics.lines("").contains("音量为 0"));
  }

  @Test
  public void unrelatedPreferencesAndLockBaselinesDoNotInterruptDoorVoice() {
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(0);
    store.prefs.edit().putInt("lowSpeed", 15).putString("audio.name.gear.d", "other")
        .putBoolean("lock.output.confirmed", false).apply();
    lock(0, 0);
    lock(1, 1);
    advance(20);
    assertTrue(door.isPlaying());
    assertEquals(1, players.size());
  }

  @Test
  public void sourceResetDoesNotStopExistingShortVoice() {
    gear(1);
    advance(20);
    MediaPlayer gearVoice = players.get(0);
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "Mega"));
    assertTrue(gearVoice.isPlaying());
    gear(3);
    advance(20);
    assertEquals(1, players.size());
    assertTrue(gearVoice.isPlaying());
    advance(2100);
    assertEquals(1, players.size());
  }

  @Test
  public void nativeSourceResetPreservesLogLockBaselineAndAutomaticTrigger() {
    externalOutput(true);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    lock(0, 0);
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "Mega"));
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "Android"));
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
    assertEquals("车辆闭锁", service.diagnostics.playing);
    assertTrue(service.diagnostics.lines("").contains("自动事件 · lock.lock"));
  }

  @Test
  public void modeAndRulesChangesReuseLogReaderAndPreserveVoice() throws Exception {
    LogReader retained = new LogReader(service, service.diagnostics, new LogReader.Listener() {
      public void line(String line, long started) { }
      public void reset() { }
    }, false);
    ReflectionHelpers.setField(service, "logs", retained);
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(0);
    int logGeneration = ReflectionHelpers.getField(service, "logGeneration");
    service.discovery(true);
    assertSame(retained, ReflectionHelpers.getField(service, "logs"));
    service.discovery(false);
    store.addRule(new org.json.JSONObject().put("id", "test-rule").put("source", "日志")
        .put("topic", "unknown").put("propertyId", 1).put("area", "0x0")
        .put("event", "gear.d").put("value", "1"));
    advance(20);
    assertSame(retained, ReflectionHelpers.getField(service, "logs"));
    assertEquals(logGeneration, (int) ReflectionHelpers.getField(service, "logGeneration"));
    assertTrue(door.isPlaying());
    assertEquals(1, players.size());
  }

  @Test
  public void modeAndRulesChangesPreserveAutomaticLockBaseline() throws Exception {
    externalOutput(true);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    LogReader retained = new LogReader(service, service.diagnostics, new LogReader.Listener() {
      public void line(String line, long started) { }
      public void reset() { }
    }, false);
    ReflectionHelpers.setField(service, "logs", retained);
    lock(0, 0);
    service.discovery(true);
    service.discovery(false);
    store.addRule(new org.json.JSONObject().put("id", "extra").put("source", "日志")
        .put("topic", "unknown").put("propertyId", 1).put("area", "0x0")
        .put("event", "gear.d").put("value", "1"));
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
    assertEquals("车辆闭锁", service.diagnostics.playing);
    assertSame(retained, ReflectionHelpers.getField(service, "logs"));
  }

  @Test
  public void logSourceLossDoesNotInterruptDoorVoice() {
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(0);
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "日志"));
    assertTrue(door.isPlaying());
    assertEquals(1, players.size());
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

  @Test
  public void centralLockNeverGuessesMeaningOrUsesGenericRules() throws Exception {
    externalOutput(true);
    store.enable("lock", true);
    store.addRule(new org.json.JSONObject().put("id", "old-rule").put("source", "日志")
        .put("topic", "VehicleDoor/CentralLock").put("propertyId", Protocol.CENTRAL_LOCK)
        .put("area", "0x0").put("event", "gear.d").put("value", "1"));
    lock(0, 0);
    lock(1, 0);
    advance(20);
    assertEquals(0, players.size());
  }

  @Test
  public void calibratedLockUsesIndependentExternalRouteAndIgnoresDuplicates() {
    AudioDeviceInfo external = externalOutput(true);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    lock(0, 0);
    assertEquals(0, players.size());
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
    PlayerShadow player = Shadow.extract(players.get(0));
    assertEquals(external.getId(), player.preferred.getId());
    assertEquals(.65f, player.volume, .001f);
    assertEquals("车辆闭锁", service.diagnostics.playing);
    lock(1, 0);
    assertEquals(1, players.size());
    lock(0, 0);
    advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    assertEquals("车辆解锁", service.diagnostics.playing);
  }

  @Test
  public void configuredExternalOutputDoesNotReplaceLockCalibration() throws Exception {
    externalOutput(true);
    store.enable("lock", true);
    lock(0, 0);
    lock(1, 0);
    advance(20);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockTriggerStatus.contains("未校准"));
    org.json.JSONObject exported = new org.json.JSONObject(service.diagnostics.export(true, store));
    assertTrue(exported.getString("lockReadiness").contains("未校准"));
    assertEquals("", exported.getJSONObject("lockConfiguration").getString("lockedValue"));
    assertTrue(exported.getJSONObject("lockConfiguration").getBoolean("enabled"));
    store.calibrateLock("0", "1");
    lock(1, 0);
    advance(20);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockTriggerStatus.contains("基线"));
    lock(0, 0);
    advance(20);
    assertEquals(1, players.size());
    assertTrue(service.diagnostics.lines("").contains("自动事件 · lock.lock"));
  }

  @Test
  public void automaticLockNeedsConfirmationButExplicitExternalPreviewIsAllowed() {
    externalOutput(false);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    lock(0, 0);
    lock(1, 0);
    advance(20);
    assertEquals(0, players.size());
    service.preview("lock.lock");
    advance(20);
    assertEquals(1, players.size());
    assertTrue(((PlayerShadow) Shadow.extract(players.get(0))).volume > 0);
  }

  @Test
  public void lockWithoutExternalDeviceNeverFallsBackToOrdinaryOutput() {
    service.preview("lock.lock");
    advance(50);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockOutputStatus.contains("已阻止"));
  }

  @Test
  public void rejectedExternalRouteReleasesPlayerWithoutSound() {
    externalOutput(true);
    PlayerShadow.acceptsRoute = false;
    service.preview("lock.lock");
    advance(20);
    assertEquals(1, players.size());
    assertEquals(0, ((PlayerShadow) Shadow.extract(players.get(0))).volume, .001f);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
  }

  @Test
  public void mismatchedRouteStaysMutedThenAborts() {
    externalOutput(true);
    PlayerShadow.initialRoute = device(7);
    service.preview("lock.lock");
    advance(20);
    MediaPlayer player = players.get(0);
    assertEquals(0, ((PlayerShadow) Shadow.extract(player)).volume, .001f);
    advance(1600);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).getState());
  }

  @Test
  public void routeLossMutesAndCancelsExternalAudio() {
    externalOutput(true);
    service.preview("lock.lock");
    advance(20);
    MediaPlayer player = players.get(0);
    PlayerShadow state = Shadow.extract(player);
    assertTrue(state.volume > 0);
    state.routed = device(7);
    state.routingListener.onRoutingChanged(player);
    assertEquals(0, state.volume, .001f);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).getState());
  }

  @Test
  public void invalidLockStopsAudioAndRecoveryAndRecalibrationAreSilent() {
    externalOutput(true);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    lock(0, 0);
    lock(1, 0);
    advance(20);
    lock(1, 1);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    lock(0, 0);
    advance(20);
    assertEquals(1, players.size());
    store.calibrateLock("0", "1");
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
  }

  @Test
  public void externalDeviceDisappearingWhilePreparingNeverUnmutesPlayer() {
    externalOutput(true);
    String path = store.source(EventSpec.find("lock.lock")).substring(5);
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path),
        new ShadowMediaPlayer.MediaInfo(2000, 1000));
    service.preview("lock.lock");
    AudioManager manager = (AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setOutputDevices(java.util.Collections.emptyList());
    advance(1100);
    assertEquals(1, players.size());
    assertEquals(0, ((PlayerShadow) Shadow.extract(players.get(0))).volume, .001f);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
  }

  @Test
  public void lockPreviewStaysMutedUntilActualRouteIsReported() {
    AudioDeviceInfo external = externalOutput(true);
    PlayerShadow.initialRoute = null;
    service.preview("lock.lock");
    advance(20);
    MediaPlayer player = players.get(0);
    PlayerShadow state = Shadow.extract(player);
    assertEquals(0, state.volume, .001f);
    state.routed = external;
    state.routingListener.onRoutingChanged(player);
    advance(1);
    assertTrue(state.volume > 0);
  }

  @Test
  public void exportedOutputsKeepExteriorSelectionSeparateFromCabin() throws Exception {
    externalOutput(true);
    org.json.JSONObject exported = new org.json.JSONObject(service.diagnostics.export(true, store));
    assertEquals(2, exported.getJSONArray("audioOutputs").length());
    assertEquals(42, exported.getJSONObject("lockConfiguration").getInt("outputId"));
    assertTrue(exported.getJSONObject("lockConfiguration").getBoolean("outputConfirmed"));
    assertEquals(7, store.prefs.getInt("output", -1));
    store.lockOutput(device(43));
    assertFalse(store.prefs.getBoolean("lock.output.confirmed", false));
  }

  @Test
  public void shortLockClipWaitsSilentlyForRouteThenRestartsOnce() {
    AudioDeviceInfo external = externalOutput(true);
    String path = store.source(EventSpec.find("lock.lock")).substring(5);
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path),
        new ShadowMediaPlayer.MediaInfo(350, 10));
    PlayerShadow.initialRoute = null;
    service.preview("lock.lock");
    advance(700);
    MediaPlayer player = players.get(0);
    PlayerShadow state = Shadow.extract(player);
    assertTrue(player.isPlaying());
    assertTrue(player.isLooping());
    assertEquals(0, state.volume, .001f);
    state.routed = external;
    state.routingListener.onRoutingChanged(player);
    advance(1);
    assertFalse(player.isLooping());
    assertTrue(state.volume > 0);
    advance(400);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).getState());
    assertEquals(1, players.size());
  }

  @Test
  public void delayedSeekTimesOutWithoutUnmuting() {
    externalOutput(true);
    service.preview("lock.lock");
    MediaPlayer player = players.get(0);
    shadowOf(player).setSeekDelay(2000);
    advance(1700);
    assertEquals(0, ((PlayerShadow) Shadow.extract(player)).volume, .001f);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).getState());
  }

  @Test
  public void routeLossDuringSeekCannotUnmuteFromLateCompletion() {
    externalOutput(true);
    service.preview("lock.lock");
    MediaPlayer player = players.get(0);
    shadowOf(player).setSeekDelay(1000);
    advance(20);
    PlayerShadow state = Shadow.extract(player);
    assertEquals(0, state.volume, .001f);
    state.routed = device(7);
    state.routingListener.onRoutingChanged(player);
    advance(1100);
    assertEquals(0, state.volume, .001f);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).getState());
  }
}
