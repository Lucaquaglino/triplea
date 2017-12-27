package games.strategy.thread;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import games.strategy.debug.ClientLogger;

/**
 * An ExecutorService backed thread pool.
 */
public class ThreadPool {
  private final ExecutorService executorService;
  private final Queue<Future<?>> futuresStack = new ArrayDeque<>();

  /**
   * Creates a thread pool that reuses a fixed number of threads
   * operating off a shared unbounded queue. At any point, at most
   * {@code nThreads} threads will be active processing tasks.
   * If additional tasks are submitted when all threads are active,
   * they will wait in the queue until a thread is available.
   * If any thread terminates due to a failure during execution
   * prior to shutdown, a new one will take its place if needed to
   * execute subsequent tasks. The threads in the pool will exist
   * until it is explicitly {@link ThreadPool#shutDown shutdown}.
   *
   * @param nThreads the number of threads in the pool
   * @throws IllegalArgumentException if {@code nThreads <= 0}
   */
  public ThreadPool(final int max) {
    executorService = Executors.newFixedThreadPool(max);
  }


  /**
   * Run the given task.
   */
  public void runTask(final Runnable task) {
    futuresStack.add(executorService.submit(task));
  }


  /**
   * Returns when all tasks run through the runTask method have finished.
   */
  public void waitForAll() {
    while (!futuresStack.isEmpty()) {
      try {
        futuresStack.poll().get();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (final ExecutionException e) {
        ClientLogger.logError(e.getCause());
      }
    }
  }

  /**
   * Shutdown the thread pool. Currently running tasks will finish, but new tasks will not start.
   * All threads will shutdown after finishing any tasks they may be currently running.
   * A call to shutDown() followed by waitForAll() will ensure that no threads are running.
   */
  public void shutDown() {
    executorService.shutdown();
  }
}
