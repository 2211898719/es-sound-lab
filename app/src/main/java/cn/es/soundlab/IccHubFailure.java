package cn.es.soundlab;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import org.json.JSONObject;

final class IccHubFailure extends IOException {
  private final String stage, reason;
  private final JSONObject details;

  IccHubFailure(String stage, Exception error, JSONObject observations) {
    super(reason(error));
    this.stage = stage;
    this.reason = getMessage();
    details = new JSONObject();
    try {
      details.put("stage", stage).put("reason", reason).put("errorType", type(error));
      String[] keys = {"httpStatus", "declaredBodyBytes", "receivedBodyBytes", "responseType",
          "responseEncoding", "responseSeedPresent", "elapsedMillis", "deadlineReached"};
      if (observations != null) for (String key : keys)
        if (observations.has(key)) details.put(key, observations.get(key));
    } catch (Exception ignored) {}
  }

  JSONObject snapshot() {
    try { return new JSONObject(details.toString()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  String summary() {
    String step = stage.equals("open-proxy") ? "创建代理连接"
        : stage.equals("configure-get") ? "准备GET请求"
        : stage.equals("response-status") ? "连接或接收HTTP状态行"
        : stage.equals("response-headers") ? "检查响应头"
        : stage.equals("response-body") ? "读取响应体"
        : stage.equals("response-envelope") ? "解析响应信封"
        : stage.equals("decrypt-response") ? "解密响应"
        : stage.equals("decode-routes") ? "解析路由数据" : "传输";
    String explanation = reason.equals("unexpected-eof") ? "对端在响应完成前关闭连接"
        : reason.equals("connection-reset") ? "连接被重置"
        : reason.equals("connection-refused") ? "目标拒绝连接"
        : reason.equals("connection-failed") ? "建立连接失败，原因尚未确认"
        : reason.equals("connection-closed") ? "连接已关闭"
        : reason.equals("timeout") ? "请求超时"
        : reason.equals("unreachable") ? "网络或目标地址不可达"
        : reason.equals("permission-denied") ? "系统禁止此访问"
        : reason.equals("cleartext-blocked") ? "系统限制明文HTTP"
        : reason.equals("protocol-error") ? "HTTP协议或状态行异常"
        : reason.equals("decryption-failed") ? "响应解密失败"
        : reason.equals("io-error") ? "发生I/O异常，尚不能确定具体原因" : reason;
    String status = details.has("httpStatus") ? " · HTTP " + details.optInt("httpStatus")
        : " · 尚未取得HTTP状态";
    return step + "失败：" + explanation + " [" + reason + "]" + status + "；请导出记录，不自动重试";
  }

  private static String type(Throwable error) {
    if (error instanceof SocketTimeoutException) return "SocketTimeoutException";
    if (error instanceof ConnectException) return "ConnectException";
    if (error instanceof NoRouteToHostException) return "NoRouteToHostException";
    if (error instanceof UnknownHostException) return "UnknownHostException";
    if (error instanceof ProtocolException) return "ProtocolException";
    if (error instanceof EOFException) return "EOFException";
    if (error instanceof SocketException) return "SocketException";
    if (error instanceof BadPaddingException) return "BadPaddingException";
    if (error instanceof IllegalBlockSizeException) return "IllegalBlockSizeException";
    if (error instanceof SecurityException) return "SecurityException";
    if (error instanceof IOException) return "IOException";
    return "Exception";
  }

  private static String reason(Throwable error) {
    Throwable current = error;
    String fallback = error instanceof IOException ? "io-error" : "transport-error";
    for (int index = 0; current != null && index < 5; index++, current = current.getCause()) {
      if (current instanceof SocketTimeoutException) return "timeout";
      if (current instanceof ConnectException) fallback = "connection-failed";
      if (current instanceof ProtocolException) fallback = "protocol-error";
      if (current instanceof InterruptedIOException) fallback = "interrupted";
      if (current instanceof NoRouteToHostException || current instanceof UnknownHostException) return "unreachable";
      if (current instanceof SecurityException) return "permission-denied";
      if (current instanceof EOFException) return "unexpected-eof";
      if (current instanceof BadPaddingException || current instanceof IllegalBlockSizeException) return "decryption-failed";
      String text = current.getMessage();
      if (text == null) continue;
      if (text.length() > 1024) text = text.substring(0, 1024);
      text = text.toLowerCase(Locale.ROOT);
      if (text.contains("unexpected end of stream") || text.contains("unexpected end of file")
          || text.contains("premature eof")) return "unexpected-eof";
      if (text.contains("connection reset") || text.contains("econnreset")) return "connection-reset";
      if (text.equals("socket closed") || text.contains("broken pipe")) return "connection-closed";
      if (text.contains("connection refused") || text.contains("econnrefused")) return "connection-refused";
      if (text.contains("cleartext") && text.contains("not permitted")) return "cleartext-blocked";
      if (text.contains("permission denied") || text.contains("eacces") || text.contains("eperm")) return "permission-denied";
      if (text.contains("no route to host") || text.contains("enetunreach") || text.contains("ehostunreach")) return "unreachable";
      if (text.contains("timed out") || text.equals("request-deadline") || text.equals("query-timeout")) return "timeout";
      if (text.contains("unexpected status line") || text.contains("invalid status line")) return "protocol-error";
      switch (text) {
        case "single-shot-finished": case "closed": case "unexpected-compression": case "body-too-large":
        case "truncated-body": case "missing-content-type": case "unsupported-content-type":
        case "missing-response-seed": case "invalid-seed": case "invalid-body-size":
        case "too-many-fields": case "invalid-route-type": case "too-many-routes":
        case "too-many-route-fields": case "invalid-route-field-type": case "duplicate-route-module":
        case "duplicate-route-address": case "invalid-route-port": case "truncated-varint":
        case "varint-overflow": case "invalid-tag": case "truncated-field":
        case "string-too-long": case "invalid-utf8": case "unsupported-wire-type":
          return text;
        default: break;
      }
    }
    return fallback;
  }
}
