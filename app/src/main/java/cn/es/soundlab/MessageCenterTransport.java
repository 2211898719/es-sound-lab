package cn.es.soundlab;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import java.util.ArrayList;
import java.util.Arrays;

final class MessageCenterTransport implements MessageCenterProbe.Transport {
  static final String DESCRIPTOR = "vendor.mega.message_center@1.0::IMessageCenter";
  static final String CALLBACK = "vendor.mega.message_center@1.0::IMsgRxCallback";
  static final String BASE = "android.hidl.base@1.0::IBase";
  private IHwBinder remote;
  private Receiver callback;
  private boolean subscriptionDispatched;

  @Override public boolean discover() throws Exception {
    remote = HwBinder.getService(DESCRIPTOR, "default", false);
    return remote != null;
  }

  @Override public void verify() throws Exception {
    HwParcel request = new HwParcel(), reply = new HwParcel();
    try {
      request.writeInterfaceToken(BASE);
      remote.transact(256067662, request, reply, 0);
      reply.verifySuccess();
      if (!reply.readStringVector().contains(DESCRIPTOR))
        throw new IllegalStateException("IMessageCenter descriptor mismatch");
    } finally {
      request.releaseTemporaryStorage();
      reply.release();
    }
  }

  @Override public int subscribe(ArrayList<String> topics, MessageCenterProbe.Sink sink)
      throws Exception {
    callback = new Receiver(sink);
    return listeners(4, topics);
  }

  @Override public int unsubscribe(ArrayList<String> topics) throws Exception {
    if (!subscriptionDispatched) throw new IllegalStateException("No subscription transaction dispatched");
    return listeners(5, topics);
  }

  @Override public boolean subscriptionMayExist() { return subscriptionDispatched; }

  private int listeners(int code, ArrayList<String> topics) throws Exception {
    if (code != 4 && code != 5) throw new IllegalArgumentException("read-only transaction only");
    HwParcel request = new HwParcel(), reply = new HwParcel();
    try {
      request.writeInterfaceToken(DESCRIPTOR);
      request.writeStringVector(topics);
      request.writeStrongBinder(callback);
      if (code == 4) subscriptionDispatched = true;
      remote.transact(code, request, reply, 0);
      reply.verifySuccess();
      return reply.readInt32();
    } finally {
      request.releaseTemporaryStorage();
      reply.release();
    }
  }

  static final class Receiver extends HwBinder implements IHwInterface {
    private final MessageCenterProbe.Sink sink;

    Receiver(MessageCenterProbe.Sink sink) { this.sink = sink; }

    @Override public IHwBinder asBinder() { return this; }
    @Override public IHwInterface queryLocalInterface(String descriptor) {
      return CALLBACK.equals(descriptor) ? this : null;
    }
    @Override public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) { return true; }
    @Override public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) { return true; }

    @Override public void onTransact(int code, HwParcel request, HwParcel reply, int flags) {
      boolean oneWay = (flags & 1) != 0;
      if (code == 1) {
        if (!oneWay) { reply.writeStatus(Integer.MIN_VALUE); reply.send(); return; }
        request.enforceInterface(CALLBACK);
        String topic = request.readString();
        if (!MessageCenterProbe.accepts(topic)) return;
        ArrayList<Byte> data = request.readInt8Vector();
        if (data == null || data.size() > MessageCenterProbe.MAX_PAYLOAD) {
          sink.receive(topic, null);
          return;
        }
        byte[] bytes = new byte[data.size()];
        for (int index = 0; index < bytes.length; index++) bytes[index] = data.get(index);
        sink.receive(topic, bytes);
        return;
      }
      if (code == 256462420 || code == 257120595) {
        if (!oneWay) { reply.writeStatus(Integer.MIN_VALUE); reply.send(); return; }
        request.enforceInterface(BASE);
        return;
      }
      if (oneWay) return;
      request.enforceInterface(BASE);
      if (code != 256067662 && code != 256136003 && code != 256398152
          && code != 257049926 && code != 256131655 && code != 256921159) {
        reply.writeStatus(Integer.MIN_VALUE);
        reply.send();
        return;
      }
      reply.writeStatus(0);
      switch (code) {
        case 256067662:
          reply.writeStringVector(new ArrayList<>(Arrays.asList(CALLBACK, BASE)));
          break;
        case 256136003:
          reply.writeString(CALLBACK);
          break;
        case 256398152:
          HwBlob header = new HwBlob(16), hashes = new HwBlob(64);
          header.putInt32(8, 2);
          header.putBool(12, false);
          hashes.putInt8Array(0, new byte[32]);
          hashes.putInt8Array(32, new byte[] {-20, 127, -41, -98, -48, 45, -6, -123,
              -68, 73, -108, 38, -83, -82, 62, -66, 35, -17, 5, 36, -13, -51, 105,
              87, 19, -109, 36, -72, 59, 24, -54, 76});
          header.putBlob(0, hashes);
          reply.writeBuffer(header);
          break;
        case 257049926:
          HwBlob debug = new HwBlob(24);
          debug.putInt32(0, -1);
          debug.putInt64(8, 0);
          debug.putInt32(16, 0);
          reply.writeBuffer(debug);
          break;
        case 256131655:
        case 256921159:
          break;
      }
      reply.send();
    }
  }
}
