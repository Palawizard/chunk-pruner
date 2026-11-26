package fr.palawi.chunkpruner.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ParallelReadsTest {
    private static final List<Integer> ITEMS = IntStream.range(0, 200).boxed().toList();

    @Test
    void resultsComeBackInItemOrderOnTheCallingThread() {
        Set<String> readers = ConcurrentHashMap.newKeySet();
        List<Integer> seen = new ArrayList<>();
        Thread caller = Thread.currentThread();
        try (ParallelReads reads = new ParallelReads(4)) {
            reads.forEachInOrder(
                    ITEMS,
                    i -> {
                        readers.add(Thread.currentThread().getName());
                        sleep((200 - i) % 7);
                        return i * 10;
                    },
                    r -> {
                        assertEquals(caller, Thread.currentThread());
                        seen.add(r / 10);
                    });
        }
        assertEquals(ITEMS, seen);
        assertTrue(readers.size() > 1, "the reads ran on several threads: " + readers);
        assertTrue(readers.stream().allMatch(n -> n.startsWith("chunkpruner-scan-")), readers.toString());
    }

    @Test
    void oneThreadReadsOnTheCallingThread() {
        List<String> readers = new ArrayList<>();
        try (ParallelReads reads = new ParallelReads(1)) {
            reads.forEachInOrder(ITEMS, i -> Thread.currentThread().getName(), readers::add);
        }
        assertEquals(Set.of(Thread.currentThread().getName()), Set.copyOf(readers));
    }

    @Test
    void aFailedReadStopsTheLoopAndNoLaterResultIsUsed() {
        List<Integer> seen = new ArrayList<>();
        AtomicInteger started = new AtomicInteger();
        try (ParallelReads reads = new ParallelReads(4)) {
            IllegalStateException e = assertThrows(
                    IllegalStateException.class,
                    () -> reads.forEachInOrder(
                            ITEMS,
                            i -> {
                                started.incrementAndGet();
                                if (i == 50) {
                                    throw new IllegalStateException("broken region");
                                }
                                return i;
                            },
                            seen::add));
            assertEquals("broken region", e.getMessage());
        }
        assertEquals(IntStream.range(0, 50).boxed().toList(), seen);
        assertTrue(started.get() <= 50 + 8 + 1, "at most twice the threads ahead: " + started.get());
    }

    @Test
    void atMostTwiceTheThreadsOfResultsWait() {
        AtomicInteger read = new AtomicInteger();
        AtomicInteger handed = new AtomicInteger();
        int[] most = {0};
        try (ParallelReads reads = new ParallelReads(3)) {
            reads.forEachInOrder(
                    ITEMS,
                    i -> {
                        read.incrementAndGet();
                        return i;
                    },
                    r -> {
                        sleep(1);
                        most[0] = Math.max(most[0], read.get() - handed.incrementAndGet());
                    });
        }
        assertTrue(most[0] <= 6, "results waiting at once: " + most[0]);
    }

    @Test
    void lessThanOneThreadIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ParallelReads(0));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
