package android.os;

public abstract class HwBinder implements IHwBinder {
  public HwBinder() { throw new UnsupportedOperationException("compile-only"); }
  public static IHwBinder getService(String name, String instance, boolean retry)
      throws RemoteException { throw new UnsupportedOperationException("compile-only"); }
  public void transact(int code, HwParcel request, HwParcel reply, int flags)
      throws RemoteException { throw new UnsupportedOperationException("compile-only"); }
  public abstract void onTransact(int code, HwParcel request, HwParcel reply, int flags)
      throws RemoteException;
}
