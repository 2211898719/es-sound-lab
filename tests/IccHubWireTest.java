package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccHubWireTest {
  static byte[] join(byte[]... parts) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (byte[] part : parts) bytes.write(part);
    return bytes.toByteArray();
  }

  static byte[] number(long value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    while ((value & ~127L) != 0) { bytes.write((int) value & 127 | 128); value >>>= 7; }
    bytes.write((int) value);
    return bytes.toByteArray();
  }

  static byte[] field(int id, byte[] value) throws Exception {
    return join(number(id << 3 | 2), number(value.length), value);
  }

  static byte[] string(int id, String value) throws Exception {
    return field(id, value.getBytes(StandardCharsets.UTF_8));
  }

  static byte[] route(String module, String address) throws Exception {
    return field(1, join(string(1, module), string(2, address)));
  }

  @Test public void originalSchemaSelectsOnlyTwoModulesAndDiscardsTrace() throws Exception {
    byte[] body = join(route("ota_icc_vsi", "192.168.55.40:51020"), route("ota_android_vsi", ""),
        route("config", "private-config"), string(2, "VIN-private-account-Seed"), number(80), number(42));
    JSONArray routes = IccHubWire.decode(body);
    assertEquals(2, routes.length());
    assertEquals("ota_icc_vsi", routes.getJSONObject(0).getString("module"));
    assertEquals("192.168.55.40:51020", routes.getJSONObject(0).getString("address"));
    assertEquals("other-address-unconfirmed", routes.getJSONObject(0).getString("addressKind"));
    assertFalse(routes.toString().contains("private"));
    assertFalse(routes.toString().contains("Seed"));
  }

  @Test public void emptyProtoAndMissingTargetsAreNotFabricated() throws Exception {
    assertEquals(0, IccHubWire.decode(new byte[0]).length());
    assertEquals(0, IccHubWire.decode(route("ota_icc", "192.168.55.31")).length());
    JSONArray routes = IccHubWire.decode(field(1, string(1, "ota_icc_vsi")));
    assertEquals("", routes.getJSONObject(0).getString("address"));
    assertEquals("local-module-or-unspecified", routes.getJSONObject(0).getString("addressKind"));
    assertFalse(routes.getJSONObject(0).has("port"));
  }

  @Test public void knownHeadUnitAddressesAreNotConfusedWithOtherPrivateNodes() throws Exception {
    for (String address : new String[] {"192.168.55.31:8000", "127.0.0.1:8000", "localhost:8000", "[::1]:8000", "::1"}) {
      assertEquals(address, "head-unit-address",
          IccHubWire.decode(route("ota_icc_vsi", address)).getJSONObject(0).getString("addressKind"));
    }
    for (String address : new String[] {"192.168.55.32:8000", "10.0.0.1", "example.invalid:8000"})
      assertEquals("other-address-unconfirmed",
          IccHubWire.decode(route("ota_icc_vsi", address)).getJSONObject(0).getString("addressKind"));
  }

  @Test public void unexpectedAddressTextIsRedactedNotExportedOrFollowed() throws Exception {
    for (String address : new String[] {"http://private.example/VIN", "user@private.example", "private\nSeed: token", "私人账户"}) {
      JSONArray routes = IccHubWire.decode(route("ota_icc_vsi", address));
      assertEquals("unrecognized-address-redacted", routes.getJSONObject(0).getString("addressKind"));
      assertFalse(routes.getJSONObject(0).has("address"));
      assertFalse(routes.toString().contains(address));
    }
  }

  @Test public void explicitPortIsBoundedAndDuplicateRoutesRemainVisible() throws Exception {
    byte[] record = join(string(1, "ota_icc_vsi"), string(2, "192.168.55.31"), number(24), number(51020));
    JSONArray routes = IccHubWire.decode(join(field(1, record), field(1, record)));
    assertEquals(2, routes.length());
    assertEquals(51020, routes.getJSONObject(0).getInt("port"));
    for (int port : new int[] {-1, 65536})
      assertThrows(IOException.class, () -> IccHubWire.decode(field(1,
          join(string(1, "ota_icc_vsi"), number(24), number(port)))));
  }

  @Test public void malformedMessagesCannotBecomeAnAddress() throws Exception {
    byte[][] invalid = {{0}, {8, 1}, {10}, {10, 2, 10}, {10, 2, 8, 1},
        {10, 2, 24, -128}, {11}, {10, 2, 0, 0}, {10, -1, -1, -1, -1, 15}};
    for (byte[] body : invalid) assertThrows(Exception.class, () -> IccHubWire.decode(body));
    assertThrows(Exception.class, () -> IccHubWire.decode(field(1,
        join(string(1, "ota_icc_vsi"), string(1, "ota_android_vsi")))));
    assertThrows(Exception.class, () -> IccHubWire.decode(field(1,
        join(string(1, "ota_icc_vsi"), string(2, ""), string(2, "192.168.55.31")))));
    assertThrows(Exception.class, () -> IccHubWire.decode(field(1, field(1, new byte[] {(byte) 0xc3, 0x28}))));
  }

  @Test public void byteRouteStringAndFieldCountLimitsAreEnforced() throws Exception {
    assertThrows(IOException.class, () -> IccHubWire.decode(null));
    assertThrows(IOException.class, () -> IccHubWire.decode(new byte[IccHubWire.MAX_BODY + 1]));
    assertThrows(IOException.class, () -> IccHubWire.decode(route("ota_icc_vsi", "a".repeat(257))));
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (int index = 0; index < 129; index++) body.write(route("ota_android_vsi", ""));
    assertThrows(IOException.class, () -> IccHubWire.decode(body.toByteArray()));
    byte[] tooManyFields = new byte[1026];
    for (int index = 0; index < tooManyFields.length; index += 2) tooManyFields[index] = 24;
    assertThrows(IOException.class, () -> IccHubWire.decode(tooManyFields));
  }
}
