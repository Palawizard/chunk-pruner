package fr.palawi.chunkpruner.core.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs a read per item on a fixed number of threads and hands each result to the caller in the
 * order of the items, on the caller's thread, so what the caller builds from them (reports,
 * plans, warnings) is the same as with one thread. At most twice the thread count of results wait
 * at a time. The reads must not share mutable state; nothing is written from these threads.
 */
final class ParallelReads implements AutoCloseable {
    static final int MAX_THREADS = 256;

    private final ExecutorService executor;
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final int window;

    ParallelReads(int threadCount) {
        if (threadCount < 1 || threadCount > MAX_THREADS) {
            throw new IllegalArgumentException("threads must be from 1 to " + MAX_THREADS);
        }
        AtomicInteger count = new AtomicInteger();
        this.executor = threadCount == 1
                ? null
                : Executors.newFixedThreadPool(threadCount, task -> {
                    Thread thread = new Thread(task, "chunkpruner-scan-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    threads.add(thread);
                    return thread;
                });
        this.window = 2 * threadCount;
    }

    /** The default: every hardware thread, at most 8 (ARCHITECTURE, Concurrency and safety). */
    static int defaultThreads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    <T, R> void forEachInOrder(List<T> items, Function<T, R> read, Consumer<R> consumer) {
        if (executor == null) {
            for (T item : items) {
                consumer.accept(read.apply(item));
            }
            return;
        }
        Deque<Future<R>> pending = new ArrayDeque<>();
        int next = 0;
        try {
            while (next < items.size() || !pending.isEmpty()) {
                while (next < items.size() && pending.size() < window) {
                    T item = items.get(next++);
                    pending.add(executor.submit(() -> read.apply(item)));
                }
                consumer.accept(await(pending.removeFirst()));
            }
        } finally {
            pending.forEach(f -> f.cancel(true));
        }
    }

    private static <R> R await(Future<R> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading regions", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Override
    public void close() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (Thread thread : threads) {
                // The pool counts a worker as gone before its thread ends: wait for the thread itself.
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
