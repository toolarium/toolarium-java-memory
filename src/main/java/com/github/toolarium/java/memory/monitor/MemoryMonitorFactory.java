/*
 * MemoryMonitorFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor;

import com.github.toolarium.java.memory.monitor.impl.MemoryInformationMonitorImpl;
import com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader;
import com.github.toolarium.java.memory.monitor.reader.MemoryInformationReaderFactory;
import com.github.toolarium.java.memory.monitor.report.IMemoryInformationReporter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;


/**
 * Singleton factory that owns the single {@link IMemoryInformationMonitor} instance for this JVM.
 *
 * <p>Call {@link #getMemoryInformationMonitor()} to obtain the monitor configured from environment
 * variables and system properties. Call {@link #newBuilder()} to supply explicit values before the
 * monitor is started; any value not supplied falls back to the corresponding environment variable.</p>
 *
 * <p><strong>Scope and window:</strong> the statistic scope is determined solely by
 * {@link Builder#window(Duration)}:</p>
 * <ul>
 *   <li>A non-zero {@link Duration}: {@link com.github.toolarium.java.memory.monitor.dto.StatisticScopeType#WINDOW}:
 *       statistics are computed over a rolling time window. Only readings within that window are considered.</li>
 *   <li>{@link Duration#ZERO}: {@link com.github.toolarium.java.memory.monitor.dto.StatisticScopeType#LIFETIME}:
 *       statistics accumulate over the entire lifetime of the monitor instance.</li>
 * </ul>
 *
 * <p>Each call to {@link Builder#build()} creates a brand-new monitor instance. If the previous
 * monitor used a window and the new one does not (or vice versa), the new instance starts collecting
 * from the moment {@link IMemoryInformationMonitor#start()} is called. No history is carried over.</p>
 *
 * <p>The factory registers a JVM shutdown hook that stops the monitor automatically.</p>
 *
 * @author patrick
 */
public final class MemoryMonitorFactory {

    /** Default memory information file path. */
    public static final Path DEFAULT_MEMORY_INFORMATION_FILE = Paths.get("/dev/shm/meminfo.out");

    /** Default sliding window size (10 minutes). */
    public static final Duration DEFAULT_SLIDING_WINDOW = Duration.ofMinutes(10);

    /** Maximum sliding window size (24 hours). */
    public static final Duration MAX_WINDOW = Duration.ofHours(24);

    /** Default age in milliseconds after which the latest statistics are considered stale (3× the default cb-meminfo.sh write interval of 5 s). */
    public static final long DEFAULT_STALE_THRESHOLD_MS = 15_000;

    /** Default report interval in milliseconds (60 s, standard operational monitoring cadence, 10 snapshots per default 10-minute window). */
    public static final long DEFAULT_REPORT_INTERVAL_MS = 60_000;

    private static final class HOLDER {
        static final MemoryMonitorFactory INSTANCE = new MemoryMonitorFactory();
    }

    private volatile IMemoryInformationMonitor monitor;
    private final Thread shutdownHook;


    /**
     * Private constructor, registers a shutdown hook that stops whatever monitor is current.
     */
    private MemoryMonitorFactory() {
        shutdownHook = new Thread(() -> {
            IMemoryInformationMonitor m = monitor;
            if (m != null) {
                m.stop();
            }
        }, "MemoryMonitorFactory-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }


    /**
     * Removes the JVM shutdown hook. Call this in environments with classloader recycling
     * (e.g. servlet containers) to allow the factory and its classloader to be garbage-collected.
     */
    void removeShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }
    }


    /**
     * Returns the singleton factory instance.
     *
     * @return the factory
     */
    public static MemoryMonitorFactory getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Returns the single {@link IMemoryInformationMonitor} instance, creating it from environment
     * variables and system properties on first call if no builder was used.
     *
     * @return the memory information monitor
     */
    public IMemoryInformationMonitor getMemoryInformationMonitor() {
        if (monitor == null) {
            synchronized (this) {
                if (monitor == null) {
                    monitor = new MemoryInformationMonitorImpl(DEFAULT_SLIDING_WINDOW, DEFAULT_STALE_THRESHOLD_MS, MemoryInformationReaderFactory.getInstance().newFileReader(DEFAULT_MEMORY_INFORMATION_FILE));
                }
            }
        }
        return monitor;
    }


    /**
     * Returns a new builder to configure the single monitor with explicit values.
     * Call {@link Builder#build()} before {@link #getMemoryInformationMonitor()} or
     * {@link IMemoryInformationMonitor#start()} is invoked.
     *
     * @return a new builder
     */
    public Builder newBuilder() {
        return new Builder();
    }


    /**
     * Builder for configuring the single {@link IMemoryInformationMonitor}.
     * Any value not set here falls back to the corresponding environment variable.
     * Calling {@link #build()} while the monitor is already running throws {@link IllegalStateException}.
     */
    public static final class Builder {

        private Path memoryInformationFile = DEFAULT_MEMORY_INFORMATION_FILE;
        private Duration[] slidingWindows = new Duration[]{DEFAULT_SLIDING_WINDOW};
        private long staleThresholdMilliseconds = DEFAULT_STALE_THRESHOLD_MS;
        private IMemoryInformationReader reader = null;
        private IMemoryInformationReporter reporter = null;
        private Duration reportInterval = null;


        /**
         * Private constructor. Use {@link MemoryMonitorFactory#newBuilder()}.
         */
        private Builder() {
        }


        /**
         * Sets the memory information file to monitor.
         *
         * @param file the file path
         * @return this builder
         */
        public Builder memoryInformationFile(Path file) {
            this.memoryInformationFile = file;
            return this;
        }


        /**
         * Sets the sliding window size and implicitly defines the statistic scope.
         *
         * <ul>
         *   <li>A non-zero {@link Duration} enables {@link com.github.toolarium.java.memory.monitor.dto.StatisticScopeType#WINDOW}
         *       scope: only readings within the most recent window are included in statistics.</li>
         *   <li>{@link Duration#ZERO} disables the window and activates
         *       {@link com.github.toolarium.java.memory.monitor.dto.StatisticScopeType#LIFETIME} scope:
         *       all readings since the monitor was started are accumulated.</li>
         * </ul>
         *
         * <p><strong>Note:</strong> each {@link #build()} produces a fresh monitor instance with no prior
         * history. If you switch from a windowed to a non-windowed configuration (or vice versa), the new
         * monitor starts collecting from the moment {@link IMemoryInformationMonitor#start()} is called.</p>
         *
         * @param slidingWindow the sliding window size ({@link Duration#ZERO} selects lifetime scope, max {@link #MAX_WINDOW})
         * @return this builder
         */
        public Builder window(Duration slidingWindow) {
            this.slidingWindows = new Duration[]{slidingWindow};
            return this;
        }


        /**
         * Configures multiple concurrent sliding windows tracked from the same file reading.
         * Each window produces an independent {@link com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics}
         * snapshot accessible via {@link IMemoryInformationMonitor#getLatestStatistics(java.time.Duration)}.
         * The first entry is the primary window returned by {@link IMemoryInformationMonitor#getLatestStatistics()}.
         * A {@code null} entry selects lifetime scope for that slot.
         *
         * @param windows one or more window durations; must not be empty
         * @return this builder
         * @throws IllegalArgumentException if windows is null or empty
         */
        public Builder windows(Duration... windows) {
            if (windows == null || windows.length == 0) {
                throw new IllegalArgumentException("At least one window duration must be specified.");
            }
            this.slidingWindows = windows.clone();
            return this;
        }


        /**
         * Sets the stale threshold. 0 or negative disables stale detection.
         *
         * @param staleThresholdMilliseconds the stale threshold in milliseconds
         * @return this builder
         */
        public Builder staleThresholdMilliseconds(long staleThresholdMilliseconds) {
            this.staleThresholdMilliseconds = staleThresholdMilliseconds;
            return this;
        }


        /**
         * Selects the default file-based reader (see {@link MemoryInformationReaderFactory#newFileReader(Path)}).
         * This is the default; the file path is taken from {@link #memoryInformationFile(Path)}.
         * Calling this method resets any previously configured JVM or custom reader.
         *
         * @return this builder
         */
        public Builder fileReader() {
            this.reader = null;
            return this;
        }


        /**
         * Selects the JVM reader (see {@link MemoryInformationReaderFactory#newJvmReader()}).
         * Use this when no external memory information file is available; metrics are read
         * directly from {@link java.lang.management.MemoryMXBean}.
         * When this is set, {@link #memoryInformationFile(Path)} is ignored.
         *
         * @return this builder
         */
        public Builder jvmReader() {
            this.reader = MemoryInformationReaderFactory.getInstance().newJvmReader();
            return this;
        }


        /**
         * Sets a custom reader. Use this to support additional data sources.
         * When set, {@link #memoryInformationFile(Path)} is ignored.
         *
         * @param reader the reader to use; must not be {@code null}
         * @return this builder
         */
        public Builder reader(IMemoryInformationReader reader) {
            this.reader = reader;
            return this;
        }


        /**
         * Sets the reporter that is called periodically in the background while the monitor is running.
         * Use {@link com.github.toolarium.java.memory.monitor.report.MemoryInformationReporterFactory}
         * to obtain a ready-made implementation such as the log reporter.
         *
         * @param reporter the reporter to use; {@code null} disables reporting
         * @return this builder
         */
        public Builder reporter(IMemoryInformationReporter reporter) {
            this.reporter = reporter;
            return this;
        }


        /**
         * Sets the interval at which the reporter is called automatically in the background.
         * Has no effect when no reporter is configured.
         *
         * <p>60 seconds is a good default: it matches the standard operational monitoring cadence and
         * produces 10 snapshots per default 10-minute window, enough to show trends without flooding the log.</p>
         *
         * @param interval the report interval; {@code null} or {@link Duration#ZERO} disables background reporting
         * @return this builder
         */
        public Builder reportInterval(Duration interval) {
            this.reportInterval = interval;
            return this;
        }


        /**
         * Configures and returns the factory's single {@link IMemoryInformationMonitor}.
         *
         * @return the monitor
         * @throws IllegalStateException if the monitor is already running
         */
        public IMemoryInformationMonitor build() {
            MemoryMonitorFactory factory = MemoryMonitorFactory.getInstance();
            synchronized (factory) {
                IMemoryInformationMonitor existing = factory.monitor;
                if (existing != null && existing.isRunning()) {
                    throw new IllegalStateException(
                            "The monitor is already running; stop it before reconfiguring.");
                }
                IMemoryInformationReader effectiveReader = reader;
                if (effectiveReader == null) {
                    effectiveReader = MemoryInformationReaderFactory.getInstance().newFileReader(memoryInformationFile);
                }
                long reportIntervalMs = 0L;
                if (reportInterval != null && !reportInterval.isZero()) {
                    reportIntervalMs = reportInterval.toMillis();
                }
                factory.monitor = new MemoryInformationMonitorImpl(slidingWindows, staleThresholdMilliseconds, effectiveReader, reporter, reportIntervalMs);
                return factory.monitor;
            }
        }
    }
}
