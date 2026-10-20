/*
 * JvmMemoryReaderImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader.impl;

import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.impl.SlidingWindow;
import com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.LocalDateTime;


/**
 * An {@link IMemoryInformationReader} that reads JVM memory metrics directly from
 * {@link MemoryMXBean}. Use this when no external memory information file is available.
 *
 * <p>{@link MemoryMetric#HEAP} (committed), {@link MemoryMetric#NON_HEAP} (committed),
 * {@link MemoryMetric#HEAP_USED} and {@link MemoryMetric#NON_HEAP_USED} are populated;
 * all other metrics carry the sentinel value {@link SlidingWindow#ABSENT}.</p>
 *
 * <p>Configure it via:
 * <pre>
 *   IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance()
 *       .newBuilder()
 *       .jvmReader()
 *       .build();
 * </pre>
 * </p>
 *
 * @author patrick
 */
public class JvmMemoryReaderImpl implements IMemoryInformationReader {

    private static final int METRIC_COUNT = MemoryMetric.values().length;


    /**
     * Reads the current JVM heap and non-heap memory usage.
     *
     * <p>Every call returns a new successful reading with the current values.
     * Returns {@code null} only if the calling thread is interrupted.</p>
     *
     * @return the current JVM memory reading, or {@code null} if the thread is interrupted
     */
    @Override
    public MemoryInformation process() {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }

        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        MemoryUsage nonHeap = bean.getNonHeapMemoryUsage();

        long[] values = new long[METRIC_COUNT];
        for (int i = 0; i < METRIC_COUNT; i++) {
            values[i] = SlidingWindow.ABSENT;
        }
        values[MemoryMetric.HEAP.ordinal()] = heap.getCommitted();
        values[MemoryMetric.NON_HEAP.ordinal()] = nonHeap.getCommitted();
        values[MemoryMetric.HEAP_USED.ordinal()] = heap.getUsed();
        values[MemoryMetric.NON_HEAP_USED.ordinal()] = nonHeap.getUsed();

        return MemoryInformation.success(LocalDateTime.now(), values);
    }


    /**
     * Resets the internal state. This implementation has no mutable state, so this is a no-op.
     */
    @Override
    public void reset() {
        // no state to reset
    }
}
