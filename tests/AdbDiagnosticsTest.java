package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
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
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 28,
    application = Application.class,
    qualifiers = "w1000dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AdbDiagnosticsTest {
  private Application app;
  private static KeyPair fixtureKey;

  @Before
  public void setup() throws Exception {
    app = RuntimeEnvironment.getApplication();
    Files.deleteIfExists(AdbDiagnostics.reportFile(app).toPath());
    if (fixtureKey == null) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      fixtureKey = generator.generateKeyPair();
    }
  }

  @After
  public void clean() throws Exception {
    Files.deleteIfExists(AdbDiagnostics.reportFile(app).toPath());
  }

  private static final class FakeSocket extends Socket {
    final InputStream incoming;
    final ByteArrayOutputStream outgoing = new ByteArrayOutputStream();
    boolean connected, closed;

    FakeSocket(byte[] input) {
      incoming = new ByteArrayInputStream(input);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) {
      connected = true;
    }

    @Override
    public void setSoTimeout(int timeout) {}

    @Override
    public InputStream getInputStream() {
      return incoming;
    }

    @Override
    public OutputStream getOutputStream() {
      return outgoing;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static int code(String text) {
    return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII))
        .order(ByteOrder.LITTLE_ENDIAN)
        .getInt();
  }

  private static byte[] packet(String command, int a, int b, byte[] payload) throws Exception {
    int sum = 0;
    for (byte n : payload) sum += n & 255;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(
        ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(code(command))
            .putInt(a)
            .putInt(b)
            .putInt(payload.length)
            .putInt(sum)
            .putInt(~code(command))
            .array());
    output.write(payload);
    return output.toByteArray();
  }

  private static byte[] packet(String command, int a, int b, String payload) throws Exception {
    return packet(command, a, b, payload.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] joined(byte[]... arrays) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] array : arrays) out.write(array);
    return out.toByteArray();
  }

  private static List<Integer> outgoingCommands(byte[] data) {
    List<Integer> commands = new ArrayList<>();
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    while (buffer.hasRemaining()) {
      commands.add(buffer.getInt());
      buffer.getInt();
      buffer.getInt();
      int length = buffer.getInt();
      buffer.getInt();
      buffer.getInt();
      buffer.position(buffer.position() + length);
    }
    return commands;
  }

  @Test
  public void adbPublicKeyAndChallengeSignatureUseStandardRsaFormat() throws Exception {
    byte[] token = "01234567890123456789".getBytes(StandardCharsets.US_ASCII);
    Cipher decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    decrypt.init(Cipher.DECRYPT_MODE, fixtureKey.getPublic());
    byte[] digest = decrypt.doFinal(AdbClient.sign(fixtureKey, token));
    assertArrayEquals(token, Arrays.copyOfRange(digest, 15, digest.length));
    assertEquals(35, digest.length);
    String wire =
        new String(
            AdbClient.publicKey((RSAPublicKey) fixtureKey.getPublic()), StandardCharsets.UTF_8);
    assertTrue(wire.endsWith(" ES-Sound-Lab@android\0"));
    byte[] encoded = Base64.getDecoder().decode(wire.split(" ")[0]);
    assertEquals(524, encoded.length);
    ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(64, buffer.getInt());
    buffer.getInt();
    byte[] littleN = new byte[256], bigN = new byte[256];
    buffer.get(littleN);
    for (int i = 0; i < 256; i++) bigN[i] = littleN[255 - i];
    assertEquals(
        ((RSAPublicKey) fixtureKey.getPublic()).getModulus(), new java.math.BigInteger(1, bigN));
    assertEquals(65537, ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(520));
  }

  @Test
  public void normalRsaAuthorizationThenMultipleShellChunksAndNextCommand() throws Exception {
    byte[] token = "01234567890123456789".getBytes(StandardCharsets.US_ASCII);
    FakeSocket socket =
        new FakeSocket(
            joined(
                packet("AUTH", 1, 0, token),
                packet("AUTH", 1, 0, token),
                packet("CNXN", 0x01000000, 4096, "device::\0"),
                packet("OKAY", 7, 1, ""),
                packet("WRTE", 7, 1, "uid=2000"),
                packet("WRTE", 7, 1, "(shell)\n"),
                packet("CLSE", 7, 1, ""),
                packet("OKAY", 8, 2, ""),
                packet("WRTE", 8, 2, "Enforcing"),
                packet("CLSE", 8, 2, "")));
    try (AdbClient client = new AdbClient(socket)) {
      List<String> progress = new ArrayList<>();
      client.connect("127.0.0.1", 12345, fixtureKey, progress::add);
      assertTrue(progress.toString().contains("系统弹窗"));
      assertEquals("uid=2000(shell)\n", client.shell("id", 1024).text);
      assertEquals("Enforcing", client.shell("getenforce", 1024).text);
      List<Integer> sent = outgoingCommands(socket.outgoing.toByteArray());
      assertEquals(
          Arrays.asList(
              code("CNXN"),
              code("AUTH"),
              code("AUTH"),
              code("OPEN"),
              code("OKAY"),
              code("OKAY"),
              code("CLSE"),
              code("OPEN"),
              code("OKAY"),
              code("CLSE")),
          sent);
    }
    assertTrue(socket.closed);
  }

  @Test
  public void outputLimitClosesOnlyCurrentStreamAndReportsTruncation() throws Exception {
    FakeSocket socket =
        new FakeSocket(
            joined(
                packet("CNXN", 0x01000000, 4096, "device::\0"),
                packet("OKAY", 7, 1, ""),
                packet("WRTE", 7, 1, "abcdef"),
                packet("CLSE", 7, 1, ""),
                packet("OKAY", 8, 2, ""),
                packet("WRTE", 8, 2, "ok"),
                packet("CLSE", 8, 2, "")));
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 12345, fixtureKey, message -> {});
      AdbClient.Result result = client.shell("pm list libraries", 3);
      assertEquals("abc", result.text);
      assertTrue(result.truncated);
      assertEquals("ok", client.shell("id", 1024).text);
    }
  }

  @Test
  public void normalDisconnectPreservesPartialCommandOutput() throws Exception {
    FakeSocket socket =
        new FakeSocket(
            joined(
                packet("CNXN", 0x01000000, 4096, "device::\0"),
                packet("OKAY", 7, 1, ""),
                packet("WRTE", 7, 1, "partial evidence")));
    try (AdbClient client = new AdbClient(socket)) {
      client.connect("127.0.0.1", 12345, fixtureKey, message -> {});
      try {
        client.shell("id", 1024);
        fail("must record disconnection");
      } catch (AdbClient.ShellException error) {
        assertEquals("partial evidence", error.partialOutput);
      }
    }
  }

  @Test
  public void tlsPairingEndpointIsReportedWithoutAttemptingShell() throws Exception {
    FakeSocket socket = new FakeSocket(packet("STLS", 0x01000000, 0, ""));
    try (AdbClient client = new AdbClient(socket)) {
      try {
        client.connect("127.0.0.1", 12345, fixtureKey, message -> {});
        fail("TLS unsupported");
      } catch (java.io.IOException error) {
        assertTrue(error.getMessage().contains("TLS"));
      }
      assertEquals(Arrays.asList(code("CNXN")), outgoingCommands(socket.outgoing.toByteArray()));
    }
  }

  @Test
  public void fixedPlanIsReadOnlyAndAdditionalPackagesAreValidated() {
    List<AdbDiagnosticCommands.Command> commands = AdbDiagnosticCommands.metadata();
    int count = commands.size();
    AdbDiagnosticCommands.addPackage(commands, "com.example.dynamic");
    assertEquals(count + 3, commands.size());
    AdbDiagnosticCommands.addPackage(commands, "com.example.dynamic");
    AdbDiagnosticCommands.addPackage(commands, "com.example; setprop x y");
    assertEquals(count + 3, commands.size());
    for (AdbDiagnosticCommands.Command command : commands) {
      assertTrue(
          command.shell.matches(
              "(?:id|getenforce|am get-current-user|pm list packages -f|cmd package"
                  + " resolve-activity .+|pm list libraries|service list|dumpsys -t 10 .+|getprop"
                  + " .+|pm path .+|cmd appops get .+)"));
      assertFalse(command.shell.contains(";"));
      assertFalse(command.shell.contains("grant"));
      assertFalse(command.shell.contains("setprop"));
    }
    assertTrue(AdbDiagnosticCommands.logs().shell.contains("logcat -d -t 2000"));
  }

  @Test
  public void keyPersistsPrivatelyAndReportExcludesKeyMaterial() throws Exception {
    KeyPair first = AdbDiagnostics.key(app), second = AdbDiagnostics.key(app);
    assertArrayEquals(first.getPublic().getEncoded(), second.getPublic().getEncoded());
    assertArrayEquals(first.getPrivate().getEncoded(), second.getPrivate().getEncoded());
    assertFalse(AdbDiagnostics.reportFile(app).exists());
  }

  @Test
  public void completeCollectionDiscoversActualServicePackageAndEmbedsInDetectionExport()
      throws Exception {
    List<AdbDiagnosticCommands.Command> commands = AdbDiagnosticCommands.metadata();
    AdbDiagnosticCommands.addPackage(commands, "com.example.home");
    AdbDiagnosticCommands.addPackage(commands, "com.example.actualcar");
    ByteArrayOutputStream input = new ByteArrayOutputStream();
    input.write(packet("CNXN", 0x01000000, 4096, "device::\0"));
    for (int i = 0; i <= commands.size(); i++) {
      int local = i + 1, remote = i + 100;
      String result =
          i == commands.size()
              ? "vehicle logs"
              : commands.get(i).name.equals("car-service-package")
                  ? "com.example.actualcar\n"
                  : commands.get(i).name.equals("home-activity")
                      ? "priority=0\r\ncom.example.home/.HomeActivity\r\n"
                      : commands.get(i).name.equals("permission-definitions")
                          ? "Permission mega.car.permission.CAR_DRIVING: prot=signature|privileged"
                          : commands.get(i).name.equals("shell-identity")
                              ? "uid=2000(shell) gid=2000(shell)"
                              : "metadata";
      input.write(packet("OKAY", remote, local, ""));
      input.write(packet("WRTE", remote, local, result));
      input.write(packet("CLSE", remote, local, ""));
    }
    FakeSocket socket = new FakeSocket(input.toByteArray());
    JSONObject report =
        AdbDiagnostics.collect(app, new AdbClient(socket), "127.0.0.1", 12345, message -> {});
    assertTrue(report.toString(), report.getBoolean("complete"));
    assertTrue(socket.closed);
    assertEquals(commands.size() + 1, report.getJSONArray("commands").length());
    assertTrue(
        report.getJSONArray("commands").toString().contains("package-com.example.actualcar"));
    assertFalse(report.getBoolean("commandExitCodesAvailable"));
    assertTrue(report.getJSONArray("commands").toString().contains("package-com.example.home"));
    assertTrue(report.getJSONArray("commands").toString().contains("prot=signature|privileged"));
    assertFalse(report.toString().contains("PRIVATE"));
    Diagnostics diagnostics = new Diagnostics();
    diagnostics.attach(app);
    JSONObject combined = new JSONObject(diagnostics.export(false, new Store(app)));
    assertEquals(
        "authenticated-adb-shell", combined.getJSONObject("adbDiagnostics").getString("source"));
    assertTrue(combined.getJSONObject("adbDiagnostics").getBoolean("complete"));
  }

  @Test
  public void cancelledCollectionKeepsFailureReportAndCannotReconnect() throws Exception {
    FakeSocket socket = new FakeSocket(new byte[0]);
    AdbClient client = new AdbClient(socket);
    client.close();
    JSONObject report = AdbDiagnostics.collect(app, client, "127.0.0.1", 12345, message -> {});
    assertFalse(report.getBoolean("complete"));
    assertTrue(report.getString("error").contains("取消"));
    assertFalse(socket.connected);
    assertNotNull(AdbDiagnostics.latest(app));
  }

  @Test
  public void collectionDisconnectKeepsPartialOutputAndStopsFurtherCommands() throws Exception {
    FakeSocket socket =
        new FakeSocket(
            joined(
                packet("CNXN", 0x01000000, 4096, "device::\0"),
                packet("OKAY", 7, 1, ""),
                packet("WRTE", 7, 1, "identity before disconnect")));
    JSONObject report =
        AdbDiagnostics.collect(app, new AdbClient(socket), "127.0.0.1", 12345, message -> {});
    assertFalse(report.getBoolean("complete"));
    assertEquals(1, report.getJSONArray("commands").length());
    assertEquals(
        "identity before disconnect",
        report.getJSONArray("commands").getJSONObject(0).getString("output"));
    assertFalse(report.getJSONArray("commands").getJSONObject(0).getBoolean("transportCompleted"));
  }

  private static View find(View view, String text) {
    if (view instanceof TextView && text.equals(((TextView) view).getText().toString()))
      return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View match = find(((ViewGroup) view).getChildAt(i), text);
        if (match != null) return match;
      }
    return null;
  }

  @Test
  public void activityDoesNotConnectOnOpeningAndValidatesPortAndExportsReport() throws Exception {
    try (ActivityController<AdbDiagnosticsActivity> controller =
        Robolectric.buildActivity(AdbDiagnosticsActivity.class)
            .create()
            .start()
            .resume()
            .visible()) {
      AdbDiagnosticsActivity activity = controller.get();
      View root = activity.findViewById(android.R.id.content);
      assertNotNull(find(root, "连接并采集诊断"));
      assertNotNull(find(root, "导出 ADB 诊断"));
      View canvasRoot = ((ViewGroup) root).getChildAt(0);
      canvasRoot.measure(
          View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
      canvasRoot.layout(0, 0, 1000, 900);
      android.graphics.Bitmap image =
          android.graphics.Bitmap.createBitmap(1000, 900, android.graphics.Bitmap.Config.ARGB_8888);
      canvasRoot.draw(new android.graphics.Canvas(image));
      try (java.io.FileOutputStream file =
          new java.io.FileOutputStream("build/adb-diagnostics.png")) {
        assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file));
      }
      image.recycle();
      assertFalse(AdbDiagnostics.reportFile(app).exists());
      find(root, "连接并采集诊断").performClick();
      assertNotNull(find(root, "请填写实际 ADB 地址和1～65535之间的端口，不能留空。"));
      assertFalse(AdbDiagnostics.reportFile(app).exists());
      Files.write(
          AdbDiagnostics.reportFile(app).toPath(),
          "{\"startedAt\":1,\"commands\":[],\"complete\":false}".getBytes(StandardCharsets.UTF_8));
      find(root, "导出 ADB 诊断").performClick();
      Intent intent = shadowOf(activity).getNextStartedActivityForResult().intent;
      assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
      assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE).startsWith("ES-ADB诊断-"));
    }
  }
}
