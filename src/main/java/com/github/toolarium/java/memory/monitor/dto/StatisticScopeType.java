/*
 * StatisticScopeType.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.dto;


/**
 * The scope of statistics collected by {@link com.github.toolarium.java.memory.monitor.IMemoryInformationMonitor}.
 *
 * @author patrick
 */
public enum StatisticScopeType {

    /** A sliding time window with exact percentiles (default 10 minutes). */
    WINDOW,

    /** All readings since the monitor was started. */
    LIFETIME
}
