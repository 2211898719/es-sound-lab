package cn.es.soundlab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class IccHubWire {
  static final int MAX_BODY = 32768;

  static JSONArray decode(byte[] body) throws Exception {
    if (body == null || body.length > MAX_BODY) throw new IOException("invalid-body-size");
    Cursor cursor = new Cursor(body, 0, body.length);
    JSONArray selected = new JSONArray();
    int fields = 0, routes = 0;
    while (cursor.remaining()) {
      if (++fields > 512) throw new IOException("too-many-fields");
      int tag = cursor.tag();
      if (tag >>> 3 == 1) {
        if ((tag & 7) != 2) throw new IOException("invalid-route-type");
        if (++routes > 128) throw new IOException("too-many-routes");
        JSONObject route = route(cursor.message());
        if (route != null) selected.put(route);
      } else cursor.skip(tag & 7);
    }
    return selected;
  }

  private static JSONObject route(Cursor cursor) throws Exception {
    String module = "", address = "";
    Integer port = null;
    boolean moduleSeen = false, addressSeen = false, portSeen = false;
    int fields = 0;
    while (cursor.remaining()) {
      if (++fields > 32) throw new IOException("too-many-route-fields");
      int tag = cursor.tag(), field = tag >>> 3;
      if (field == 1 || field == 2) {
        if ((tag & 7) != 2) throw new IOException("invalid-route-field-type");
        if (field == 1) {
          if (moduleSeen) throw new IOException("duplicate-route-module");
          moduleSeen = true;
          module = cursor.string(128);
        } else {
          if (addressSeen) throw new IOException("duplicate-route-address");
          addressSeen = true;
          address = cursor.string(256);
        }
      } else if (field == 3) {
        if ((tag & 7) != 0 || portSeen) throw new IOException("invalid-route-port");
        portSeen = true;
        long value = cursor.varint();
        if (value < 0 || value > 65535) throw new IOException("invalid-route-port");
        if (value != 0) port = (int) value;
      } else cursor.skip(tag & 7);
    }
    if (!module.equals("ota_icc_vsi") && !module.equals("ota_android_vsi")) return null;
    JSONObject result = new JSONObject().put("module", module);
    if (port != null) result.put("port", port);
    if (address.isEmpty()) {
      return result.put("address", "").put("addressKind", "local-module-or-unspecified");
    }
    if (!address.matches("[A-Za-z0-9_.:\\[\\]-]{1,256}"))
      return result.put("addressKind", "unrecognized-address-redacted");
    result.put("address", address);
    String host = address;
    if (host.startsWith("[")) {
      int close = host.indexOf(']');
      if (close >= 0) host = host.substring(1, close);
    } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') >= 0) {
      host = host.substring(0, host.indexOf(':'));
    }
    boolean local = host.equals(IccVehicleTransport.PROXY_HOST) || host.equals("127.0.0.1")
        || host.equals("::1") || host.equalsIgnoreCase("localhost");
    return result.put("addressKind", local ? "head-unit-address" : "other-address-unconfirmed");
  }

  private static final class Cursor {
    final byte[] bytes;
    final int limit;
    int offset;

    Cursor(byte[] bytes, int offset, int limit) { this.bytes = bytes; this.offset = offset; this.limit = limit; }
    boolean remaining() { return offset < limit; }

    long varint() throws IOException {
      long value = 0;
      for (int index = 0; index < 10; index++) {
        if (!remaining()) throw new IOException("truncated-varint");
        int next = bytes[offset++] & 255;
        if (index == 9 && next > 1) throw new IOException("varint-overflow");
        value |= (long) (next & 127) << (index * 7);
        if ((next & 128) == 0) return value;
      }
      throw new IOException("varint-overflow");
    }

    int tag() throws IOException {
      long value = varint();
      if (value <= 0 || value > 0xffffffffL || value >>> 3 == 0) throw new IOException("invalid-tag");
      return (int) value;
    }

    void advance(long count) throws IOException {
      if (count < 0 || count > limit - offset) throw new IOException("truncated-field");
      offset += (int) count;
    }

    Cursor message() throws IOException {
      long size = varint();
      int start = offset;
      advance(size);
      return new Cursor(bytes, start, offset);
    }

    String string(int max) throws IOException {
      Cursor value = message();
      if (value.limit - value.offset > max) throw new IOException("string-too-long");
      try {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, value.offset, value.limit - value.offset)).toString();
      } catch (CharacterCodingException error) { throw new IOException("invalid-utf8"); }
    }

    void skip(int wire) throws IOException {
      if (wire == 0) varint();
      else if (wire == 1) advance(8);
      else if (wire == 2) advance(varint());
      else if (wire == 5) advance(4);
      else throw new IOException("unsupported-wire-type");
    }
  }
}
