/*
 * MemoryMetric.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.dto;


/**
 * Memory metrics tracked by {@link com.github.toolarium.java.memory.monitor.IMemoryInformationMonitor}.
 *
 * <p>Ordinal order is used to index the per-metric arrays inside the monitor, so values must not be
 * reordered without migrating the arrays.</p>
 *
 * @author patrick
 */
public enum MemoryMetric {

    /** Total OS memory. */
    TOTAL("Total", false),

    /** Used OS memory. */
    USED("Used", false),

    /** Free OS memory. */
    FREE("Free", false),

    /** Process RSS (resident set size). */
    PROCESS("Process", false),

    /** JVM total memory (heap + non-heap + code). */
    JVM_TOTAL("JVM-Total", true),

    /**
     * JVM committed heap memory (bytes reserved from the OS). Matches the {@code Heap} column written by {@code cb-meminfo.sh} via NMT. With G1 (Java 12+, JEP 346) this shrinks after GC.
     */
    HEAP("Heap", true),

    /**
     * JVM committed non-heap memory (bytes reserved from the OS for class metadata). Matches the {@code Non-Heap} column written by {@code cb-meminfo.sh} via NMT.
     */
    NON_HEAP("Non-Heap", true),

    /**
     * JVM used heap memory (bytes actually holding live objects). Available from {@code JvmMemoryReaderImpl} and from file (cb-meminfo.sh v1.8.0+ with {@code -pid} flag).
     */
    HEAP_USED("Heap-Used", true),

    /**
     * JVM used non-heap memory (bytes actually used for class metadata, interned strings, etc.). Available from {@code JvmMemoryReaderImpl} and from file (cb-meminfo.sh v1.8.0+ with {@code -pid} flag).
     */
    NON_HEAP_USED("Non-Heap-Used", true),

    /**
     * JVM thread memory.
     */
    THREAD("Thread", true),

    /**
     * JVM GC memory.
     */
    GC("GC", true),

    /**
     * JVM JIT code cache. Column name {@code "Code"} is the legacy script name; {@code "JIT"} is the current name.
     */
    JIT("JIT", true, "Code");

    private final String columnName;
    private final boolean jvm;
    private final String[] aliases;


    /**
     * Constructor without aliases.
     *
     * @param columnName the column name in the meminfo file
     * @param jvm true if this is a JVM metric (present only in header-based format)
     */
    MemoryMetric(String columnName, boolean jvm) {
        this.columnName = columnName;
        this.jvm = jvm;
        this.aliases = new String[0];
    }


    /**
     * Constructor with aliases.
     *
     * @param columnName the column name in the meminfo file
     * @param jvm true if this is a JVM metric (present only in header-based format)
     * @param aliases additional column names accepted as equivalent
     */
    MemoryMetric(String columnName, boolean jvm, String... aliases) {
        this.columnName = columnName;
        this.jvm = jvm;
        this.aliases = aliases;
    }


    /**
     * Returns the column name used in the meminfo file header.
     *
     * @return the column name
     */
    public String getColumnName() {
        return columnName;
    }


    /**
     * Returns whether this is a JVM metric, i.e. only available in the header-based format.
     *
     * @return true if JVM metric
     */
    public boolean isJvm() {
        return jvm;
    }


    /**
     * Returns whether the given name matches this metric's column name or any of its aliases.
     *
     * @param name the column name to test
     * @return true if the name matches
     */
    public boolean matchesColumnName(String name) {
        if (columnName.equals(name)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
