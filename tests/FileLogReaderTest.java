package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class FileLogReaderTest {
  private static final class Node {
    final String identity;
    byte[] content;
    Node(String identity, String text) {
      this.identity = identity;
      content = text.getBytes(StandardCharsets.UTF_8);
    }
  }

  private static final class FakeAccess implements FileLogReader.Access {
    Node node = new Node("1:1", "historical MessageProperty\n");
    Exception statError, openError;
    int opens, closes;
    boolean changeOnOpen;
    boolean carota;
    public String backend() { return carota ? "carota" : "file"; }
    public String path() { return carota ? "/storage/emulated/0/carota/" + node.identity + ".log" : FileLogReader.PATH; }
    public boolean relevant(String line) { return carota ? CarotaLog.relevant(line) : Protocol.relevant(line); }
    public String identity() throws Exception {
      if (statError != null) throw statError;
      return node.identity;
    }
    public JSONArray evidence() { return new JSONArray().put(FileLogReader.PATH); }
    public FileLogReader.Handle open() throws Exception {
      opens++;
      if (openError != null) throw openError;
      Node owner = node;
      if (changeOnOpen) node = new Node("1:2", "new MessageProperty\n");
      return new FileLogReader.Handle() {
        long position;
        boolean closed;
        public long size() { return owner.content.length; }
        public void seek(long offset) { position = offset; }
        public int read(byte[] bytes, int count) throws Exception {
          if (closed) throw new IOException("closed");
          int received = (int) Math.min(count, Math.max(0, owner.content.length - position));
          if (received == 0) return -1;
          System.arraycopy(owner.content, (int) position, bytes, 0, received);
          position += received;
          return received;
        }
        public void close() { if (!closed) { closed = true; closes++; } }
      };
    }
    void append(String text) { append(text.getBytes(StandardCharsets.UTF_8)); }
    void append(byte[] bytes) {
      int offset = node.content.length;
      node.content = Arrays.copyOf(node.content, offset + bytes.length);
      System.arraycopy(bytes, 0, node.content, offset, bytes.length);
    }
  }

  private Diagnostics diagnostics;
  private FakeAccess access;
  private FileLogReader reader;
  private final List<String> lines = new ArrayList<>();
  private final List<Long> starts = new ArrayList<>();
  private int resets;

  @Before public void setup() {
    RuntimeEnvironment.getApplication().getSharedPreferences("file_log", 0).edit().clear().commit();
    diagnostics = new Diagnostics();
    access = new FakeAccess();
    reader = new FileLogReader(diagnostics, new LogReader.Listener() {
      public void line(String line, long start) { lines.add(line); starts.add(start); }
      public void reset() { resets++; }
    }, access);
  }

  @After public void cleanup() { reader.stop(); }

  private JSONObject report() { return diagnostics.fileLogSnapshot(); }

  @Test public void startsAtEofAndReceivesOnlyNewCompleteRelevantLines() throws Exception {
    reader.poll(1000);
    assertTrue(lines.isEmpty()); assertEquals(1, resets);
    access.append("not relevant\nMessageProperty first"); reader.poll(1250);
    assertTrue(lines.isEmpty());
    access.append(" complete\nMessageProperty second\r\n"); reader.poll(1500);
    assertEquals(Arrays.asList("MessageProperty first complete", "MessageProperty second"), lines);
    assertEquals(Arrays.asList(1000L, 1000L), starts);
    assertEquals(2, report().getInt("relevantLines"));
    assertTrue(report().getBoolean("opened")); assertEquals(1, access.opens);
  }

  @Test public void existingPartialLineIsNotReplayed() {
    access.node = new Node("1:1", "MessageProperty historical fragment"); reader.poll(1000);
    access.append(" appended tail\nMessageProperty fresh\n"); reader.poll(1250);
    assertEquals(Arrays.asList("MessageProperty fresh"), lines);
  }

  @Test public void readBatchMetricsMeasureVisibleAppendsNotIdlePollsAndResetAtRotation() throws Exception {
    access.carota = true; reader.poll(1000);
    long now = System.currentTimeMillis();
    String first = CarotaLogTest.line(now, "PARKING") + "\n" + CarotaLogTest.line(now + 1, "REVERSE") + "\n";
    access.append(first); reader.poll(1250);
    assertEquals(1, report().getInt("readBatches"));
    assertEquals(first.getBytes(StandardCharsets.UTF_8).length, report().getLong("lastBatchBytes"));
    assertEquals(2, report().getInt("lastBatchRelevantLines"));
    reader.poll(3000); assertEquals(1, report().getInt("readBatches"));
    access.append(CarotaLogTest.line(now + 6000, "DRIVE") + "\n"); reader.poll(7250);
    assertEquals(2, report().getInt("readBatches")); assertEquals(6000, report().getLong("lastGrowthGapMillis"));
    assertEquals(1, report().getInt("lastBatchRelevantLines"));
    access.node = new Node("1:2", "historical data\n"); reader.poll(7500);
    assertEquals(0, report().getLong("lastGrowthAt"));
    assertEquals(0, report().getLong("lastBatchBytes")); assertEquals(0, report().getLong("lastGrowthGapMillis"));
    assertEquals(3, lines.size());
  }

  @Test public void utf8SplitAcrossReadsAndPartialLinesSurvive() {
    reader.poll(1000);
    byte[] bytes = "MessageProperty 中文新事件\n".getBytes(StandardCharsets.UTF_8);
    access.append(Arrays.copyOfRange(bytes, 0, 17)); reader.poll(1250);
    assertTrue(lines.isEmpty());
    access.append(Arrays.copyOfRange(bytes, 17, bytes.length)); reader.poll(1500);
    assertEquals(Arrays.asList("MessageProperty 中文新事件"), lines);
  }

  @Test public void rotationReopensAtEofAndResetsBaseline() throws Exception {
    reader.poll(1000); access.append("MessageProperty old partial"); reader.poll(1250);
    access.node = new Node("1:2", "MessageProperty rotated historical\n"); reader.poll(1500);
    assertTrue(lines.isEmpty()); assertEquals(2, resets); assertEquals(1, access.closes);
    access.append("MessageProperty current\n"); reader.poll(1750);
    assertEquals(Arrays.asList("MessageProperty current"), lines);
    assertEquals(Long.valueOf(1500), starts.get(0)); assertEquals(1, report().getInt("rotations"));
  }

  @Test public void truncationDropsBacklogAndPartialLine() throws Exception {
    reader.poll(1000); access.append("MessageProperty partial"); reader.poll(1250);
    access.node.content = "new\n".getBytes(StandardCharsets.UTF_8); reader.poll(1500);
    access.append("MessageProperty after truncate\n"); reader.poll(1750);
    assertEquals(Arrays.asList("MessageProperty after truncate"), lines);
    assertEquals(1, report().getInt("skipped")); assertEquals(2, resets);
  }

  @Test public void copyTruncateRewriteLargerThanPositionDoesNotReplay() throws Exception {
    reader.poll(1000);
    access.node.content = "MessageProperty replacement historical much longer than old file\n".getBytes(StandardCharsets.UTF_8);
    reader.poll(1250);
    assertTrue(lines.isEmpty()); assertEquals("rewritten", report().getString("phase"));
    access.append("MessageProperty current\n"); reader.poll(1500);
    assertEquals(Arrays.asList("MessageProperty current"), lines);
  }

  @Test public void excessiveBacklogIsSkippedWithoutReplay() throws Exception {
    reader.poll(1000);
    access.append("MessageProperty " + "x".repeat(FileLogReader.MAX_BACKLOG) + "\n"); reader.poll(1250);
    assertTrue(lines.isEmpty()); assertEquals(1, report().getInt("skipped"));
    access.append("MessageProperty fresh\n"); reader.poll(1500);
    assertEquals(Arrays.asList("MessageProperty fresh"), lines);
  }

  @Test public void overlongLineIsDroppedAndFollowingLineStillWorks() throws Exception {
    reader.poll(1000); access.append("MessageProperty " + "x".repeat(FileLogReader.MAX_LINE) + "\nMessageProperty valid\n");
    reader.poll(1250);
    assertEquals(Arrays.asList("MessageProperty valid"), lines); assertEquals(1, report().getInt("skipped"));
    assertEquals(2, resets);
  }

  @Test public void errnoDistinguishesMissingFromOpenDeniedWithoutRetryOrFallback() throws Exception {
    access.openError = new ErrnoException("open", OsConstants.EACCES);
    reader.poll(1000); reader.poll(1250);
    assertEquals(1, access.opens); assertEquals("failed", report().getString("phase"));
    assertTrue(report().getString("error").contains("EACCES"));
    assertTrue(report().getBoolean("openAttempted")); assertFalse(report().getBoolean("opened"));
    assertTrue(lines.isEmpty()); assertTrue(diagnostics.logStatus.contains("不") || diagnostics.logStatus.contains("不可读"));
  }

  @Test public void missingPathIsNotReportedAsPermissionDenied() throws Exception {
    access.statError = new ErrnoException("lstat", OsConstants.ENOENT);
    reader.poll(1000);
    assertEquals(0, access.opens); assertFalse(report().getBoolean("openAttempted"));
    assertTrue(report().getString("error").contains("ENOENT"));
  }

  @Test public void fileIdentityRaceFailsClosedAndReleasesDescriptor() throws Exception {
    access.changeOnOpen = true; reader.poll(1000);
    assertEquals("failed", report().getString("phase")); assertEquals(1, access.closes);
    assertFalse(report().getString("error").isEmpty()); assertTrue(lines.isEmpty());
  }

  @Test public void stopClosesExactlyOnceAndRejectsLateBytes() {
    reader.poll(1000); reader.stop(); reader.stop();
    access.append("MessageProperty late\n"); reader.poll(1250);
    assertEquals(1, access.closes); assertTrue(lines.isEmpty()); assertEquals(2, resets);
  }

  @Test public void failedReportAndEvidenceRemainExportableAndRestorable() throws Exception {
    diagnostics.attach(RuntimeEnvironment.getApplication());
    access.openError = new ErrnoException("open", OsConstants.EACCES); reader.poll(1000);
    JSONObject exported = new JSONObject(diagnostics.export(false, new Store(RuntimeEnvironment.getApplication())));
    assertEquals("failed", exported.getJSONObject("fileLog").getString("phase"));
    assertEquals(1, exported.getJSONObject("fileLog").getJSONArray("metadata").length());
    Diagnostics restored = new Diagnostics(); restored.attach(RuntimeEnvironment.getApplication());
    assertEquals("failed", restored.fileLogSnapshot().getString("phase"));
  }

  @Test public void carotaShardSwitchStartsAtEofAndNeverIncludesOtherOtaAccountLogs() throws Exception {
    access.carota = true;
    reader.poll(1000);
    access.append("OtaVehicleInfo GearStatus:0 VinNumber:PRIVATE\n" + CarotaLogTest.line(1100, "REVERSE") + "\n");
    reader.poll(1250);
    assertEquals(1, lines.size()); assertFalse(lines.get(0).contains("PRIVATE"));
    access.node = new Node("shard2", CarotaLogTest.line(1300, "DRIVE") + "\n");
    reader.poll(1500); assertEquals(1, lines.size());
    access.append(CarotaLogTest.line(1600, "PARKING") + "\n"); reader.poll(1750);
    assertEquals(2, lines.size()); assertEquals(Long.valueOf(1500), starts.get(1));
    assertEquals("carota", report().getString("backend"));
    assertTrue(report().getString("path").contains("shard2"));
    assertTrue(diagnostics.fileLogSummary().contains("shard2"));
    assertEquals(1, report().getInt("rotations"));
  }

  @Test public void carotaRotationGapWaitsWithoutReplayingOrRepeatedReset() throws Exception {
    access.carota = true;
    reader.poll(1000);
    access.statError = new CarotaLogAccess.WaitingForShard("waiting for next shard");
    reader.poll(1250); reader.poll(1500);
    assertEquals("waiting-shard", report().getString("phase"));
    assertEquals(1, access.closes); assertEquals(2, resets);
    access.statError = null;
    access.node = new Node("next", CarotaLogTest.line(1000, "DRIVE") + "\n");
    reader.poll(1750); assertTrue(lines.isEmpty());
    access.append(CarotaLogTest.line(1800, "REVERSE") + "\n"); reader.poll(2000);
    assertEquals(1, lines.size()); assertEquals(Long.valueOf(1750), starts.get(0));
    assertEquals(1, report().getInt("rotations"));
    assertEquals("", report().getString("error"));
  }

  @Test public void carotaDisappearingFileWaitsButPermissionDeniedDoesNotRetry() throws Exception {
    access.carota = true;
    reader.poll(1000);
    access.statError = new ErrnoException("lstat", OsConstants.ENOENT);
    reader.poll(1250);
    assertEquals("waiting-shard", report().getString("phase"));
    access.statError = null;
    access.node = new Node("new", "history\n");
    reader.poll(1500);
    access.statError = new ErrnoException("lstat", OsConstants.EACCES);
    reader.poll(1750); reader.poll(2000);
    assertEquals("failed", report().getString("phase"));
    assertTrue(report().getString("error").contains("EACCES"));
    assertEquals(2, access.opens);
  }

  @Test public void restoredRunningReportIsMarkedInterruptedNotLive() throws Exception {
    diagnostics.attach(RuntimeEnvironment.getApplication()); reader.poll(1000);
    Diagnostics restored = new Diagnostics(); restored.attach(RuntimeEnvironment.getApplication());
    assertEquals("previous-session-interrupted", restored.fileLogSnapshot().getString("phase"));
  }
}
