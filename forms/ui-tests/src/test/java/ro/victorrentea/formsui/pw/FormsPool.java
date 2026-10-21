package ro.victorrentea.formsui.pw;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One long-lived Forms client per parallel worker (-Dworkers, default 3; prepare them with
 * ../setup-workers.sh). A test borrows a worker, which gives it its own client and its own schema,
 * so tests running side by side never see each other's orders.
 */
public final class FormsPool {
  static final int WORKERS = Integer.getInteger("workers", 3);
  private static final BlockingQueue<Integer> FREE = new ArrayBlockingQueue<>(WORKERS);
  private static final Map<Integer, FormsApp> CLIENTS = new ConcurrentHashMap<>();

  static {
    for (int i = 1; i <= WORKERS; i++) FREE.add(i);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> CLIENTS.values().parallelStream().forEach(FormsApp::close)));
  }

  private FormsPool() {
  }

  /** Waits for a free worker, starts its client the first time, and readies it for this test. */
  public static FormsApp acquire(String testName) {
    int worker;
    try {
      worker = FREE.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    try {
      FormsApp app = CLIENTS.computeIfAbsent(worker, FormsApp::launch);
      app.startTest(new Trace(testName));
      return app;
    } catch (RuntimeException | Error e) {
      discard(worker);
      throw e;
    }
  }

  public static void release(FormsApp app, boolean healthy) {
    int worker = CLIENTS.entrySet().stream().filter(e -> e.getValue() == app).findFirst().orElseThrow().getKey();
    if (!healthy) {
      discard(worker); // a client left in an unknown state is restarted for the next test
    } else {
      FREE.add(worker);
    }
  }

  private static void discard(int worker) {
    FormsApp broken = CLIENTS.remove(worker);
    if (broken != null) broken.close();
    FREE.add(worker);
  }
}
