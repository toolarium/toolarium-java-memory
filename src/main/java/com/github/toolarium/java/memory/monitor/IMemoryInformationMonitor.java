/*
 * IMemoryInformationMonitor.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor;

import com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.dto.Percentile;
import com.github.toolarium.java.memory.monitor.dto.StatisticScopeType;
import java.time.Duration;



/**
 * Monitors a memory information file, computes per-metric streaming statistics and
 * publishes immutable snapshots for lock-free reads.
 *
 * <p>Obtain an instance via {@link MemoryMonitorFactory}.</p>
 *
 * @author patrick
 */
public interface IMemoryInformationMonitor {

    /**
     * Starts the internal collector thread. Subsequent calls are ignored while running.
     * The monitor also starts when the memory information file does not exist yet.
     */
    void start();


    /**
     * Stops the collector thread and waits for it to finish. The collected statistics are kept.
     */
    void stop();


    /**
     * Returns whether the collector thread is running.
     *
     * @return true if the monitor is running
     */
    boolean isRunning();


    /**
     * Returns the latest statistics snapshot for the primary (first configured) window.
     * Thread-safe; can be called from any thread without blocking the collector.
     *
     * @return the latest statistics snapshot, or {@code null} if no data has been collected yet
     */
    MemoryInformationStatistics getLatestStatistics();


    /**
     * Returns the latest statistics snapshot for a specific window duration.
     * Use {@code null} to select the lifetime scope.
     *
     * @param window the window duration to look up; {@code null} selects the lifetime scope
     * @return the statistics snapshot for that window, or {@code null} if no data has been collected yet
     * @throws IllegalArgumentException if the window duration was not configured
     */
    MemoryInformationStatistics getLatestStatistics(Duration window);


    /**
     * Returns the value of a percentile of a metric in the configured scope.
     *
     * @param metric the metric
     * @param percentile the percentile
     * @return the value in bytes, or {@code null} if no data is available
     */
    Long getPercentile(MemoryMetric metric, Percentile percentile);


    /**
     * Returns the statistic scope this monitor was configured with.
     *
     * <p>The scope is determined at build time by the window setting:</p>
     * <ul>
     *   <li>{@link StatisticScopeType#WINDOW}: a positive window length was set; statistics and
     *       percentiles reflect only readings within that rolling window.</li>
     *   <li>{@link StatisticScopeType#LIFETIME}: window is disabled (0); statistics accumulate
     *       from the moment {@link #start()} is called. Switching to a different scope requires
     *       reconfiguring via {@link MemoryMonitorFactory#newBuilder()} and restarting. The new
     *       instance begins with no history.</li>
     * </ul>
     *
     * @return the scope used by {@link #getPercentile(MemoryMetric, Percentile)}
     */
    StatisticScopeType getScope();


    /**
     * Returns the configured sliding window size, or {@code null} if the window is disabled (lifetime scope).
     *
     * @return the window size, or {@code null} if disabled
     */
    Duration getWindow();


    /**
     * Returns whether the data source is available: at least one reading has been successfully
     * collected and there is no active problem (e.g. file missing, parse error).
     *
     * <p>Returns {@code false} before the first successful read, while a problem is active,
     * or after the source disappears. Recovers automatically once the source is readable again.</p>
     *
     * @return true if data is being collected without error
     */
    boolean isAvailable();


    /**
     * Returns whether the latest statistics are stale: no new reading within the stale threshold,
     * or no data collected yet. Returns false when stale detection is disabled (threshold &lt;= 0).
     *
     * @return true if the statistics are stale or no data has been collected
     */
    boolean isStale();


    /**
     * Returns the wall-clock timestamp of the latest statistics snapshot in milliseconds
     * ({@link System#currentTimeMillis()}), or -1 if no data has been collected yet.
     *
     * @return the snapshot timestamp in milliseconds, or -1 if no data has been collected yet
     */
    long getLatestStatisticTimestamp();


}
