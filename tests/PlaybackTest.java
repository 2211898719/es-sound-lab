package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
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
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
import org.robolectric.util.ReflectionHelpers;

/** Playback lifecycle tests. Audio decoding and real vehicle outputs still need device testing. */
@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    shadows = {PlaybackTest.PlayerShadow.class, PlaybackTest.DeviceShadow.class, PlaybackTest.AttributesBuilderShadow.class, PlaybackTest.AttributesShadow.class})
public final class PlaybackTest {
  @Implements(AudioAttributes.class)
  public static class AttributesShadow {
    @Implementation protected int getVolumeControlStream() { return AudioManager.STREAM_MUSIC; }
  }
  @Implements(AudioAttributes.Builder.class)
  public static class AttributesBuilderShadow {
    @RealObject AudioAttributes.Builder builder;
    static boolean supportsExterior = true;

    @Implementation protected AudioAttributes.Builder setUsage(int usage) {
      ReflectionHelpers.setField(builder, "mUsage", usage == ExteriorAudio.USAGE && !supportsExterior ? 0 : usage);
      return builder;
    }
  }

  @Implements(MediaPlayer.class)
  public static class PlayerShadow extends ShadowMediaPlayer {
    private PlaybackParams params = new PlaybackParams().setSpeed(1).setPitch(1);
    static boolean acceptsRoute = true;
    static AudioDeviceInfo initialRoute;
    AudioDeviceInfo preferred, routed;
    AudioAttributes attributes;
    float volume;
    AudioRouting.OnRoutingChangedListener routingListener;

    @Implementation protected void setAudioAttributes(AudioAttributes value) { attributes = value; }

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
    String address;

    @Implementation
    protected int getId() { return id; }

    @Implementation
    protected int getType() { return AudioDeviceInfo.TYPE_BUS; }

    @Implementation
    protected CharSequence getProductName() { return "test-vehicle-bus"; }

    @Implementation
    protected String getAddress() { return address != null ? address : id == 42 ? ExteriorAudio.ADDRESS : "test-output-" + id; }

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
    String meaning = store.lockState(Integer.toString(value));
    String raw = "locked".equals(meaning) ? "2" : "unlocked".equals(meaning) ? "1" : "0";
    accept(new Protocol.Sample("ICC/VehicleCondition/Lock", 13, "0x0", 0, raw, System.currentTimeMillis(),
        "ICC", "lock", status == 0 ? meaning : null));
  }

  private void autoHold(int value, int status) {
    store.logsEnabled(true);
    ReflectionHelpers.setField(service, "logEventsActive", true);
    Protocol.Sample nativeSample = Protocol.property(true, Protocol.AUTO_HOLD, 0, status, value, System.currentTimeMillis());
    accept(new Protocol.Sample("T1CF1/AutoHold", Protocol.AUTO_HOLD, "0x0", status,
        nativeSample.raw, nativeSample.time, "日志", nativeSample.key, nativeSample.value));
  }

  private void protocolLock(int raw) {
    accept(new Protocol.Sample("ICC/VehicleCondition/Lock", 13, "0x0", 0,
        Integer.toString(raw), System.currentTimeMillis(), "ICC", "lock",
        raw == 1 ? "unlocked" : raw == 2 ? "locked" : null));
  }

  @Test public void factoryBusIsFoundWithoutSavedDeviceAndUsesFactoryUsageAndUnityGain() {
    AudioDeviceInfo external = device(42);
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setOutputDevices(java.util.Arrays.asList(device(7), external));
    PlayerShadow.initialRoute = external;
    assertFalse(store.prefs.contains("lock.output.id"));
    assertFalse(store.prefs.getBoolean("lock.output.confirmed", false));
    protocolLock(1); protocolLock(2); advance(20);
    assertEquals(1, players.size());
    PlayerShadow player = Shadow.extract(players.get(0));
    assertEquals(42, player.preferred.getId());
    assertEquals(ExteriorAudio.USAGE, player.attributes.getUsage());
    assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, player.attributes.getContentType());
    assertEquals(1f, player.volume, .001f);
    SoundEngine audio = ReflectionHelpers.getField(service, "audio");
    android.media.AudioFocusRequest focus = ReflectionHelpers.getField(audio, "focus");
    assertEquals(ExteriorAudio.USAGE, focus.getAudioAttributes().getUsage());
  }

  @Test public void drivingAndUnknownGearLockChangesAreSilentAndNeverReplayedAtP() {
    externalOutput(false); store.enable("gear", false);
    for (int raw : new int[] {2, 3, 4, 0}) {
      gear(raw); protocolLock(1); protocolLock(2); advance(20);
      assertEquals(0, players.size());
    }
    gear(1); protocolLock(2); advance(20);
    assertEquals(0, players.size());
    protocolLock(1); advance(20);
    assertEquals(1, players.size());
    assertEquals("车辆解锁", service.diagnostics.playing);
  }

  @Test public void snapshotEnteringPAndChangingLockTogetherIsSilentUntilLaterLockChange() {
    externalOutput(false); store.enable("gear", false); startIcc();
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 3, 13, 1));
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, 2));
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, 1));
    iccNext(); assertEquals(0, players.size());
    iccNext(); assertEquals(0, players.size());
    iccNext(); assertEquals(1, players.size());
  }

  @Test public void leavingPCancelsPreparingAutomaticLockAndLatePrepareCannotRestartIt() {
    externalOutput(false); store.enable("gear", false);
    String path = store.source(EventSpec.find("lock.lock")).substring(5);
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), new ShadowMediaPlayer.MediaInfo(2000, 1000));
    protocolLock(1); protocolLock(2);
    assertEquals(1, players.size());
    MediaPlayer lock = players.get(0);
    MediaPlayer.OnPreparedListener late = shadowOf(lock).getOnPreparedListener();
    gear(4); advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(lock).getState());
    late.onPrepared(lock); advance(1100);
    assertEquals(1, players.size());
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(lock).getState());
  }

  @Test public void leavingPStopsAlreadyAudibleAutomaticLockImmediately() {
    externalOutput(false); store.enable("gear", false);
    protocolLock(1); protocolLock(2); advance(20);
    assertTrue(players.get(0).isPlaying());
    gear(2); advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    gear(1); protocolLock(2); advance(20);
    assertEquals(1, players.size());
  }

  @Test public void stalePGearCannotUnmutePendingAutomaticLock() {
    externalOutput(false); PlayerShadow.initialRoute = null;
    protocolLock(1); protocolLock(2); advance(20);
    PlayerShadow player = Shadow.extract(players.get(0));
    assertEquals(0f, player.volume, 0f);
    java.util.Map<String, ?> states = ReflectionHelpers.getField(service, "states");
    ReflectionHelpers.setField(states.get("gear"), "at", System.currentTimeMillis() - 6000);
    player.routed = device(42);
    player.routingListener.onRoutingChanged(players.get(0));
    assertEquals(0f, player.volume, 0f);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
  }

  @Test public void sourceLossStopsAutomaticLockAndRecoveryDoesNotReplayCurrentLock() {
    externalOutput(false);
    protocolLock(1); protocolLock(2); advance(20);
    ReflectionHelpers.callInstanceMethod(service, "resetSource", ReflectionHelpers.ClassParameter.from(String.class, "ICC"));
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    gear(1); protocolLock(2); advance(20);
    assertEquals(1, players.size());
  }

  @Test public void unsupportedFactoryUsageNeverFallsBackToCabinOrCrashes() {
    externalOutput(false); AttributesBuilderShadow.supportsExterior = false;
    protocolLock(1); protocolLock(2); advance(20);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockOutputStatus.contains("不支持原厂车外usage2007"));
  }

  @Test public void misleadingDeviceIdOrNameCannotReplaceFactoryBusAddress() {
    AudioDeviceInfo wrong = device(42);
    ((DeviceShadow) Shadow.extract(wrong)).address = "BUS_MEDIA";
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setOutputDevices(java.util.Collections.singletonList(wrong));
    store.lockOutput(wrong);
    protocolLock(1); protocolLock(2); advance(20);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockOutputStatus.contains("不回退车内"));
  }

  @Test public void factoryAddressSurvivesDeviceIdChangesBetweenPlaybacks() {
    AudioDeviceInfo external = externalOutput(false);
    protocolLock(1); protocolLock(2); advance(20);
    service.stopAudio();
    AudioDeviceInfo replacement = device(99);
    ((DeviceShadow) Shadow.extract(replacement)).address = ExteriorAudio.ADDRESS;
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setOutputDevices(java.util.Collections.singletonList(replacement));
    PlayerShadow.initialRoute = replacement;
    protocolLock(1); advance(20);
    assertEquals(2, players.size());
    assertEquals(99, ((PlayerShadow) Shadow.extract(players.get(1))).preferred.getId());
    assertNotEquals(external.getId(), replacement.getId());
  }

  @Test public void lockFactoryDefaultsMigrateOnceAndLaterUserVolumeAndSwitchPersist() {
    store.prefs.edit().remove("lock.factory-defaults.v1").putBoolean("enabled.lock", false)
        .putInt("volume.lock", 65).commit();
    Store upgraded = new Store(service);
    assertTrue(upgraded.enabled("lock")); assertEquals(100, upgraded.volume("lock"));
    upgraded.enable("lock", false); upgraded.volume("lock", 37);
    Store reopened = new Store(service);
    assertFalse(reopened.enabled("lock")); assertEquals(37, reopened.volume("lock"));
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
    AttributesBuilderShadow.supportsExterior = true;
    PlayerShadow.initialRoute = null;
    store.enable("gear", true);
    store.enable("reverse", true);
    shadowOf((AudioManager) c.getSystemService(Context.AUDIO_SERVICE))
        .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    for (String id :
        new String[] {
          "gear.p",
          "gear.r",
          "gear.d",
          "reverse.loop",
          "engine.loop",
          "door.front_left.close",
          "lock.lock",
          "lock.unlock",
          "autohold.holding",
          "autohold.released",
          "charge.start",
          "charge.stop"
        }) {
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
    SoundEngine audio = ReflectionHelpers.getField(service, "audio");
    audio.engineFactory = (context, source, preview, output, listener) -> new FakeEngineVoice(source, preview, listener);
    ReflectionHelpers.setField(service, "running", true);
    startIcc();
    gear(1);
    assertEquals(0, players.size());
  }

  @After
  public void cleanup() {
    if (controller != null) controller.destroy();
  }

  private void gear(int raw) {
    Integer protocolRaw = raw == 1 ? 1 : raw == 2 ? 4 : raw == 3 ? 2 : raw == 4 ? 3 : raw;
    accept(new IccVehicleWire.Condition(protocolRaw, new org.json.JSONObject()).stateSamples(System.currentTimeMillis()).get(0));
  }

  private void accept(Protocol.Sample sample) {
    ReflectionHelpers.callInstanceMethod(
        service, "accept", ReflectionHelpers.ClassParameter.from(Protocol.Sample.class, sample));
  }

  private void advance(long ms) {
    shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS);
  }

  private void engineSpeed(int value) {
    gear(4);
    try {
      IccVehicleWire.Condition condition = new IccVehicleWire.Condition(3,
          new org.json.JSONObject().put("speedRaw", value));
      accept(condition.stateSamples(System.currentTimeMillis()).get(1));
    } catch (Exception error) { throw new AssertionError(error); }
  }

  private SoundEngine engineAudio() { return ReflectionHelpers.getField(service, "audio"); }
  private FakeEngineVoice engineLoop() {
    boolean preview = ReflectionHelpers.getField(engineAudio(), "enginePreview");
    return preview ? null : ReflectionHelpers.getField(engineAudio(), "engineVoice");
  }

  @Test public void synthesizedRealPlaybackUsesFreshDAndStopsOnStalenessAndPark() {
    store.prefs.edit().putBoolean("engine.synth.enabled", true).apply();
    startEngine();
    FakeEngineVoice voice = engineLoop();
    assertTrue(voice.source.startsWith("synth:1:"));
    assertTrue(service.diagnostics.enginePowertrain.optBoolean("virtual"));
    assertFalse(service.diagnostics.enginePowertrain.optBoolean("actualPedalMeasurement", true));
    advance(3000);
    assertTrue(voice.closed);
    assertNull(engineLoop());
    engineSpeed(24); advance(300); engineSpeed(25); advance(100);
    assertNotNull(engineLoop());
    voice = engineLoop();
    gear(1); advance(100);
    assertTrue(voice.closed);
    assertNull(engineLoop());
  }

  @Test public void synthesizedSettingsCancelOldVoiceAndRequireFreshSamples() {
    store.prefs.edit().putBoolean("engine.synth.enabled", true).apply();
    startEngine();
    FakeEngineVoice voice = engineLoop();
    store.prefs.edit().putInt("engine.synth.tone", 112).apply();
    assertTrue(voice.closed);
    advance(200);
    assertNull(engineLoop());
    engineSpeed(24); advance(300); engineSpeed(25); advance(100);
    assertNotNull(engineLoop());
    assertTrue(engineLoop().source.contains(":112:"));
    voice.listener.failed(new java.io.IOException("late cancelled synthesis"));
    assertNotNull(engineLoop());
    assertNotSame(voice, engineLoop());
  }

  @Test public void synthesizedPreviewDoesNotNeedCustomFileOrEnableAutomaticSound() {
    String source = store.source(EventSpec.find("engine.loop"));
    store.prefs.edit().putString("audio.engine.loop", "file:/missing/user-loop.wav")
        .putBoolean("engine.synth.enabled", true).apply();
    service.preview("engine.loop"); advance(100);
    FakeEngineVoice voice = ReflectionHelpers.getField(engineAudio(), "engineVoice");
    assertNotNull(voice);
    assertTrue(voice.preview);
    assertTrue(voice.source.startsWith("synth:"));
    assertFalse(store.enabled("engine"));
    advance(5000);
    assertNull(ReflectionHelpers.getField(engineAudio(), "engineVoice"));
    store.prefs.edit().putString("audio.engine.loop", source).putBoolean("engine.synth.enabled", false).apply();
    assertEquals(source, store.engineSource());
  }

  @Test public void synthesizedDemoExportsVirtualGearAndStopsForRealDriveAndFocusLoss() {
    store.prefs.edit().putBoolean("engine.synth.enabled", true).apply();
    assertTrue(service.startEngineDemo(280, 7));
    for (int frame = 0; frame < 20; frame++) { service.engineDemoThrottle(.9f); advance(100); }
    assertNotNull(engineLoop());
    assertTrue(service.engineDemo().powertrain().gear() > 0);
    assertTrue(service.diagnostics.enginePowertrain.optBoolean("virtual"));
    assertFalse(store.enabled("engine"));
    store.volume("engine", 0);
    assertEquals(0, engineLoop().gain, 0);
    store.enable("gear", false);
    gear(4); advance(100);
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
    gear(1);
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1); advance(200);
    android.media.AudioFocusRequest focus = ReflectionHelpers.getField(engineAudio(), "focus");
    AudioManager.OnAudioFocusChangeListener listener = ReflectionHelpers.getField(focus, "mFocusListener");
    listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
    advance(100);
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
  }

  @Test public void synthesizedRealPlaybackStopsForCalibrationAndFocusWithoutAutomaticRevival() {
    store.prefs.edit().putBoolean("engine.synth.enabled", true).apply();
    startEngine();
    FakeEngineVoice voice = engineLoop();
    service.calibrationMode(true); advance(100);
    assertTrue(voice.closed);
    assertNull(engineLoop());
    service.calibrationMode(false);
    engineSpeed(24); advance(300); engineSpeed(25); advance(100);
    assertNotNull(engineLoop());
    voice = engineLoop();
    android.media.AudioFocusRequest focus = ReflectionHelpers.getField(engineAudio(), "focus");
    AudioManager.OnAudioFocusChangeListener listener = ReflectionHelpers.getField(focus, "mFocusListener");
    listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(100);
    assertTrue(voice.closed);
    assertNull(engineLoop());
    engineSpeed(26); advance(300); engineSpeed(27); advance(100);
    assertNull(engineLoop());
  }

  private void startEngine() {
    store.enable("gear", false);
    store.enable("engine", true);
    shadowOf(Looper.getMainLooper()).idle();
    engineSpeed(20);
    assertNull(engineLoop());
    advance(300);
    engineSpeed(22);
    advance(100);
    assertNotNull(engineLoop());
  }

  @Test public void virtualDemoUsesTheSelectedAudioCurvesAndMasterWithoutEnablingAutomaticSound() throws Exception {
    store.volume("engine", 27);
    assertTrue(service.startEngineDemo(280, 7));
    for (int index = 0; index < 15; index++) { service.engineDemoThrottle(.8f); advance(100); }
    EngineDemo demo = service.engineDemo();
    assertTrue(demo.active);
    assertTrue(demo.speed > 0);
    assertNotNull(engineLoop());
    assertEquals(demo.gain * .27f, engineLoop().gain, .001f);
    assertEquals(store.source(EventSpec.find("engine.loop")),
        engineLoop().source);
    assertFalse(store.enabled("engine"));
    assertFalse(service.diagnostics.live().containsKey("speed"));
    assertTrue(service.diagnostics.engineDemoReading.getBoolean("virtual"));
    store.volume("engine", 0);
    advance(100);
    assertEquals(0, engineLoop().gain, 0);
    service.stopEngineDemo();
    assertFalse(demo.active);
    assertNull(engineLoop());
    assertEquals(0, service.diagnostics.engineDemoReading.getDouble("finalGain"), 0);
    advance(300);
    assertNull(engineLoop());
  }

  @Test public void virtualDemoStopsOnMissingUiHeartbeatAndDoesNotRestartOnLaterTicks() {
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1);
    advance(200);
    assertNotNull(engineLoop());
    advance(700);
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
    service.engineDemoThrottle(1); advance(300);
    assertNull(engineLoop());
  }

  @Test public void virtualDemoYieldsToDrivingManualStopCalibrationAndFocusLoss() {
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1); advance(200);
    store.enable("gear", false);
    gear(4);
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
    assertFalse(service.startEngineDemo(280, 7));
    gear(1);
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1); advance(200);
    android.media.AudioFocusRequest focus = ReflectionHelpers.getField(engineAudio(), "focus");
    AudioManager.OnAudioFocusChangeListener listener = ReflectionHelpers.getField(focus, "mFocusListener");
    listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    advance(100);
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
    assertTrue(service.startEngineDemo(280, 7));
    service.stopAudio();
    assertFalse(service.engineDemo().active);
    assertTrue(service.startEngineDemo(280, 7));
    service.calibrationMode(true);
    assertFalse(service.engineDemo().active);
    assertFalse(service.startEngineDemo(280, 7));
  }

  @Test public void virtualDemoFocusRefusalIsNotRetriedEveryFrame() {
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1); advance(300);
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
    assertTrue(players.isEmpty());
    shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    advance(300);
    assertNull(engineLoop());
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1); advance(100);
    assertNotNull(engineLoop());
  }

  @Test public void virtualDemoCanChangeAudioAndCurvesWithoutTurningAutomaticPlaybackOn() throws Exception {
    assertTrue(service.startEngineDemo(280, 7));
    service.engineDemoThrottle(1); advance(200);
    FakeEngineVoice previous = engineLoop();
    File source = File.createTempFile("demo-source-", ".wav", service.getFilesDir());
    Files.copy(new File("app/src/main/assets/audio/engine.wav").toPath(), source.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(source.getAbsolutePath()), new ShadowMediaPlayer.MediaInfo(2000, 10));
    store.file(EventSpec.find("engine.loop"), source, "演示替换音源.wav");
    service.engineDemoThrottle(1); advance(100);
    assertNotNull(engineLoop());
    assertNotSame(previous, engineLoop());
    assertTrue(previous.closed);
    assertEquals("file:" + source.getAbsolutePath(), engineLoop().source);
    store.engineProfile(new EngineProfile("静音", new float[] {1, 1, 1, 1, 1, 1},
        new float[] {0, 0, 0, 0, 0, 0}, new float[] {0, 0, 0, 0, 0}, 200));
    for (int index = 0; index < 20; index++) { service.engineDemoThrottle(1); advance(100); }
    assertEquals(0, engineLoop().gain, .001f);
    assertFalse(store.enabled("engine"));
    controller.destroy(); controller = null;
    assertFalse(service.engineDemo().active);
    assertNull(engineLoop());
  }

  @Test public void engineNeedsExplicitOptInAndRealDriveGearThenModulatesPitchAndGain() {
    store.enable("gear", false);
    engineSpeed(20); advance(300); engineSpeed(22); advance(50);
    assertNull(engineLoop());
    startEngine();
    FakeEngineVoice player = engineLoop();
    assertTrue(player.rate >= .5f && player.rate <= 2);
    assertTrue(player.gain > 0 && player.gain <= store.volume("engine") / 100f);
    android.media.AudioFocusRequest engineFocus = ReflectionHelpers.getField(engineAudio(), "focus");
    assertEquals(AudioAttributes.USAGE_MEDIA, engineFocus.getAudioAttributes().getUsage());
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    assertEquals(AudioManager.AUDIOFOCUS_GAIN, shadowOf(manager).getLastAudioFocusRequest().audioFocusRequest.getFocusGain());
    assertTrue(service.diagnostics.engineReading().optBoolean("estimating"));
    for (int gear : new int[] {3, 2, 1}) {
      gear(gear);
      advance(100);
      assertNull("N/R/P must not synthesize", engineLoop());
    }
  }

  @Test public void engineStaleAndMissingSpeedStopAndRecoveryReprimes() {
    startEngine();
    advance(2700);
    assertNull(engineLoop());
    engineSpeed(40);
    assertNull(engineLoop());
    advance(300); engineSpeed(40); advance(100);
    assertNotNull(engineLoop());
    accept(new IccVehicleWire.Condition(3, new org.json.JSONObject()).stateSamples(System.currentTimeMillis()).get(1));
    assertNull(engineLoop());
    assertTrue(service.diagnostics.engineReading().isNull("accelerationMps2"));
    engineSpeed(40);
    assertNull(engineLoop());
    advance(300); engineSpeed(40); advance(100);
    assertNotNull(engineLoop());
    ReflectionHelpers.callInstanceMethod(service, "resetSource", ReflectionHelpers.ClassParameter.from(String.class, "ICC"));
    assertNull(engineLoop());
  }

  @Test public void engineStopIsNotUndoneBySpeedChangesAndDisableCancelsPlayback() {
    startEngine();
    service.stopAudio();
    assertNull(engineLoop());
    engineSpeed(24); advance(300); engineSpeed(26); advance(100);
    assertNull(engineLoop());
    store.enable("engine", false); shadowOf(Looper.getMainLooper()).idle();
    store.enable("engine", true); shadowOf(Looper.getMainLooper()).idle();
    engineSpeed(26); advance(300); engineSpeed(26); advance(100);
    assertNotNull(engineLoop());
    store.enable("engine", false); shadowOf(Looper.getMainLooper()).idle();
    assertNull(engineLoop());
  }

  @Test public void engineProfileEditsAndCalibrationRequireNewSamples() {
    startEngine();
    store.engineProfile(EngineProfile.preset(2));
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(engineLoop());
    engineSpeed(26); advance(300); engineSpeed(26); advance(100);
    assertNotNull(engineLoop());
    service.calibrationMode(true);
    advance(100);
    assertNull(engineLoop());
    service.calibrationMode(false);
    engineSpeed(26);
    assertNull(engineLoop());
  }

  @Test public void engineFocusRejectionDoesNotCreatePlayersOrRetryEveryFrame() {
    store.enable("gear", false);
    store.enable("engine", true);
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    shadowOf(Looper.getMainLooper()).idle();
    int count = players.size();
    engineSpeed(20); advance(300); engineSpeed(22); advance(500);
    assertNull(engineLoop());
    assertEquals(count, players.size());
    assertTrue((Boolean) ReflectionHelpers.getField(engineAudio(), "engineBlocked"));
  }

  @Test public void pcmFailureIsLatchedAndOldCallbacksCannotCancelANewVoice() {
    startEngine();
    FakeEngineVoice previous = engineLoop();
    previous.listener.failed(new java.io.IOException("PCM设备拒绝"));
    assertTrue(previous.closed);
    assertNull(engineLoop());
    engineSpeed(24); advance(300); engineSpeed(25); advance(100);
    assertNull(engineLoop());
    store.engineProfile(EngineProfile.preset(1));
    shadowOf(Looper.getMainLooper()).idle();
    engineSpeed(25); advance(300); engineSpeed(26); advance(100);
    FakeEngineVoice next = engineLoop();
    assertNotNull(next);
    previous.listener.finished();
    previous.listener.failed(new java.io.IOException("迟到的旧失败"));
    assertSame(next, engineLoop());
    assertFalse(next.closed);
  }

  @Test public void pcmPreviewVolumeAndCancellationDoNotAffectTheSavedAutoSwitch() {
    service.preview("engine.loop");
    FakeEngineVoice preview = ReflectionHelpers.getField(engineAudio(), "engineVoice");
    assertTrue(preview.preview);
    store.volume("engine", 20);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(.2f, preview.gain, .0001f);
    service.stopAudio();
    assertTrue(preview.closed);
    preview.listener.finished();
    preview.listener.failed(new java.io.IOException("旧试听失败"));
    assertNull(ReflectionHelpers.getField(engineAudio(), "engineVoice"));
    assertFalse(store.enabled("engine"));
  }

  @Test public void enginePreviewIsBoundedEvenWithLongImportedAudioAndDoesNotEnableSimulation() {
    String source = store.source(EventSpec.find("engine.loop"));
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(source.substring(5)),
        new ShadowMediaPlayer.MediaInfo(20000, 10));
    assertFalse(store.enabled("engine"));
    service.preview("engine.loop");
    advance(100);
    assertNotNull(ReflectionHelpers.getField(engineAudio(), "engineVoice"));
    advance(5000);
    assertNull(ReflectionHelpers.getField(engineAudio(), "engineVoice"));
    assertFalse(store.enabled("engine"));
    assertNull(engineLoop());
  }

  @Test public void engineMasterVolumeChangesKeepTheCurveGainInsteadOfJumpingToFullScale() {
    startEngine();
    store.volume("engine", 80);
    shadowOf(Looper.getMainLooper()).idle();
    FakeEngineVoice player = engineLoop();
    float gain = ReflectionHelpers.getField(engineAudio(), "engineGain");
    assertEquals(.8f * gain, player.gain, .0001f);
    store.volume("engine", 0);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, player.gain, 0);
  }

  @Test public void engineAudioReplacementReleasesOldPlayerAndUsesTheNewPrivateFile() throws Exception {
    startEngine();
    FakeEngineVoice old = engineLoop();
    File file = File.createTempFile("engine-replacement-", ".wav", service.getFilesDir());
    Files.copy(new File("app/src/main/assets/audio/engine.wav").toPath(), file.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.getAbsolutePath()),
        new ShadowMediaPlayer.MediaInfo(2000, 10));
    store.file(EventSpec.find("engine.loop"), file, "新声浪.wav");
    shadowOf(Looper.getMainLooper()).idle();
    advance(100);
    assertNotNull(engineLoop());
    assertNotSame(old, engineLoop());
    assertTrue(old.closed);
    assertEquals("新声浪.wav", store.audioName(EventSpec.find("engine.loop")));
  }

  @Test public void engineYieldsToFocusDuckAndRequiresUserReenableToRetry() {
    startEngine();
    android.media.AudioFocusRequest focus = ReflectionHelpers.getField(engineAudio(), "focus");
    AudioManager.OnAudioFocusChangeListener listener = ReflectionHelpers.getField(focus, "mFocusListener");
    listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    advance(100);
    assertNull(engineLoop());
    engineSpeed(24); advance(300); engineSpeed(26); advance(100);
    assertNull(engineLoop());
    store.enable("engine", false); shadowOf(Looper.getMainLooper()).idle();
    store.enable("engine", true); shadowOf(Looper.getMainLooper()).idle();
    engineSpeed(26); advance(300); engineSpeed(26); advance(100);
    assertNotNull(engineLoop());
  }

  @Test public void engineYieldsToShortEffectsAndDoesNotResumeIfDataExpiresDuringThem() {
    startEngine();
    service.preview("door.front_left.close");
    advance(100);
    assertNull(engineLoop());
    assertNotNull(ReflectionHelpers.getField(engineAudio(), "shot"));
    advance(1000); engineSpeed(22);
    advance(1100);
    assertNotNull(engineLoop());
    service.preview("door.front_left.close");
    advance(3000);
    assertNull(engineLoop());
  }

  @Test public void engineIgnoresUnknownGearAndVehicleStopAndServiceDestroy() {
    startEngine();
    accept(new IccVehicleWire.Condition(null, new org.json.JSONObject()).gearSample(System.currentTimeMillis()));
    assertNull(engineLoop());
    engineSpeed(20); advance(300); engineSpeed(20); advance(100);
    assertNotNull(engineLoop());
    engineSpeed(0); advance(100);
    assertNull(engineLoop());
    engineSpeed(20); advance(1000); engineSpeed(20); advance(100);
    controller.destroy();
    controller = null;
    assertNull(engineLoop());
  }

  private IccVehicleProbeTest.Worker iccWorker;
  private IccVehicleProbeTest.Transport iccTransport;

  private void startIcc(int... gears) {
    ReflectionHelpers.setField(service, "iccSourceFactory", (MonitorService.IccSourceFactory) listener -> {
      iccWorker = new IccVehicleProbeTest.Worker();
      iccTransport = new IccVehicleProbeTest.Transport();
      for (int raw : gears) iccTransport.replies.add(IccVehicleProbeTest.gear(raw));
      return new IccVehicleSource(new android.os.Handler(Looper.getMainLooper()), service.diagnostics,
          listener, store.iccIntervalMillis(), iccTransport, iccWorker,
          android.os.SystemClock::elapsedRealtime, System::currentTimeMillis);
    });
    ReflectionHelpers.callInstanceMethod(service, "stopIccSource");
    ReflectionHelpers.callInstanceMethod(service, "restartSources");
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(iccWorker);
    assertNull(ReflectionHelpers.getField(service, "logs"));
  }

  private void iccNext() {
    if (iccWorker.tasks.isEmpty()) advance(250);
    iccWorker.runNext(); shadowOf(Looper.getMainLooper()).idle(); advance(20);
  }

  @Test public void formalIccSourceUsesGearBaselineAndDoesNotTurnHandbrakeIntoAutoHold() throws Exception {
    store.applyEsStatePreset(new String[] {"gear"});
    startIcc(1, 3, 0, 4, 3);
    iccNext(); assertEquals(0, players.size());
    assertEquals("1", service.diagnostics.live().get("gear").value);
    iccNext(); assertEquals(EventSpec.find("gear.d").title, service.diagnostics.playing);
    assertEquals("4", service.diagnostics.live().get("gear").value);
    assertEquals("3", service.diagnostics.stateReadings().get("gear").sample.raw);
    assertEquals(1, players.size());
    iccNext(); assertEquals("不可用", service.diagnostics.live().get("gear").value);
    iccNext(); assertEquals(1, players.size()); assertEquals("2", service.diagnostics.live().get("gear").value);
    assertNull(ReflectionHelpers.getField(ReflectionHelpers.getField(service, "audio"), "loop"));
    iccNext(); assertEquals(2, players.size());
    assertFalse(service.diagnostics.live().containsKey("autohold"));
    assertEquals(0, service.diagnostics.iccVehicleProbeSnapshot().length());
    assertTrue(service.diagnostics.iccVehicleSourceSnapshot().getBoolean("audioConnected"));
  }

  @Test public void iccCalibrationInvalidatesInFlightReplyAndExitRequiresFreshSilentBaseline() {
    startIcc(1, 3, 4, 3);
    iccNext();
    advance(250); iccWorker.runNext();
    service.calibrationMode(true); shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, players.size()); assertFalse(service.diagnostics.live().containsKey("gear"));
    iccNext(); assertEquals(0, players.size());
    service.calibrationMode(false);
    iccNext(); assertEquals(0, players.size());
    assertEquals("4", service.diagnostics.live().get("gear").value);
  }

  @Test public void iccRetriesAfterTimeoutWithoutFallbackAndUnselectedSourcesCannotPlay() throws Exception {
    startIcc(1);
    iccNext();
    accept(Protocol.property(true, 67108874, 0, 0, 2, System.currentTimeMillis()));
    advance(20); assertEquals(0, players.size());
    accept(CarotaLog.decode(CarotaLog.normalize(CarotaLogTest.line(System.currentTimeMillis(), "REVERSE"),
        System.currentTimeMillis())));
    advance(20); assertEquals(0, players.size());
    iccTransport.error = new java.net.SocketTimeoutException("private-route-details");
    iccNext(); assertTrue(service.diagnostics.iccVehicleSourceSnapshot().getBoolean("active"));
    assertFalse(service.diagnostics.live().containsKey("gear")); assertFalse(iccTransport.closed);
    assertNull(ReflectionHelpers.getField(service, "logs"));
    int calls = iccTransport.calls;
    advance(1000); iccWorker.runNext(); shadowOf(Looper.getMainLooper()).idle();
    assertEquals(calls + 1, iccTransport.calls);
    iccTransport.error = null;
    iccTransport.replies.add(IccVehicleProbeTest.gear(3));
    advance(2000); iccWorker.runNext(); shadowOf(Looper.getMainLooper()).idle();
    assertEquals("4", service.diagnostics.live().get("gear").value);
    assertEquals(0, players.size());
    assertFalse(new org.json.JSONObject(service.diagnostics.export(false, store)).getJSONObject("iccVehicleSource")
        .toString().contains("private-route-details"));
  }

  @Test public void manualStopDuringIccRetryWaitDoesNotRestartTheServiceOrSource() throws Exception {
    startIcc();
    iccTransport.error = new java.net.SocketTimeoutException();
    iccNext();
    int calls = iccTransport.calls;
    service.end(); advance(60000);
    assertFalse(service.running()); assertTrue(iccTransport.closed);
    assertTrue(iccWorker.tasks.isEmpty()); assertEquals(calls, iccTransport.calls);
    assertFalse(store.prefs.getBoolean("monitor", true));
    assertFalse(service.diagnostics.iccVehicleSourceSnapshot().getBoolean("active"));
  }

  @Test public void changingIntervalDuringRetryCancelsOldTimerAndIgnoresLateOldReply() {
    startIcc(); iccTransport.error = new java.net.SocketTimeoutException(); iccNext();
    IccVehicleProbeTest.Worker previousWorker = iccWorker;
    IccVehicleProbeTest.Transport previous = iccTransport;
    store.iccIntervalMillis(500); shadowOf(Looper.getMainLooper()).idle();
    assertTrue(previous.closed); assertTrue(previousWorker.shutdown);
    assertNotSame(previous, iccTransport);
    iccTransport.replies.add(IccVehicleProbeTest.gear(3));
    iccNext(); assertEquals(0, players.size());
    advance(10000); assertTrue(previousWorker.tasks.isEmpty());
    assertEquals(1, previous.calls);
  }

  @Test public void iccRetryDoesNotReconnectAuthorizedLogOrClearDoorState() {
    startIcc(1); iccNext();
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis();
    loggedDoor(listener, "1", now, now - 1000); advance(20);
    Object logs = ReflectionHelpers.getField(service, "logs");
    iccTransport.error = new java.net.SocketTimeoutException(); iccNext();
    assertEquals("1", service.diagnostics.live().get("door.front_left").value);
    assertFalse(service.diagnostics.live().containsKey("gear"));
    iccTransport.error = null;
    advance(1000); iccWorker.runNext(); shadowOf(Looper.getMainLooper()).idle();
    assertSame(logs, ReflectionHelpers.getField(service, "logs"));
    assertEquals("1", service.diagnostics.live().get("door.front_left").value);
    assertEquals("1", service.diagnostics.live().get("gear").value);
    assertEquals(0, players.size());
  }

  @Test public void iccLockRecoveryDoesNotPlayMissedTransitionOrReusePriorPGear() {
    externalOutput(false); store.enable("gear", false); startIcc();
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, 1)); iccNext();
    iccTransport.error = new java.net.SocketTimeoutException(); iccNext();
    assertFalse(service.diagnostics.live().containsKey("lock"));
    assertFalse(service.diagnostics.live().containsKey("gear"));
    assertEquals(0, players.size());
    iccTransport.error = null;
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, 2));
    advance(1000); iccNext();
    assertEquals(0, players.size());
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, 1)); iccNext();
    assertEquals(1, players.size());
    assertEquals("车辆解锁", service.diagnostics.playing);
  }

  @Test public void rejectedExteriorFocusIsExportedAndNeverFallsBackOrReplaysStaleLock() throws Exception {
    externalOutput(false);
    AudioManager manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
    shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    protocolLock(1); protocolLock(2); advance(20);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockOutputStatus.contains("系统未授予"));
    org.json.JSONObject report = new org.json.JSONObject(service.diagnostics.export(false, store));
    assertTrue(report.getJSONObject("lockAudioFocus").getString("lastResult").contains("result=0"));
    assertEquals(ExteriorAudio.USAGE, report.getJSONObject("lockAudioFocus").getInt("usage"));
    assertTrue(report.getJSONArray("sessionHistory").toString().contains("result=0"));
    shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    protocolLock(2); advance(20);
    assertEquals(0, players.size());
    protocolLock(1); advance(20);
    assertEquals(1, players.size());
    assertTrue(service.diagnostics.lockFocusResult.contains("result=1"));
  }

  @Test public void iccIntervalChangeReconnectsWithNewSilentBaselineAndProbeCannotDoublePoll() throws Exception {
    startIcc(1, 3); iccNext();
    IccVehicleProbeTest.Transport first = iccTransport;
    store.iccIntervalMillis(500); shadowOf(Looper.getMainLooper()).idle();
    assertTrue(first.closed); assertNotSame(first, iccTransport);
    iccNext(); assertEquals(0, players.size());
    assertEquals(500, service.diagnostics.iccVehicleSourceSnapshot().getInt("minIntervalMillis"));
    service.end(); assertTrue(iccTransport.closed);
    assertFalse(service.diagnostics.live().containsKey("gear"));
    assertFalse(service.diagnostics.iccVehicleSourceSnapshot().getBoolean("active"));
  }

  @Test public void switchingAwayFromIccClosesTransportAndRejectsLateReply() throws Exception {
    startIcc(4); iccWorker.runNext();
    ReflectionHelpers.setField(service, "running", false);
    ReflectionHelpers.callInstanceMethod(service, "stopSources");
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(iccTransport.closed); assertEquals(0, players.size());
    assertFalse(service.diagnostics.live().containsKey("gear"));
  }

  @Test public void iccFreshnessExpiryDropsLiveStateAndRecoveryDoesNotReplayMissedChanges() {
    startIcc(1, 3, 4, 3); iccNext();
    advance(6000);
    assertFalse(service.diagnostics.live().containsKey("gear"));
    iccNext(); assertEquals(0, players.size());
    iccNext(); assertEquals(0, players.size());
    assertEquals("2", service.diagnostics.live().get("gear").value);
    iccNext(); assertEquals(1, players.size());
    assertEquals(EventSpec.find("gear.d").title, service.diagnostics.playing);
  }

  @Test public void iccOtherStatesDisplayButCannotTriggerFromOldLogCalibrationOrEnabledGroups() throws Exception {
    store.enable("charge", true); store.enable("lock", true); store.enable("autohold", true);
    store.calibrateLock("1", "2"); store.calibrateAutoHold("1", "2"); externalOutput(true);
    startIcc();
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 2, 120, 6, 2, 7, 3, 8, 1, 13, 1));
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 6, 1, 7, 4, 8, 3, 13, 2));
    iccNext(); assertEquals(0, players.size());
    assertEquals("unlocked", service.diagnostics.live().get("lock").value);
    assertEquals("120", service.diagnostics.stateReadings().get("speed").sample.value);
    iccNext(); assertEquals(1, players.size());
    assertEquals("locked", service.diagnostics.live().get("lock").value);
    assertEquals("charging", service.diagnostics.live().get("charge").value);
    assertEquals("released", service.diagnostics.live().get("handbrake").value);
    assertEquals("start", service.diagnostics.live().get("power").value);
    assertFalse(service.diagnostics.stateReadings().containsKey("autohold"));
    assertEquals("MISSING", service.diagnostics.stateReadings().get("speed").sample.raw);
    assertNull(ReflectionHelpers.getField(ReflectionHelpers.getField(service, "audio"), "loop"));
  }

  @Test public void iccChargeRequiresConfirmationAndDoesNotRepeatWhenSlowBecomesFast() {
    store.enable("charge", true); store.iccTriggerConfirmed("charge", true);
    startIcc();
    for (int raw : new int[] {1, 3, 4, 2, 0, 3})
      iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 8, raw));
    iccNext(); assertEquals(0, players.size());
    iccNext(); assertEquals(1, players.size()); assertEquals("开始充电", service.diagnostics.playing);
    iccNext(); assertEquals(1, players.size());
    iccNext(); assertEquals(2, players.size()); assertEquals("结束充电", service.diagnostics.playing);
    iccNext(); assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(1)).getState());
    iccNext(); assertEquals(2, players.size());
  }

  @Test public void iccLockUsesIndependentMeaningAndStillRequiresConfirmedExternalOutput() {
    store.enable("lock", true); store.calibrateLock("1", "2");
    store.iccTriggerConfirmed("lock", true); externalOutput(false);
    startIcc();
    for (int raw : new int[] {1, 2, 1, 2}) iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, raw));
    iccNext(); iccNext(); assertEquals(1, players.size());
    externalOutput(true);
    iccNext(); assertEquals(2, players.size()); assertEquals("车辆解锁", service.diagnostics.playing);
    iccNext(); assertEquals(3, players.size()); assertEquals("车辆闭锁", service.diagnostics.playing);
  }

  @Test public void iccConfirmationDropsOlderQueryAndDoesNotPlayCurrentStateOnSave() {
    store.enable("charge", true); startIcc();
    for (int raw : new int[] {1, 3, 3, 1}) iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 8, raw));
    iccNext(); advance(250); iccWorker.runNext();
    store.iccTriggerConfirmed("charge", true); shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, players.size()); assertFalse(service.diagnostics.live().containsKey("charge"));
    iccNext(); assertEquals(0, players.size());
    iccNext(); assertEquals(1, players.size()); assertEquals("结束充电", service.diagnostics.playing);
    store.iccTriggerConfirmed("charge", false);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    assertFalse(service.diagnostics.live().containsKey("charge"));
  }

  @Test public void iccExpiryClearsEveryFieldIncludingDisplayOnlySpeed() {
    startIcc(); iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 2, 0, 6, 2, 7, 3, 8, 1, 13, 1));
    iccNext(); assertEquals(6, service.diagnostics.live().size());
    assertTrue(service.diagnostics.live().containsKey("speed"));
    advance(6000); assertTrue(service.diagnostics.live().isEmpty());
    iccNext(); assertTrue(service.diagnostics.live().isEmpty()); assertEquals(0, players.size());
  }

  @Test
  public void autoHoldRequiresCalibrationAndNeverRunsGenericRulesTwice() throws Exception {
    store.enable("autohold", true);
    store.prefs.edit().putString("autohold.holding", "").putString("autohold.released", "").apply();
    store.addRule(
        new org.json.JSONObject()
            .put("id", "auto-test")
            .put("source", "Mega")
            .put("topic", "property/0x50000022")
            .put("propertyId", Protocol.AUTO_HOLD)
            .put("area", "0x0")
            .put("value", "1")
            .put("event", "gear.p"));
    autoHold(2, 0);
    autoHold(1, 0);
    advance(30);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.autoHoldTriggerStatus.contains("未校准"));
    store.calibrateAutoHold("1", "2");
    autoHold(2, 0);
    autoHold(1, 0);
    advance(20);
    assertEquals(1, players.size());
    assertEquals("AutoHold · 保持", service.diagnostics.playing);
  }

  @Test
  public void autoHoldBaselineAndDuplicatesAreSilentAndReleaseCancelsStalePrepare() {
    store.enable("autohold", true);
    store.calibrateAutoHold("2", "1");
    String path = store.source(EventSpec.find("autohold.holding")).substring(5);
    ShadowMediaPlayer.addMediaInfo(
        DataSource.toDataSource(path), new ShadowMediaPlayer.MediaInfo(2000, 1000));
    autoHold(1, 0);
    autoHold(1, 0);
    assertEquals(0, players.size());
    autoHold(2, 0);
    MediaPlayer hold = players.get(0);
    MediaPlayer.OnPreparedListener stale = shadowOf(hold).getOnPreparedListener();
    autoHold(1, 0);
    advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(hold).getState());
    stale.onPrepared(hold);
    autoHold(1, 0);
    assertEquals(2, players.size());
    assertTrue(players.get(1).isPlaying());
    assertEquals("AutoHold · 释放", service.diagnostics.playing);
  }

  @Test
  public void latestAutoHoldQueuesBehindDoorAndGearClearsItsPendingSound() {
    store.enable("autohold", true);
    store.calibrateAutoHold("1", "2");
    autoHold(2, 0);
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(0);
    autoHold(1, 0);
    autoHold(2, 0);
    assertTrue(door.isPlaying());
    assertEquals(1, players.size());
    advance(2010);
    assertEquals(2, players.size());
    assertEquals("AutoHold · 释放", service.diagnostics.playing);
    gear(4);
    advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(1)).getState());
    assertEquals("D 挡", service.diagnostics.playing);
    autoHold(1, 0); // Waits behind D, then a newer P discards this obsolete request.
    gear(1);
    advance(2200);
    assertEquals(4, players.size());
  }

  @Test
  public void invalidAutoHoldAndRecalibrationCancelOnlyItsOwnSoundAndResetBaseline() {
    store.enable("autohold", true);
    store.calibrateAutoHold("1", "2");
    autoHold(2, 0);
    autoHold(1, 0);
    advance(20);
    MediaPlayer hold = players.get(0);
    autoHold(1, 1);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(hold).getState());
    autoHold(2, 0);
    assertEquals(1, players.size());
    autoHold(1, 0);
    advance(20);
    store.calibrateAutoHold("2", "1");
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(1)).getState());
    autoHold(1, 0);
    assertEquals(2, players.size());
    service.preview("door.front_left.close");
    advance(20);
    MediaPlayer door = players.get(2);
    autoHold(0, 0);
    assertTrue(door.isPlaying());
    assertEquals(3, players.size());
  }

  @Test
  public void autoHoldSourceResetIsLocalAndDisabledGroupDoesNotPlay() {
    store.enable("autohold", true);
    store.calibrateAutoHold("1", "2");
    autoHold(2, 0);
    ReflectionHelpers.callInstanceMethod(
        service, "resetSource", ReflectionHelpers.ClassParameter.from(String.class, "Android"));
    autoHold(1, 0);
    advance(20);
    assertEquals(1, players.size());
    ReflectionHelpers.callInstanceMethod(
        service, "resetSource", ReflectionHelpers.ClassParameter.from(String.class, "日志"));
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    autoHold(2, 0);
    assertEquals(1, players.size());
    store.enable("autohold", false);
    autoHold(1, 0);
    advance(20);
    assertEquals(1, players.size());
  }

  @Test
  public void playingRIsReleasedImmediatelyWhenDArises() {
    gear(2);
    advance(20);
    MediaPlayer r = players.get(0);
    assertTrue(r.isPlaying());
    MediaPlayer.OnCompletionListener stale = shadowOf(r).getOnCompletionListener();
    gear(4);
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
    gear(2);
    MediaPlayer r = players.get(0);
    MediaPlayer.OnPreparedListener stale = shadowOf(r).getOnPreparedListener();
    assertEquals(ShadowMediaPlayer.State.PREPARING, shadowOf(r).getState());
    gear(4);
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
    gear(2);
    MediaPlayer r = players.get(0);
    gear(4);
    MediaPlayer d = players.get(1);
    gear(1);
    advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(d).getState());
    assertEquals(3, players.size());
    assertTrue(players.get(2).isPlaying());
    assertEquals("P 挡", service.diagnostics.playing);
  }

  @Test
  public void missingDAudioPreservesShortVoiceWithoutRestartingReverse() {
    store.file(EventSpec.find("gear.d"), new File("/missing-d-clip.wav"), "missing.wav");
    gear(2);
    advance(20);
    MediaPlayer r = players.get(0);
    gear(4);
    assertTrue(r.isPlaying());
    advance(2100);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(r).getState());
    assertEquals(1, players.size());
    for (MediaPlayer p : players) assertEquals(ShadowMediaPlayer.State.END, shadowOf(p).getState());
  }

  @Test
  public void invalidGearPreservesShortVoiceAndRecoverySetsSilentBaseline() {
    gear(2);
    advance(20);
    MediaPlayer r = players.get(0);
    accept(new Protocol.Sample("ICC/VehicleCondition/Gear", 9, "0x0", 0, "0", System.currentTimeMillis(), "ICC", "gear", null));
    assertTrue(r.isPlaying());
    gear(4);
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
    gear(4);
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
    gear(4);
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
    gear(2);
    advance(20);
    MediaPlayer gearVoice = players.get(0);
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "ICC"));
    assertTrue(gearVoice.isPlaying());
    gear(4);
    advance(20);
    assertEquals(1, players.size());
    assertTrue(gearVoice.isPlaying());
    advance(2100);
    assertEquals(1, players.size());
  }

  @Test
  public void unrelatedSourceResetPreservesNativeLockBaselineAndAutomaticTrigger() {
    externalOutput(true);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    store.iccTriggerConfirmed("lock", true);
    lock(0, 0);
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "日志"));
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "Android"));
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
    assertEquals("车辆闭锁", service.diagnostics.playing);
    assertTrue(service.diagnostics.lines("").contains("自动事件 · lock.lock"));
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

  private LogReader.Listener activeLogListener() {
    store.logsEnabled(true);
    ReflectionHelpers.callInstanceMethod(service, "resetSource",
        ReflectionHelpers.ClassParameter.from(String.class, "日志"));
    ReflectionHelpers.setField(service, "logEventsActive", true);
    return service.logListener(ReflectionHelpers.getField(service, "logGeneration"));
  }

  private void loggedGear(LogReader.Listener listener, String raw, long time, long start) {
    listener.line(String.format(java.util.Locale.ROOT, "%d.%03d 123 456 I MessageProperty: receive DrivingInfo/Gear, value: CarPropertyValue{id=0x400000a, area=0x0, status=0, value=%s}",
        time / 1000, time % 1000, raw), start);
  }

  private void loggedDoor(LogReader.Listener listener, String raw, long time, long start) {
    listener.line(String.format(java.util.Locale.ROOT, "%d.%03d 123 456 I MessageProperty: receive VehicleDoor/Door/FrontLeft, value: CarPropertyValue{id=0x600002c, area=0x0, status=0, value=%s}",
        time / 1000, time % 1000, raw), start);
  }

  private void loggedLock(LogReader.Listener listener, String raw, long time, long start) {
    listener.line(String.format(java.util.Locale.ROOT,
        "%d.%03d 123 456 I MessageProperty: receive VehicleDoor/CentralLock, value: CarPropertyValue{id=0x%x, area=0x0, status=0, value=%s}",
        time / 1000, time % 1000, Protocol.CENTRAL_LOCK, raw), start);
  }

  @Test public void selectedLogLockUsesSavedMappingAndIccCannotOverrideOrDuplicateIt() throws Exception {
    externalOutput(false); store.enable("gear", false);
    store.calibrateLock("1", "0"); store.logLockSelected(true);
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis(), start = now - 1000;
    loggedLock(listener, "0", now - 100, start); advance(20);
    assertEquals(0, players.size());
    protocolLock(2); advance(20);
    assertEquals(0, players.size());
    assertEquals("日志", service.diagnostics.live().get("lock").source);
    assertEquals("unlocked", service.diagnostics.live().get("lock").value);
    loggedLock(listener, "1", now, start); advance(20);
    assertEquals(1, players.size());
    assertEquals("车辆闭锁", service.diagnostics.playing);
    assertTrue(service.diagnostics.lines("").contains("ICC锁对照"));
    org.json.JSONObject report = new org.json.JSONObject(service.diagnostics.export(false, store));
    assertEquals("authorized-log", report.getJSONObject("lockConfiguration").getString("triggerSource"));
    assertFalse(report.getJSONObject("sourcePolicy").getBoolean("automaticFallback"));
  }

  @Test public void selectingLogLockDoesNotEnableLogsOrGuessCalibration() {
    externalOutput(false); store.logLockSelected(true);
    assertFalse(store.logsEnabled()); assertFalse(store.lockTriggerCalibrated());
    protocolLock(1); protocolLock(2); advance(20);
    assertEquals(0, players.size());
    assertFalse(service.diagnostics.live().containsKey("lock"));
    assertTrue(store.lockReadiness().contains("启用并授权"));
  }

  @Test public void defaultIccLockIgnoresLogLockEvenWithOldCalibrationAndAuthorizedLogs() {
    externalOutput(false); store.calibrateLock("1", "0");
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis();
    loggedLock(listener, "0", now - 100, now - 1000); advance(20);
    loggedLock(listener, "1", now, now - 1000); advance(20);
    assertEquals(0, players.size());
    assertFalse(service.diagnostics.stateReadings().containsKey("lock"));
    protocolLock(1); protocolLock(2); advance(20);
    assertEquals(1, players.size());
    assertEquals("ICC", service.diagnostics.live().get("lock").source);
  }

  @Test public void logLockStillRequiresFreshPAndDoesNotReplayChangesAfterReturnToP() {
    externalOutput(false); store.enable("gear", false);
    store.calibrateLock("1", "0"); store.logLockSelected(true);
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis(), start = now - 1000;
    gear(4);
    loggedLock(listener, "0", now - 500, start); advance(20);
    loggedLock(listener, "1", now - 400, start); advance(20);
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockTriggerStatus.contains("新鲜P挡=false"));
    gear(1);
    loggedLock(listener, "1", now - 300, start); advance(20);
    assertEquals(0, players.size());
    loggedLock(listener, "0", now - 200, start); advance(20);
    assertEquals(1, players.size());
    java.util.Map<String, ?> states = ReflectionHelpers.getField(service, "states");
    ReflectionHelpers.setField(states.get("gear"), "at", System.currentTimeMillis() - 6000);
    now = System.currentTimeMillis();
    loggedLock(listener, "1", now, start); advance(20);
    assertEquals(1, players.size());
  }

  @Test public void switchingLockSourcesCancelsPendingAudioAndEstablishesNewSilentBaseline() {
    externalOutput(false); store.calibrateLock("1", "0");
    String path = store.source(EventSpec.find("lock.lock")).substring(5);
    ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(path), new ShadowMediaPlayer.MediaInfo(2000, 1000));
    protocolLock(1); protocolLock(2);
    assertEquals(1, players.size());
    MediaPlayer oldShot = players.get(0);
    store.logLockSelected(true);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(oldShot).getState());
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis();
    loggedLock(listener, "1", now, now - 1000); advance(1200);
    assertEquals(1, players.size());
    store.logLockSelected(false);
    protocolLock(1); advance(20);
    assertEquals(1, players.size());
  }

  @Test public void missingGearAtOutsidePowerDownIsRecordedAndNeverUsesCachedP() {
    externalOutput(false); store.enable("gear", false); startIcc();
    iccTransport.replies.add(IccVehicleStateTest.reply(9, 1, 13, 1));
    iccTransport.replies.add(IccVehicleStateTest.reply(13, 2));
    iccNext(); iccNext();
    assertEquals(0, players.size());
    assertTrue(service.diagnostics.lockTriggerStatus.contains("新鲜P挡=false"));
    assertTrue(service.diagnostics.lines("").contains("P挡依据失效"));
  }

  @Test public void confirmedAutoHoldDefaultsReleaseOneHoldTwoWithoutImplicitEnablement() {
    assertFalse(store.enabled("autohold"));
    store.enable("autohold", true);
    autoHold(1, 0); autoHold(1, 0); advance(20);
    assertEquals(0, players.size());
    autoHold(2, 0); autoHold(2, 0); advance(20);
    assertEquals(1, players.size());
    assertEquals("AutoHold · 保持", service.diagnostics.playing);
    autoHold(1, 0); advance(20);
    assertEquals(2, players.size());
    assertEquals("AutoHold · 释放", service.diagnostics.playing);
  }

  @Test public void authorizedDoorLogUsesSilentBaselineThenPlaysCloseWhileIccStillRuns() {
    store.enable("door", true);
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis(), start = now - 1000;
    loggedDoor(listener, "1", now - 100, start); advance(20);
    assertEquals(0, players.size());
    loggedDoor(listener, "0", now, start); advance(20);
    assertEquals(1, players.size());
    assertEquals(EventSpec.find("door.front_left.close").title, service.diagnostics.playing);
    assertEquals("日志", service.diagnostics.live().get("door.front_left").source);
    assertEquals("ICC", service.diagnostics.live().get("gear").source);
    assertFalse(iccTransport.closed);
    loggedDoor(listener, "1", now, start); advance(20);
    assertEquals(1, players.size());
  }

  @Test public void authorizedDoorReconnectAndCalibrationRestoreSilentBaseline() {
    store.enable("door", true);
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis(), start = now - 1000;
    loggedDoor(listener, "1", now - 500, start); advance(20);
    listener.reset(); advance(20);
    loggedDoor(listener, "0", now - 400, start); advance(20);
    assertEquals(0, players.size());
    service.calibrationMode(true);
    loggedDoor(listener, "1", now - 300, start); advance(20);
    loggedDoor(listener, "0", now - 200, start); advance(20);
    assertEquals(0, players.size());
    service.calibrationMode(false);
    loggedDoor(listener, "1", now - 100, start); advance(20);
    assertEquals(0, players.size());
    loggedDoor(listener, "0", now, start); advance(20);
    assertEquals(1, players.size());
  }

  @Test public void doorQueueOverflowDropsOldWorkAndDoesNotReplaySounds() throws Exception {
    store.enable("door", true);
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis(), start = now - 1000;
    loggedDoor(listener, "1", now - 500, start); advance(20);
    for (int index = 0; index < 160; index++) loggedDoor(listener, index % 2 == 0 ? "0" : "1", now, start);
    advance(20);
    loggedDoor(listener, "0", now + 1, start); advance(20);
    assertEquals(0, players.size());
    org.json.JSONObject report = new org.json.JSONObject(service.diagnostics.export(false, store));
    assertTrue(report.getLong("logQueueDropped") > 0);
  }

  @Test public void disabledLogRouteRejectsEventsAndOldCustomRulesNeverPlay() throws Exception {
    store.enable("door", true);
    store.addRule(new org.json.JSONObject().put("id", "legacy-rule").put("source", "日志")
        .put("topic", "VehicleDoor/Door/FrontLeft").put("propertyId", 100663340).put("area", "0x0")
        .put("value", "0").put("event", "gear.d"));
    LogReader.Listener listener = activeLogListener();
    long now = System.currentTimeMillis();
    loggedDoor(listener, "1", now - 100, now - 1000); advance(20);
    store.logsEnabled(false); advance(20);
    loggedDoor(listener, "0", now, now - 1000); advance(20);
    assertEquals(0, players.size());
    assertFalse(service.diagnostics.live().containsKey("door.front_left"));
    assertFalse(iccTransport.closed);
  }

  @Test
  public void centralLockNeverGuessesMeaningOrUsesGenericRules() throws Exception {
    externalOutput(true);
    store.enable("lock", true);
    store.addRule(new org.json.JSONObject().put("id", "old-rule").put("source", "Mega")
        .put("topic", "property/0x67000096").put("propertyId", Protocol.CENTRAL_LOCK)
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
    store.iccTriggerConfirmed("lock", true);
    lock(0, 0);
    assertEquals(0, players.size());
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
    PlayerShadow player = Shadow.extract(players.get(0));
    assertEquals(external.getId(), player.preferred.getId());
    assertEquals(1f, player.volume, .001f);
    assertEquals("车辆闭锁", service.diagnostics.playing);
    lock(1, 0);
    assertEquals(1, players.size());
    lock(0, 0);
    advance(20);
    assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.get(0)).getState());
    assertEquals("车辆解锁", service.diagnostics.playing);
  }

  @Test public void defaultLockMappingAndExternalBusNeedNoSecondConfirmation() {
    externalOutput(false);
    assertTrue(store.iccTriggerConfirmed("lock"));
    assertTrue(store.enabled("lock"));
    accept(new Protocol.Sample("ICC/VehicleCondition/Lock", 13, "0x0", 0, "1", System.currentTimeMillis(), "ICC", "lock", "unlocked"));
    accept(new Protocol.Sample("ICC/VehicleCondition/Lock", 13, "0x0", 0, "2", System.currentTimeMillis(), "ICC", "lock", "locked"));
    advance(20);
    assertEquals(1, players.size());
    assertEquals("车辆闭锁", service.diagnostics.playing);
  }

  @Test
  public void automaticLockAndExplicitPreviewUseFactoryBusWithoutConfirmation() {
    externalOutput(false);
    store.enable("lock", true);
    store.calibrateLock("1", "0");
    store.iccTriggerConfirmed("lock", true);
    lock(0, 0);
    lock(1, 0);
    advance(20);
    assertEquals(1, players.size());
    assertTrue(((PlayerShadow) Shadow.extract(players.get(0))).volume > 0);
    service.preview("lock.lock");
    advance(20);
    assertEquals(2, players.size());
    assertTrue(((PlayerShadow) Shadow.extract(players.get(1))).volume > 0);
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
    store.iccTriggerConfirmed("lock", true);
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
