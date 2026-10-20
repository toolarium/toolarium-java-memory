/*
 * LogMemoryInformationReporterImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.report.impl;

import com.github.toolarium.common.util.ByteUtil;
import com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetricStatistics;
import com.github.toolarium.java.memory.monitor.impl.MemoryInformationMonitorConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * An {@link com.github.toolarium.java.memory.monitor.report.IMemoryInformationReporter} that writes
 * the formatted memory statistics to the SLF4J logger at INFO level and problems at WARN/INFO level.
 *
 * <p>Obtain an instance via
 * {@link com.github.toolarium.java.memory.monitor.report.MemoryInformationReporterFactory#newLogReporter()}.</p>
 *
 * @author patrick
 */
public class LogMemoryInformationReporterImpl extends AbstractMemoryInformationReporter implements MemoryInformationMonitorConstants {

    private static final Logger LOG = LoggerFactory.getLogger(LogMemoryInformationReporterImpl.class);
    private static final String VALUE_SEPARATOR = "|";


    /**
     * Logs that memory reporting started at INFO level.
     *
     * @param interval the interval in milliseconds between report calls
     */
    @Override
    public void start(long interval) {
        LOG.info("Start memory reporting, interval: {} ms.", interval);
    }


    /**
     * Logs the formatted memory statistics snapshot at INFO level.
     * When debug is enabled, also logs heap and non-heap details.
     *
     * @param statistics the statistics snapshot to report; may be {@code null}
     */
    @Override
    public void report(MemoryInformationStatistics statistics) {
        if (LOG.isDebugEnabled()) {
            MemoryMetricStatistics heapStats = null;
            MemoryMetricStatistics nonHeapStats = null;
            if (statistics != null) {
                heapStats = statistics.get(MemoryMetric.HEAP);
                nonHeapStats = statistics.get(MemoryMetric.NON_HEAP);
            }
            if (heapStats != null && nonHeapStats != null) {
                LOG.debug("Memory: "
                        + "heap ["
                        + ByteUtil.getInstance().prettyPrintByteSize(heapStats.getMin()) + VALUE_SEPARATOR
                        + ByteUtil.getInstance().prettyPrintByteSize(heapStats.getAvg()) + VALUE_SEPARATOR
                        + ByteUtil.getInstance().prettyPrintByteSize(heapStats.getLast()) + VALUE_SEPARATOR
                        + ByteUtil.getInstance().prettyPrintByteSize(heapStats.getMax())
                        + "]"
                        + ", "
                        + "non heap ["
                        + ByteUtil.getInstance().prettyPrintByteSize(nonHeapStats.getMin()) + VALUE_SEPARATOR
                        + ByteUtil.getInstance().prettyPrintByteSize(nonHeapStats.getAvg()) + VALUE_SEPARATOR
                        + ByteUtil.getInstance().prettyPrintByteSize(nonHeapStats.getLast()) + VALUE_SEPARATOR
                        + ByteUtil.getInstance().prettyPrintByteSize(nonHeapStats.getMax())
                        + "]"
                        + " - [min" + VALUE_SEPARATOR + "avg" + VALUE_SEPARATOR + "last" + VALUE_SEPARATOR + "max]");
            }
        }
        LOG.info("{}", formatStatistics(statistics));
    }


    /**
     * Logs a monitoring problem. A missing file is logged at INFO level; all other problems at WARN level.
     *
     * @param problem the problem key
     * @param detail additional detail about the problem
     */
    @Override
    public void reportProblem(String problem, String detail) {
        if (PROBLEM_FILE_MISSING.equals(problem)) {
            LOG.info("MemoryInformationMonitor: {} ({}), waiting for it.", problem, detail);
        } else {
            LOG.warn("MemoryInformationMonitor: {} ({}), further occurrences are suppressed until recovery.", problem, detail);
        }
    }


    /**
     * Logs that memory reporting ended at INFO level.
     */
    @Override
    public void end() {
        LOG.info("Ended memory reporting.");
    }
}
