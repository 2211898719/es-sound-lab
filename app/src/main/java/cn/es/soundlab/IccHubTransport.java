package cn.es.soundlab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import org.json.JSONObject;

final class IccHubTransport implements IccHubProbe.Transport {
  static final String INFO_URL = "http://ota_proxy/info";
  private final IccVehicleTransport.Connections connections;
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "icc-hub-timeout");
    thread.setDaemon(true);
    return thread;
  });
  private HttpURLConnection current;
  private boolean closed, consumed;

  IccHubTransport() { this((url, proxy) -> (HttpURLConnection) url.openConnection(proxy)); }
  IccHubTransport(IccVehicleTransport.Connections connections) { this.connections = connections; }

  @Override public IccVehicleProbe.Reply query() throws Exception {
    JSONObject observations = new JSONObject();
    long started = System.nanoTime();
    observations.put("stage", "open-proxy");
    try { return queryOnce(observations); }
    catch (Exception error) {
      long duration = TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
      observations.put("elapsedMillis", duration).put("deadlineReached", duration >= 3500);
      if (duration >= 3500) error = new SocketTimeoutException("request-deadline");
      throw new IccHubFailure(observations.optString("stage", "transport"), error, observations);
    }
  }

  private IccVehicleProbe.Reply queryOnce(JSONObject observations) throws Exception {
    synchronized (this) {
      if (closed || consumed) throw new IOException("single-shot-finished");
      consumed = true;
    }
    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3500);
    HttpURLConnection connection = connections.open(new URL(INFO_URL), new Proxy(Proxy.Type.HTTP,
        new InetSocketAddress(IccVehicleTransport.PROXY_HOST, IccVehicleTransport.PROXY_PORT)));
    synchronized (this) {
      if (closed) { connection.disconnect(); throw new IOException("closed"); }
      current = connection;
    }
    ScheduledFuture<?> timeout = null;
    try {
      timeout = deadlines.schedule(connection::disconnect, 3500, TimeUnit.MILLISECONDS);
      observations.put("stage", "configure-get");
      SecureRandom random = new SecureRandom();
      StringBuilder seed = new StringBuilder(20);
      for (int index = 0; index < 20; index++) seed.append((char) ('a' + random.nextInt(26)));
      connection.setConnectTimeout(1500);
      connection.setReadTimeout(1500);
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestMethod("GET");
      connection.setDoOutput(false);
      connection.setRequestProperty("Accept", "cs/pb-a, application/x-protobuf, application/octet-stream");
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("Connection", "close");
      connection.setRequestProperty("Seed", seed.toString());
      observations.put("stage", "response-status");
      int status = connection.getResponseCode();
      if (status < 100 || status > 999) throw new ProtocolException("invalid status line");
      observations.put("httpStatus", status);
      requireTime(until);
      if (status != 200) return new IccVehicleProbe.Reply(status, null);
      observations.put("stage", "response-headers");
      String type = connection.getContentType();
      type = type == null ? "missing" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
      boolean supportedType = type.equals("cs/pb-a") || type.equals("cs/plain")
          || type.equals("application/x-protobuf") || type.equals("application/octet-stream");
      observations.put("responseType", supportedType || type.equals("missing") ? type : "other");
      String encoding = connection.getContentEncoding();
      observations.put("responseEncoding", encoding == null ? "missing"
          : encoding.equalsIgnoreCase("identity") ? "identity"
          : encoding.equalsIgnoreCase("gzip") ? "gzip" : "other");
      if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("unexpected-compression");
      String responseSeed = connection.getHeaderField("Seed");
      observations.put("responseSeedPresent", responseSeed != null);
      long declared = connection.getContentLengthLong();
      observations.put("declaredBodyBytes", declared).put("receivedBodyBytes", 0);
      if (declared > IccHubWire.MAX_BODY) throw new IOException("body-too-large");
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      observations.put("stage", "response-body");
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[1024];
        while (true) {
          requireTime(until);
          int count = input.read(buffer);
          if (count < 0) break;
          observations.put("receivedBodyBytes", body.size() + count);
          if (body.size() + count > IccHubWire.MAX_BODY) throw new IOException("body-too-large");
          body.write(buffer, 0, count);
        }
      }
      requireTime(until);
      if (declared >= 0 && declared != body.size()) throw new IOException("truncated-body");
      byte[] result = body.toByteArray();
      if (result.length == 0) return new IccVehicleProbe.Reply(status, result);
      observations.put("stage", "response-envelope");
      if (type.equals("missing")) throw new IOException("missing-content-type");
      if (!supportedType) throw new IOException("unsupported-content-type");
      if (responseSeed != null) {
        observations.put("stage", "decrypt-response");
        result = IccVehicleWire.crypt(Cipher.DECRYPT_MODE, result, responseSeed);
      }
      else if (type.equals("cs/pb-a")) throw new IOException("missing-response-seed");
      requireTime(until);
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
