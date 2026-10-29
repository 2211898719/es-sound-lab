package cn.es.soundlab;

import java.util.HashMap;
import java.util.Map;

/** Baselines on startup and on source changes; never turns a first reading into a sound. */
public final class TransitionGate {
  public enum Change {
    BASELINE,
    SAME,
    CHANGED,
    INVALID
  }

  private static final class Entry {
    String source, value;

    Entry(String s, String v) {
      source = s;
      value = v;
    }
  }

  private final Map<String, Entry> entries = new HashMap<>();

  public Change accept(String key, String value, String source) {
    if (value == null) {
      entries.remove(key);
      return Change.INVALID;
    }
    Entry old = entries.put(key, new Entry(source, value));
    if (old == null || !old.source.equals(source)) return Change.BASELINE;
    return old.value.equals(value) ? Change.SAME : Change.CHANGED;
  }

  public void reset() {
    entries.clear();
  }
}
