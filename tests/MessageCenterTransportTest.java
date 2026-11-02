package cn.es.soundlab;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
    shadows = {MessageCenterTransportTest.BinderShadow.class,
        MessageCenterTransportTest.ParcelShadow.class, MessageCenterTransportTest.BlobShadow.class})
public final class MessageCenterTransportTest {
  static FakeRemote remote;
  static boolean retry;
  static String serviceName, instance;
  private static ParcelShadow parcel(HwParcel value) { return Shadow.extract(value); }

  @Implements(HwBinder.class)
  public static class BinderShadow {
    @Implementation protected void __constructor__() {}
    @Implementation protected static IHwBinder getService(String name, String requestedInstance,
        boolean shouldRetry) {
      serviceName = name;
      instance = requestedInstance;
      retry = shouldRetry;
      return remote;
    }
  }

  @Implements(HwParcel.class)
  public static class ParcelShadow {
    String token, text;
    ArrayList<String> strings;
    ArrayList<Byte> bytes;
    IHwBinder binder;
    int status, result;
    boolean sent, released, temporaryReleased;
    @Implementation protected void __constructor__() {}
    @Implementation protected void writeInterfaceToken(String value) { token = value; }
    @Implementation protected void enforceInterface(String value) {
      if (!value.equals(token)) throw new SecurityException("wrong descriptor");
    }
    @Implementation protected void writeStringVector(ArrayList<String> values) { strings = values; }
    @Implementation protected ArrayList<String> readStringVector() { return strings; }
    @Implementation protected void writeStrongBinder(IHwBinder value) { binder = value; }
    @Implementation protected String readString() { return text; }
    @Implementation protected ArrayList<Byte> readInt8Vector() { return bytes; }
    @Implementation protected void writeString(String value) { text = value; }
    @Implementation protected int readInt32() { return result; }
    @Implementation protected void writeStatus(int value) { status = value; }
    @Implementation protected void writeBuffer(HwBlob value) {}
    @Implementation protected void send() { sent = true; }
    @Implementation protected void verifySuccess() {
      if (status != 0) throw new SecurityException("transport failure");
    }
    @Implementation protected void releaseTemporaryStorage() { temporaryReleased = true; }
    @Implementation protected void release() { released = true; }
  }

  @Implements(HwBlob.class)
  public static class BlobShadow {
    @Implementation protected void __constructor__(int size) {}
    @Implementation protected void putInt32(long offset, int value) {}
    @Implementation protected void putInt64(long offset, long value) {}
    @Implementation protected void putBool(long offset, boolean value) {}
    @Implementation protected void putBlob(long offset, HwBlob value) {}
    @Implementation protected void putInt8Array(long offset, byte[] value) {}
  }

  static final class FakeRemote implements IHwBinder {
    final ArrayList<Integer> codes = new ArrayList<>();
    IHwBinder callback;
    ArrayList<String> topics;
    HwParcel lastRequest, lastReply;
    boolean mismatch, fail;
    @Override public void transact(int code, HwParcel request, HwParcel reply, int flags) {
      codes.add(code);
      assertEquals(0, flags);
      lastRequest = request;
      lastReply = reply;
      ParcelShadow data = parcel(request), response = parcel(reply);
      if (code == 256067662) {
        assertEquals(MessageCenterTransport.BASE, data.token);
        response.strings = new ArrayList<>(Arrays.asList(mismatch ? "wrong" : MessageCenterTransport.DESCRIPTOR));
      } else {
        assertTrue(code == 4 || code == 5);
        assertEquals(MessageCenterTransport.DESCRIPTOR, data.token);
        assertNotNull(data.binder);
        if (code == 4) { callback = data.binder; topics = data.strings; }
        else { assertSame(callback, data.binder); assertEquals(topics, data.strings); }
        response.result = 0;
      }
      if (fail) response.status = -1;
    }
    @Override public IHwInterface queryLocalInterface(String descriptor) { return null; }
    @Override public boolean linkToDeath(DeathRecipient recipient, long cookie) { return true; }
    @Override public boolean unlinkToDeath(DeathRecipient recipient) { return true; }
  }

  @Before public void setup() { remote = new FakeRemote(); retry = true; }

  @Test public void verifiesInterfaceUsesOnlyBatchSubscribeAndUnsubscribeAndReleasesParcels() throws Exception {
    MessageCenterTransport transport = new MessageCenterTransport();
    assertTrue(transport.discover());
    assertEquals(MessageCenterTransport.DESCRIPTOR, serviceName);
    assertEquals("default", instance);
    assertFalse(retry);
    transport.verify();
    assertTrue(parcel(remote.lastRequest).temporaryReleased);
    assertTrue(parcel(remote.lastReply).released);
    assertEquals(0, transport.subscribe(MessageCenterProbe.topics(), (topic, bytes) -> {}));
    assertEquals(0, transport.unsubscribe(MessageCenterProbe.topics()));
    assertEquals(Arrays.asList(256067662, 4, 5), remote.codes);
    assertTrue(parcel(remote.lastRequest).temporaryReleased);
    assertTrue(parcel(remote.lastReply).released);
  }

  @Test public void wrongInterfaceAndTransportErrorsCannotBeReportedAsSuccess() throws Exception {
    MessageCenterTransport transport = new MessageCenterTransport();
    transport.discover();
    remote.mismatch = true;
    try { transport.verify(); fail(); } catch (IllegalStateException expected) {}
    remote.mismatch = false;
    remote.fail = true;
    try { transport.verify(); fail(); } catch (SecurityException expected) {}
    assertTrue(parcel(remote.lastRequest).temporaryReleased);
    assertTrue(parcel(remote.lastReply).released);
  }

  @Test public void neverUnsubscribesWithAnUnconstructedNullCallback() throws Exception {
    MessageCenterTransport transport = new MessageCenterTransport();
    transport.discover();
    assertFalse(transport.subscriptionMayExist());
    try { transport.unsubscribe(MessageCenterProbe.topics()); fail(); }
    catch (IllegalStateException expected) {}
    assertTrue(remote.codes.isEmpty());
  }

  @Test public void callbackHonorsDescriptorOnewayFlagAndTopicAllowlist() throws Exception {
    final ArrayList<String> received = new ArrayList<>();
    MessageCenterTransport.Receiver receiver = new MessageCenterTransport.Receiver(
        (topic, bytes) -> received.add(topic + ":" + new String(bytes, StandardCharsets.UTF_8)));
    HwParcel request = new HwParcel(), reply = new HwParcel();
    ParcelShadow data = parcel(request);
    data.token = MessageCenterTransport.CALLBACK;
    data.text = "DrivingInfo/Gear";
    data.bytes = new ArrayList<>();
    for (byte value : "{\"valid\":true,\"value\":0}".getBytes(StandardCharsets.UTF_8)) data.bytes.add(value);
    receiver.onTransact(1, request, reply, 0);
    assertTrue(received.isEmpty());
    assertEquals(Integer.MIN_VALUE, parcel(reply).status);
    receiver.onTransact(1, request, reply, 1);
    assertEquals(1, received.size());
    data.text = "Identity/VIN";
    receiver.onTransact(1, request, reply, 1);
    assertEquals(1, received.size());
    data.token = "wrong";
    try { receiver.onTransact(1, request, reply, 1); fail(); } catch (SecurityException expected) {}
    assertSame(receiver, receiver.asBinder());
    assertSame(receiver, receiver.queryLocalInterface(MessageCenterTransport.CALLBACK));
    assertNull(receiver.queryLocalInterface("wrong"));
  }

  @Test public void callbackSupportsRequiredBaseQueriesWithoutCallingVendorSend() {
    MessageCenterTransport.Receiver receiver = new MessageCenterTransport.Receiver((topic, bytes) -> {});
    for (int code : new int[] {256067662, 256136003, 256398152, 257049926, 256921159, 256131655}) {
      HwParcel request = new HwParcel(), reply = new HwParcel();
      parcel(request).token = MessageCenterTransport.BASE;
      receiver.onTransact(code, request, reply, 0);
      assertEquals(0, parcel(reply).status);
      assertTrue(parcel(reply).sent);
      if (code == 256067662) assertEquals(Arrays.asList(MessageCenterTransport.CALLBACK,
          MessageCenterTransport.BASE), parcel(reply).strings);
    }
    assertTrue(remote.codes.isEmpty());
  }
}
