package cn.es.soundlab;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
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
import javax.crypto.Cipher;

/** Minimal authenticated legacy TCP ADB transport. Exposes a single bounded shell stream. */
public final class AdbClient implements Closeable {
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
  private volatile boolean cancelled;

  public interface Progress {
    void message(String message);
  }

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
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("ADB 命令超过20秒");
        socket.setSoTimeout((int) Math.max(1, Math.min(15000, remaining / 1000000)));
        Packet packet = receive();
        if (packet.arg1 != local) throw new IOException("ADB stream ID 不匹配");
        if (packet.command == OKAY) {
          if (remote != 0 && packet.arg0 != remote) throw new IOException("ADB remote ID 不匹配");
          remote = packet.arg0;
        } else if (packet.command == WRTE && remote != 0 && packet.arg0 == remote) {
          int room = limit - bytes.size();
          bytes.write(packet.data, 0, Math.min(room, packet.data.length));
          send(OKAY, local, remote, new byte[0]);
          if (packet.data.length > room) {
            send(CLSE, local, remote, new byte[0]);
            // Consume only this stream's close before starting the next command.
            for (int i = 0; i < 16; i++) {
              Packet trailing = receive();
              if (trailing.arg1 != local) throw new IOException("ADB stream close 不匹配");
              if (trailing.command == CLSE)
                return new Result(new String(bytes.toByteArray(), StandardCharsets.UTF_8), true);
              if (trailing.command == WRTE) send(OKAY, local, remote, new byte[0]);
              else throw new IOException("ADB stream close 未完成");
            }
            throw new IOException("ADB 截断流无法正常关闭");
          }
        } else if (packet.command == CLSE) {
          send(CLSE, local, packet.arg0, new byte[0]);
          return new Result(new String(bytes.toByteArray(), StandardCharsets.UTF_8), false);
        } else throw new IOException("ADB shell 流协议不匹配");
      }
    } catch (IOException error) {
      throw new ShellException(error, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
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
