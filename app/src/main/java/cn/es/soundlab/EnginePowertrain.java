package cn.es.soundlab;

import org.json.JSONObject;

final class EnginePowertrain {
  private static final float[] RATIOS = {3.8f, 2.5f, 1.78f, 1.34f, 1.03f, .79f};
  private final EngineTuning tuning;
  private final float launch, knee;
  private boolean primed;
  private int gear;
  private float held, shiftElapsed, shiftDuration, shiftFrom;
  float rpm, load, throttle;
  int shiftDirection;

  EnginePowertrain(EngineTuning tuning, int horsepower, float zeroHundred) {
    this.tuning = tuning;
    int power = Math.max(80, Math.min(800, horsepower));
    float duration = Float.isFinite(zeroHundred) ? clamp(zeroHundred, 3, 18) : 7;
    knee = 20 * (float) Math.sqrt(power / 280f);
    float target = 100 / 3.6f;
    launch = (target + target * target * target / (3 * knee * knee)) / duration;
    reset();
  }

  void reset() {
    primed = false;
    gear = 0;
    held = shiftElapsed = shiftDuration = shiftFrom = throttle = load = 0;
    shiftDirection = 0;
    rpm = tuning.idle();
  }

  static float clamp(float value, float minimum, float maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private float coupled(float speed, int selected) {
    return speed / 3.6f / 2.3f * 60 * RATIOS[selected] * 3.4f;
  }

  boolean step(float speed, float acceleration, float pedal, float seconds) {
    if (!Float.isFinite(speed) || speed < 0 || speed > 240 || !Float.isFinite(acceleration)
        || !Float.isFinite(seconds) || seconds <= 0 || seconds > .5f
        || !Float.isNaN(pedal) && (!Float.isFinite(pedal) || pedal < 0 || pedal > 1)) {
      reset();
      return false;
    }
    float velocity = speed / 3.6f;
    float resistance = .16f + .0007f * velocity * velocity;
    float maximum = launch / (1 + velocity * velocity / (knee * knee));
    float demand = Float.isNaN(pedal) ? clamp((acceleration + resistance) / maximum, 0, 1) : pedal;
    if (speed < 1 && Math.abs(acceleration) < .15f && Float.isNaN(pedal)) demand = 0;
    throttle += (1 - (float) Math.exp(-seconds / (Float.isNaN(pedal) ? .28 : .09))) * (demand - throttle);
    if (!primed) {
      while (gear < RATIOS.length - 1 && coupled(speed, gear) > (throttle > .6f ? 4200 : 2800)) gear++;
      rpm = Math.max(tuning.idle(), Math.min(tuning.redline(), coupled(speed, gear)));
      held = .4f;
      primed = true;
    }
    held += seconds;
    float demandRpm = coupled(speed, gear);
    if (shiftDirection == 0) {
      if (speed < 1) gear = 0;
      else if (tuning.style != 2 && held > 1.1f) {
        float shiftAt = tuning.cruiseShift() + (tuning.powerShift() - tuning.cruiseShift()) * throttle * throttle;
        if (gear < RATIOS.length - 1 && demandRpm > shiftAt && coupled(speed, gear + 1) > tuning.idle() * 1.6f)
          shift(gear + 1);
        else if (gear > 0 && (demandRpm < tuning.idle() * 1.8f
            || throttle > .72f && demandRpm < tuning.redline() * .48f)
            && coupled(speed, gear - 1) < tuning.redline() * .76f) shift(gear - 1);
      }
    }
    float target = Math.max(tuning.idle(), coupled(speed, gear));
    if (gear == 0 && speed < 22)
      target = Math.max(target, tuning.idle() + 1550 * (float) Math.sqrt(throttle) * (1 - speed / 22));
    if (tuning.style == 2) target = tuning.idle() + speed * 26 + throttle * 850;
    target = clamp(target, tuning.idle(), tuning.redline());
    float desiredLoad = throttle > .04f ? throttle : speed > 3 ? -.3f - clamp(-acceleration / 5, 0, .6f) : 0;
    if (shiftDirection != 0) {
      shiftElapsed += seconds;
      float progress = clamp(shiftElapsed / shiftDuration, 0, 1);
      float blend = progress * progress * (3 - 2 * progress);
      if (shiftDirection > 0) {
        rpm = shiftFrom + (target - shiftFrom) * blend;
        if (progress < .65f) desiredLoad = -.45f;
      } else {
        float overshoot = (float) Math.sin(Math.PI * progress) * target * .06f;
        rpm = shiftFrom + (target - shiftFrom) * blend + overshoot;
        if (progress < .6f) desiredLoad = Math.max(desiredLoad, .65f);
      }
      if (progress >= 1) shiftDirection = 0;
    } else {
      float change = (target - rpm) * (1 - (float) Math.exp(-seconds / .11));
      rpm += clamp(change, -11000 * seconds, 7500 * seconds);
    }
    rpm = clamp(rpm, tuning.idle(), tuning.redline());
    load += (1 - (float) Math.exp(-seconds / .075)) * (desiredLoad - load);
    load = clamp(load, -1, 1);
    return true;
  }

  private void shift(int selected) {
    shiftDirection = selected > gear ? 1 : -1;
    shiftFrom = rpm;
    shiftElapsed = held = 0;
    shiftDuration = shiftDirection > 0 ? .4f : .48f;
    gear = selected;
  }

  int gear() { return primed ? gear + 1 : 0; }

  JSONObject json() {
    JSONObject result = new JSONObject();
    try {
      result.put("virtual", true).put("rpm", rpm).put("gear", gear()).put("signedLoad", load)
          .put("estimatedThrottle", throttle).put("shifting", shiftDirection != 0)
          .put("shiftDirection", shiftDirection).put("actualPedalMeasurement", false)
          .put("actualGearMeasurement", false);
    } catch (Exception ignored) {}
    return result;
  }
}
