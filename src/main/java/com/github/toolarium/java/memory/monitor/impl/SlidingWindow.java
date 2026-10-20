/*
 * SlidingWindow.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl;

import java.util.Arrays;


/**
 * Fixed-capacity circular buffer for a time-based sliding window.
 *
 * <p>Each slot stores a millisecond timestamp and one {@code long} value per metric. Entries whose
 * timestamp is older than {@code windowMs} milliseconds before the most recent {@link #add} or an
 * explicit {@link #evict} call are removed. When the buffer is full, the oldest entry is dropped
 * to make room, so the effective window may be shorter than {@code windowMs} if readings arrive
 * faster than the capacity allows.</p>
 *
 * <p>Not thread-safe — all calls must be serialized externally (typically by the collector lock
 * in {@link com.github.toolarium.java.memory.monitor.impl.MemoryInformationMonitorImpl}).</p>
 *
 * @author patrick
 */
public class SlidingWindow {

    /** Sentinel value used in value arrays to indicate that a metric was not present in a reading. */
    public static final long ABSENT = Long.MIN_VALUE;

    private final long windowMs;
    private final int capacity;
    private final int metricCount;
    private final long[] timestamps;
    private final long[][] values;
    private int head;
    private int size;


    /**
     * Constructor
     *
     * @param windowMs the window length in milliseconds (must be &gt; 0)
     * @param capacity the maximum number of readings to keep (must be &gt; 0)
     * @param metricCount the number of metrics per reading
     * @throws IllegalArgumentException if windowMs or capacity is not positive
     */
    public SlidingWindow(long windowMs, int capacity, int metricCount) {
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be positive: " + windowMs);
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.windowMs = windowMs;
        this.capacity = capacity;
        this.metricCount = metricCount;
        this.timestamps = new long[capacity];
        this.values = new long[capacity][metricCount];
        this.head = 0;
        this.size = 0;
    }


    /**
     * Adds a reading and evicts entries older than the window.
     *
     * @param milliTime the wall-clock timestamp from {@link System#currentTimeMillis()}
     * @param readingValues the metric values for this reading ({@link #ABSENT} for missing metrics)
     */
    public void add(long milliTime, long[] readingValues) {
        evict(milliTime);

        if (size == capacity) {
            // buffer full — drop oldest to make room
            head = (head + 1) % capacity;
            size--;
        }

        int tail = (head + size) % capacity;
        timestamps[tail] = milliTime;
        System.arraycopy(readingValues, 0, values[tail], 0, metricCount);
        size++;
    }


    /**
     * Evicts all entries older than the window relative to the given timestamp.
     *
     * @param milliTime the current wall-clock time (entries with timestamp &lt; milliTime - windowMs are removed)
     */
    public void evict(long milliTime) {
        long cutoff = milliTime - windowMs;
        while (size > 0 && timestamps[head] < cutoff) {
            head = (head + 1) % capacity;
            size--;
        }
    }


    /**
     * Returns all non-{@link #ABSENT} values of the given column in ascending sorted order.
     *
     * @param col the metric column index
     * @return sorted values; empty array if no values are present
     */
    public long[] getSortedValues(int col) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (values[(head + i) % capacity][col] != ABSENT) {
                count++;
            }
        }
        if (count == 0) {
            return new long[0];
        }

        long[] result = new long[count];
        int idx = 0;
        for (int i = 0; i < size; i++) {
            long v = values[(head + i) % capacity][col];
            if (v != ABSENT) {
                result[idx++] = v;
            }
        }
        Arrays.sort(result);
        return result;
    }


    /**
     * Returns the most recent non-{@link #ABSENT} value of the given column.
     *
     * @param col the metric column index
     * @return the last value, or {@link #ABSENT} if no value is present
     */
    public long getLastValue(int col) {
        for (int i = size - 1; i >= 0; i--) {
            long v = values[(head + i) % capacity][col];
            if (v != ABSENT) {
                return v;
            }
        }
        return ABSENT;
    }


    /**
     * Returns the current number of readings in the window.
     *
     * @return the number of readings
     */
    public int size() {
        return size;
    }


    /**
     * Computes a percentile of a sorted value array using the nearest-rank method.
     *
     * @param sorted a sorted (ascending) non-empty array
     * @param quantile the quantile (0.0 to 1.0 inclusive)
     * @return the value at the given quantile
     */
    public static long valueAtQuantile(long[] sorted, double quantile) {
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1);
        return sorted[index];
    }
}
