/*
 * Percentile.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.dto;


/**
 * Percentiles supported by {@link com.github.toolarium.java.memory.monitor.IMemoryInformationMonitor} statistics. The ordinal order matches
 * the {@code double[]} returned by {@link com.github.toolarium.common.statistic.PercentileHistogram#getValuesAtQuantiles}.
 *
 * @author patrick
 */
public enum Percentile {

    /** 50th percentile (median). */
    MEDIAN(0.50, "Median"),

    /** 75th percentile. */
    P75(0.75, "P75"),

    /** 90th percentile. */
    P90(0.90, "P90"),

    /** 95th percentile. */
    P95(0.95, "P95"),

    /** 99th percentile. */
    P99(0.99, "P99"),

    /** 99.9th percentile. */
    P999(0.999, "P999");

    private final double quantile;
    private final String label;


    /**
     * Constructor
     *
     * @param quantile the quantile value (0.0 to 1.0)
     * @param label the display label
     */
    Percentile(double quantile, String label) {
        this.quantile = quantile;
        this.label = label;
    }


    /**
     * Returns the quantile value.
     *
     * @return the quantile (0.0 to 1.0)
     */
    public double getQuantile() {
        return quantile;
    }


    /**
     * Returns the display label.
     *
     * @return the label
     */
    public String getLabel() {
        return label;
    }
}
