package cn.es.soundlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;

final class IccVehicleTransport implements IccVehicleProbe.Transport {
  static final String PROXY_HOST = "192.168.55.31", LOGICAL_HOST = "ota_icc_vsi";
  static final int PROXY_PORT = 51020;
  static final String CONDITION_URL = "http://ota_icc_vsi/cdt";

  interface Connections { HttpURLConnection open(URL url, Proxy proxy) throws Exception; }

  private final Connections connections;
  private final SecureRandom random = new SecureRandom();
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "icc-query-timeout");
    thread.setDaemon(true);
    return thread;
  });
  private HttpURLConnection current;
  private boolean closed;

  IccVehicleTransport() { this((url, proxy) -> (HttpURLConnection) url.openConnection(proxy)); }
  IccVehicleTransport(Connections connections) { this.connections = connections; }

  @Override public IccVehicleProbe.Reply query() throws Exception {
    synchronized (this) { if (closed) throw new IOException("closed"); }
    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3500);
    HttpURLConnection connection = connections.open(new URL(CONDITION_URL),
        new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT)));
    synchronized (this) {
      if (closed) { connection.disconnect(); throw new IOException("closed"); }
      current = connection;
    }
    ScheduledFuture<?> timeout = null;
    try {
      timeout = deadlines.schedule(connection::disconnect, 3500, TimeUnit.MILLISECONDS);
      StringBuilder seedText = new StringBuilder(20);
      for (int index = 0; index < 20; index++) seedText.append((char) ('a' + random.nextInt(26)));
      String seed = seedText.toString();
      byte[] request = IccVehicleWire.request(seed);
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(1500);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "cs/pb-a");
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("Connection", "close");
      connection.setRequestProperty("Seed", seed);
      connection.setFixedLengthStreamingMode(request.length);
      try (OutputStream output = connection.getOutputStream()) { output.write(request); }
      int status = connection.getResponseCode();
      if (status != 200) return new IccVehicleProbe.Reply(status, null);
      requireTime(until);
      String type = connection.getContentType();
      if (type == null) throw new IOException("missing-content-type");
      type = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
      if (!type.equals("cs/pb-a") && !type.equals("cs/plain")
          && !type.equals("application/x-protobuf") && !type.equals("application/octet-stream"))
        throw new IOException("unsupported-content-type");
      String encoding = connection.getContentEncoding();
      if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("unexpected-compression");
      long declared = connection.getContentLengthLong();
      if (declared > IccVehicleWire.MAX_BODY) throw new IOException("body-too-large");
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[1024];
        while (true) {
          requireTime(until);
          int count = input.read(buffer);
          if (count < 0) break;
          if (body.size() + count > IccVehicleWire.MAX_BODY) throw new IOException("body-too-large");
          body.write(buffer, 0, count);
        }
      }
      requireTime(until);
      if (declared >= 0 && declared != body.size()) throw new IOException("truncated-body");
      byte[] result = body.toByteArray();
      String responseSeed = connection.getHeaderField("Seed");
      if (responseSeed != null) {
        result = IccVehicleWire.crypt(Cipher.DECRYPT_MODE, result, responseSeed);
      } else if (type.equals("cs/pb-a")) throw new IOException("missing-response-seed");
      return new IccVehicleProbe.Reply(status, result);
    } finally {
      if (timeout != null) timeout.cancel(false);
      synchronized (this) { if (current == connection) current = null; }
      connection.disconnect();
    }
  }

  private static void requireTime(long until) throws SocketTimeoutException {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= until)
      throw new SocketTimeoutException("request-deadline");
  }

  @Override public void close() {
    HttpURLConnection connection;
    synchronized (this) { closed = true; connection = current; current = null; }
    if (connection != null) connection.disconnect();
    deadlines.shutdownNow();
  }
}
