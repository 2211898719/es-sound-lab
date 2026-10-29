package cn.es.soundlab;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public final class LogReaderTest {
  private static final class FakeProcess extends Process {
    final PipedInputStream output = new PipedInputStream(16384);
    final PipedOutputStream writer;
    final InputStream errors;
    volatile boolean ended;

    FakeProcess(String stderr) throws Exception {
      writer = new PipedOutputStream(output);
      errors = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
    }

    void line(String value) throws Exception {
      writer.write((value + "\n").getBytes(StandardCharsets.UTF_8));
      writer.flush();
    }

    void finish() throws Exception {
      ended = true;
      writer.close();
    }

    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    @Override public InputStream getInputStream() { return output; }
    @Override public InputStream getErrorStream() { return errors; }
    @Override public int waitFor() { return 0; }
    @Override public int exitValue() {
      if (!ended) throw new IllegalThreadStateException("running");
      return 0;
    }
    @Override public void destroy() {
      ended = true;
      try {
        writer.close();
        output.close();
      } catch (Exception ignored) {
      }
    }
  }

  private final Diagnostics diagnostics = new Diagnostics();
  private final AtomicInteger launches = new AtomicInteger();
  private final CountDownLatch started = new CountDownLatch(1), reset = new CountDownLatch(1);
  private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
  private LogReader reader;
  private FakeProcess process;
  private String[] command;

  @Before
  public void permission() {
    shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.READ_LOGS");
  }

  @After
  public void cleanup() throws Exception {
    if (reader != null) {
      reader.stop();
      Thread thread = ReflectionHelpers.getField(reader, "thread");
      if (thread != null) thread.join(2000);
    }
  }

  private LogReader.Listener listener() {
    return new LogReader.Listener() {
      public void line(String value, long start) { lines.add(value); }
      public void reset() { reset.countDown(); }
    };
  }

  private void start(boolean discovery, String stderr) throws Exception {
    process = new FakeProcess(stderr);
    reader = new LogReader(RuntimeEnvironment.getApplication(), diagnostics, listener(), discovery,
        arguments -> {
          command = arguments;
          launches.incrementAndGet();
          started.countDown();
          return process;
        });
    reader.start();
    assertTrue(started.await(3, TimeUnit.SECONDS));
  }

  private String log(String tag, String text) {
    return "1793284794.220 2178 2358 I " + tag + ": " + text;
  }

  private void assertDelivered(String expected) throws Exception {
    assertEquals(expected, lines.poll(3, TimeUnit.SECONDS));
  }

  @Test
  public void denialTextFromAnotherAppDoesNotCloseTheLogReader() throws Exception {
    start(true, "");
    String denied = log("MessageProperty", "VehicleDoor Permission denied; operation not permitted");
    process.line(denied);
    assertDelivered(denied);
    String next = log("MessageProperty", "VehicleDoor/CentralLock value=1");
    process.line(next);
    assertDelivered(next);
    assertFalse(process.ended);
    assertEquals(1, launches.get());
    assertEquals(1, reset.getCount());
  }

  @Test
  public void modeChangesFilterInPlaceWithoutLaunchingOrResetting() throws Exception {
    start(false, "");
    String extended = log("AnotherTag", "VehicleDoor extended");
    String standard = log("MessageProperty", "VehicleDoor standard");
    process.line(extended);
    process.line(standard);
    assertDelivered(standard);
    reader.discovery(true);
    process.line(extended);
    assertDelivered(extended);
    reader.discovery(false);
    process.line(extended);
    process.line(standard);
    assertDelivered(standard);
    assertTrue(lines.isEmpty());
    assertEquals("*:V", command[command.length - 1]);
    assertEquals(1, launches.get());
    assertEquals(1, reset.getCount());
  }

  @Test
  public void genuineStderrIsRecordedAndEofRequiresManualReconnect() throws Exception {
    start(true, "logcat: Permission denied\n");
    process.finish();
    assertTrue(reset.await(3, TimeUnit.SECONDS));
    Thread thread = ReflectionHelpers.getField(reader, "thread");
    thread.join(2000);
    assertFalse(thread.isAlive());
    assertEquals(1, launches.get());
    assertTrue(diagnostics.logStatus.contains("重新连接"));
    assertTrue(diagnostics.lines("").contains("退出码=0"));
    Thread errors = ReflectionHelpers.getField(reader, "errorThread");
    errors.join(2000);
    assertTrue(diagnostics.lines("").contains("logcat stderr：logcat: Permission denied"));
  }

  @Test
  public void launchFailureDoesNotLoopAndRequestAuthorizationAgain() throws Exception {
    reader = new LogReader(RuntimeEnvironment.getApplication(), diagnostics, listener(), false,
        arguments -> {
          launches.incrementAndGet();
          throw new java.io.IOException("cannot launch");
        });
    reader.start();
    assertTrue(reset.await(3, TimeUnit.SECONDS));
    Thread thread = ReflectionHelpers.getField(reader, "thread");
    thread.join(2000);
    assertFalse(thread.isAlive());
    assertEquals(1, launches.get());
    assertTrue(diagnostics.lines("").contains("cannot launch"));
  }

  @Test
  public void disconnectedModeChangeKeepsManualReconnectStatus() throws Exception {
    start(false, "");
    process.line(log("MessageProperty", "VehicleDoor data"));
    assertDelivered(log("MessageProperty", "VehicleDoor data"));
    process.finish();
    assertTrue(reset.await(3, TimeUnit.SECONDS));
    Thread thread = ReflectionHelpers.getField(reader, "thread");
    thread.join(2000);
    reader.discovery(true);
    assertTrue(diagnostics.logStatus.contains("重新连接"));
    assertEquals(1, launches.get());
  }
}
