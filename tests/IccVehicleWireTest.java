package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.crypto.Cipher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class IccVehicleWireTest {
  private static final String SEED = "abcdefghijklmnopqrst";

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  private static void varint(ByteArrayOutputStream out, long value) {
    while ((value & ~127L) != 0) { out.write((int) value & 127 | 128); value >>>= 7; }
    out.write((int) value);
  }

  @Test public void originalApkEnvelopeMatchesIndependentOpenSslVectors() throws Exception {
    assertEquals("62586abf77a0fab62179698fddb46365", hex(IccVehicleWire.request(SEED)));
    byte[] encrypted = IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, new byte[] {72, 1}, SEED);
    assertEquals("e0d117ca042cae5719b28a4a9f629328", hex(encrypted));
    assertArrayEquals(new byte[] {72, 1}, IccVehicleWire.crypt(Cipher.DECRYPT_MODE, encrypted, SEED));
    assertArrayEquals(new byte[] {10, 3, 77, 68, 65},
        IccVehicleWire.crypt(Cipher.DECRYPT_MODE, IccVehicleWire.request(SEED), SEED));
  }

  @Test public void enumUsesProtobufValuesNotMessagePropertyOrGearStatusValues() throws Exception {
    String[] expected = {"未知(0)", "P", "N", "D", "R", "未知(5)"};
    for (int index = 0; index < expected.length; index++) {
      IccVehicleWire.Condition condition = IccVehicleWire.decode(new byte[] {72, (byte) index});
      assertEquals(expected[index], condition.gearName());
      assertEquals(index >= 1 && index <= 4, condition.knownGear());
    }
  }

  @Test public void responseSeedsPreservePrintableHeaderValuesButRequestsStayLowercaseTwenty() throws Exception {
    byte[] plain = {72, 1};
    for (String seed : new String[] {"SERVER-Seed_20261101", " seed ", "A".repeat(256)}) {
      byte[] encrypted = IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, plain, seed);
      assertArrayEquals(plain, IccVehicleWire.crypt(Cipher.DECRYPT_MODE, encrypted, seed));
      assertThrows(IOException.class, () -> IccVehicleWire.request(seed));
    }
    assertFalse(java.util.Arrays.equals(IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, plain, " seed "),
        IccVehicleWire.crypt(Cipher.ENCRYPT_MODE, plain, "seed")));
  }

  @Test public void invalidSeedsCannotReachEnvelopeCrypto() {
    for (String seed : new String[] {null, "", "A".repeat(257), "private\rseed", "private\nseed",
        "private\tseed", "\u0000", "\u007f", "\u0080"}) {
      IOException failure = assertThrows(IOException.class,
          () -> IccVehicleWire.crypt(Cipher.DECRYPT_MODE, new byte[16], seed));
      assertEquals("invalid-seed", failure.getMessage());
    }
  }

  @Test public void missingGearCannotDefaultToParkingAndPrivateTraceIsDiscarded() throws Exception {
    IccVehicleWire.Condition absent = IccVehicleWire.decode(new byte[] {16, 0});
    assertNull(absent.gear); assertFalse(absent.knownGear()); assertEquals("缺失", absent.gearName());
    IccVehicleWire.Condition selected = IccVehicleWire.decode(new byte[] {10, 3, 86, 73, 78, 72, 4, 104, 1});
    assertEquals("R", selected.gearName()); assertEquals(2, selected.fields.length());
    assertFalse(selected.fields.toString().contains("VIN"));
  }

  @Test public void selectedSignedFieldsPreserveRawValuesWithoutInferringUnits() throws Exception {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(16); varint(body, -1); body.write(48); body.write(2); body.write(72); body.write(3);
    IccVehicleWire.Condition condition = IccVehicleWire.decode(body.toByteArray());
    assertEquals(-1, condition.fields.getInt("speedRaw"));
    assertEquals(2, condition.fields.getInt("handbrakeRaw")); assertEquals("D", condition.gearName());
  }

  @Test public void malformedTruncatedDuplicateAndWrongWireBodiesAreRejected() {
    byte[][] invalid = {new byte[0], {72}, {0}, {72, 1, 72, 4}, {74, 1, 0},
        {10, 127}, {75}, {8, 1}, {72, -128, -128, -128, -128, 16},
        {72, -128, -128, -128, -128, -128, -128, -128, -128, -128, 2}};
    for (byte[] body : invalid) assertThrows(Exception.class, () -> IccVehicleWire.decode(body));
  }

  @Test public void sizeAndFieldCountLimitsAreEnforced() {
    assertThrows(IOException.class, () -> IccVehicleWire.decode(new byte[IccVehicleWire.MAX_BODY + 1]));
    byte[] fields = new byte[514];
    for (int index = 0; index < fields.length; index += 2) fields[index] = 8;
    assertThrows(IOException.class, () -> IccVehicleWire.decode(fields));
    assertThrows(Exception.class, () -> IccVehicleWire.request("bad\r\nseed"));
  }
}
