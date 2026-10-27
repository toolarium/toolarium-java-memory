/*
 * MemoryMetricStatistics.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.dto;

import java.util.Arrays;
import java.util.Objects;



/**
 * Immutable statistics snapshot for a single {@link MemoryMetric} in a {@link StatisticScopeType}.
 * All byte values are in bytes.
 *
 * @author patrick
 */
public final class MemoryMetricStatistics {

    private final MemoryMetric metric;
    private final StatisticScopeType scope;
    private final long count;
    private final long last;
    private final long avg;
    private final long min;
    private final long max;
    private final long stdDev;
    private final long[] percentiles; // indexed by Percentile.ordinal(), null if not computed


    /**
     * Constructor
     *
     * @param metric the metric
     * @param scope the statistic scope
     * @param count the number of readings
     * @param last the last recorded value in bytes
     * @param avg the average in bytes
     * @param min the minimum in bytes
     * @param max the maximum in bytes
     * @param stdDev the standard deviation in bytes
     * @param percentiles the percentile values indexed by {@link Percentile#ordinal()}, or null
     */
    public MemoryMetricStatistics(MemoryMetric metric, StatisticScopeType scope, long count, long last,
                                  long avg, long min, long max, long stdDev, long[] percentiles) {
        this.metric = metric;
        this.scope = scope;
        this.count = count;
        this.last = last;
        this.avg = avg;
        this.min = min;
        this.max = max;
        this.stdDev = stdDev;
        if (percentiles != null) {
            this.percentiles = percentiles.clone();
        } else {
            this.percentiles = null;
        }
    }


    /**
     * Returns the metric.
     *
     * @return the metric
     */
    public MemoryMetric getMetric() {
        return metric;
    }


    /**
     * Returns the statistic scope.
     *
     * @return the scope
     */
    public StatisticScopeType getScope() {
        return scope;
    }


    /**
     * Returns the number of readings included in these statistics.
     *
     * @return the reading count
     */
    public long getCount() {
        return count;
    }


    /**
     * Returns the last recorded value in bytes.
     *
     * @return the last value
     */
    public long getLast() {
        return last;
    }


    /**
     * Returns the average in bytes.
     *
     * @return the average
     */
    public long getAvg() {
        return avg;
    }


    /**
     * Returns the minimum in bytes.
     *
     * @return the minimum
     */
    public long getMin() {
        return min;
    }


    /**
     * Returns the maximum in bytes.
     *
     * @return the maximum
     */
    public long getMax() {
        return max;
    }


    /**
     * Returns the standard deviation in bytes.
     *
     * @return the standard deviation
     */
    public long getStandardDeviation() {
        return stdDev;
    }


    /**
     * Returns whether percentile data is available.
     *
     * @return true if percentiles are computed
     */
    public boolean hasPercentiles() {
        return percentiles != null;
    }


    /**
     * Returns the value at a percentile.
     *
     * @param percentile the percentile
     * @return the value in bytes, or {@code null} if percentiles are not available
     */
    public Long get(Percentile percentile) {
        if (percentiles == null) {
            return null;
        }
        return percentiles[percentile.ordinal()];
    }


    /**
     * Returns true if the other object is a {@code MemoryMetricStatistics} with identical field values.
     *
     * @param o the object to compare
     * @return true if equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MemoryMetricStatistics)) {
            return false;
        }
        MemoryMetricStatistics other = (MemoryMetricStatistics) o;
        return count == other.count
                && last == other.last
                && avg == other.avg
                && min == other.min
                && max == other.max
                && stdDev == other.stdDev
                && metric == other.metric
                && scope == other.scope
                && Arrays.equals(percentiles, other.percentiles);
    }


    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(metric, scope, count, last, avg, min, max, stdDev);
        result = 31 * result + Arrays.hashCode(percentiles);
        return result;
    }


    /**
     * Returns a human-readable representation of this statistics snapshot.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "MemoryMetricStatistics{"
                + "metric=" + metric
                + ", scope=" + scope
                + ", count=" + count
                + ", last=" + last
                + ", avg=" + avg
                + ", min=" + min
                + ", max=" + max
                + ", stdDev=" + stdDev
                + ", percentiles=" + Arrays.toString(percentiles)
                + '}';
    }
}
