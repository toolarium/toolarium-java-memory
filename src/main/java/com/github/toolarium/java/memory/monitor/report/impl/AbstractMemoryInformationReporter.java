/*
 * AbstractMemoryInformationReporter.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.report.impl;

import com.github.toolarium.common.util.ByteUtil;
import com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetricStatistics;
import com.github.toolarium.java.memory.monitor.dto.Percentile;
import com.github.toolarium.java.memory.monitor.impl.MemoryInformationMonitorConstants;
import com.github.toolarium.java.memory.monitor.report.IMemoryInformationReporter;
import java.time.Instant;


/**
 * Base class for {@link IMemoryInformationReporter} implementations.
 *
 * <p>Provides {@link #formatStatistics(MemoryInformationStatistics)} which produces a
 * human-readable summary of a statistics snapshot. Subclasses implement
 * {@link #report(MemoryInformationStatistics)} and decide what to do with that string
 * (log it, write it to a file, send it over the network, etc.).</p>
 *
 * <p>The default implementations of {@link #start(long)}, {@link #reportProblem(String, String)}
 * and {@link #end()} are no-ops; override them to react to lifecycle and problem events.</p>
 *
 * @author patrick
 */
public abstract class AbstractMemoryInformationReporter implements IMemoryInformationReporter, MemoryInformationMonitorConstants {

    /**
     * Called when the monitor starts the reporter. Default: no-op.
     *
     * @param interval the interval in milliseconds between {@link #report(MemoryInformationStatistics)} calls
     */
    @Override
    public void start(long interval) {
        // default: no-op
    }


    /**
     * Called when a problem is detected during monitoring. Default: no-op.
     *
     * @param problem the problem key
     * @param detail additional detail about the problem
     */
    @Override
    public void reportProblem(String problem, String detail) {
        // default: no-op
    }


    /**
     * Called when the monitor stops the reporter. Default: no-op.
     */
    @Override
    public void end() {
        // default: no-op
    }


    /**
     * Formats a memory statistics snapshot into a human-readable string.
     *
     * <p>Returns a fixed "no data collected yet" message when {@code statistics} is {@code null}.</p>
     *
     * @param statistics the statistics snapshot to format; may be {@code null}
     * @return the formatted statistics summary
     */
    protected String formatStatistics(MemoryInformationStatistics statistics) {
        if (statistics == null) {
            return "=== Memory Information Statistics: no data collected yet ===";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Memory Information Statistics (%d readings, last reading: %s, age: %d ms) ===%n  Last          —",
                statistics.getTotalNumber(), Instant.ofEpochMilli(statistics.getTimestamp()),
                System.currentTimeMillis() - statistics.getSnapshotTimestamp()));
        boolean hasOsMetrics = false;
        for (MemoryMetric metric : statistics.getMetrics()) {
            if (!metric.isJvm()) {
                hasOsMetrics = true;
                break;
            }
        }
        for (MemoryMetric metric : statistics.getMetrics()) {
            if (!hasOsMetrics || !metric.isJvm()) {
                appendByteSize(sb, metric.getColumnName(), statistics.get(metric).getLast());
            }
        }
        appendStatistic(sb, statistics);
        return sb.toString();
    }


    /**
     * Appends the statistic lines of the window or lifetime scope.
     *
     * @param sb the string builder to append to
     * @param stats the statistics snapshot
     */
    protected void appendStatistic(StringBuilder sb, MemoryInformationStatistics stats) {
        if (stats.getWindowSize() != null) {
            sb.append(String.format("%n--- Window (last %d s) ---", stats.getWindowSize().toSeconds()));
        } else {
            sb.append(String.format("%n--- Lifetime (%d readings) ---", stats.getTotalNumber()));
        }
        for (MemoryMetric metric : stats.getMetrics()) {
            sb.append(String.format("%n  %-13s —", metric.getColumnName()));
            appendMetric(sb, stats.get(metric));
        }
    }


    /**
     * Appends the formatted statistics of a single metric.
     *
     * @param sb the string builder to append to
     * @param ms the metric statistics
     */
    protected void appendMetric(StringBuilder sb, MemoryMetricStatistics ms) {
        if (!ms.hasPercentiles()) {
            appendByteSize(sb, LABEL_AVG, ms.getAvg());
        } else {
            long p95 = ms.get(Percentile.P95);
            long median = ms.get(Percentile.MEDIAN);
            appendByteSize(sb, Percentile.P95.getLabel(), p95);
            appendByteSize(sb, Percentile.MEDIAN.getLabel(), median);
            appendByteSize(sb, LABEL_SPREAD, p95 - median);
        }
        appendByteSize(sb, LABEL_MIN, ms.getMin());
        appendByteSize(sb, LABEL_MAX, ms.getMax());
    }


    /**
     * Appends a labeled value as a human-readable byte size, preceded by a comma unless immediately after {@code —}.
     *
     * @param sb the string builder to append to
     * @param label the label
     * @param bytes the value in bytes
     */
    protected void appendByteSize(StringBuilder sb, String label, long bytes) {
        if (sb.charAt(sb.length() - 1) != '—') {
            sb.append(',');
        }
        sb.append(' ').append(label).append(": ").append(ByteUtil.getInstance().prettyPrintByteSize(bytes));
    }
}
