package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayInputStream;
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
import java.net.URL;
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
public final class IccVehicleTransportTest {
  static class Connection extends HttpURLConnection {
    final ByteArrayOutputStream written = new ByteArrayOutputStream();
    byte[] plain = {72, 4};
    byte[] rawBody;
    boolean encrypted = true;
    int status = 200, reads, disconnects;
    String type = "cs/pb-a", responseSeed, encoding;
    long declared = -2;
    Connection() throws Exception { super(new URL(IccVehicleTransport.CONDITION_URL)); }
    @Override public void connect() {}
    @Override public void disconnect() { disconnects++; }
    @Override public boolean usingProxy() { return true; }
    @Override public OutputStream getOutputStream() { return written; }
    @Override public int getResponseCode() throws IOException { return status; }
    @Override public String getContentType() { return type; }
    @Override public String getContentEncoding() { return encoding; }
    @Override public String getHeaderField(String name) {
      return name.equalsIgnoreCase("Seed") && encrypted
          ? responseSeed == null ? getRequestProperty("Seed") : responseSeed : null;
    }
    private byte[] encoded() {
      if (rawBody != null) return rawBody;
      try { return encrypted ? IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, plain, getHeaderField("Seed")) : plain; }
      catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @Override public long getContentLengthLong() { return declared == -2 ? encoded().length : declared; }
    @Override public InputStream getInputStream() { reads++; return new ByteArrayInputStream(encoded()); }
  }

  @Test public void onlyFixedIccProxyAndReadOnlyConditionRequestAreSent() throws Exception {
    Connection connection = new Connection();
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> {
      assertEquals("http://ota_icc_vsi/cdt", url.toString()); assertEquals(Proxy.Type.HTTP, proxy.type());
      InetSocketAddress address = (InetSocketAddress) proxy.address();
      assertEquals("192.168.55.31", address.getHostString()); assertEquals(51020, address.getPort());
      return connection;
    });
    try {
      IccVehicleProbe.Reply reply = transport.query(); assertEquals(200, reply.status);
      assertArrayEquals(new byte[] {72, 4}, reply.body); assertEquals("POST", connection.getRequestMethod());
      assertFalse(connection.getInstanceFollowRedirects()); assertFalse(connection.getUseCaches());
      assertEquals(1500, connection.getConnectTimeout()); assertEquals(1500, connection.getReadTimeout());
      assertEquals("cs/pb-a", connection.getRequestProperty("Content-Type"));
      assertTrue(connection.getRequestProperty("Seed").matches("[a-z]{20}"));
      assertArrayEquals(new byte[] {10, 3, 77, 68, 65}, IccVehicleWire.crypt(Cipher.DECRYPT_MODE,
          connection.written.toByteArray(), connection.getRequestProperty("Seed")));
      assertEquals(1, connection.disconnects);
    } finally { transport.close(); }
  }

  @Test public void forbiddenAndRedirectResponsesNeverReadBodiesOrFollow() throws Exception {
    for (int status : new int[] {401, 403, 302}) {
      Connection connection = new Connection(); connection.status = status;
      AtomicInteger calls = new AtomicInteger();
      IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> { calls.incrementAndGet(); return connection; });
      try {
        assertEquals(status, transport.query().status); assertEquals(1, calls.get());
        assertEquals(0, connection.reads); assertFalse(connection.getInstanceFollowRedirects());
      } finally { transport.close(); }
    }
  }

  @Test public void failedRequestDoesNotPermanentlyCloseTransportAndNextAttemptUsesNewConnection() throws Exception {
    Connection failed = new Connection() {
      @Override public int getResponseCode() throws IOException { throw new SocketTimeoutException("test timeout"); }
    };
    Connection recovered = new Connection();
    AtomicInteger opens = new AtomicInteger();
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> opens.incrementAndGet() == 1 ? failed : recovered);
    try {
      assertThrows(SocketTimeoutException.class, transport::query);
      assertEquals(1, failed.disconnects);
      assertEquals(200, transport.query().status);
      assertEquals(2, opens.get());
      assertEquals(1, recovered.disconnects);
      assertNotEquals(failed.getRequestProperty("Seed"), recovered.getRequestProperty("Seed"));
    } finally { transport.close(); }
    assertThrows(IOException.class, transport::query);
    assertEquals(2, opens.get());
  }

  @Test public void plainProtobufIsAcceptedButHtmlAndCompressedResponsesAreNot() throws Exception {
    Connection connection = new Connection(); connection.encrypted = false; connection.type = "application/x-protobuf";
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    try {
      assertArrayEquals(connection.plain, transport.query().body);
      connection.type = "text/html"; assertThrows(IOException.class, transport::query);
      connection.type = "application/x-protobuf"; connection.encoding = "gzip";
      assertThrows(IOException.class, transport::query);
    } finally { transport.close(); }
  }

  @Test public void declaredAndStreamingSizeBoundsAreBothEnforced() throws Exception {
    Connection connection = new Connection(); connection.encrypted = false; connection.type = "cs/plain";
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    try {
      connection.declared = IccVehicleWire.MAX_BODY + 1;
      assertThrows(IOException.class, transport::query); assertEquals(0, connection.reads);
      connection.declared = -1; connection.plain = new byte[IccVehicleWire.MAX_BODY + 1];
      assertThrows(IOException.class, transport::query); assertEquals(1, connection.reads);
    } finally { transport.close(); }
  }

  @Test public void seedAndTruncatedBodyErrorsAreNotDecodedAsVehicleState() throws Exception {
    Connection connection = new Connection();
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    try {
      connection.encrypted = false;
      assertThrows(IOException.class, transport::query);
      connection.type = "cs/plain"; connection.declared = 5;
      assertThrows(IOException.class, transport::query);
    } finally { transport.close(); }
  }

  @Test public void responseSeedIsIndependentAndNotLimitedToTheRequestGeneratorFormat() throws Exception {
    Connection connection = new Connection();
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    try {
      for (String seed : new String[] {"abcdefghijklmnopqrst", "SERVER-Seed_20261101-0123456789", " seed "}) {
        connection.responseSeed = seed;
        IccVehicleProbe.Reply reply = transport.query();
        assertNotEquals(seed, connection.getRequestProperty("Seed"));
        assertArrayEquals(connection.plain, reply.body);
        assertEquals("R", IccVehicleWire.decode(reply.body).gearName());
      }
    } finally { transport.close(); }
  }

  @Test public void invalidResponseSeedsAreRejectedWithoutLeakingTheirValues() throws Exception {
    Connection connection = new Connection(); connection.rawBody = new byte[16];
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    try {
      for (String seed : new String[] {"", "A".repeat(257), "private\r\nseed", "private\tseed", "\u007f", "\u0080"}) {
        connection.responseSeed = seed;
        IOException failure = assertThrows(IOException.class, transport::query);
        assertEquals("invalid-seed", failure.getMessage());
      }
    } finally { transport.close(); }
  }

  @Test public void invalidEncryptedBodyCannotFallBackToPlaintextVehicleState() throws Exception {
    Connection connection = new Connection(); connection.responseSeed = "abcdefghijklmnopqrst";
    connection.rawBody = new byte[] {72, 4};
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    try {
      assertThrows(javax.crypto.IllegalBlockSizeException.class, transport::query);
      assertEquals(1, connection.reads); assertEquals(1, connection.disconnects);
      byte[] badPadding = IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, new byte[16], connection.responseSeed);
      badPadding[15] ^= 1; connection.rawBody = badPadding;
      assertThrows(javax.crypto.BadPaddingException.class, transport::query);
      assertEquals(2, connection.reads); assertEquals(2, connection.disconnects);
    } finally { transport.close(); }
  }

  @Test public void closedTransportCannotSendRequests() throws Exception {
    Connection connection = new Connection();
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    transport.close(); assertThrows(IOException.class, transport::query);
    assertEquals(0, connection.written.size());
  }

  @Test public void closingProbeDisconnectsAnInFlightTransport() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
    Connection connection = new Connection() {
      @Override public int getResponseCode() throws IOException {
        entered.countDown();
        try { released.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        throw new SocketTimeoutException("cancelled");
      }
      @Override public void disconnect() { super.disconnect(); released.countDown(); }
    };
    IccVehicleTransport transport = new IccVehicleTransport((url, proxy) -> connection);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<IccVehicleProbe.Reply> future = executor.submit(transport::query);
      assertTrue(entered.await(2, TimeUnit.SECONDS)); transport.close();
      assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
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

  @Test public void realHttpProxyUsesAbsoluteReadOnlyUrlAndDoesNotResolveLogicalHost() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(3000);
      Future<String> request = executor.submit(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(3000); InputStream input = socket.getInputStream();
          String requestLine = line(input), seed = null; int size = 0;
          for (int index = 0; index < 64; index++) {
            String header = line(input); if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            String name = header.substring(0, colon).trim(), value = header.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Seed")) seed = value;
            if (name.equalsIgnoreCase("Content-Length")) size = Integer.parseInt(value);
          }
          assertEquals(16, size);
          assertArrayEquals(new byte[] {10, 3, 77, 68, 65},
              IccVehicleWire.crypt(Cipher.DECRYPT_MODE, input.readNBytes(size), seed));
          assertTrue(seed.matches("[a-z]{20}"));
          String responseSeed = "SERVER-Seed_20261101-0123456789";
          assertNotEquals(seed, responseSeed);
          byte[] body = IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, new byte[] {72, 4}, responseSeed);
          OutputStream output = socket.getOutputStream();
          output.write(("HTTP/1.1 200 OK\r\nContent-Type: cs/pb-a\r\nContent-Length: " + body.length
              + "\r\nSeed: " + responseSeed + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          output.write(body); output.flush(); return requestLine;
        }
      });
      IccVehicleTransport transport = new IccVehicleTransport((url, proxy) ->
          (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP,
              new InetSocketAddress(server.getInetAddress(), server.getLocalPort()))));
      try {
        assertArrayEquals(new byte[] {72, 4}, transport.query().body);
        assertEquals("POST http://ota_icc_vsi/cdt HTTP/1.1", request.get(2, TimeUnit.SECONDS));
      } finally { transport.close(); }
    } finally { executor.shutdownNow(); }
  }
}
