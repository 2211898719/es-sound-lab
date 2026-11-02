package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Locale;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class CarotaLogTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  static String line(long time, String gear) {
    return "[INFO] " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new java.util.Date(time))
        + " in ConditionHandlerImpl.kt(61) >Vehicle condition data: PowerState = PWR_ON; GearState = "
        + gear + "; ChargeState = CHG_OFF; Speed = 0; Handbrake = HANDBRAKE_OFF; Lock = LOCK_OFF";
  }

  @Test public void parsesOnlyObservedNamedGearsAndRetainsActualSourceTime() {
    long now = System.currentTimeMillis();
    String[] raw = {"PARKING", "REVERSE", "DRIVE"};
    String[] expected = {"1", "2", "4"};
    for (int index = 0; index < raw.length; index++) {
      Protocol.Sample sample = CarotaLog.decode(CarotaLog.normalize(line(now, raw[index]), now + 1000));
      assertEquals(now, sample.time); assertEquals(expected[index], sample.value);
      assertEquals(raw[index], sample.raw); assertTrue(CarotaLog.gear(sample));
    }
  }

  @Test public void unsupportedMissingAndDuplicateGearInvalidateInsteadOfKeepingP() {
    long now = System.currentTimeMillis();
    for (String raw : new String[] {"UNKNOWN", "NEUTRAL", "MANUAL", "INVALID"})
      assertNull(CarotaLog.decode(CarotaLog.normalize(line(now, raw), now)).value);
    String missing = line(now, "PARKING").replace("GearState = PARKING;", "");
    assertEquals("MISSING", CarotaLog.decode(CarotaLog.normalize(missing, now)).raw);
    String duplicate = line(now, "PARKING") + "; GearState = DRIVE";
    Protocol.Sample sample = CarotaLog.decode(CarotaLog.normalize(duplicate, now));
    assertEquals("DUPLICATE", sample.raw); assertNull(sample.value);
  }

  @Test public void otaInfoWrongClassWrongFormatAndBadDateAreNotVehicleEvents() {
    long now = System.currentTimeMillis();
    assertNull(CarotaLog.normalize("[WARN] 2026-10-31 19:20:50.803 in AgentHelper.kt(612) >OtaVehicleInfo GearStatus:0,DrivenSpeed:0.0", now));
    assertNull(CarotaLog.normalize(line(now, "DRIVE").replace("ConditionHandlerImpl.kt", "Other.kt"), now));
    assertNull(CarotaLog.normalize(line(now, "DRIVE").replaceFirst("\\d{4}-\\d{2}-\\d{2}", "2026-02-30"), now));
    assertNull(CarotaLog.normalize("x".repeat(8193), now));
    assertNull(CarotaLog.decode(null)); assertFalse(CarotaLog.relevant(null));
  }

  @Test public void numericEsCalibrationNeverReinterpretsCarotaNamedValues() {
    Store store = new Store(RuntimeEnvironment.getApplication());
    store.prefs.edit().clear().commit();
    store.applyEsStatePreset(new String[] {"gear"});
    long now = System.currentTimeMillis();
    Protocol.Sample sample = CarotaLog.decode(CarotaLog.normalize(line(now, "REVERSE"), now));
    assertSame(sample, store.calibratedLogSample(sample));
    assertFalse(Store.knownLogIdentity(sample));
    store.calibrateState(StateCalibrationTest.sample("DrivingInfo/Gear", 67108874L, "1"),
        new String[] {"0", "1", "2", "3", "4"}, false);
    assertSame(sample, store.calibratedLogSample(sample));
  }

  private File shard(File directory, String stamp) throws Exception {
    File file = new File(directory, "com.carota.chery.v4[2618T" + stamp + "].log");
    Files.writeString(file.toPath(), "historical data\n");
    return file;
  }

  @Test public void selectsNewestFilenameTimestampNotOldFileMtimeOrUnrelatedFiles() throws Exception {
    File root = temporary.newFolder("carota");
    File old = shard(root, "2026-1031-1559-55");
    File newest = shard(root, "2026-1101-0001-00");
    assertTrue(old.setLastModified(System.currentTimeMillis() + 1000000));
    assertTrue(newest.setLastModified(1));
    Files.writeString(new File(root, "unrelated.log").toPath(), "GearState = DRIVE");
    Files.writeString(new File(root, "com.carota.chery.v4[2618T2027-0101-0000-00].log.zip").toPath(), "archive");
    assertEquals(newest, CarotaLogAccess.latest(root));
  }

  @Test public void searchesOnlyRootAndExactPackageSubdirectoryAndTracksNewShard() throws Exception {
    File root = temporary.newFolder("carota");
    File old = shard(root, "2026-1031-1559-55");
    File nested = new File(root, CarotaLogAccess.PACKAGE); assertTrue(nested.mkdir());
    File newest = shard(nested, "2026-1031-1800-00");
    File unrelated = new File(root, "other"); assertTrue(unrelated.mkdir());
    shard(unrelated, "2027-0101-0000-00");
    assertEquals(newest, CarotaLogAccess.latest(root));
    File next = shard(nested, "2026-1101-0000-00");
    assertEquals(next, CarotaLogAccess.latest(root));
    assertTrue(old.exists());
  }

  @Test public void invalidNamesAndSymlinkFilesAreNeverSelected() throws Exception {
    File root = temporary.newFolder("carota");
    File valid = shard(root, "2026-1031-1559-55");
    shard(root, "2027-0230-1200-00");
    Path target = Files.writeString(temporary.newFile("outside").toPath(), "outside");
    Files.createSymbolicLink(new File(root, "com.carota.chery.v4[2618T2027-0101-0000-00].log").toPath(), target);
    assertEquals(valid, CarotaLogAccess.latest(root));
    assertEquals(Long.MIN_VALUE, CarotaLogAccess.fileTime("../com.carota.chery.v4[2618T2027-0101-0000-00].log"));
  }

  @Test public void activeShardNeverMovesBackToOldFileWhenArchiveMtimeChangesOrCurrentDisappears() throws Exception {
    File root = temporary.newFolder("carota");
    File old = shard(root, "2026-1031-1559-55");
    File active = shard(root, "2026-1031-1800-00");
    CarotaLogAccess access = new CarotaLogAccess(root);
    assertEquals(active, access.select());
    assertTrue(old.setLastModified(System.currentTimeMillis() + 1000000));
    assertEquals(active, access.select());
    Files.delete(active.toPath());
    try { access.select(); fail(); } catch (CarotaLogAccess.WaitingForShard expected) {}
    File next = shard(root, "2026-1031-2000-00");
    assertEquals(next, access.select());
    assertTrue(access.path().endsWith(next.getName()));
  }

  @Test public void emptyDirectoryWaitsButMissingDirectoryFailsExplicitly() throws Exception {
    File root = temporary.newFolder("empty");
    try { CarotaLogAccess.latest(root); fail(); }
    catch (CarotaLogAccess.WaitingForShard expected) {}
    try { CarotaLogAccess.latest(new File(root, "missing")); fail(); }
    catch (CarotaLogAccess.WaitingForShard wrong) { fail(); }
    catch (IOException expected) {}
  }

  @Test public void directorySymlinkIsRejectedAndTooManyEntriesAreBounded() throws Exception {
    File actual = temporary.newFolder("real");
    shard(actual, "2026-1031-1559-55");
    File link = new File(temporary.getRoot(), "linked");
    Files.createSymbolicLink(link.toPath(), actual.toPath());
    try { CarotaLogAccess.latest(link); fail(); } catch (IOException expected) {}
    File crowded = temporary.newFolder("crowded");
    for (int index = 0; index < 4097; index++) Files.createFile(new File(crowded, "item" + index).toPath());
    try { CarotaLogAccess.latest(crowded); fail(); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("4096")); }
  }
}
