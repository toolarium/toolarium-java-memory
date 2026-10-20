/*
 * MemoryInformationStatistics.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.dto;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * Immutable, thread-safe snapshot of statistics produced by the memory information monitor at the time of
 * one reading. Published via volatile write; readers never block the collector thread.
 *
 * <p>The scope is fixed by the monitor configuration: {@link StatisticScopeType#WINDOW} when a sliding
 * window was configured, {@link StatisticScopeType#LIFETIME} otherwise. Use {@link #get(MemoryMetric)}
 * and {@link #getMetrics()} to access data in the configured scope.</p>
 *
 * @author patrick
 */
public final class MemoryInformationStatistics {

    private final long timestamp;
    private final long totalNumber;
    private final long snapshotTimestamp;
    private final Duration windowSize;
    private final Map<MemoryMetric, MemoryMetricStatistics> memoryMetricStatisticMap;


    /**
     * Constructor
     *
     * @param timestamp wall-clock time of this reading ({@link System#currentTimeMillis()})
     * @param totalNumber the total number of readings recorded so far (sample count)
     * @param snapshotTimestamp wall-clock time of this snapshot in milliseconds ({@link System#currentTimeMillis()})
     * @param windowSize the configured sliding window size, {@code null} if the window is disabled
     * @param memoryMetricStatisticMap statistics per metric in the configured scope
     */
    public MemoryInformationStatistics(long timestamp, long totalNumber, long snapshotTimestamp, Duration windowSize, Map<MemoryMetric, MemoryMetricStatistics> memoryMetricStatisticMap) {
        this.timestamp = timestamp;
        this.totalNumber = totalNumber;
        this.snapshotTimestamp = snapshotTimestamp;
        this.windowSize = windowSize;
        this.memoryMetricStatisticMap = Collections.unmodifiableMap(new EnumMap<>(memoryMetricStatisticMap));
    }


    /**
     * Returns the wall-clock time of this reading in milliseconds since epoch.
     *
     * @return wall-clock time ({@link System#currentTimeMillis()})
     */
    public long getTimestamp() {
        return timestamp;
    }


    /**
     * Returns the total number of readings recorded since the monitor started.
     *
     * @return the total number of readings
     */
    public long getTotalNumber() {
        return totalNumber;
    }


    /**
     * Returns the wall-clock time of this snapshot in milliseconds since epoch ({@link System#currentTimeMillis()}).
     * Use this to compute the age of the snapshot: {@code System.currentTimeMillis() - getSnapshotTimestamp()}.
     *
     * @return wall-clock time at snapshot creation
     */
    public long getSnapshotTimestamp() {
        return snapshotTimestamp;
    }


    /**
     * Returns the configured sliding window size, or {@code null} if the window is disabled (lifetime scope).
     *
     * @return the window size, or {@code null} if disabled
     */
    public Duration getWindowSize() {
        return windowSize;
    }


    /**
     * Returns the scope this snapshot was produced with: {@link StatisticScopeType#WINDOW} when a
     * sliding window is configured, otherwise {@link StatisticScopeType#LIFETIME}.
     *
     * @return the statistic scope type
     */
    public StatisticScopeType getStatisticScopeType() {
        if (windowSize != null) {
            return StatisticScopeType.WINDOW;
        }
        return StatisticScopeType.LIFETIME;
    }


    /**
     * Returns the statistics for a metric in the configured scope.
     *
     * @param metric the metric
     * @return the statistics, or {@code null} if the metric has no data
     */
    public MemoryMetricStatistics get(MemoryMetric metric) {
        return memoryMetricStatisticMap.get(metric);
    }


    /**
     * Returns all metrics that have statistics in the configured scope.
     *
     * @return the set of metrics in iteration order (enum ordinal)
     */
    public Set<MemoryMetric> getMetrics() {
        return memoryMetricStatisticMap.keySet();
    }


    /**
     * Returns true if the other object is a {@code MemoryInformationStatistics} with identical field values.
     *
     * @param o the object to compare
     * @return true if equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MemoryInformationStatistics)) {
            return false;
        }
        MemoryInformationStatistics other = (MemoryInformationStatistics) o;
        return timestamp == other.timestamp
                && totalNumber == other.totalNumber
                && snapshotTimestamp == other.snapshotTimestamp
                && Objects.equals(windowSize, other.windowSize)
                && Objects.equals(memoryMetricStatisticMap, other.memoryMetricStatisticMap);
    }


    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(timestamp, totalNumber, snapshotTimestamp, windowSize, memoryMetricStatisticMap);
    }


    /**
     * Returns a human-readable representation of this snapshot.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "MemoryInformationStatistics{"
                + "timestamp=" + timestamp
                + ", totalNumber=" + totalNumber
                + ", snapshotTimestamp=" + snapshotTimestamp
                + ", windowSize=" + windowSize
                + ", memoryMetricStatisticMap=" + memoryMetricStatisticMap
                + '}';
    }
}
