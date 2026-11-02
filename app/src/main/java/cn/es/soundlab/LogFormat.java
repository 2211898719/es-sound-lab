package cn.es.soundlab;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogFormat {
  private static final Pattern DATED = Pattern.compile(
      "^\\s*((?:\\d{4}-)?\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(.*)$");

  public static String normalize(String line, long now) {
    if (line == null || line.length() > 8192) return null;
    if (Protocol.logTime(line) >= 0) return line;
    Matcher match = DATED.matcher(line);
    if (!match.matches()) return null;
    String stamp = match.group(1);
    try {
      boolean hasYear = stamp.length() == 23;
      SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
      format.setLenient(false);
      long closest = Long.MIN_VALUE;
      Calendar calendar = Calendar.getInstance();
      calendar.setTimeInMillis(now);
      int year = calendar.get(Calendar.YEAR);
      for (int offset = -1; offset <= 1; offset++) {
        Date parsed;
        try { parsed = format.parse(hasYear ? stamp : (year + offset) + "-" + stamp); }
        catch (java.text.ParseException invalid) {
          if (hasYear) return null;
          continue;
        }
        long candidate = parsed.getTime();
        if (closest == Long.MIN_VALUE || Math.abs(candidate - now) < Math.abs(closest - now))
          closest = candidate;
        if (hasYear) break;
      }
      if (closest == Long.MIN_VALUE) return null;
      return String.format(Locale.ROOT, "%d.%03d %s", closest / 1000,
          Math.floorMod(closest, 1000), match.group(2));
    } catch (Exception error) {
      return null;
    }
  }

  static boolean fresh(long time, long start, long now) {
    return time >= start && time >= now - 5000 && time <= now + 500;
  }

  private LogFormat() {}
}
