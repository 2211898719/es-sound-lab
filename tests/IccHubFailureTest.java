package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import javax.crypto.BadPaddingException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccHubFailureTest {
  @Test public void unexpectedEndOfStreamIsClassifiedWithoutLeakingTheOriginalMessage() throws Exception {
    IccHubFailure failure = new IccHubFailure("response-status",
        new IOException("unexpected end of stream on private-device Seed=private-token VIN=secret"), null);
    assertEquals("unexpected-eof", failure.getMessage());
    assertEquals("response-status", failure.snapshot().getString("stage"));
    assertEquals("IOException", failure.snapshot().getString("errorType"));
    assertTrue(failure.summary().contains("连接或接收HTTP状态行失败"));
    assertTrue(failure.summary().contains("尚未取得HTTP状态"));
    for (String secret : new String[] {"private-device", "private-token", "VIN=secret"}) {
      assertFalse(failure.snapshot().toString().contains(secret));
      assertFalse(failure.summary().contains(secret));
    }
    assertNull(failure.getCause());
  }

  @Test public void connectionExceptionsDoNotAutomaticallyMeanRefusal() throws Exception {
    ConnectException denied = new ConnectException("Failed to connect to private-host");
    denied.initCause(new IOException("connect failed: EPERM (Operation not permitted) Seed=secret"));
    assertEquals("permission-denied", new IccHubFailure("response-status", denied, null).getMessage());
    assertEquals("connection-failed", new IccHubFailure("response-status", new ConnectException(), null).getMessage());
    assertEquals("connection-refused", new IccHubFailure("response-status",
        new ConnectException("connect failed: ECONNREFUSED"), null).getMessage());
  }

  @Test public void knownSignalsMapToFixedCodesInsteadOfRawText() {
    String[][] cases = {{"Connection reset by peer Seed=secret", "connection-reset"},
        {"Socket closed", "connection-closed"}, {"Broken pipe at private-device", "connection-closed"},
        {"Cleartext HTTP traffic to private-host not permitted", "cleartext-blocked"},
        {"connect failed: ENETUNREACH", "unreachable"}, {"Read timed out", "timeout"},
        {"Unexpected status line: private-response", "protocol-error"},
        {"body-too-large", "body-too-large"}, {"invalid-seed", "invalid-seed"},
        {"private-token", "io-error"}, {"request failed with private data", "io-error"}};
    for (String[] entry : cases) assertEquals(entry[0], entry[1],
        new IccHubFailure("transport", new IOException(entry[0]), null).getMessage());
  }

  @Test public void typedFailuresAndNestedCausesAreRecognized() {
    assertEquals("timeout", new IccHubFailure("response-body", new SocketTimeoutException(), null).getMessage());
    assertEquals("unexpected-eof", new IccHubFailure("response-body", new EOFException(), null).getMessage());
    assertEquals("protocol-error", new IccHubFailure("response-status", new ProtocolException(), null).getMessage());
    assertEquals("decryption-failed", new IccHubFailure("decrypt-response", new BadPaddingException(), null).getMessage());
    assertEquals("timeout", new IccHubFailure("response-body",
        new IOException("generic wrapper", new SocketTimeoutException("private-data")), null).getMessage());
  }

  @Test public void metadataIsAllowlistedAndDefensiveWithoutKeepingBodySeedOrMessage() throws Exception {
    JSONObject metadata = new JSONObject().put("httpStatus", 200).put("declaredBodyBytes", 48)
        .put("receivedBodyBytes", 8).put("responseType", "cs/pb-a").put("responseSeedPresent", true)
        .put("rawBody", "private-body").put("Seed", "private-seed").put("exceptionMessage", "private-msg");
    IccHubFailure failure = new IccHubFailure("response-body", new IOException("Connection reset by peer"), metadata);
    JSONObject snapshot = failure.snapshot();
    assertEquals(200, snapshot.getInt("httpStatus")); assertEquals(8, snapshot.getInt("receivedBodyBytes"));
    assertFalse(snapshot.has("rawBody")); assertFalse(snapshot.has("Seed")); assertFalse(snapshot.has("exceptionMessage"));
    metadata.put("httpStatus", 403); snapshot.put("httpStatus", 500);
    assertEquals(200, failure.snapshot().getInt("httpStatus"));
    assertTrue(failure.summary().contains("读取响应体失败")); assertTrue(failure.summary().contains("HTTP 200"));
  }

  @Test public void nullMessagesAndCyclicCauseChainsStayBoundedAndSanitized() {
    IOException cycle = new IOException("private") {
      @Override public synchronized Throwable getCause() { return this; }
    };
    assertEquals("io-error", new IccHubFailure("transport", cycle, null).getMessage());
    assertEquals("io-error", new IccHubFailure("transport", new IOException(), null).getMessage());
    assertEquals("transport-error", new IccHubFailure("transport", new Exception("private"), null).getMessage());
  }
}
