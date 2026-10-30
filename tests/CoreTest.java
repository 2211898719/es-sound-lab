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
    Protocol.Sample autoHold = log("T1CF1/AutoHold", "0x50000022", "1", 0, "");
    eq("autohold", autoHold.key, "observed AutoHold identity");
    eq("1", autoHold.value, "AutoHold retains raw enum");
    eq(null, Protocol.event("autohold", "1"), "no guessed AutoHold sound");
    eq(null, Protocol.autoHoldState("1", "", ""), "AutoHold requires calibration");
    eq(null, Protocol.autoHoldState("1", "1", "1"), "AutoHold enums must differ");
    eq(null, Protocol.autoHoldState("1", "1", "3"), "only observed AutoHold enums accepted");
    eq("holding", Protocol.autoHoldState("2", "2", "1"), "holding calibration");
    eq("released", Protocol.autoHoldState("1", "2", "1"), "release calibration");
    eq("holding", Protocol.autoHoldState("1", "1", "2"), "reverse calibration allowed");
    eq(
        null,
        Protocol.autoHoldState(null, "1", "2"),
        "invalid status remains invalid after mapping");
    eq("autohold.holding", Protocol.event("autohold", "holding"), "holding event");
    eq("autohold.released", Protocol.event("autohold", "released"), "released event");
    for (String bad : new String[] {"0", "3", "-1", "1.0", "01"})
      eq(
          null,
          log("T1CF1/AutoHold", "0x50000022", bad, 0, "").value,
          "invalid AutoHold raw " + bad);
    eq(null, log("T1CF1/AutoHold", "0x50000022", "1", 1, "").value, "AutoHold status unavailable");
    eq(null, log("T1CF1/AutoHold", "0x50000023", "1", 0, "").key, "AutoHold ID must match");
    eq(null, log("Other/AutoHold", "0x50000022", "1", 0, "").key, "AutoHold topic must match");
    eq(
        null,
        Protocol.decode(
                "1760000000.125 345 789 I MessageProperty: receive T1CF1/AutoHold,"
                    + " value: CarPropertyValue{id=0x50000022, area=0x1, status=0, value=1}")
            .key,
        "AutoHold area must match");
    eq("tailgate", log("VehicleDoor/Door/Back", "0x6000031", "1", 0, "").key, "tailgate");
    eq("speed", log("DrivingInfo/Speed", "0x400000b", "17.3", 0, "").key, "speed");
    eq(null, log("DrivingInfo/Speed", "0x400000b", "NaN", 0, "").value, "nonfinite speed");
    Protocol.Sample lock = log("VehicleDoor/Lock", "0x6000055", "1", 0, "");
    eq(null, lock.key, "unverified lock signal remains candidate");
    eq("1", lock.raw, "candidate raw value preserved");
    Protocol.Sample central = log("VehicleDoor/CentralLock", "0x67000096", "1", 0, "");
    eq("lock", central.key, "observed central lock identity");
    eq("1", central.value, "central lock keeps uncalibrated enum");
    eq(null, Protocol.event("lock", central.value), "raw lock value never guesses event");
    eq(null, Protocol.lockState("1", "", ""), "lock mapping disabled by default");
    eq(null, Protocol.lockState("1", "1", "1"), "equal lock enums rejected");
    eq("locked", Protocol.lockState("1", "1", "0"), "explicit locked calibration");
    eq("unlocked", Protocol.lockState("1", "0", "1"), "reversed calibration supported");
    eq(null, Protocol.lockState("2", "0", "1"), "unknown lock enum invalidates baseline");
    eq("lock.lock", Protocol.event("lock", "locked"), "calibrated lock event");
    eq("lock.unlock", Protocol.event("lock", "unlocked"), "calibrated unlock event");
    eq(null, log("VehicleDoor/CentralLock", "0x67000096", "1", 1, "").value,
        "invalid central lock status");
    eq(null, log("VehicleDoor/CentralLock", "0x67000096", "-1", 0, "").value,
        "negative central lock value");
    eq(null, log("VehicleDoor/CentralLock", "0x67000097", "1", 0, "").key,
        "central lock ID must match");
    eq(false, Protocol.lockValue("2147483648"), "overflow calibration rejected");
    eq(false, Protocol.lockValue("01"), "noncanonical calibration rejected");
    eq(false, Protocol.lockValue("1.0"), "fractional calibration rejected");
    String object = "{\"value\":1,\"nested\":{\"text\":\"a,}\\\"b\"},\"values\":[1,2]}";
    eq(object, log("BodyInfo/Unknown", "0x670000aa", object, 0, ", time=1").raw,
        "nested JSON and quoted delimiters preserved");
    eq("[1,2,3]", log("BodyInfo/Unknown", "0x670000aa", "[1,2,3]", 0, "").raw,
        "array raw value preserved");
    eq(null, Protocol.decode("1760000000.125 345 789 I MessageProperty: receive BodyInfo/Unknown,"
        + " value: CarPropertyValue{id=0x1, area=0x0, status=0, value={\"a\":1}"),
        "incomplete nested sample rejected");
    for (String topic : new String[] {"BodyInfo/VerticalAcceleration", "EcologyMedia/LRC",
        "AVM/NtfMRAInfo", "DMS/MatchFaceid", "DMS/CancelMatchFaceid/Set"})
      eq(true, Protocol.noisy("MessageProperty: receive " + topic + ", value: noise"),
          "known noise filtered: " + topic);
    for (String topic : new String[] {"VehicleDoor/CentralLock", "VehicleDoor/UnknownLock",
        "DrivingInfo/Speed", "DrivingInfo/Gear", "BodyInfo/BrakePedal", "DMS/UnknownSignal"})
      eq(false, Protocol.noisy("MessageProperty: receive " + topic + ", value: 1"),
          "useful and unknown signals retained: " + topic);
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
    gate.accept("lock", "locked", "日志");
    gate.forget("lock");
    eq(TransitionGate.Change.BASELINE, gate.accept("lock", "unlocked", "日志"), "calibration resets lock baseline");
    eq(TransitionGate.Change.CHANGED, gate.accept("door", "1", "日志"), "lock reset preserves other baselines");
    gate.accept("lock", "locked", "日志|VehicleDoor/CentralLock|0x0");
    gate.accept("gear", "1", "Mega|property/gear|0x0");
    gate.forgetSource("Mega");
    eq(TransitionGate.Change.CHANGED, gate.accept("lock", "unlocked", "日志|VehicleDoor/CentralLock|0x0"),
        "native reset preserves log lock baseline");
    eq(TransitionGate.Change.BASELINE, gate.accept("gear", "4", "Mega|property/gear|0x0"),
        "reset affects only matching source");
    eq(true, Protocol.standardLog("1760000000.125 345 789 I MessageProperty: Permission denied for another app"),
        "payload denial text is still a normal log entry");
    eq(false, Protocol.standardLog("1760000000.125 345 789 I AnotherTag: VehicleDoor/Unknown"),
        "extended-only tag is not a standard entry");
    eq(false, Protocol.standardLog("logcat: Permission denied"), "stderr is not a vehicle log entry");
    for (EventSpec e : EventSpec.ALL) {
      eq(e, EventSpec.find(e.id), "event ID lookup");
      if (e.group.equals("lock") || e.group.equals("charge"))
        eq(false, e.mapped, "unconfirmed event metadata");
    }
    System.out.println("Core checks passed: " + checks);
  }
}
