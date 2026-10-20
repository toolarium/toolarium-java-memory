/*
 * MemoryInformationMonitorImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl;

import com.github.toolarium.common.statistic.PercentileHistogram;
import com.github.toolarium.common.statistic.StatisticCounter;
import com.github.toolarium.common.util.ByteUtil;
import com.github.toolarium.java.memory.monitor.IMemoryInformationMonitor;
import com.github.toolarium.java.memory.monitor.MemoryMonitorFactory;
import com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetricStatistics;
import com.github.toolarium.java.memory.monitor.dto.Percentile;
import com.github.toolarium.java.memory.monitor.dto.StatisticScopeType;
import com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import com.github.toolarium.java.memory.monitor.report.IMemoryInformationReporter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * MemoryInformationMonitor - Singleton that monitors /dev/shm/meminfo.out for changes,
 * parses memory information, and computes statistics per {@link MemoryMetric}.
 *
 * <p>An internal daemon thread polls the file and collects data. A thread-safe, immutable
 * snapshot of the latest statistics is published via {@link #getLatestStatistics()} — readers never
 * block the collector thread.</p>
 *
 * <p>Statistics are computed in two {@link StatisticScopeType scopes}:</p>
 * <ul>
 *   <li>{@link StatisticScopeType#WINDOW}: the readings of a sliding window (default 10 minutes) with exact
 *       percentiles. Memory: window / 0.5 s × 8 bytes per metric (9.6 KB for 10 minutes).</li>
 *   <li>{@link StatisticScopeType#LIFETIME}: all readings since the start. Average, minimum, maximum and standard
 *       deviation are always computed; percentiles (relative error below 0.1%, 8–24 KB per metric) only when
 *       enabled.</li>
 * </ul>
 *
 *
 * <p>Two file formats are supported:</p>
 * <ul>
 *   <li>Default: a single data line with the values at the indices 2, 4, 6 and optional 8.</li>
 *   <li>Header-based: a header line starting with "Timestamp" followed by a data line. The
 *       columns are resolved by their header names (see {@link MemoryMetric#getColumnName()}; a suffix
 *       in brackets such as {@code Process[123]} is ignored), so the column order is not relevant. The header
 *       "Timestamp" column corresponds to the two data fields date and time. The JVM metrics are tracked
 *       when all JVM columns are present.</li>
 * </ul>
 *
 * <p>Resilience: the file is only read when it is a regular file (symbolic links, pipes and devices
 * are rejected), its content is never written to the log, an incomplete or invalid reading is
 * retried on the next poll, repeated problems are logged only once until recovery and the collector
 * thread survives unexpected runtime exceptions. The monitor can be started before the file exists.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().getMemoryInformationMonitor();
 *   monitor.start();
 *   // ... later, from any thread (values in bytes):
 *   OptionalLong heapP95 = monitor.getPercentile(MemoryMetric.HEAP, Percentile.P95);
 *   monitor.getLatestStatistics()
 *          .flatMap(stats -&gt; stats.get(MemoryMetric.PROCESS))
 *          .ifPresent(process -&gt; LOG.info("Process max: {} bytes", process.getMax()));
 * </pre>
 *
 * @author patrick
 */
public class MemoryInformationMonitorImpl implements IMemoryInformationMonitor, MemoryInformationMonitorConstants {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryInformationMonitorImpl.class);
    private static final long POLL_INTERVAL_MS = 1_000;
    private static final long STOP_TIMEOUT_MS = POLL_INTERVAL_MS * 2;

    private static final MemoryMetric[] METRICS = MemoryMetric.values();
    private static final int METRIC_COUNT = METRICS.length;
    private static final double[] QUANTILES = buildQuantiles();

    private final IMemoryInformationReader reader;
    private final IMemoryInformationReporter reporter;
    private final long reportIntervalMs;
    private final Object collectLock = new Object();
    private final StatisticCounter[] counters = new StatisticCounter[METRIC_COUNT];
    private final PercentileHistogram[] histograms = new PercentileHistogram[METRIC_COUNT];
    private final long[] lastValues = new long[METRIC_COUNT];

    private volatile boolean running;
    private volatile Thread collectorThread;
    private volatile MemoryInformationStatistics[] latestStatisticsPerWindow;
    private Duration[] windowSizes;
    private volatile boolean initialized;
    private volatile long staleThresholdMs;
    private volatile String currentProblem;

    // guarded by collectLock
    private SlidingWindow[] slidingWindows;
    private boolean staleWarned;
    private long readingCount;
    private long lastReportMs;


    /**
     * Constructor for test and single-window usage — no reporter.
     *
     * @param windowSize the sliding window size (null disables the window, max {@link MemoryMonitorFactory#MAX_WINDOW})
     * @param staleThresholdMilliseconds age after which stats are stale (0 to disable)
     * @param reader the reader to use
     */
    public MemoryInformationMonitorImpl(Duration windowSize, long staleThresholdMilliseconds, IMemoryInformationReader reader) {
        this(new Duration[]{windowSize}, staleThresholdMilliseconds, reader, null, 0L);
    }


    /**
     * Constructor for single-window builder-created instances with explicit configuration and a reporter.
     *
     * @param windowSize the sliding window size (null disables the window, max {@link MemoryMonitorFactory#MAX_WINDOW})
     * @param staleThresholdMilliseconds age after which stats are stale (0 to disable)
     * @param reader the reader to use
     * @param reporter the reporter to call periodically, or {@code null} to disable reporting
     * @param reportIntervalMs the interval in milliseconds between reporter calls; 0 disables reporting
     */
    public MemoryInformationMonitorImpl(Duration windowSize, long staleThresholdMilliseconds, IMemoryInformationReader reader,
                                        IMemoryInformationReporter reporter, long reportIntervalMs) {
        this(new Duration[]{windowSize}, staleThresholdMilliseconds, reader, reporter, reportIntervalMs);
    }


    /**
     * Constructor for multi-window builder-created instances.
     *
     * @param windowSizes the sliding window sizes; {@code null} entries disable the window (lifetime scope) for that slot
     * @param staleThresholdMilliseconds age after which stats are stale (0 to disable)
     * @param reader the reader to use
     * @param reporter the reporter to call periodically, or {@code null} to disable reporting
     * @param reportIntervalMs the interval in milliseconds between reporter calls; 0 disables reporting
     */
    public MemoryInformationMonitorImpl(Duration[] windowSizes, long staleThresholdMilliseconds, IMemoryInformationReader reader,
                                        IMemoryInformationReporter reporter, long reportIntervalMs) {
        this.windowSizes = windowSizes.clone();
        this.staleThresholdMs = staleThresholdMilliseconds;
        this.reader = reader;
        this.reporter = reporter;
        this.reportIntervalMs = reportIntervalMs;
    }


    /**
     * Returns the singleton instance.
     *
     * @return the MemoryInformationMonitor instance
     */


    /**
     * Starts the internal collector thread; subsequent calls are ignored while running.
     * The monitor also starts when the memory information file does not exist yet, the file is
     * picked up as soon as it appears. Fixes the window length and the lifetime percentiles setting.
     */
    public synchronized void start() {
        if (running) {
            return;
        }

        synchronized (collectLock) {
            ensureInitialized();
        }

        running = true;
        Thread thread = new Thread(this::collectLoop, MemoryInformationMonitorImpl.class.getSimpleName());
        thread.setDaemon(true);
        collectorThread = thread;
        thread.start();
        if (reporter != null && reportIntervalMs > 0) {
            reporter.start(reportIntervalMs);
        }
        LOG.info("MemoryInformationMonitor started.");
    }


    /**
     * Stops the collector thread and waits for it to finish. A collector thread that does not
     * terminate in time can not interfere with a later {@link #start()}: it exits at its next
     * loop check and all data processing is serialized. The collected statistics are kept.
     */
    public void stop() {
        Thread thread;
        synchronized (this) {
            if (!running) {
                return;
            }

            running = false;
            thread = collectorThread;
            collectorThread = null;
        }

        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (thread.isAlive()) {
                LOG.warn("MemoryInformationMonitor collector thread did not terminate within {} ms.", STOP_TIMEOUT_MS);
            }
        }
        if (reporter != null && reportIntervalMs > 0) {
            reporter.end();
        }
        LOG.info("MemoryInformationMonitor stopped.");
    }


    /**
     * Returns whether the collector thread is running.
     *
     * @return true if the monitor is running
     */
    public boolean isRunning() {
        return running;
    }


    /**
     * Returns the latest statistics snapshot. Thread-safe — can be called from any thread
     * without blocking the collector. Use {@link #isStale()} or {@link #getLatestStatisticTimestamp()}
     * to detect outdated data.
     *
     * @return the latest statistics snapshot, or {@code null} if no data has been collected yet
     */
    public MemoryInformationStatistics getLatestStatistics() {
        MemoryInformationStatistics[] snapshots = latestStatisticsPerWindow;
        if (snapshots == null) {
            return null;
        }
        return snapshots[0];
    }


    /**
     * Returns the latest statistics snapshot for the given window duration.
     *
     * @param window the window duration to look up; {@code null} selects the lifetime scope
     * @return the statistics snapshot for that window, or {@code null} if not configured or no data yet
     * @throws IllegalArgumentException if the given window duration is not configured
     */
    public MemoryInformationStatistics getLatestStatistics(Duration window) {
        MemoryInformationStatistics[] snapshots = latestStatisticsPerWindow;
        for (int i = 0; i < windowSizes.length; i++) {
            if (Objects.equals(windowSizes[i], window)) {
                if (snapshots == null) {
                    return null;
                }
                return snapshots[i];
            }
        }
        throw new IllegalArgumentException("Window " + window + " is not configured. Configured windows: " + Arrays.toString(windowSizes));
    }


    /**
     * Returns the value of a percentile of a metric in the default scope: the sliding window if enabled,
     * otherwise the lifetime.
     *
     * @param metric the metric
     * @param percentile the percentile
     * @return the value in bytes, or {@code null} if no data is available
     */
    public Long getPercentile(MemoryMetric metric, Percentile percentile) {
        MemoryInformationStatistics stats = getLatestStatistics();
        if (stats == null) {
            return null;
        }
        MemoryMetricStatistics metricStatsistic = stats.get(metric);
        if (metricStatsistic == null) {
            return null;
        }
        return metricStatsistic.get(percentile);
    }


    /**
     * Returns the age of the latest statistics snapshot, measured with the monotonic JVM clock
     * since the last new reading was recorded.
     *
     * @return the snapshot timestamp in milliseconds, or -1 if no data has been collected yet
     */
    public long getLatestStatisticTimestamp() {
        MemoryInformationStatistics[] snapshots = latestStatisticsPerWindow;
        if (snapshots == null) {
            return -1L;
        }
        return snapshots[0].getSnapshotTimestamp();
    }


    /**
     * Returns whether the data source is available: at least one reading has been successfully
     * collected and there is no active problem.
     *
     * @return true if data is being collected without error
     */
    public boolean isAvailable() {
        return latestStatisticsPerWindow != null && currentProblem == null;
    }


    /**
     * Returns whether the latest statistics are stale, i.e. no new reading was recorded within the
     * stale threshold (see {@link #setStaleThresholdMilliseconds(long)}). This typically means the process writing
     * the memory information file has stopped.
     *
     * @return true if no data has been collected yet or the latest reading is older than the threshold;
     *         false if the data is current or the stale detection is disabled
     */
    public boolean isStale() {
        long threshold = staleThresholdMs;
        if (threshold <= 0) {
            return false;
        }
        long snapshotTimestamp = getLatestStatisticTimestamp();
        if (snapshotTimestamp < 0) {
            return true;
        }
        return System.currentTimeMillis() - snapshotTimestamp > threshold;
    }


    /**
     * Sets the sliding window size. Must be called before {@link #start()} and before the first reading.
     * Pass {@code null} or {@link Duration#ZERO} to disable the window (lifetime scope).
     *
     * @param window the window size (null or {@link Duration#ZERO} disables the window, max {@link MemoryMonitorFactory#MAX_WINDOW})
     * @throws IllegalArgumentException if the window size is negative or exceeds the maximum
     * @throws IllegalStateException if the monitor was already started or has recorded data
     */
    public void setWindow(Duration window) {
        if (window != null && (window.isNegative() || window.compareTo(MemoryMonitorFactory.MAX_WINDOW) > 0)) {
            throw new IllegalArgumentException("Window size must be between Duration.ZERO and " + MemoryMonitorFactory.MAX_WINDOW + ": " + window);
        }

        synchronized (collectLock) {
            checkNotInitialized();
            if (window == null || window.isZero()) {
                windowSizes[0] = null;
            } else {
                windowSizes[0] = window;
            }
        }
    }


    /**
     * Returns the primary (first configured) sliding window size, or {@code null} if lifetime scope.
     *
     * @return the primary window size, or {@code null} if disabled
     */
    public Duration getWindow() {
        return windowSizes[0];
    }


    /**
     * Returns the effective statistic scope for the primary window: {@link StatisticScopeType#WINDOW}
     * when a sliding window is configured, otherwise {@link StatisticScopeType#LIFETIME}.
     *
     * @return the scope used by {@link #getPercentile(MemoryMetric, Percentile)}
     */
    public StatisticScopeType getScope() {
        if (windowSizes[0] != null) {
            return StatisticScopeType.WINDOW;
        }
        return StatisticScopeType.LIFETIME;
    }


    /**
     * Sets the age after which the latest statistics are considered stale. When exceeded, a warning
     * is logged once and {@link #isStale()} returns true. Set to 0 or negative to disable.
     * Can be changed at any time from any thread.
     *
     * @param thresholdMs the stale threshold in milliseconds, 0 or negative to disable
     */
    public void setStaleThresholdMilliseconds(long thresholdMs) {
        this.staleThresholdMs = thresholdMs;
    }


    /**
     * Returns the stale threshold in milliseconds.
     *
     * @return the stale threshold, 0 or negative if disabled
     */
    public long getStaleThresholdMilliseconds() {
        return staleThresholdMs;
    }


    /**
     * Returns the currently reported, not yet resolved problem.
     *
     * @return the problem description, or null if there is no problem
     */
    String getCurrentProblem() {
        return currentProblem;
    }


    /**
     * Performs one collection cycle: reads and processes the file, then checks the
     * staleness. Serialized with all other data processing.
     */
    void collectOnce() {
        synchronized (collectLock) {
            processIfChanged();
            checkStale();
        }
    }




    /**
     * Builds the quantile array from the {@link Percentile} enum ordinals.
     *
     * @return the quantile values in enum ordinal order
     */
    private static double[] buildQuantiles() {
        Percentile[] percentiles = Percentile.values();
        double[] result = new double[percentiles.length];
        for (int i = 0; i < percentiles.length; i++) {
            result[i] = percentiles[i].getQuantile();
        }
        return result;
    }


    /**
     * Fixes the configuration and creates the sliding window. Must be called with the collect lock held.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }

        slidingWindows = new SlidingWindow[windowSizes.length];
        for (int i = 0; i < windowSizes.length; i++) {
            if (windowSizes[i] != null) {
                long length = windowSizes[i].toMillis();
                // at most one reading per poll is recorded, so the capacity bounds the window completely
                slidingWindows[i] = new SlidingWindow(length, (int) (length / POLL_INTERVAL_MS) + 1, METRIC_COUNT);
            }
        }
        initialized = true;
        LOG.info("MemoryInformationMonitor configuration: windows {}, stale threshold {} ms.", Arrays.toString(windowSizes), staleThresholdMs);
    }


    /**
     * Throws an exception if the configuration is already fixed.
     *
     * @throws IllegalStateException if the monitor was already started or has recorded data
     */
    private void checkNotInitialized() {
        if (initialized) {
            throw new IllegalStateException("MemoryInformationMonitor is already started, the window setting can not be changed.");
        }
    }


    /**
     * The collector loop that runs on the internal daemon thread. Runtime exceptions of a single
     * cycle are logged and the loop continues. When the thread terminates while it is still the
     * active collector (e.g. due to an {@link Error}), the monitor is marked as stopped so that it
     * can be restarted.
     */
    private void collectLoop() {
        Thread current = Thread.currentThread();

        try {
            while (isActiveCollector(current)) {
                try {
                    collectOnce();
                    callReporterIfDue();
                } catch (RuntimeException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("MemoryInformationMonitor collection cycle failed: " + e.getMessage(), e);
                    }
                    synchronized (collectLock) {
                        reportProblem(PROBLEM_UNEXPECTED, e.getClass().getName());
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            onCollectorExit(current);
        }
    }


    /**
     * Checks whether the given thread is the currently active collector thread.
     *
     * @param thread the thread to check
     * @return true if the monitor is running and the thread is the active collector
     */
    private boolean isActiveCollector(Thread thread) {
        return running && collectorThread == thread;
    }


    /**
     * Calls the reporter if one is configured and the report interval has elapsed.
     */
    private void callReporterIfDue() {
        if (reporter == null || reportIntervalMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReportMs >= reportIntervalMs) {
            lastReportMs = now;
            MemoryInformationStatistics[] snapshots = latestStatisticsPerWindow;
            MemoryInformationStatistics primary = null;
            if (snapshots != null) {
                primary = snapshots[0];
            }
            reporter.report(primary);
        }
    }


    /**
     * Marks the monitor as stopped if the exiting thread is still the active collector,
     * i.e. the thread terminated without {@link #stop()} being called.
     *
     * @param thread the exiting collector thread
     */
    private synchronized void onCollectorExit(Thread thread) {
        if (collectorThread == thread) {
            collectorThread = null;
            running = false;
            LOG.error("MemoryInformationMonitor collector thread terminated unexpectedly, the monitor is stopped.");
        }
    }


    /**
     * Reads and parses the file via the configured parser, records the reading when new
     * valid data is found, and manages the problem-reporting state.
     */
    private void processIfChanged() {
        ensureInitialized();
        MemoryInformation reading = reader.process();
        if (reading == null) {
            return;
        }
        if (!reading.isSuccess()) {
            reportProblem(reading.getProblem(), reading.getProblemDetail());
            return;
        }
        record(reading.getTimestamp(), reading.getValues());
        clearProblem();
    }


    /**
     * Records the values of a validated reading and publishes a new immutable snapshot.
     * Lifetime statistics of a metric are kept once recorded; when a reading lacks the metric,
     * the last value of the most recent reading containing it is reported.
     *
     * @param readingTimestamp the timestamp of the reading (from the file or the JVM clock)
     * @param values the values per metric ({@code SlidingWindow.ABSENT} if not available)
     */
    private void record(LocalDateTime readingTimestamp, long[] values) {
        long now = System.currentTimeMillis();
        final long readingEpochMs = readingTimestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // Determine whether any window uses lifetime scope (needs histogram)
        boolean needsHistogram = false;
        for (Duration ws : windowSizes) {
            if (ws == null) {
                needsHistogram = true;
                break;
            }
        }

        // Update shared lifetime accumulators (counters, histograms, lastValues)
        for (int col = 0; col < METRIC_COUNT; col++) {
            if (values[col] != SlidingWindow.ABSENT) {
                if (counters[col] == null) {
                    counters[col] = new StatisticCounter();
                    if (needsHistogram) {
                        histograms[col] = new PercentileHistogram();
                    }
                }
                counters[col].add(values[col]);
                if (histograms[col] != null) {
                    histograms[col].add(values[col]);
                }
                lastValues[col] = values[col];
            }
        }

        // Feed all sliding windows from the same reading
        for (SlidingWindow sw : slidingWindows) {
            if (sw != null) {
                sw.add(now, values);
            }
        }

        // Build one statistics snapshot per window and publish atomically
        readingCount++;
        MemoryInformationStatistics[] snapshots = new MemoryInformationStatistics[windowSizes.length];
        for (int i = 0; i < windowSizes.length; i++) {
            Map<MemoryMetric, MemoryMetricStatistics> map = new EnumMap<>(MemoryMetric.class);
            if (slidingWindows[i] != null) {
                for (MemoryMetric metric : METRICS) {
                    MemoryMetricStatistics ms = createWindowStats(metric, slidingWindows[i]);
                    if (ms != null) {
                        map.put(metric, ms);
                    }
                }
            } else {
                for (MemoryMetric metric : METRICS) {
                    if (counters[metric.ordinal()] != null) {
                        map.put(metric, createLifetimeStats(metric));
                    }
                }
            }
            snapshots[i] = new MemoryInformationStatistics(readingEpochMs, readingCount, now, windowSizes[i], map);
        }
        latestStatisticsPerWindow = snapshots;

        if (staleWarned) {
            staleWarned = false;
            LOG.info("MemoryInformationMonitor receives new memory information readings again.");
        }

        if (LOG.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Memory Reading [%s] ===", Instant.ofEpochMilli(snapshots[0].getTimestamp())));
            for (MemoryMetric metric : METRICS) {
                long value = values[metric.ordinal()];
                if (value != SlidingWindow.ABSENT) {
                    sb.append(String.format("%n  %-13s: %d bytes (%s)", metric.getColumnName(), value, ByteUtil.getInstance().prettyPrintByteSize(value)));
                }
            }
            LOG.debug("{}", sb);
        }
    }


    /**
     * Creates the lifetime statistics of a metric.
     *
     * @param metric the metric
     * @return the statistics
     */
    private MemoryMetricStatistics createLifetimeStats(MemoryMetric metric) {
        int col = metric.ordinal();
        StatisticCounter counter = counters[col];
        long[] percentiles = null;
        if (histograms[col] != null) {
            percentiles = histograms[col].getValuesAtQuantiles(QUANTILES);
        }

        return new MemoryMetricStatistics(metric, StatisticScopeType.LIFETIME, counter.getCounter(), lastValues[col],
                                         (long) counter.getAverage(), (long) counter.getMinValue(), (long) counter.getMaxValue(),
                                         (long) counter.getStandardDeviation(), percentiles);
    }


    /**
     * Creates the sliding window statistics of a metric with exact percentiles.
     *
     * @param metric the metric
     * @param sw the sliding window
     * @return the statistics, or null if the metric has no value in the window
     */
    private MemoryMetricStatistics createWindowStats(MemoryMetric metric, SlidingWindow sw) {
        long[] sorted = sw.getSortedValues(metric.ordinal());
        if (sorted.length == 0) {
            return null;
        }

        StatisticCounter counter = new StatisticCounter();
        for (long value : sorted) {
            counter.add(value);
        }

        long[] percentiles = new long[QUANTILES.length];
        for (int i = 0; i < QUANTILES.length; i++) {
            percentiles[i] = SlidingWindow.valueAtQuantile(sorted, QUANTILES[i]);
        }

        return new MemoryMetricStatistics(metric, StatisticScopeType.WINDOW, sorted.length, sw.getLastValue(metric.ordinal()),
                                          (long) counter.getAverage(), sorted[0], sorted[sorted.length - 1],
                                          (long) counter.getStandardDeviation(), percentiles);
    }


    /**
     * Logs a warning once when no new reading was recorded within the stale threshold.
     */
    private void checkStale() {
        MemoryInformationStatistics[] snapshots = latestStatisticsPerWindow;
        MemoryInformationStatistics stats = null;
        if (snapshots != null) {
            stats = snapshots[0];
        }
        long threshold = staleThresholdMs;
        if (stats == null || threshold <= 0 || staleWarned) {
            return;
        }

        long age = System.currentTimeMillis() - stats.getSnapshotTimestamp();
        if (age > threshold) {
            staleWarned = true;
            LOG.warn("MemoryInformationMonitor: no new memory information reading for {} ms, the statistics are stale.", age);
        }
    }


    /**
     * Logs a problem once; repeated occurrences are suppressed until {@link #clearProblem()} is called
     * or a different problem occurs. A missing file is logged at information level since it is expected
     * during startup. The file content is never logged.
     *
     * @param problem the problem description
     * @param detail additional detail without file content
     */
    private void reportProblem(String problem, String detail) {
        if (problem.equals(currentProblem)) {
            return;
        }

        currentProblem = problem;
        if (reporter != null) {
            reporter.reportProblem(problem, detail);
        } else if (PROBLEM_FILE_MISSING.equals(problem)) {
            LOG.info("MemoryInformationMonitor: {} ({}), waiting for it.", problem, detail);
        } else {
            LOG.warn("MemoryInformationMonitor: {} ({}), further occurrences are suppressed until recovery.", problem, detail);
        }
    }


    /**
     * Clears the currently reported problem after a successful reading.
     */
    private void clearProblem() {
        String problem = currentProblem;
        if (problem != null) {
            currentProblem = null;
            LOG.info("MemoryInformationMonitor: reading memory information data again, resolved: {}.", problem);
        }
    }
}
