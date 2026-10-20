/*
 * MemoryInformationReaderFactory.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader;

import com.github.toolarium.java.memory.monitor.reader.impl.JvmMemoryReaderImpl;
import com.github.toolarium.java.memory.monitor.reader.impl.MemoryInformationFileReaderImpl;
import java.nio.file.Path;


/**
 * Singleton factory for creating {@link IMemoryInformationReader} instances.
 *
 * <p>Use {@link #newFileReader(Path)} to read memory information from a file and
 * {@link #newJvmReader()} to read directly from the JVM via
 * {@link java.lang.management.MemoryMXBean}.</p>
 *
 * @author patrick
 */
public final class MemoryInformationReaderFactory {

    private static final class HOLDER {
        static final MemoryInformationReaderFactory INSTANCE = new MemoryInformationReaderFactory();
    }


    /**
     * Private constructor.
     */
    private MemoryInformationReaderFactory() {
    }


    /**
     * Returns the singleton factory instance.
     *
     * @return the factory
     */
    public static MemoryInformationReaderFactory getInstance() {
        return HOLDER.INSTANCE;
    }


    /**
     * Creates a new file-based reader that reads and parses the given memory information file.
     *
     * @param file the memory information file path
     * @return a new {@link IMemoryInformationReader} backed by the file
     */
    public IMemoryInformationReader newFileReader(Path file) {
        return new MemoryInformationFileReaderImpl(file);
    }


    /**
     * Creates a new JVM reader that reads heap and non-heap memory metrics directly
     * from {@link java.lang.management.MemoryMXBean}.
     *
     * @return a new {@link IMemoryInformationReader} backed by the JVM
     */
    public IMemoryInformationReader newJvmReader() {
        return new JvmMemoryReaderImpl();
    }
}
