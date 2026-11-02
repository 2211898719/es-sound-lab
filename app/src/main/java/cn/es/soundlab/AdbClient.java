package cn.es.soundlab;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import javax.crypto.Cipher;

/** Minimal authenticated legacy TCP ADB transport. Exposes a single bounded shell stream. */
public final class AdbClient implements Closeable {
  public static final class TransferLimitException extends IOException {
    TransferLimitException(long limit, long transferred) {
      super("ADB文件超过本次读取上限：" + limit + "字节，已读取" + transferred + "字节");
    }
  }

  public static final class ShellException extends IOException {
    public final String partialOutput;

    ShellException(IOException error, String partial) {
      super(error.getMessage(), error);
      partialOutput = partial;
    }
  }

  private static final int CNXN = command("CNXN"),
      AUTH = command("AUTH"),
      OPEN = command("OPEN"),
      OKAY = command("OKAY"),
      WRTE = command("WRTE"),
      CLSE = command("CLSE"),
      STLS = command("STLS");
  private final Socket socket;
  private DataInputStream input;
  private OutputStream output;
  private int localId;
  private final LinkedHashMap<Integer, Integer> closedStreams = new LinkedHashMap<>();
  private volatile boolean cancelled;

  public interface Progress {
    void message(String message);
  }

  boolean isCancelled() { return cancelled; }

  public static final class Result {
    public final String text;
    public final boolean truncated;

    Result(String text, boolean truncated) {
      this.text = text;
      this.truncated = truncated;
    }
  }

  public AdbClient() {
    this(new Socket());
  }

  AdbClient(Socket socket) {
    this.socket = socket;
  }

  public void connect(String host, int port, KeyPair key, Progress progress) throws Exception {
    if (cancelled) throw new IOException("采集已取消");
    if (host == null || host.trim().isEmpty() || port < 1 || port > 65535)
      throw new IllegalArgumentException("请填写已开启的 ADB 地址和端口");
    socket.connect(new InetSocketAddress(host.trim(), port), 8000);
    socket.setSoTimeout(60000); // Allow the user to approve the device's RSA authorization dialog.
    input = new DataInputStream(socket.getInputStream());
    output = socket.getOutputStream();
    send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
    boolean signatureSent = false, publicSent = false;
    for (int attempt = 0; attempt < 8; attempt++) {
      Packet packet = receive();
      if (packet.command == CNXN) {
        socket.setSoTimeout(15000);
        progress.message("ADB 已连接；开始采集只读诊断");
        return;
      }
      if (packet.command == STLS)
        throw new IOException("此端口要求无线调试 TLS 配对。本页支持工程模式的传统 TCP ADB；请使用对应端口，或在电脑配对后运行采集脚本。");
      if (packet.command != AUTH || packet.arg0 != 1 || packet.data.length != 20)
        throw new IOException("ADB 握手不匹配；请核对传统 TCP ADB 端口");
      if (!signatureSent) {
        send(AUTH, 2, 0, sign(key, packet.data));
        signatureSent = true;
      } else if (!publicSent) {
        progress.message("请在车机系统弹窗中允许“ES Sound Lab”这次 ADB 连接；正在等待授权…");
        send(AUTH, 3, 0, publicKey((RSAPublicKey) key.getPublic()));
        publicSent = true;
      } else throw new IOException("ADB 授权未通过；请在车机确认授权后重试");
    }
    throw new IOException("ADB 握手未完成");
  }

  public Result shell(String command, int limit) throws IOException {
    if (cancelled) throw new IOException("采集已取消");
    int local = ++localId, remote = 0;
    long deadline = System.nanoTime() + 20000000000L;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
    try {
      while (true) {
        Packet packet = receiveStream(local, remote, deadline);
        if (packet.command == OKAY) {
          if (packet.arg0 == 0) throw streamError("ADB remote ID 无效", packet, local, remote);
          remote = packet.arg0;
        } else if (packet.command == WRTE && remote != 0 && packet.arg0 == remote) {
          int room = limit - bytes.size();
          bytes.write(packet.data, 0, Math.min(room, packet.data.length));
          send(OKAY, local, remote, new byte[0]);
          if (packet.data.length > room) {
            send(CLSE, local, remote, new byte[0]);
            for (int attempt = 0; attempt < 16; attempt++) {
              Packet trailing = receiveStream(local, remote, deadline);
              if (trailing.command == CLSE) {
                rememberClosed(local, remote);
                return new Result(new String(bytes.toByteArray(), StandardCharsets.UTF_8), true);
              }
              if (trailing.command == WRTE) send(OKAY, local, remote, new byte[0]);
              else throw streamError("ADB stream close 未完成", trailing, local, remote);
            }
            throw new IOException("ADB 截断流无法正常关闭");
          }
        } else if (packet.command == CLSE) {
          if (remote == 0)
            throw streamError("ADB shell 服务拒绝打开", packet, local, remote);
          rememberClosed(local, remote);
          send(CLSE, local, packet.arg0, new byte[0]);
          return new Result(new String(bytes.toByteArray(), StandardCharsets.UTF_8), false);
        } else throw streamError("ADB shell 流协议不匹配", packet, local, remote);
      }
    } catch (IOException error) {
      throw new ShellException(error, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
  }

  public long pull(String path, OutputStream destination, long limit, Progress progress)
      throws IOException {
    if (cancelled) throw new IOException("采集已取消");
    byte[] name = path.getBytes(StandardCharsets.UTF_8);
    if (!path.startsWith("/") || path.indexOf('\0') >= 0 || name.length > 1024 || limit <= 0)
      throw new IOException("无效的ADB文件读取请求");
    int local = ++localId;
    long deadline = System.nanoTime() + 1800000000000L;
    send(OPEN, local, 0, "sync:\0".getBytes(StandardCharsets.UTF_8));
    Packet opened = receiveStream(local, 0, deadline);
    if (opened.command != OKAY || opened.arg0 == 0)
      throw streamError("ADB sync 服务未打开", opened, local, 0);
    int remote = opened.arg0;
    ByteBuffer request = ByteBuffer.allocate(8 + name.length).order(ByteOrder.LITTLE_ENDIAN);
    request.putInt(command("RECV")).putInt(name.length).put(name);
    send(WRTE, local, remote, request.array());
    DataInputStream stream = new DataInputStream(new InputStream() {
      private byte[] data = new byte[0];
      private int offset;

      @Override
      public int read() throws IOException {
        byte[] single = new byte[1];
        return read(single, 0, 1) < 0 ? -1 : single[0] & 255;
      }

      @Override
      public int read(byte[] buffer, int start, int length) throws IOException {
        if (length == 0) return 0;
        while (offset == data.length) {
          Packet packet = receiveStream(local, remote, deadline);
          if (packet.command == OKAY) continue;
          if (packet.command != WRTE)
            throw streamError("ADB 文件传输提前结束", packet, local, remote);
          data = packet.data;
          offset = 0;
          send(OKAY, local, remote, new byte[0]);
        }
        int count = Math.min(length, data.length - offset);
        System.arraycopy(data, offset, buffer, start, count);
        offset += count;
        return count;
      }
    });
    long total = 0, reported = 0;
    byte[] buffer = new byte[16384];
    while (true) {
      int type = Integer.reverseBytes(stream.readInt());
      int length = Integer.reverseBytes(stream.readInt());
      if (type == command("DONE")) break;
      if (length < 0 || length > 65536) throw new IOException("ADB sync 数据块无效");
      if (type == command("FAIL")) {
        byte[] reason = new byte[length];
        stream.readFully(reason);
        throw new IOException("ADB 文件读取被拒绝：" + new String(reason, StandardCharsets.UTF_8));
      }
      if (type != command("DATA")) throw new IOException("ADB sync 响应无效");
      if (length > limit - total)
        throw new TransferLimitException(limit, total);
      for (int remaining = length; remaining > 0; ) {
        int count = Math.min(remaining, buffer.length);
        stream.readFully(buffer, 0, count);
        destination.write(buffer, 0, count);
        total += count;
        remaining -= count;
      }
      if (total - reported >= 1024 * 1024) {
        reported = total;
        progress.message("已读取 " + total / (1024 * 1024) + " MiB");
      }
    }
    send(CLSE, local, remote, new byte[0]);
    for (int attempt = 0; attempt < 16; attempt++) {
      Packet packet = receiveStream(local, remote, deadline);
      if (packet.command == CLSE) {
        rememberClosed(local, remote);
        return total;
      }
      if (packet.command == WRTE) send(OKAY, local, remote, new byte[0]);
      else if (packet.command != OKAY)
        throw streamError("ADB sync 关闭失败", packet, local, remote);
    }
    throw new IOException("ADB sync 关闭超出报文上限");
  }

  private Packet receiveStream(int local, int remote, long deadline) throws IOException {
    for (int attempt = 0; attempt < 64; attempt++) {
      if (cancelled) throw new IOException("采集已取消");
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new IOException("ADB 操作超过时限");
      socket.setSoTimeout((int) Math.max(1, Math.min(15000, remaining / 1000000)));
      Packet packet = receive();
      if (packet.arg1 == local) {
        if (remote != 0
            && packet.arg0 != remote
            && !(packet.command == CLSE && packet.arg0 == 0))
          throw streamError("ADB remote ID 不匹配", packet, local, remote);
        return packet;
      }
      Integer closedRemote = closedStreams.get(packet.arg1);
      if (closedRemote != null
          && (packet.arg0 == closedRemote
              || (packet.command == CLSE && packet.arg0 == 0))) {
        if (packet.command == CLSE || packet.command == OKAY) continue;
        if (packet.command == WRTE) {
          send(CLSE, packet.arg1, packet.arg0, new byte[0]);
          continue;
        }
      }
      throw streamError("ADB stream ID 不匹配", packet, local, remote);
    }
    throw new IOException("ADB 旧流报文过多；已停止采集");
  }

  private void rememberClosed(int local, int remote) {
    closedStreams.put(local, remote);
    if (closedStreams.size() > 64) closedStreams.remove(closedStreams.keySet().iterator().next());
  }

  private static IOException streamError(String message, Packet packet, int local, int remote) {
    String name =
        new String(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(packet.command).array(),
            StandardCharsets.US_ASCII);
    return new IOException(
        message
            + " [command=" + name
            + ", expectedLocal=" + local
            + ", actualLocal=" + packet.arg1
            + ", expectedRemote=" + remote
            + ", actualRemote=" + packet.arg0 + "]");
  }

  private static int command(String name) {
    byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
    ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    int checksum = 0;
    for (byte value : data) checksum += value & 255;
    header
        .putInt(command)
        .putInt(arg0)
        .putInt(arg1)
        .putInt(data.length)
        .putInt(checksum)
        .putInt(~command);
    output.write(header.array());
    output.write(data);
    output.flush();
  }

  private Packet receive() throws IOException {
    byte[] header = new byte[24];
    input.readFully(header);
    ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    int command = buffer.getInt(),
        arg0 = buffer.getInt(),
        arg1 = buffer.getInt(),
        length = buffer.getInt(),
        sum = buffer.getInt(),
        magic = buffer.getInt();
    if (magic != ~command || length < 0 || length > 1024 * 1024)
      throw new IOException("ADB 报文头无效或过大");
    byte[] data = new byte[length];
    input.readFully(data);
    int actual = 0;
    for (byte value : data) actual += value & 255;
    // ADB v1.0.1 peers may omit checksums, but older peers must provide the correct sum.
    if (sum != 0 && sum != actual) throw new IOException("ADB 报文校验失败");
    return new Packet(command, arg0, arg1, data);
  }

  private static final class Packet {
    final int command, arg0, arg1;
    final byte[] data;

    Packet(int c, int a, int b, byte[] d) {
      command = c;
      arg0 = a;
      arg1 = b;
      data = d;
    }
  }

  static byte[] sign(KeyPair key, byte[] token) throws Exception {
    byte[] prefix = {
      0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };
    byte[] digest = new byte[prefix.length + token.length];
    System.arraycopy(prefix, 0, digest, 0, prefix.length);
    System.arraycopy(token, 0, digest, prefix.length, token.length);
    Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    rsa.init(Cipher.ENCRYPT_MODE, key.getPrivate());
    return rsa.doFinal(digest);
  }

  static byte[] publicKey(RSAPublicKey key) {
    ByteBuffer adb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
    BigInteger n = key.getModulus(), word = BigInteger.ONE.shiftLeft(32);
    adb.putInt(64).putInt(n.modInverse(word).negate().intValue());
    putLittle(adb, n);
    putLittle(adb, BigInteger.ONE.shiftLeft(4096).mod(n));
    adb.putInt(key.getPublicExponent().intValue());
    return (Base64.getEncoder().encodeToString(adb.array()) + " ES-Sound-Lab@android\0")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void putLittle(ByteBuffer target, BigInteger value) {
    byte[] big = value.toByteArray();
    for (int i = 0; i < 256; i++) target.put(i < big.length ? big[big.length - i - 1] : (byte) 0);
  }

  @Override
  public void close() {
    cancelled = true;
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
