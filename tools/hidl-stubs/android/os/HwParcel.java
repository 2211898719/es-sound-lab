package android.os;

import java.util.ArrayList;

public class HwParcel {
  public HwParcel() { throw new UnsupportedOperationException("compile-only"); }
  public void writeInterfaceToken(String token) { throw new UnsupportedOperationException(); }
  public void enforceInterface(String token) { throw new UnsupportedOperationException(); }
  public void writeStringVector(ArrayList<String> values) { throw new UnsupportedOperationException(); }
  public ArrayList<String> readStringVector() { throw new UnsupportedOperationException(); }
  public void writeStrongBinder(IHwBinder binder) { throw new UnsupportedOperationException(); }
  public String readString() { throw new UnsupportedOperationException(); }
  public ArrayList<Byte> readInt8Vector() { throw new UnsupportedOperationException(); }
  public void writeString(String value) { throw new UnsupportedOperationException(); }
  public int readInt32() { throw new UnsupportedOperationException(); }
  public void writeStatus(int status) { throw new UnsupportedOperationException(); }
  public void writeBuffer(HwBlob blob) { throw new UnsupportedOperationException(); }
  public void send() { throw new UnsupportedOperationException(); }
  public void verifySuccess() { throw new UnsupportedOperationException(); }
  public void releaseTemporaryStorage() { throw new UnsupportedOperationException(); }
  public void release() { throw new UnsupportedOperationException(); }
}
