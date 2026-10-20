/*
 * MemoryInformationReporterFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.report;

import com.github.toolarium.java.memory.monitor.report.impl.LogMemoryInformationReporterImpl;


/**
 * Singleton factory for creating {@link IMemoryInformationReporter} instances.
 *
 * <p>Use {@link #newLogReporter()} to obtain a reporter that writes the formatted
 * statistics to the SLF4J logger of
 * {@link com.github.toolarium.java.memory.monitor.report.impl.LogMemoryInformationReporterImpl}
 * at INFO level.</p>
 *
 * @author patrick
 */
public final class MemoryInformationReporterFactory {

    private static final class HOLDER {
        static final MemoryInformationReporterFactory INSTANCE = new MemoryInformationReporterFactory();
    }


    /**
     * Private constructor.
     */
    private MemoryInformationReporterFactory() {
    }


    /**
     * Returns the singleton factory instance.
     *
     * @return the factory
     */
    public static MemoryInformationReporterFactory getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Creates a new reporter that writes the formatted statistics to the SLF4J logger at INFO level.
     *
     * @return a new {@link IMemoryInformationReporter} backed by the logger
     */
    public IMemoryInformationReporter newLogReporter() {
        return new LogMemoryInformationReporterImpl();
    }
}
