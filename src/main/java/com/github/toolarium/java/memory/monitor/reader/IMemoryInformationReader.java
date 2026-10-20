/*
 * IMemoryInformationReader.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader;

import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;


/**
 * Reads and produces a {@link MemoryInformation} reading from any data source.
 *
 * <p>Implementations are stateful: they remember the last seen reading to detect unchanged
 * content and may cache derived information such as resolved column mappings. File-based
 * implementations receive the file path in their constructor.</p>
 *
 * <p>To support a custom data source, implement this interface and supply the implementation
 * via {@link com.github.toolarium.java.memory.monitor.MemoryMonitorFactory.Builder#reader(IMemoryInformationReader) Builder.reader(IMemoryInformationReader)}.</p>
 *
 * @author patrick
 */
public interface IMemoryInformationReader {

    /**
     * Reads and produces a memory information reading.
     *
     * <p>Returns {@code null} when the data source is unchanged (e.g. same {@code lastModifiedTime}
     * on a file, or the thread was interrupted). Returns a
     * {@link MemoryInformation#isSuccess() failing} reading when the source is missing, unreadable,
     * or contains invalid data. Returns a successful reading when new valid data is found.</p>
     *
     * @return the reading, or {@code null} if the data is unchanged
     */
    MemoryInformation process();


    /**
     * Resets the internal state (last seen timestamp, last modified time, cached mappings).
     * Call this when the monitor is reconfigured or a new data source is being monitored.
     */
    void reset();
}
