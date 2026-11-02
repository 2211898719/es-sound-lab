package android.car.hardware;

public final class CarPropertyValue<T> {
  private final int property, area, status;
  private final long timestamp;
  private final T value;
  public CarPropertyValue(int property, int area, int status, long timestamp, T value) {
    this.property = property; this.area = area; this.status = status;
    this.timestamp = timestamp; this.value = value;
  }
  public int getPropertyId() { return property; }
  public int getAreaId() { return area; }
  public int getStatus() { return status; }
  public long getTimestamp() { return timestamp; }
  public T getValue() { return value; }
}
