package android.os;

public interface IHwBinder {
  void transact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException;
  IHwInterface queryLocalInterface(String descriptor);
  boolean linkToDeath(DeathRecipient recipient, long cookie);
  boolean unlinkToDeath(DeathRecipient recipient);
  interface DeathRecipient { void serviceDied(long cookie); }
}
