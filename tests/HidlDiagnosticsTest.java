package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
    shadows = HidlDiagnosticsTest.FakeClient.class)
public final class HidlDiagnosticsTest {
  private static final String XML = "<manifest version=\"1.0\" type=\"device\">"
      + "<hal format=\"hidl\"><name>vendor.mega.message_center</name><transport>hwbinder</transport>"
      + "<version>1.0</version><interface><name>IMessageCenter</name><instance>default</instance>"
      + "</interface><fqname>@1.0::IMessageCenter/alternate</fqname></hal></manifest>";
  private static final String FRAME = "ES_VINTF_ROOT_RC=0\nES_VINTF_FILE=/vendor/etc/vintf/manifest.xml\n"
      + XML + "\nES_VINTF_FILE_END=0:  " + XML.getBytes(StandardCharsets.UTF_8).length + "\nES_VINTF_FILES=1\n";
  private Application app;

  private static String exit(String body, int code) { return body + "\nES_HIDL_EXIT=" + code + "\n"; }
  private static JSONObject item(String name, String text, boolean truncated, boolean completed) throws Exception {
    return new JSONObject().put("name", name).put("output", text)
        .put("truncated", truncated).put("transportCompleted", completed);
  }

  @Implements(AdbClient.class)
  public static final class FakeClient {
    @RealObject AdbClient realClient;
    final List<String> operations = new ArrayList<>();
    boolean unsupported, failLogs, closed;
    @Implementation public void connect(String host, int port, KeyPair key, AdbClient.Progress progress) {}
    @Implementation public AdbClient.Result shell(String command, int limit) throws IOException {
      operations.add(command);
      String text;
      if (command.contains("date '+%s")) text = exit("1793437684 2026-10-31 17:08:04.000 +0800\n", 0);
      else if (command.contains("hwservicemanager:V")) {
        if (failLogs) throw new AdbClient.ShellException(new IOException("cancelled stream"), "partial audit");
        text = exit("1793437684.654 444 444 E SELinux : avc: denied { find } pid=42 uid=10091 name=vendor.mega.message_center\n", 0);
      } else if (command.contains("lshal")) text = unsupported
          ? exit("lshal: unrecognized option --types\n", 1)
          : exit("hwbinder vendor.mega.message_center@1.0::IMessageCenter/default 2343\n", 0);
      else if (command.contains("ES_VINTF_FILE=")) text = exit(FRAME, 0);
      else text = "metadata";
      return new AdbClient.Result(text, false);
    }
    @Implementation public void close() { closed = true; }
  }

  private FakeClient fakeClient() { return Shadow.extract(new AdbClient()); }

  @Before public void setup() throws Exception {
    app = RuntimeEnvironment.getApplication();
    java.nio.file.Files.deleteIfExists(AdbDiagnostics.reportFile(app).toPath());
  }

  private JSONObject collect(FakeClient client, String host) throws Exception {
    return AdbDiagnostics.collect(app, client.realClient, host, 5555, new String[0], false, true,
        ignored -> {
          client.operations.add("APP_LOOKUP");
          return new JSONObject().put("phase", "service-unavailable").put("failedPhase", "discovering")
              .put("error", "java.util.NoSuchElementException: null").put("discoveryOnly", true)
              .put("uid", 10091).put("pid", 42)
              .put("startedAt", 1793437684653L).put("finishedAt", 1793437684654L);
        }, message -> {});
  }

  @Test public void combinedFlowCapturesClockLookupThenLogsBeforeInventoryAndExports() throws Exception {
    app.getSharedPreferences("message_center_probe", Context.MODE_PRIVATE).edit()
        .putString("latest", "{\"sessionId\":333,\"phase\":\"old-probe\"}").commit();
    FakeClient client = fakeClient();
    JSONObject report = collect(client, "127.0.0.1"), hidl = report.getJSONObject("hidlDiagnostics");
    assertTrue(report.getBoolean("complete"));
    assertTrue(client.closed);
    assertFalse(report.getBoolean("nativeProbeRequested"));
    assertFalse(report.getJSONObject("apkExtraction").getBoolean("requested"));
    assertEquals("old-probe", report.getJSONObject("messageCenterProbe").getString("phase"));
    assertEquals("APP_LOOKUP", client.operations.get(1));
    assertTrue(client.operations.get(0).contains("date"));
    assertTrue(client.operations.get(2).contains("hwservicemanager:V"));
    assertTrue(client.operations.get(3).contains("lshal"));
    assertTrue(hidl.getBoolean("logsAnchoredToDeviceClock"));
    assertTrue(hidl.getBoolean("collectionComplete"));
    assertFalse(hidl.getBoolean("sameDeviceVerified"));
    JSONObject denial = hidl.getJSONObject("logEvidence").getJSONArray("denialsOrLookupFailures").getJSONObject(0);
    assertTrue(denial.getBoolean("withinAppLookupWindow"));
    assertTrue(denial.getBoolean("mentionsAppIdentity"));
    assertTrue(denial.getBoolean("mentionsMessageCenter"));
    assertTrue(HidlDiagnostics.summary(hidl).contains("service-unavailable"));
    assertEquals(0, hidl.getJSONObject("commands").getJSONObject("hidl-registered-services").getInt("exitCode"));
    Diagnostics diagnostics = new Diagnostics();
    diagnostics.attach(app);
    JSONObject exported = new JSONObject(diagnostics.export(false, new Store(app)));
    assertTrue(exported.getJSONObject("adbDiagnostics").getJSONObject("hidlDiagnostics").getBoolean("collectionComplete"));
  }

  @Test public void nonLoopbackDoesNotReproduceLocalAppAgainstAnotherDevicesLogs() throws Exception {
    FakeClient client = fakeClient();
    JSONObject hidl = collect(client, "192.168.1.20").getJSONObject("hidlDiagnostics");
    assertFalse(client.operations.contains("APP_LOOKUP"));
    assertEquals("skipped-non-loopback-adb", hidl.getJSONObject("appLookup").getString("phase"));
    assertFalse(hidl.getBoolean("sameDeviceVerified"));
  }

  @Test public void unsupportedToolsDoNotLoseOtherEvidenceOrBecomeEmptySuccess() throws Exception {
    FakeClient client = fakeClient();
    client.unsupported = true;
    JSONObject report = collect(client, "localhost");
    assertTrue(report.getBoolean("complete"));
    JSONObject commands = report.getJSONObject("hidlDiagnostics").getJSONObject("commands");
    assertEquals("unsupported", commands.getJSONObject("hidl-registered-services").getString("state"));
    assertEquals(1, commands.getJSONObject("hidl-vintf-vendor").getJSONArray("files").length());
  }

  @Test public void transportFailureCheckpointsPartialLookupAndLogOutputWithoutPretendingComplete() throws Exception {
    FakeClient client = fakeClient();
    client.failLogs = true;
    JSONObject report = collect(client, "::1");
    assertFalse(report.getBoolean("complete"));
    JSONObject saved = AdbDiagnostics.latest(app).getJSONObject("hidlDiagnostics");
    assertFalse(saved.optBoolean("collectionComplete"));
    assertEquals("service-unavailable", saved.getJSONObject("appLookup").getString("phase"));
    assertEquals("transport-error", saved.getJSONObject("commands").getJSONObject("hidl-security-logs").getString("state"));
    assertTrue(report.getJSONArray("commands").toString().contains("partial audit"));
    assertTrue(client.closed);
  }

  @Test public void statusesKeepMissingMarkerTruncationTimeoutAndDenialDistinct() throws Exception {
    for (String[] pair : Arrays.asList(new String[] {"permission denied", "permission-denied"},
        new String[] {"unknown option -i", "unsupported"}, new String[] {"ordinary failure", "command-error"}))
      assertEquals(pair[1], HidlDiagnostics.status(item("cmd", exit(pair[0], 1), false, true)).getString("state"));
    assertEquals("timeout", HidlDiagnostics.status(item("cmd", exit("", 124), false, true)).getString("state"));
    assertEquals("tool-unavailable", HidlDiagnostics.status(item("cmd", exit("not found", 127), false, true)).getString("state"));
    assertEquals("truncated", HidlDiagnostics.status(item("cmd", exit("", 0), true, true)).getString("state"));
    assertEquals("exit-marker-missing", HidlDiagnostics.status(item("cmd", "", false, true)).getString("state"));
    assertEquals("collected", HidlDiagnostics.status(item("cmd", exit("avc: denied", 0), false, true)).getString("state"));
  }

  @Test public void manifestsParseInstancesAndTransportWithoutExecutingEntitiesOrMatchingComments() throws Exception {
    JSONObject hal = HidlDiagnostics.declarations(XML).getJSONObject(0);
    assertEquals("hwbinder", hal.getString("transport"));
    assertEquals("IMessageCenter", hal.getJSONArray("interfaces").getString(0));
    assertEquals("default", hal.getJSONArray("instances").getString(0));
    assertEquals("@1.0::IMessageCenter/alternate", hal.getJSONArray("fqnames").getString(0));
    assertEquals(0, HidlDiagnostics.declarations("<manifest><!--vendor.mega.message_center--></manifest>").length());
    try { HidlDiagnostics.declarations("<!DOCTYPE manifest SYSTEM 'file:///x'><manifest/>"); fail(); }
    catch (IllegalArgumentException expected) {}
  }

  @Test public void oversizedUnreadableOrPartialManifestIsNotParsedAsAbsence() throws Exception {
    JSONObject snapshot = HidlDiagnostics.snapshot();
    String text = FRAME.replace("ES_VINTF_FILE_END=0:  " + XML.length(), "ES_VINTF_FILE_END=0:  65537");
    HidlDiagnostics.record(snapshot, item("hidl-vintf-vendor", exit(text, 0), false, true));
    JSONObject file = snapshot.getJSONObject("commands").getJSONObject("hidl-vintf-vendor").getJSONArray("files").getJSONObject(0);
    assertTrue(file.getBoolean("truncated"));
    assertFalse(file.has("targetDeclarations"));
    HidlDiagnostics.record(snapshot, item("hidl-vintf-odm", exit("Permission denied\nES_VINTF_FILE_LIMIT=8\n", 0), true, true));
    JSONObject odm = snapshot.getJSONObject("commands").getJSONObject("hidl-vintf-odm");
    assertTrue(odm.getBoolean("rootAccessIssue"));
    assertTrue(odm.getBoolean("fileLimitReached"));
    assertEquals("truncated", odm.getString("state"));
  }

  @Test public void noLshalRowsIsOnlyNotObservedNotServiceAbsent() throws Exception {
    JSONObject snapshot = HidlDiagnostics.snapshot();
    HidlDiagnostics.record(snapshot, item("hidl-registered-services", exit("", 0), false, true));
    JSONObject parsed = snapshot.getJSONObject("commands").getJSONObject("hidl-registered-services");
    assertEquals("not-observed-not-proof-of-absence", parsed.getString("interpretation"));
    assertEquals(0, parsed.getJSONArray("observedTargetRows").length());
  }

  @Test public void oldUnrelatedDenialAndMissingTimeCannotImpersonateCurrentAppFailure() throws Exception {
    JSONObject snapshot = HidlDiagnostics.snapshot();
    JSONObject lookup = new JSONObject().put("uid", 10091).put("pid", 42)
        .put("startedAt", 1793437684653L).put("finishedAt", 1793437684654L);
    HidlDiagnostics.logEvidence(snapshot, item("hidl-security-logs",
        "1793434334.100 444 444 E SELinux: avc denied pid=22481 uid=2000 name=gatekeeper\n", false, true), lookup);
    JSONObject denial = snapshot.getJSONObject("logEvidence").getJSONArray("denialsOrLookupFailures").getJSONObject(0);
    assertFalse(denial.getBoolean("withinAppLookupWindow"));
    assertFalse(denial.getBoolean("mentionsAppIdentity"));
    assertFalse(denial.getBoolean("mentionsMessageCenter"));
    HidlDiagnostics.logEvidence(snapshot, item("hidl-security-logs", "permission denied", false, true), lookup);
    assertEquals(0, snapshot.getJSONObject("logEvidence").getInt("parsedLines"));
  }

  @Test public void logCommandUsesValidatedDeviceTimeOrExplicitBoundedFallbackAndInventoryIsReadOnly() throws Exception {
    assertTrue(HidlDiagnostics.logs(item("clock", exit("1793437684 2026-10-31 17:08:04.000 +0800\n", 0), false, true))
        .shell.contains("-T '10-31 17:08:04.000'"));
    assertTrue(HidlDiagnostics.logs(item("clock", "invalid; malicious", false, true)).shell.contains("-t 2000"));
    assertFalse(HidlDiagnostics.logs(item("clock", "invalid; malicious", false, true)).shell.contains("malicious"));
    List<AdbDiagnosticCommands.Command> commands = HidlDiagnostics.inventory();
    assertEquals(7, commands.size());
    for (AdbDiagnosticCommands.Command command : commands) {
      assertTrue(command.shell.contains("timeout 6"));
      assertFalse(command.shell.matches("(?s).*\\b(setprop|su|chmod|chown|setenforce|reboot|svc|rm)\\b.*"));
    }
  }
}
