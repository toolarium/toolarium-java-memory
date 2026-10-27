/*
 * JvmMemoryReaderImplTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.impl.SlidingWindow;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import org.junit.jupiter.api.Test;


/**
 * Tests for {@link JvmMemoryReaderImpl}.
 *
 * @author patrick
 */
public class JvmMemoryReaderImplTest {

    /**
     * {@code process()} must return a successful reading with positive HEAP and NON_HEAP values.
     * All other metrics must carry the {@link SlidingWindow#ABSENT} sentinel.
     */
    @Test
    public void testProcessReturnsHeapAndNonHeap() {
        JvmMemoryReaderImpl reader = new JvmMemoryReaderImpl();
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getTimestamp());

        long[] values = result.getValues();
        assertTrue(values[MemoryMetric.HEAP.ordinal()] > 0, "Heap must be positive");
        assertTrue(values[MemoryMetric.NON_HEAP.ordinal()] > 0, "NonHeap must be positive");
        assertTrue(values[MemoryMetric.TOTAL.ordinal()] == SlidingWindow.ABSENT, "Total must be ABSENT");
        assertTrue(values[MemoryMetric.USED.ordinal()] == SlidingWindow.ABSENT, "Used must be ABSENT");
        assertTrue(values[MemoryMetric.FREE.ordinal()] == SlidingWindow.ABSENT, "Free must be ABSENT");
        assertTrue(values[MemoryMetric.PROCESS.ordinal()] == SlidingWindow.ABSENT, "Process must be ABSENT");
        assertTrue(values[MemoryMetric.JIT.ordinal()] == SlidingWindow.ABSENT, "Code must be ABSENT");
    }


    /**
     * {@code process()} returns a reading on the first call and after {@code reset()};
     * a second call without reset returns {@code null} when the values have not changed.
     */
    @Test
    public void testProcessCanBeCalledRepeatedly() {
        JvmMemoryReaderImpl reader = new JvmMemoryReaderImpl();
        MemoryInformation first = reader.process();
        assertNotNull(first);
        assertTrue(first.isSuccess());

        // Without reset, unchanged values return null (change detection)
        MemoryInformation unchanged = reader.process();
        assertNull(unchanged, "Unchanged values must return null");

        // After reset, the next call must produce a fresh reading
        reader.reset();
        MemoryInformation afterReset = reader.process();
        assertNotNull(afterReset);
        assertTrue(afterReset.isSuccess());
    }


    /**
     * {@code reset()} must be a no-op: it must not throw and subsequent calls to
     * {@code process()} must still succeed.
     */
    @Test
    public void testResetIsNoOp() {
        JvmMemoryReaderImpl reader = new JvmMemoryReaderImpl();
        reader.reset(); // must not throw
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
    }


    /**
     * When the calling thread's interrupt flag is set, {@code process()} must return
     * {@code null} and leave the interrupt flag set.
     */
    @Test
    public void testInterruptReturnsNull() {
        JvmMemoryReaderImpl reader = new JvmMemoryReaderImpl();
        Thread.currentThread().interrupt();
        try {
            assertNull(reader.process());
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupt flag must remain set");
        } finally {
            Thread.interrupted(); // clear flag for subsequent tests
        }
    }
}
