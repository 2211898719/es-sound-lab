package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import mega.car.MegaCar;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class AdbExtrasTest {
  private Application app;
  private static KeyPair key;

  @Before
  public void setup() throws Exception {
    app = RuntimeEnvironment.getApplication();
    app.getApplicationInfo().sourceDir = "/data/app/test/base.apk";
    cleanup();
    if (key == null) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      key = generator.generateKeyPair();
    }
  }

  @After
  public void cleanup() throws Exception {
    for (File file : app.getFilesDir().listFiles())
      if (file.getName().startsWith("adb-apks-") || file.equals(AdbDiagnostics.reportFile(app)))
        Files.deleteIfExists(file.toPath());
    MegaCar.nextManager = null;
    MegaCar.latest = null;
  }

  private static final class FakeSocket extends Socket {
    final InputStream incoming;
    final ByteArrayOutputStream outgoing = new ByteArrayOutputStream();
    boolean closed;

    FakeSocket(byte[] data) {
      this(new ByteArrayInputStream(data));
    }

    FakeSocket(InputStream data) {
      incoming = data;
    }

    @Override public void connect(SocketAddress endpoint, int timeout) {}
    @Override public void setSoTimeout(int timeout) {}
    @Override public InputStream getInputStream() { return incoming; }
    @Override public OutputStream getOutputStream() { return outgoing; }
    @Override public void close() { closed = true; }
  }

  private static int code(String name) {
    return ByteBuffer.wrap(name.getBytes(StandardCharsets.US_ASCII))
        .order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static byte[] frame(String name, byte[] payload) throws Exception {
    return joined(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(code(name)).putInt(payload.length).array(), payload);
  }

  private static byte[] packet(String name, int remote, int local, byte[] payload) throws Exception {
    int checksum = 0;
    for (byte value : payload) checksum += value & 255;
    return joined(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(code(name))
        .putInt(remote).putInt(local).putInt(payload.length).putInt(checksum)
        .putInt(~code(name)).array(), payload);
  }

  private static byte[] packet(String name, int remote, int local, String text) throws Exception {
    return packet(name, remote, local, text.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] joined(byte[]... arrays) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (byte[] data : arrays) output.write(data);
    return output.toByteArray();
  }

  private static byte[] connected() throws Exception {
    return packet("CNXN", 0x01000000, 4096, "device::\0");
  }

  private static byte[] shell(int local, String output) throws Exception {
    return joined(packet("OKAY", local + 100, local, ""),
        packet("WRTE", local + 100, local, output), packet("CLSE", local + 100, local, ""),
        packet("CLSE", 0, local, ""));
  }

  private static byte[] pull(int local, byte[] data) throws Exception {
    return joined(packet("OKAY", local + 100, local, ""),
        packet("OKAY", local + 100, local, ""),
        packet("WRTE", local + 100, local, joined(frame("DATA", data), frame("DONE", new byte[0]))),
        packet("CLSE", local + 100, local, ""));
  }

  @Test
  public void binarySyncPreservesBytesAcrossTransportFragmentsAndNextShell() throws Exception {
    byte[] data = {0, (byte) 255, 13, 10, 0, 80, 75, (byte) 128};
    byte[] wire = joined(frame("DATA", data), frame("DONE", new byte[0]));
    FakeSocket socket = new FakeSocket(joined(connected(), packet("OKAY", 7, 1, ""),
        packet("OKAY", 7, 1, ""),
        packet("WRTE", 7, 1, Arrays.copyOfRange(wire, 0, 3)),
        packet("WRTE", 7, 1, Arrays.copyOfRange(wire, 3, 11)),
        packet("WRTE", 7, 1, Arrays.copyOfRange(wire, 11, wire.length)),
        packet("CLSE", 7, 1, ""), packet("CLSE", 0, 1, ""), shell(2, "Enforcing")));
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      assertEquals(data.length, client.pull("/system/test.apk", output, 1024, message -> {}));
      assertArrayEquals(data, output.toByteArray());
      assertEquals("Enforcing", client.shell("getenforce", 1024).text);
      assertTrue(new String(socket.outgoing.toByteArray(), StandardCharsets.ISO_8859_1).contains("RECV"));
    }
  }

  @Test
  public void syncStreamsMoreThan256MiBWithoutBufferingEntireFile() throws Exception {
    byte[] block = new byte[65536];
    for (int index = 0; index < block.length; index++) block[index] = (byte) (index * 31);
    byte[] dataPacket = packet("WRTE", 7, 1, frame("DATA", block));
    List<InputStream> segments = new ArrayList<>();
    segments.add(new ByteArrayInputStream(joined(connected(), packet("OKAY", 7, 1, ""),
        packet("OKAY", 7, 1, ""))));
    CRC32 expectedCrc = new CRC32();
    long expectedBytes = 257L * 1024 * 1024;
    for (long offset = 0; offset < expectedBytes; offset += block.length) {
      segments.add(new ByteArrayInputStream(dataPacket));
      expectedCrc.update(block);
    }
    segments.add(new ByteArrayInputStream(joined(
        packet("WRTE", 7, 1, frame("DONE", new byte[0])), packet("CLSE", 7, 1, ""))));
    FakeSocket socket = new FakeSocket(new SequenceInputStream(Collections.enumeration(segments)));
    CRC32 actualCrc = new CRC32();
    long[] written = {0};
    OutputStream destination = new OutputStream() {
      @Override public void write(int value) {
        actualCrc.update(value);
        written[0]++;
      }

      @Override public void write(byte[] data, int offset, int length) {
        actualCrc.update(data, offset, length);
        written[0] += length;
      }
    };
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      assertEquals(expectedBytes, client.pull("/system/large.apk", destination,
          AdbApkExtractor.transferLimit(0), message -> {}));
    }
    assertEquals(expectedBytes, written[0]);
    assertEquals(expectedCrc.getValue(), actualCrc.getValue());
  }

  @Test
  public void limitsUseLongBudgetAndReportActualBytesRatherThanHardcoded256() throws Exception {
    assertEquals(2L * 1024 * 1024 * 1024, AdbApkExtractor.transferLimit(0));
    assertEquals(1024L, AdbApkExtractor.transferLimit(AdbApkExtractor.TOTAL_LIMIT - 1024));
    for (long total : new long[] {-1, AdbApkExtractor.TOTAL_LIMIT, Long.MAX_VALUE}) {
      try {
        AdbApkExtractor.transferLimit(total);
        fail("must reject exhausted or invalid budget");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("4GiB"));
      }
    }
    try (AdbClient client = new AdbClient(new FakeSocket(joined(connected(),
        packet("OKAY", 7, 1, ""), packet("WRTE", 7, 1, frame("DATA", new byte[20])))))) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      try {
        client.pull("/system/test.apk", new ByteArrayOutputStream(), 4, message -> {});
        fail("must reject above the caller's limit");
      } catch (AdbClient.TransferLimitException expected) {
        assertTrue(expected.getMessage().contains("4字节"));
        assertFalse(expected.getMessage().contains("256"));
      }
    }
  }

  @Test
  public void storageGuardRejectsLowSpaceAndAllowsReserveBoundary() throws Exception {
    for (long available : new long[] {0, AdbApkExtractor.MIN_FREE_SPACE - 1}) {
      File directory = new File("unused") {
        @Override public long getUsableSpace() { return available; }
      };
      try {
        AdbApkExtractor.requireSpace(directory);
        fail("must preserve free space reserve");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("存储空间不足"));
      }
    }
    AdbApkExtractor.requireSpace(new File("unused") {
      @Override public long getUsableSpace() { return AdbApkExtractor.MIN_FREE_SPACE; }
    });
  }

  @Test
  public void lowSpaceDuringTransferDeletesPartialAndRecordsFileAndBytes() throws Exception {
    int[] checks = {0};
    File directory = new File(app.getFilesDir().getPath()) {
      @Override public long getUsableSpace() {
        return ++checks[0] < 3 ? AdbApkExtractor.MIN_FREE_SPACE : 0;
      }
    };
    ContextWrapper context = new ContextWrapper(app) {
      @Override public File getFilesDir() { return directory; }
    };
    ByteArrayOutputStream wire = new ByteArrayOutputStream();
    wire.write(connected());
    wire.write(shell(1, "package:/system/first.apk\n"));
    wire.write(packet("OKAY", 102, 2, ""));
    byte[] dataPacket = packet("WRTE", 102, 2, frame("DATA", new byte[65536]));
    for (int index = 0; index < 17; index++) wire.write(dataPacket);
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(new FakeSocket(wire.toByteArray()))) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      try {
        AdbApkExtractor.extract(context, client, result, 42, message -> {});
        fail("must stop when storage reserve is reached");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("RedwoodCarService.apk"));
        assertTrue(expected.getMessage().contains("存储空间不足"));
      }
    }
    assertFalse(result.getBoolean("complete"));
    JSONObject file = result.getJSONArray("files").getJSONObject(0);
    assertFalse(file.getBoolean("complete"));
    assertEquals(1024L * 1024, file.getLong("bytes"));
    assertEquals(file.getLong("bytes"), result.getLong("bytes"));
    assertEquals(AdbApkExtractor.FILE_LIMIT, file.getLong("limitBytes"));
    assertTrue(file.getString("error").contains("存储空间不足"));
    assertFalse(new File(directory, "adb-apks-42.zip").exists());
    assertFalse(new File(directory, "adb-apks-42.zip.part").exists());
  }

  @Test
  public void syncFailureAndOversizedOutputAreNotSuccess() throws Exception {
    byte[][] responses = {
        frame("FAIL", "Permission denied".getBytes(StandardCharsets.UTF_8)),
        frame("DATA", new byte[20]),
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(code("DATA")).putInt(65537).array()
    };
    for (byte[] response : responses) {
      try (AdbClient client = new AdbClient(new FakeSocket(joined(connected(),
          packet("OKAY", 7, 1, ""), packet("WRTE", 7, 1, response))))) {
        client.connect("127.0.0.1", 5555, key, message -> {});
        try {
          client.pull("/system/test.apk", new ByteArrayOutputStream(), 4, message -> {});
          fail("must reject failed or oversized transfer");
        } catch (IOException expected) {
          assertNotNull(expected.getMessage());
        }
      }
    }
  }

  @Test
  public void apkPathsAreDiscoveredAndUnsafePathsRejected() throws Exception {
    assertEquals(Arrays.asList("/system/priv-app/RedwoodCarService/RedwoodCarService.apk"),
        AdbApkExtractor.paths("package:/system/priv-app/RedwoodCarService/RedwoodCarService.apk\n"));
    for (String path : new String[] {"", "package:/data/user/0/private.apk",
        "package:/system/../data/private.apk", "package:/system/bad;id.apk"}) {
      try {
        AdbApkExtractor.paths(path);
        fail("must reject " + path);
      } catch (IOException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test
  public void selectedPackagesAndSplitsAreArchivedWithoutLegacyPackages() throws Exception {
    for (boolean legacy : new boolean[] {false, true}) {
      String[] selected = AdbApkExtractor.targets(legacy, true);
      ByteArrayOutputStream input = new ByteArrayOutputStream();
      input.write(connected());
      int local = 1;
      byte[] payload = {80, 75, 3, 4, 5};
      for (int index = 0; index < selected.length; index++) {
        input.write(shell(local++, "package:/system/base" + index + ".apk\n"
            + (index == 0 ? "package:/product/split_config.apk\n" : "")));
        input.write(pull(local++, payload));
        if (index == 0) input.write(pull(local++, payload));
      }
      FakeSocket socket = new FakeSocket(input.toByteArray());
      JSONObject result = new JSONObject();
      try (AdbClient client = new AdbClient(socket)) {
        client.connect("127.0.0.1", 5555, key, message -> {});
        AdbApkExtractor.extract(app, client, result, 42, selected, message -> {});
      }
      assertTrue(result.toString(), result.getBoolean("complete"));
      assertEquals(legacy ? 6 : 4, selected.length);
      assertEquals(selected.length + 1, result.getJSONArray("files").length());
      assertEquals(selected.length, result.getJSONArray("packageResults").length());
      try (ZipFile zip = new ZipFile(new File(app.getFilesDir(), "adb-apks-42.zip"))) {
        for (String name : AdbApkExtractor.INTERFACE_NAMES)
          assertArrayEquals(payload, zip.getInputStream(zip.getEntry(name + ".apk")).readAllBytes());
        assertNotNull(zip.getEntry("RockyEcologyCenter/1-split_config.apk"));
        assertEquals(legacy, zip.getEntry("RockySystemUI.apk") != null);
        assertEquals(legacy, zip.getEntry("RedwoodCarService.apk") != null);
        JSONObject info = new JSONObject(new String(zip.getInputStream(zip.getEntry("extraction-info.json"))
            .readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(new JSONArray(Arrays.asList(selected)).toString(),
            info.getJSONArray("packages").toString());
        for (int index = 0; index < info.getJSONArray("files").length(); index++) {
          JSONObject file = info.getJSONArray("files").getJSONObject(index);
          byte[] actual = zip.getInputStream(zip.getEntry(file.getString("entry"))).readAllBytes();
          StringBuilder digest = new StringBuilder();
          for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(actual))
            digest.append(String.format(java.util.Locale.US, "%02x", value & 255));
          assertEquals(digest.toString(), file.getString("sha256"));
        }
      }
      String sent = new String(socket.outgoing.toByteArray(), StandardCharsets.UTF_8);
      assertEquals(legacy, sent.contains("pm path com.android.systemui"));
    }
  }

  @Test
  public void invalidAndEmptyExtractionSelectionsAreRejectedBeforeCommands() throws Exception {
    for (String[] targets : new String[][] {new String[0], {"com.bad;id"},
        {"com.android.shell/../../bad"}, {"com.mega.ecology", "com.mega.ecology"}}) {
      FakeSocket socket = new FakeSocket(new byte[0]);
      try (AdbClient client = new AdbClient(socket)) {
        try {
          AdbApkExtractor.extract(app, client, new JSONObject(), 42, targets, message -> {});
          fail("must reject invalid selection");
        } catch (IllegalArgumentException expected) {
          assertNotNull(expected.getMessage());
        }
      }
      assertEquals(0, socket.outgoing.size());
      assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip.part").exists());
    }
  }

  @Test
  public void missingSelectedPackageRecordsItsNameAndDoesNotOfferPartialArchive() throws Exception {
    FakeSocket socket = new FakeSocket(joined(connected(), shell(1, "")));
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      try {
        AdbApkExtractor.extract(app, client, result, 42,
            AdbApkExtractor.targets(false, true), message -> {});
        fail("missing package must not count as complete");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("pm path"));
      }
    }
    assertEquals("com.mega.ecology", result.getString("failedPackage"));
    assertFalse(result.getBoolean("complete"));
    assertTrue(result.getJSONArray("packageResults").getJSONObject(0).has("error"));
    assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip").exists());
    assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip.part").exists());
  }

  @Test
  public void selectedPackageMetadataAndNoProbeAreBoundToCollection() throws Exception {
    for (boolean interfaces : new boolean[] {false, true}) {
      String[] selected = AdbApkExtractor.targets(false, interfaces);
      List<AdbDiagnosticCommands.Command> commands = new ArrayList<>(AdbDiagnosticCommands.metadata());
      for (String pkg : selected) AdbDiagnosticCommands.addPackage(commands, pkg);
      ByteArrayOutputStream input = new ByteArrayOutputStream();
      input.write(connected());
      for (int index = 0; index < commands.size(); index++)
        input.write(shell(index + 1, commands.get(index).name.equals("home-activity")
            && interfaces ? "com.android.launcher3/.Launcher" : "metadata"));
      int local = commands.size() + 1;
      input.write(shell(local++, "logs"));
      for (int index = 0; index < selected.length; index++) {
        input.write(shell(local++, "package:/system/base" + index + ".apk\n"));
        input.write(pull(local++, new byte[] {80, 75, 3, 4}));
      }
      FakeSocket socket = new FakeSocket(input.toByteArray());
      JSONObject report = AdbDiagnostics.collect(app, new AdbClient(socket),
          "127.0.0.1", 5555, selected, false, message -> {});
      assertTrue(report.toString(), report.getBoolean("complete"));
      assertFalse(report.getBoolean("nativeProbeRequested"));
      assertFalse(report.has("nativeReadProbe"));
      JSONObject extraction = report.getJSONObject("apkExtraction");
      assertEquals(interfaces, extraction.getBoolean("requested"));
      assertEquals(interfaces, extraction.getBoolean("complete"));
      assertEquals(selected.length, extraction.getJSONArray("packages").length());
      assertEquals(commands.size() + 1, report.getJSONArray("commands").length());
      assertFalse(new String(socket.outgoing.toByteArray(), StandardCharsets.UTF_8).contains("app_process"));
      if (interfaces) assertTrue(AdbApkExtractor.archive(app, AdbDiagnostics.latest(app)).isFile());
    }
  }

  @Test
  public void activityDefaultsToNoExtractionAndRestoresAllOptions() throws Exception {
    android.os.Bundle state = new android.os.Bundle();
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create().start().resume().visible()) {
      View root = controller.get().findViewById(android.R.id.content);
      android.widget.CheckBox interfaces = (android.widget.CheckBox)
          find(root, "提取开放接口分析四包（生态中心 / 场景引擎 / 桌面 / 场景模式）");
      android.widget.CheckBox legacy = (android.widget.CheckBox)
          find(root, "同时提取车辆服务和 SystemUI APK（完成后另行导出ZIP）");
      android.widget.CheckBox probe = (android.widget.CheckBox)
          find(root, "重新运行 shell 原生只读验证（已知车辆权限拒绝，默认跳过）");
      assertFalse(interfaces.isChecked());
      assertFalse(legacy.isChecked());
      assertNull(probe);
      java.lang.reflect.Method toggle = AdbDiagnosticsActivity.class
          .getDeclaredMethod("setOptionsEnabled", boolean.class);
      toggle.setAccessible(true);
      toggle.invoke(controller.get(), false);
      assertFalse(interfaces.isEnabled());
      assertFalse(legacy.isEnabled());
      toggle.invoke(controller.get(), true);
      assertTrue(interfaces.isEnabled());
      assertTrue(legacy.isEnabled());
      interfaces.setChecked(false);
      legacy.setChecked(true);
      controller.saveInstanceState(state);
    }
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create(state).start().resume().visible()) {
      android.os.Bundle restored = new android.os.Bundle();
      controller.saveInstanceState(restored);
      assertFalse(restored.getBoolean("includeInterfaceApks"));
      assertTrue(restored.getBoolean("includeApks"));
      assertFalse(restored.containsKey("runNativeProbe"));
    }
  }

  @Test
  public void bothApksAreStreamedIntoZipWithDigestAndBoundToReport() throws Exception {
    byte[] first = {80, 75, 3, 4, 0, (byte) 255}, second = {80, 75, 3, 4, 1};
    FakeSocket socket = new FakeSocket(joined(connected(),
        shell(1, "package:/system/priv-app/RedwoodCarService/RedwoodCarService.apk\n"), pull(2, first),
        shell(3, "package:/system/priv-app/RockySystemUI/RockySystemUI.apk\n"), pull(4, second)));
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      AdbApkExtractor.extract(app, client, result, 42, message -> {});
    }
    assertTrue(result.toString(), result.getBoolean("complete"));
    assertEquals(2, result.getJSONArray("files").length());
    assertEquals(64, result.getJSONArray("files").getJSONObject(0).getString("sha256").length());
    File archive = AdbApkExtractor.archive(app,
        new JSONObject().put("startedAt", 42).put("apkExtraction", result));
    try (ZipFile zip = new ZipFile(archive)) {
      assertArrayEquals(first, zip.getInputStream(zip.getEntry("RedwoodCarService.apk")).readAllBytes());
      assertArrayEquals(second, zip.getInputStream(zip.getEntry("RockySystemUI.apk")).readAllBytes());
      JSONObject info = new JSONObject(new String(zip.getInputStream(zip.getEntry("extraction-info.json"))
          .readAllBytes(), StandardCharsets.UTF_8));
      assertTrue(info.getBoolean("complete"));
    }
    try {
      AdbApkExtractor.archive(app, new JSONObject().put("startedAt", 43).put("apkExtraction", result));
      fail("must not export an older archive for a new report");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("本次"));
    }
  }

  @Test
  public void failedSecondApkDeletesPartialArchiveAndKeepsFailureEvidence() throws Exception {
    FakeSocket socket = new FakeSocket(joined(connected(), shell(1, "package:/system/first.apk\n"),
        pull(2, new byte[] {80, 75}), shell(3, "Unable to find package")));
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      try {
        AdbApkExtractor.extract(app, client, result, 42, message -> {});
        fail("both packages are required");
      } catch (IOException expected) {
        assertFalse(result.getBoolean("complete"));
        assertTrue(result.has("error"));
        assertEquals(1, result.getJSONArray("files").length());
      }
    }
    assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip").exists());
    assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip.part").exists());
  }

  @Test
  public void probeReadsEvenWithoutPackageGrantButStopsRetryingActualSecurityDenial() throws Exception {
    JSONArray targets = AdbVehicleProbe.targets(app);
    assertEquals(4, targets.length());
    VehicleAccessTest.Manager manager = new VehicleAccessTest.Manager();
    manager.readDenied.add(0x67000096);
    AdbVehicleProbe.snapshot(manager, targets);
    assertEquals(0, manager.callbacks.size());
    manager.values.put(0x0600002c, Integer.valueOf(2));
    AdbVehicleProbe.snapshot(manager, targets);
    assertEquals(Integer.valueOf(1), manager.reads.get(0x67000096));
    assertEquals(Integer.valueOf(2), manager.reads.get(0x0600002c));
    JSONObject report = new JSONObject().put("properties", targets);
    AdbVehicleProbe.summarize(report);
    assertTrue(report.getBoolean("readVerified"));
    assertTrue(report.getBoolean("changeObserved"));
    assertTrue(targets.getJSONObject(2).getBoolean("readDenied"));
    assertFalse(targets.getJSONObject(2).getBoolean("readVerified"));
  }

  @Test
  public void invalidStatusWrongIdsAndNonIntegerValuesCannotVerifyReads() throws Exception {
    JSONArray targets = AdbVehicleProbe.targets(app);
    Object manager = new Object() {
      public VehicleAccessTest.Value getProperty(Class<?> type, int id, int area) {
        return new VehicleAccessTest.Value(id + 1, area, 1, "1", 0);
      }
    };
    AdbVehicleProbe.snapshot(manager, targets);
    JSONObject report = new JSONObject().put("properties", targets);
    AdbVehicleProbe.summarize(report);
    assertFalse(report.getBoolean("readVerified"));
    assertFalse(report.getBoolean("changeObserved"));
  }

  @Test
  public void fixedProbeCommandAndResultCannotImplySubscriptionOrRootSuccess() throws Exception {
    String command = AdbDiagnosticCommands.nativeProbe("/data/app/test/base.apk").shell;
    assertTrue(command.contains("/system/bin/app_process"));
    assertFalse(command.contains("grant"));
    assertFalse(command.contains("setProperty"));
    try {
      AdbDiagnosticCommands.nativeProbe("/data/app/a';setprop x y;.apk");
      fail("must reject shell injection");
    } catch (IllegalArgumentException expected) {
      assertNotNull(expected.getMessage());
    }
    for (String output : new String[] {"Permission denied", "", AdbVehicleProbe.MARKER
        + "{\"source\":\"adb-shell-native-read-probe\",\"uid\":0,\"readVerified\":true}"}) {
      JSONObject parsed = AdbDiagnosticCommands.probeResult(output, false);
      assertFalse(parsed.getBoolean("readVerified"));
      assertFalse(parsed.getBoolean("subscriptionVerified"));
    }
    assertFalse(Arrays.asList(AdbDiagnosticCommands.PACKAGES).contains("com.mega.systemui"));
    assertTrue(Arrays.asList(AdbDiagnosticCommands.PACKAGES).contains("com.android.shell"));
    assertTrue(Arrays.asList(AdbDiagnosticCommands.PACKAGES).contains("com.android.systemui"));
  }

  @Test
  public void extendedCollectionEmbedsShellProbeAndMissingMarkerIsNotSuccess() throws Exception {
    List<AdbDiagnosticCommands.Command> commands = new ArrayList<>(AdbDiagnosticCommands.metadata());
    commands.add(AdbDiagnosticCommands.nativeProbe(app.getApplicationInfo().sourceDir));
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(connected());
    for (int index = 0; index < commands.size(); index++) input.write(shell(index + 1, "metadata"));
    input.write(shell(commands.size() + 1, "logs"));
    JSONObject report = AdbDiagnostics.collect(app, new AdbClient(new FakeSocket(input.toByteArray())),
        "127.0.0.1", 5555, false, true, message -> {});
    assertTrue(report.toString(), report.getBoolean("complete"));
    assertEquals(commands.size() + 1, report.getJSONArray("commands").length());
    assertEquals("not-verified", report.getJSONObject("nativeReadProbe").getString("outcome"));
    assertFalse(report.getJSONObject("nativeReadProbe").getBoolean("readVerified"));
    assertFalse(report.getJSONObject("apkExtraction").getBoolean("requested"));
  }

  private static View find(View view, String text) {
    if (view instanceof TextView && text.equals(((TextView) view).getText().toString())) return view;
    if (view instanceof ViewGroup)
      for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
        View found = find(((ViewGroup) view).getChildAt(index), text);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void manualPackageSelectionMergesGroupsAndRejectsUnsafeNames() throws Exception {
    assertArrayEquals(new String[] {"com.mega.car", "com.mega.engineeringmode", "android"},
        AdbPackageCatalog.selection("com.mega.engineeringmode，android\ncom.mega.car",
            new String[] {"com.mega.car"}, new String[] {"com.mega.engineeringmode"}));
    assertEquals(0, AdbPackageCatalog.selection(" \n").length);
    for (String invalid : new String[] {"com.bad/xx", "-bad", "com..bad", "com.$(id)",
        "com.bad`id`", "com.bad|id", "com.bad&id", "com.bad=foo", "com.bad>file", "com.bad'"}) {
      try {
        AdbPackageCatalog.selection(invalid);
        fail("unsafe package " + invalid);
      } catch (IllegalArgumentException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test
  public void genericSelectionHasBoundedCountAndRejectsDuplicatesAtTransferBoundary() {
    String[] packages = new String[65];
    for (int index = 0; index < packages.length; index++) packages[index] = "com.test.package" + index;
    for (boolean merge : new boolean[] {true, false}) {
      try {
        if (merge) AdbPackageCatalog.selection("", packages);
        else AdbApkExtractor.validateTargets(packages);
        fail("must bound selection");
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("64"));
      }
    }
    assertEquals(64, AdbPackageCatalog.selection("", Arrays.copyOf(packages, 64)).length);
    assertArrayEquals(new String[] {"android", "com.mega.engineeringmode"},
        AdbApkExtractor.validateTargets(new String[] {"android", "com.mega.engineeringmode"}));
  }

  @Test
  public void packageScanIsSortedDeduplicatedAndSupportsRandomizedDataApkPaths() throws Exception {
    assertEquals(Arrays.asList("android", "com.mega.engineeringmode", "com.test.app"),
        AdbPackageCatalog.parse("package:/data/app/~~ABC==/com.test.app-Qw==/base.apk=com.test.app\n"
            + "package:/system/framework/framework-res.apk=android\r\n"
            + "package:/system/priv-app/Engineer/base.apk=com.mega.engineeringmode\n"
            + "package:/system/test.apk=com.test.app\n"
            + "package:/private/secret.apk=com.hidden.app\n", false));
  }

  @Test
  public void incompleteAndDeniedScansCannotReplacePackageCatalog() throws Exception {
    for (String invalid : new String[] {"", "Permission denied", "package:com.test.app",
        "package:/system/base.apk=com.bad;id"}) {
      try {
        AdbPackageCatalog.parse(invalid, false);
        fail("must reject incomplete scan");
      } catch (IOException expected) {
        assertNotNull(expected.getMessage());
      }
    }
    try {
      AdbPackageCatalog.parse("package:/system/base.apk=com.test.app", true);
      fail("truncation must not be accepted as full scan");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("截断"));
    }
    StringBuilder oversized = new StringBuilder();
    for (int index = 0; index <= AdbPackageCatalog.MAX_PACKAGES; index++)
      oversized.append("package:/system/base.apk=com.test.package").append(index).append('\n');
    try {
      AdbPackageCatalog.parse(oversized.toString(), false);
      fail("must bound scan size");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("4096"));
    }
  }

  @Test
  public void scanOnlyListsPackagesAndDoesNotExtractOrChangeSettings() throws Exception {
    FakeSocket socket = new FakeSocket(joined(connected(),
        shell(1, "package:/system/Engineer.apk=com.mega.engineeringmode\n")));
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      assertEquals(Arrays.asList("com.mega.engineeringmode"), AdbPackageCatalog.scan(client));
    }
    String sent = new String(socket.outgoing.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(sent.contains("pm list packages -f"));
    assertFalse(sent.contains("pm path"));
    assertFalse(sent.contains("RECV"));
    assertFalse(sent.contains("setprop"));
  }

  @Test
  public void arbitraryEngineeringPackageAndSplitsUseCollisionFreeArchiveNames() throws Exception {
    String[] packages = {"com.mega.engineeringmode", "com.mega.ecology", "RockyEcologyCenter"};
    byte[] payload = {80, 75, 3, 4};
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(connected());
    int local = 1;
    for (int index = 0; index < packages.length; index++) {
      input.write(shell(local++, "package:/system/base.apk\n"
          + (index == 0 ? "package:/system/split.apk\n" : "")));
      input.write(pull(local++, payload));
      if (index == 0) input.write(pull(local++, payload));
    }
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(new FakeSocket(input.toByteArray()))) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      AdbApkExtractor.extract(app, client, result, 42, packages, message -> {});
    }
    assertTrue(result.getBoolean("complete"));
    try (ZipFile zip = new ZipFile(new File(app.getFilesDir(), "adb-apks-42.zip"))) {
      assertNotNull(zip.getEntry("packages/com.mega.engineeringmode.apk"));
      assertNotNull(zip.getEntry("packages/com.mega.engineeringmode/1-split.apk"));
      assertNotNull(zip.getEntry("packages/RockyEcologyCenter.apk"));
      assertNotNull(zip.getEntry("RockyEcologyCenter.apk"));
    }
  }

  @Test
  public void vehicleDebugSnapshotDistinguishesFalseEmptyErrorsAndTruncation() throws Exception {
    String[] raw = {"true\n", "1", "false\n", "0", "", "getprop: Permission denied", "0\n1"};
    String[] expected = {"true", "true", "false", "false", "empty", "unrecognized", "unrecognized"};
    for (int index = 0; index < raw.length; index++) {
      JSONObject snapshot = AdbReadOnlyStatus.snapshot();
      AdbReadOnlyStatus.record(snapshot, "vehicle-debug-property", "getprop persist.mega.car.userdebug",
          raw[index], false, true);
      assertEquals(expected[index], snapshot.getJSONObject("vehicleDebug").getString("state"));
      assertEquals(raw[index], snapshot.getJSONObject("vehicleDebug").getString("raw"));
      assertFalse(snapshot.getBoolean("lanReachabilityVerified"));
    }
    for (boolean completed : new boolean[] {false, true}) {
      JSONObject snapshot = AdbReadOnlyStatus.snapshot();
      AdbReadOnlyStatus.record(snapshot, "vehicle-debug-property", "getprop persist.mega.car.userdebug",
          "true", true, completed);
      assertEquals(completed ? "truncated" : "transport-error",
          snapshot.getJSONObject("vehicleDebug").getString("state"));
      assertFalse(snapshot.getJSONObject("vehicleDebug").has("value"));
    }
    assertTrue(AdbReadOnlyStatus.summary(null).contains("尚未采集"));
  }

  @Test
  public void networkSnapshotDoesNotClaimLanSuccessFromListenerOrPortOutput() throws Exception {
    JSONObject snapshot = AdbReadOnlyStatus.snapshot();
    AdbReadOnlyStatus.record(snapshot, "adb-service-port", "getprop service.adb.tcp.port", "5555", false, true);
    AdbReadOnlyStatus.record(snapshot, "network-tcp-listeners", "ss -ltn", "ss: Permission denied", false, true);
    assertEquals("5555", snapshot.getJSONObject("properties").getJSONObject("service.adb.tcp.port").getString("value"));
    assertEquals("raw-output-only", snapshot.getJSONObject("network").getJSONObject("network-tcp-listeners")
        .getString("interpretation"));
    assertFalse(snapshot.getBoolean("lanReachabilityVerified"));
    assertTrue(AdbReadOnlyStatus.summary(snapshot).contains("尚未验证外部设备可达"));
  }

  @Test
  public void apkHashCommandsOnlyUseValidatedInstalledPathsForRelevantPackages() {
    List<AdbDiagnosticCommands.Command> commands = new ArrayList<>();
    AdbDiagnosticCommands.addApkHashes(commands, "path-com.mega.engineeringmode",
        "package:/system/priv-app/Engineer/base.apk\n", false);
    assertEquals("sha256sum /system/priv-app/Engineer/base.apk", commands.get(0).shell);
    AdbDiagnosticCommands.addApkHashes(commands, "path-com.mega.car", "package:/system/a;id.apk", false);
    AdbDiagnosticCommands.addApkHashes(commands, "path-com.mega.car", "package:/system/base.apk", true);
    AdbDiagnosticCommands.addApkHashes(commands, "path-com.other.app", "package:/system/base.apk", false);
    assertEquals(1, commands.size());
  }

  @Test
  public void packageDialogPreservesSelectionsAcrossFilteringCancelAndActivityRecreation() throws Exception {
    android.os.Bundle state = new android.os.Bundle();
    app.getSharedPreferences("adb_diagnostics", 0).edit().remove("selectedPackages").commit();
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create().start().resume().visible()) {
      AdbDiagnosticsActivity activity = controller.get();
      java.lang.reflect.Field catalog = AdbDiagnosticsActivity.class.getDeclaredField("installedPackages");
      catalog.setAccessible(true);
      catalog.set(activity, Arrays.asList("com.mega.engineeringmode", "com.test.other"));
      java.lang.reflect.Method choose = AdbDiagnosticsActivity.class.getDeclaredMethod("choosePackages");
      choose.setAccessible(true);
      choose.invoke(activity);
      android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      android.widget.ListView list = findType(dialog.getWindow().getDecorView(), android.widget.ListView.class);
      android.widget.EditText search = findType(dialog.getWindow().getDecorView(), android.widget.EditText.class);
      list.performItemClick(null, 0, 0);
      search.setText("other");
      assertEquals(1, list.getAdapter().getCount());
      list.performItemClick(null, 0, 0);
      search.setText("");
      assertTrue(list.isItemChecked(0));
      assertTrue(list.isItemChecked(1));
      dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
      shadowOf(android.os.Looper.getMainLooper()).idle();
      choose.invoke(activity);
      dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
      find(dialog.getWindow().getDecorView(), "清空多选").performClick();
      dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
      shadowOf(android.os.Looper.getMainLooper()).idle();
      java.lang.reflect.Field manual = AdbDiagnosticsActivity.class.getDeclaredField("packageNames");
      manual.setAccessible(true);
      ((android.widget.EditText) manual.get(activity)).setText("com.custom.test");
      controller.saveInstanceState(state);
      assertEquals("com.custom.test", state.getString("packageNames"));
      assertArrayEquals(new String[] {"com.mega.engineeringmode", "com.test.other"}, state.getStringArray("selectedPackages"));
    }
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create(state).start().resume().visible()) {
      android.os.Bundle restored = new android.os.Bundle();
      controller.saveInstanceState(restored);
      assertArrayEquals(state.getStringArray("selectedPackages"), restored.getStringArray("selectedPackages"));
      assertEquals("com.custom.test", restored.getString("packageNames"));
    } finally {
      app.getSharedPreferences("adb_diagnostics", 0).edit().remove("selectedPackages").commit();
    }
  }

  private static <ViewType extends View> ViewType findType(View view, Class<ViewType> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup)
      for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
        ViewType found = findType(((ViewGroup) view).getChildAt(index), type);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void fullCollectionEmbedsTargetedStatusAndHashesWithoutEnablingAnything() throws Exception {
    List<AdbDiagnosticCommands.Command> commands = AdbDiagnosticCommands.metadata();
    AdbDiagnosticCommands.addApkHashes(commands, "path-com.mega.car", "package:/system/Car/base.apk", false);
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(connected());
    for (int index = 0; index < commands.size(); index++) {
      AdbDiagnosticCommands.Command command = commands.get(index);
      String output = command.name.equals("vehicle-debug-property") ? "true\n"
          : command.name.equals("path-com.mega.car") ? "package:/system/Car/base.apk\n"
          : command.name.equals("adb-service-port") ? "5555\n"
          : command.name.equals("network-tcp-listeners") ? "ss: Permission denied\n"
          : command.name.startsWith("apk-sha256-") ? "abc /system/Car/base.apk\n" : "metadata";
      input.write(shell(index + 1, output));
    }
    input.write(shell(commands.size() + 1, "logs"));
    FakeSocket socket = new FakeSocket(input.toByteArray());
    JSONObject report = AdbDiagnostics.collect(app, new AdbClient(socket), "127.0.0.1", 5555,
        new String[0], false, message -> {});
    assertTrue(report.toString(), report.getBoolean("complete"));
    JSONObject targeted = report.getJSONObject("targetedReadOnlyDiagnostics");
    assertEquals("true", targeted.getJSONObject("vehicleDebug").getString("state"));
    assertFalse(targeted.getBoolean("lanReachabilityVerified"));
    assertTrue(AdbDiagnosticsActivity.summary(report).contains("不等于车辆接口已通过"));
    assertEquals(commands.size() + 1, report.getJSONArray("commands").length());
    assertEquals("true", AdbDiagnostics.latest(app).getJSONObject("targetedReadOnlyDiagnostics")
        .getJSONObject("vehicleDebug").getString("state"));
    String sent = new String(socket.outgoing.toByteArray(), StandardCharsets.UTF_8);
    assertTrue(sent.contains("getprop persist.mega.car.userdebug"));
    assertTrue(sent.contains("sha256sum /system/Car/base.apk"));
    assertFalse(sent.contains("setprop"));
    assertFalse(sent.contains("shell-native-read-probe"));
  }

  @Test
  public void cancelledExtractionRemovesPartialFileAndDoesNotExportOldArchive() throws Exception {
    FakeSocket socket = new FakeSocket(joined(connected(), shell(1, "package:/system/first.apk\n")));
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      try {
        AdbApkExtractor.extract(app, client, result, 42,
            message -> { if (message.startsWith("读取")) client.close(); });
        fail("cancelled transfer cannot succeed");
      } catch (IOException expected) {
        assertFalse(result.getBoolean("complete"));
        assertTrue(result.getString("error").contains("取消"));
      }
    }
    assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip.part").exists());
    try {
      AdbApkExtractor.archive(app, new JSONObject().put("startedAt", 42).put("apkExtraction", result));
      fail("partial archive must not be exported");
    } catch (IOException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  @Test
  public void emptyApkIsNotReportedAsExtracted() throws Exception {
    FakeSocket socket = new FakeSocket(joined(connected(), shell(1, "package:/system/first.apk\n"),
        pull(2, new byte[0])));
    JSONObject result = new JSONObject();
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 5555, key, message -> {});
      try {
        AdbApkExtractor.extract(app, client, result, 42, message -> {});
        fail("empty file cannot be an APK");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("APK为空"));
      }
    }
    assertFalse(new File(app.getFilesDir(), "adb-apks-42.zip").exists());
  }

  @Test
  public void probeSummaryDoesNotTrustSuccessWithoutValidSamplesOrTruncatedOutput() throws Exception {
    String text = AdbVehicleProbe.MARKER + new JSONObject()
        .put("source", "adb-shell-native-read-probe").put("uid", 2000)
        .put("readVerified", true).put("subscriptionVerified", true);
    assertFalse(AdbDiagnosticCommands.probeResult(text, false).getBoolean("readVerified"));
    assertFalse(AdbDiagnosticCommands.probeResult(text, false).getBoolean("subscriptionVerified"));
    assertFalse(AdbDiagnosticCommands.probeResult(text, true).getBoolean("readVerified"));
  }

  @Test
  public void fullDiagnosticAndExtractionRemainInOneReport() throws Exception {
    List<AdbDiagnosticCommands.Command> commands = new ArrayList<>(AdbDiagnosticCommands.metadata());
    commands.add(AdbDiagnosticCommands.nativeProbe(app.getApplicationInfo().sourceDir));
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(connected());
    for (int index = 0; index < commands.size(); index++) input.write(shell(index + 1, "metadata"));
    int local = commands.size() + 1;
    input.write(shell(local++, "logs"));
    input.write(shell(local++, "package:/system/first.apk\n"));
    input.write(pull(local++, new byte[] {80, 75, 3, 4}));
    input.write(shell(local++, "package:/system/second.apk\n"));
    input.write(pull(local, new byte[] {80, 75, 3, 4}));
    JSONObject report = AdbDiagnostics.collect(app, new AdbClient(new FakeSocket(input.toByteArray())),
        "127.0.0.1", 5555, true, true, message -> {});
    assertTrue(report.toString(), report.getBoolean("complete"));
    assertTrue(report.getJSONObject("apkExtraction").getBoolean("complete"));
    assertFalse(report.getJSONObject("nativeReadProbe").getBoolean("readVerified"));
    assertTrue(AdbApkExtractor.archive(app, AdbDiagnostics.latest(app)).isFile());
  }

  @Test
  public void jsonExportKeepsOnlySnapshotFilenameInSavedState() throws Exception {
    JSONObject report = new JSONObject().put("startedAt", 42).put("complete", true)
        .put("largeOutput", new String(new char[500000]).replace('\0', 'x'));
    Files.write(AdbDiagnostics.reportFile(app).toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create().start().resume().visible()) {
      AdbDiagnosticsActivity activity = controller.get();
      find(activity.findViewById(android.R.id.content), "导出 ADB 诊断").performClick();
      android.os.Bundle state = new android.os.Bundle();
      controller.saveInstanceState(state);
      String name = state.getString("pendingReport");
      assertTrue(name, name.matches("adb-export-[0-9-]+\\.json"));
      assertTrue(name.length() < 100);
      assertTrue(new File(activity.getCacheDir(), name).length() > 500000);
      new File(activity.getCacheDir(), name).delete();
    }
  }

  @Test
  public void activityOffersSeparateZipExportWithoutConnecting() throws Exception {
    JSONObject result = new JSONObject().put("complete", true).put("archiveFile", "adb-apks-42.zip");
    Files.write(new File(app.getFilesDir(), "adb-apks-42.zip").toPath(), new byte[] {80, 75});
    Files.write(AdbDiagnostics.reportFile(app).toPath(), new JSONObject().put("startedAt", 42)
        .put("apkExtraction", result).toString().getBytes(StandardCharsets.UTF_8));
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class).create().start().resume().visible()) {
      AdbDiagnosticsActivity activity = controller.get();
      View root = activity.findViewById(android.R.id.content);
      assertNotNull(find(root, "同时提取车辆服务和 SystemUI APK（完成后另行导出ZIP）"));
      find(root, "导出系统 APK（ZIP）").performClick();
      Intent intent = shadowOf(activity).getNextStartedActivityForResult().intent;
      assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
      assertEquals("application/zip", intent.getType());
      assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE).startsWith("ES-系统APK-"));
    }
  }
}
