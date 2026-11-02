package cn.es.soundlab;

import java.util.ArrayDeque;

final class EngineMotion {
  static final long MAX_AGE = 2500;
  private final ArrayDeque<Point> points = new ArrayDeque<>();
  private long last = -1;
  private float raw, speed, acceleration;

  private static final class Point {
    final long time;
    final float speed;
    Point(long time, float speed) { this.time = time; this.speed = speed; }
  }

  void reset() {
    points.clear();
    last = -1;
    raw = speed = acceleration = 0;
  }

  void sample(float kmh, long now, EngineProfile profile) {
    if (Float.isNaN(kmh) || Float.isInfinite(kmh) || kmh < 0 || kmh > 240) {
      reset();
      return;
    }
    if (last >= 0 && now <= last) return;
    if (last >= 0 && (now - last > MAX_AGE
        || Math.abs(kmh - raw) / ((now - last) / 1000f) > 40)) reset();
    float blend = last < 0 ? 1 : 1 - (float) Math.exp(-(now - last) / (double) profile.responseMillis);
    raw = kmh;
    speed += blend * (kmh - speed);
    last = now;
    points.addLast(new Point(now, kmh));
    while (points.size() > 2 && now - points.peekFirst().time > 1500) points.removeFirst();
    while (points.size() > 32) points.removeFirst();
    double sumTime = 0, sumSpeed = 0, sumSquares = 0, sumProduct = 0;
    for (Point point : points) {
      double seconds = (point.time - now) / 1000.0;
      sumTime += seconds;
      sumSpeed += point.speed / 3.6;
      sumSquares += seconds * seconds;
      sumProduct += seconds * point.speed / 3.6;
    }
    double denominator = points.size() * sumSquares - sumTime * sumTime;
    float slope = denominator < .0001 ? 0
        : (float) ((points.size() * sumProduct - sumTime * sumSpeed) / denominator);
    slope = Math.max(-6, Math.min(6, slope));
    if (Math.abs(slope) < .08f) slope = 0;
    acceleration += blend * (slope - acceleration);
  }

  boolean ready(long now) {
    return available(now) && points.size() >= 2
        && last - points.peekFirst().time >= 200;
  }

  boolean available(long now) { return last >= 0 && now >= last && now - last <= MAX_AGE; }
  long age(long now) { return last < 0 ? -1 : Math.max(0, now - last); }

  float raw() { return raw; }
  float speed() { return speed; }
  float acceleration() { return acceleration; }
}
