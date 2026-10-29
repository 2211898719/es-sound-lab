package cn.es.soundlab;

public final class CoreTest {
  private static int checks;

  private static void eq(Object expected, Object actual, String label) {
    checks++;
    if (expected == null ? actual != null : !expected.equals(actual))
      throw new AssertionError(label + ": expected " + expected + ", got " + actual);
  }

  private static Protocol.Sample log(
      String topic, String id, String value, int status, String extra) {
    return Protocol.decode(
        "1760000000.125  345  789 I MessageProperty: receive "
            + topic
            + ", value: CarPropertyValue{id="
            + id
            + ", area=0x0, status="
            + status
            + ", value="
            + value
            + extra
            + "}");
  }

  public static void main(String[] args) {
    Protocol.Sample door = log("VehicleDoor/Door/FrontLeft", "0x600002c", "1", 0, "");
    eq("door.front_left", door.key, "door identity");
    eq("1", door.value, "door open");
    eq("door.front_left.open", Protocol.event(door.key, door.value), "open event");
    eq(1760000000125L, door.time, "epoch time");
    Protocol.Sample color = log("VehicleDoor/Door/RearRight", "0x600002f", "0", 0, ", color=2.0");
    eq("1", color.value, "rear door color encoding");
    eq(
        null,
        log("VehicleDoor/Door/FrontLeft", "0x600002d", "1", 0, "").key,
        "topic and ID must agree");
    Protocol.Sample invalid = log("VehicleDoor/Door/FrontLeft", "0x600002c", "1", 1, "");
    eq("door.front_left", invalid.key, "invalid status retains identity");
    eq(null, invalid.value, "invalid status never a trigger");
    eq(
        null,
        log("VehicleDoor/Door/FrontLeft", "0x600002c", "7", 0, "").value,
        "invalid door value");
    eq("1", log("DrivingInfo/Gear", "0x400000a", "101", 0, "").value, "P gear enum");
    eq("4", log("DrivingInfo/Gear", "0x400000a", "113", 0, "").value, "D gear enum");
    eq("3", log("DrivingInfo/Gear", "0x400000a", "300", 0, "").value, "N gear enum");
    eq(null, Protocol.gearLog(5), "unknown log gear is not guessed");
    eq("tailgate", log("VehicleDoor/Door/Back", "0x6000031", "1", 0, "").key, "tailgate");
    eq("speed", log("DrivingInfo/Speed", "0x400000b", "17.3", 0, "").key, "speed");
    eq(null, log("DrivingInfo/Speed", "0x400000b", "NaN", 0, "").value, "nonfinite speed");
    Protocol.Sample lock = log("VehicleDoor/Lock", "0x6000055", "1", 0, "");
    eq(null, lock.key, "unverified lock signal remains candidate");
    eq("1", lock.raw, "candidate raw value preserved");
    eq(null, Protocol.decode("garbage"), "malformed log");
    eq(null, Protocol.decode(new String(new char[9000])), "oversized input");
    eq("1", Protocol.property(false, Protocol.GEAR_SELECTION, 0, 0, 4, 1).value, "Android P gear");
    eq(
        "4",
        Protocol.property(false, Protocol.CURRENT_GEAR, 0, 0, 16, 1).value,
        "Android current gear forward");
    eq(
        "5",
        Protocol.property(false, Protocol.GEAR_SELECTION, 0, 0, 16, 1).value,
        "Android selection manual gear");
    eq("2", Protocol.property(true, 67108874, 0, 0, 1, 1).value, "Mega R gear");
    eq("36.0", Protocol.property(false, Protocol.SPEED, 0, 0, 10f, 1).value, "Android m/s to km/h");
    eq(
        "door.rear_left",
        Protocol.property(false, Protocol.DOOR_POS, 16, 0, 25, 1).key,
        "Android door area");
    eq(null, Protocol.property(false, Protocol.DOOR_POS, 999, 0, 25, 1).key, "unmapped area");
    TransitionGate gate = new TransitionGate();
    eq(TransitionGate.Change.BASELINE, gate.accept("door", "0", "Mega"), "first reading is silent");
    eq(TransitionGate.Change.SAME, gate.accept("door", "0", "Mega"), "duplicates silent");
    eq(TransitionGate.Change.CHANGED, gate.accept("door", "1", "Mega"), "real change triggers");
    eq(TransitionGate.Change.BASELINE, gate.accept("door", "0", "日志"), "provider switch silent");
    eq(TransitionGate.Change.INVALID, gate.accept("door", null, "日志"), "invalid reading resets");
    eq(TransitionGate.Change.BASELINE, gate.accept("door", "1", "日志"), "recovery silent");
    gate.reset();
    eq(TransitionGate.Change.BASELINE, gate.accept("door", "0", "日志"), "reconnection silent");
    for (EventSpec e : EventSpec.ALL) {
      eq(e, EventSpec.find(e.id), "event ID lookup");
      if (e.group.equals("lock") || e.group.equals("charge"))
        eq(false, e.mapped, "unconfirmed event metadata");
    }
    System.out.println("Core checks passed: " + checks);
  }
}
