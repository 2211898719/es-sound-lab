package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccHubTransportTest {
  static class Connection extends IccVehicleTransportTest.Connection {
    Connection() throws Exception { plain = IccHubWireTest.route("ota_icc_vsi", "192.168.55.40:51020"); }
    @Override public OutputStream getOutputStream() { throw new AssertionError("GET must not write a request body"); }
  }

  @Test public void fixedReadOnlyGetUsesOriginalProxyAndSeedButNeverSendsBodyOrRetries() throws Exception {
    Connection connection = new Connection();
    AtomicInteger opens = new AtomicInteger();
    IccHubTransport transport = new IccHubTransport((url, proxy) -> {
      opens.incrementAndGet();
      assertEquals("http://ota_proxy/info", url.toString());
      assertEquals(Proxy.Type.HTTP, proxy.type());
      assertEquals(new InetSocketAddress("192.168.55.31", 51020), proxy.address());
      return connection;
    });
    try {
      assertArrayEquals(connection.plain, transport.query().body);
      assertEquals("GET", connection.getRequestMethod());
      assertFalse(connection.getDoOutput()); assertFalse(connection.getUseCaches());
      assertFalse(connection.getInstanceFollowRedirects());
      assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
      assertTrue(connection.getRequestProperty("Seed").matches("[a-z]{20}"));
      assertEquals(1500, connection.getConnectTimeout()); assertEquals(1500, connection.getReadTimeout());
      assertThrows(IOException.class, transport::query);
      assertEquals(1, opens.get()); assertEquals(1, connection.reads); assertEquals(1, connection.disconnects);
    } finally { transport.close(); }
  }

  @Test public void responseSeedIsIndependentAndPlainProtobufIsAccepted() throws Exception {
    for (boolean encrypted : new boolean[] {true, false}) {
      Connection connection = new Connection();
      connection.encrypted = encrypted;
      connection.responseSeed = "SERVER-Independent_Seed";
      if (!encrypted) connection.type = "application/x-protobuf; charset=utf-8";
      IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
      try { assertArrayEquals(connection.plain, transport.query().body); }
      finally { transport.close(); }
    }
  }

  @Test public void originalServerEmptyProtoWithoutContentTypeRemainsEmpty() throws Exception {
    Connection connection = new Connection();
    connection.encrypted = false; connection.plain = new byte[0]; connection.type = null;
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    try { assertArrayEquals(new byte[0], transport.query().body); }
    finally { transport.close(); }
  }

  @Test public void deniedRedirectAndMissingHandlerDoNotReadErrorBodiesOrRetry() throws Exception {
    for (int status : new int[] {301, 302, 307, 401, 403, 404, 500}) {
      Connection connection = new Connection(); connection.status = status;
      IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
      try {
        assertEquals(status, transport.query().status);
        assertEquals(0, connection.reads); assertEquals(1, connection.disconnects);
        assertThrows(IOException.class, transport::query);
      } finally { transport.close(); }
    }
  }

  @Test public void badTypesCompressionMissingSeedTruncationAndOversizeAreRejected() throws Exception {
    for (int mode = 0; mode < 7; mode++) {
      Connection connection = new Connection();
      if (mode == 0) connection.type = "text/html";
      if (mode == 1) connection.type = null;
      if (mode == 2) connection.encoding = "gzip";
      if (mode == 3) connection.encrypted = false;
      if (mode == 4) connection.declared = IccHubWire.MAX_BODY + 1;
      if (mode == 5) connection.declared = 1;
      if (mode == 6) connection.rawBody = new byte[IccHubWire.MAX_BODY + 1];
      IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
      try { assertThrows(Exception.class, transport::query); assertEquals(1, connection.disconnects); }
      finally { transport.close(); }
    }
  }

  @Test public void closedTransportCannotOpenConnection() throws Exception {
    AtomicInteger opens = new AtomicInteger();
    IccHubTransport transport = new IccHubTransport((url, proxy) -> { opens.incrementAndGet(); return new Connection(); });
    transport.close(); assertThrows(IOException.class, transport::query); assertEquals(0, opens.get());
  }

  @Test public void cancellationDisconnectsInFlightWithoutWaitingForReply() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
    Connection connection = new Connection() {
      @Override public int getResponseCode() throws IOException {
        entered.countDown();
        try { released.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        throw new SocketTimeoutException("cancelled");
      }
      @Override public void disconnect() { super.disconnect(); released.countDown(); }
    };
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<IccVehicleProbe.Reply> result = executor.submit(transport::query);
      assertTrue(entered.await(2, TimeUnit.SECONDS)); transport.close();
      assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
      assertTrue(connection.disconnects > 0);
    } finally { transport.close(); executor.shutdownNow(); }
  }

  private static String line(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    while (bytes.size() < 2048) {
      int next = input.read();
      if (next < 0) throw new IOException("end-of-headers");
      if (next == '\n') return bytes.toString(StandardCharsets.US_ASCII).trim();
      bytes.write(next);
    }
    throw new IOException("header-too-long");
  }

  @Test public void openingProxyFailureIsDistinctFromReceivingAnHttpResponse() throws Exception {
    AtomicInteger opens = new AtomicInteger();
    IccHubTransport transport = new IccHubTransport((url, proxy) -> {
      opens.incrementAndGet(); throw new IOException("private socket failure VIN=secret");
    });
    try {
      IccHubFailure failure = assertThrows(IccHubFailure.class, transport::query);
      assertEquals("open-proxy", failure.snapshot().getString("stage"));
      assertEquals("io-error", failure.snapshot().getString("reason"));
      assertFalse(failure.snapshot().has("httpStatus"));
      assertFalse(failure.snapshot().toString().contains("secret"));
      assertThrows(IccHubFailure.class, transport::query); assertEquals(1, opens.get());
    } finally { transport.close(); }
  }

  @Test public void peerClosingBeforeStatusDoesNotInventAnHttpStatus() throws Exception {
    Connection connection = new Connection() {
      @Override public int getResponseCode() throws IOException {
        throw new IOException("unexpected end of stream on private-peer Seed=private-seed");
      }
    };
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    try {
      IccHubFailure failure = assertThrows(IccHubFailure.class, transport::query);
      assertEquals("response-status", failure.snapshot().getString("stage"));
      assertEquals("unexpected-eof", failure.snapshot().getString("reason"));
      assertFalse(failure.snapshot().has("httpStatus")); assertEquals(0, connection.reads);
      assertFalse(failure.snapshot().toString().contains("private-seed"));
    } finally { transport.close(); }
  }

  @Test public void partialResponseKeepsHttpStatusByteCountsAndSanitizedHeaderKinds() throws Exception {
    Connection connection = new Connection() {
      @Override public InputStream getInputStream() {
        reads++;
        return new InputStream() {
          boolean first = true;
          @Override public int read() throws IOException { throw new IOException("Connection reset Seed=private-seed"); }
          @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!first) throw new IOException("Connection reset Seed=private-seed VIN=private-vin");
            first = false; java.util.Arrays.fill(buffer, offset, offset + 8, (byte) 7); return 8;
          }
        };
      }
    };
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    try {
      IccHubFailure failure = assertThrows(IccHubFailure.class, transport::query);
      org.json.JSONObject report = failure.snapshot();
      assertEquals("response-body", report.getString("stage")); assertEquals(200, report.getInt("httpStatus"));
      assertEquals("connection-reset", report.getString("reason"));
      assertEquals(8, report.getInt("receivedBodyBytes")); assertTrue(report.getLong("declaredBodyBytes") > 8);
      assertEquals("cs/pb-a", report.getString("responseType")); assertTrue(report.getBoolean("responseSeedPresent"));
      assertFalse(report.toString().contains("private-seed")); assertFalse(report.toString().contains("private-vin"));
      assertEquals(1, connection.reads); assertEquals(1, connection.disconnects);
    } finally { transport.close(); }
  }

  @Test public void decryptionFailureRetainsResponseMetadataButNotCiphertextOrSeed() throws Exception {
    Connection connection = new Connection(); connection.rawBody = new byte[] {72, 4};
    connection.responseSeed = "private-response-seed";
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    try {
      IccHubFailure failure = assertThrows(IccHubFailure.class, transport::query);
      assertEquals("decrypt-response", failure.snapshot().getString("stage"));
      assertEquals("decryption-failed", failure.snapshot().getString("reason"));
      assertEquals(200, failure.snapshot().getInt("httpStatus"));
      assertEquals(2, failure.snapshot().getInt("receivedBodyBytes"));
      assertFalse(failure.snapshot().toString().contains("private-response-seed"));
    } finally { transport.close(); }
  }

  @Test public void invalidStatusSentinelIsReportedAsProtocolFailureNotHttpMinusOne() throws Exception {
    Connection connection = new Connection(); connection.status = -1;
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    try {
      IccHubFailure failure = assertThrows(IccHubFailure.class, transport::query);
      assertEquals("protocol-error", failure.snapshot().getString("reason"));
      assertFalse(failure.snapshot().has("httpStatus")); assertEquals(0, connection.reads);
    } finally { transport.close(); }
  }

  @Test public void totalDeadlineDisconnectIsIdentifiedAsTimeoutRatherThanGenericSocketClosure() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    Connection connection = new Connection() {
      @Override public int getResponseCode() throws IOException {
        try { release.await(6, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        throw new IOException("Socket closed");
      }
      @Override public void disconnect() { super.disconnect(); release.countDown(); }
    };
    IccHubTransport transport = new IccHubTransport((url, proxy) -> connection);
    try {
      IccHubFailure failure = assertThrows(IccHubFailure.class, transport::query);
      assertEquals("timeout", failure.snapshot().getString("reason"));
      assertTrue(failure.snapshot().getBoolean("deadlineReached"));
      assertTrue(failure.snapshot().getLong("elapsedMillis") < 6000);
    } finally { transport.close(); }
  }

  @Test public void realLoopbackProxyReceivesAbsoluteGetAndNoBody() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(3000);
      Future<String> request = executor.submit(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(3000);
          InputStream input = socket.getInputStream();
          String requestLine = line(input), seed = null;
          for (int index = 0; index < 64; index++) {
            String header = line(input); if (header.isEmpty()) break;
            assertFalse(header.toLowerCase(java.util.Locale.ROOT).startsWith("transfer-encoding:"));
            if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
              assertEquals("0", header.substring(header.indexOf(':') + 1).trim());
            if (header.toLowerCase(java.util.Locale.ROOT).startsWith("seed:")) seed = header.substring(5).trim();
          }
          assertNotNull(seed); assertTrue(seed.matches("[a-z]{20}"));
          byte[] plain = IccHubWireTest.route("ota_icc_vsi", "192.168.55.40:8000");
          String responseSeed = "SERVER-Different_Seed";
          byte[] encrypted = IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, plain, responseSeed);
          OutputStream output = socket.getOutputStream();
          output.write(("HTTP/1.1 200 OK\r\nContent-Type: cs/pb-a\r\nContent-Length: " + encrypted.length
              + "\r\nSeed: " + responseSeed + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          output.write(encrypted); output.flush();
          return requestLine;
        }
      });
      IccHubTransport transport = new IccHubTransport((url, proxy) ->
          (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP,
              new InetSocketAddress(server.getInetAddress(), server.getLocalPort()))));
      try {
        assertEquals(1, IccHubWire.decode(transport.query().body).length());
        assertEquals("GET http://ota_proxy/info HTTP/1.1", request.get(2, TimeUnit.SECONDS));
      } finally { transport.close(); }
    } finally { executor.shutdownNow(); }
  }
}
