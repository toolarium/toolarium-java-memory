/*
 * IMemoryInformationReporter.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.report;

import com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics;


/**
 * Reports memory statistics and problem events from a monitor.
 *
 * <p>The monitor calls {@link #start(long)} when reporting begins, {@link #report(MemoryInformationStatistics)}
 * periodically at the configured interval, {@link #reportProblem(String, String)} when a problem
 * is detected, and {@link #end()} when the monitor stops.
 * Implementations may also be invoked on demand at any time.</p>
 *
 * <p>Obtain a ready-made implementation via
 * {@link MemoryInformationReporterFactory}.</p>
 *
 * @author patrick
 */
public interface IMemoryInformationReporter {

    /**
     * Starts the memory report with the given interval.
     *
     * @param interval the interval in milliseconds between {@link #report(MemoryInformationStatistics)} calls
     */
    void start(long interval);


    /**
     * Reports a memory statistics snapshot.
     *
     * @param statistics the statistics snapshot to report; may be {@code null} when no data has been collected yet
     */
    void report(MemoryInformationStatistics statistics);


    /**
     * Reports a problem detected during monitoring. Called once per distinct problem key;
     * subsequent occurrences of the same problem are suppressed until it is cleared.
     *
     * @param problem the problem key
     * @param detail additional detail about the problem
     */
    void reportProblem(String problem, String detail);


    /**
     * Ends the memory report.
     */
    void end();
}
