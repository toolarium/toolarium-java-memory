/*
 * MemoryInformation.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader.dto;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;


/**
 * The result of one reading produced by an {@link com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader}.
 *
 * <p>Create instances via {@link #success(LocalDateTime, long[])} or {@link #failure(String, String)}.
 * A {@code null} return from
 * {@link com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader#process()}
 * means the content is unchanged; a failure instance means the source was missing,
 * unreadable, or contained invalid data.</p>
 *
 * @author patrick
 */
public final class MemoryInformation {
    private final LocalDateTime timestamp;
    private final long[] values;
    private final String problem;
    private final String problemDetail;


    /**
     * Private constructor — use {@link #success(LocalDateTime, long[])} or {@link #failure(String, String)}.
     *
     * @param timestamp the timestamp of this reading, or {@code null} for failure instances
     * @param values the metric values, or {@code null} for failure instances
     * @param problem the problem key, or {@code null} for success instances
     * @param problemDetail the problem detail, or {@code null} for success instances
     */
    private MemoryInformation(LocalDateTime timestamp, long[] values, String problem, String problemDetail) {
        this.timestamp = timestamp;
        this.values = values;
        this.problem = problem;
        this.problemDetail = problemDetail;
    }


    /**
     * Creates a successful reading.
     *
     * @param timestamp the timestamp of this reading
     * @param values the values per metric indexed by {@link com.github.toolarium.java.memory.monitor.dto.MemoryMetric#ordinal()}
     * @return the successful reading
     */
    public static MemoryInformation success(LocalDateTime timestamp, long[] values) {
        return new MemoryInformation(timestamp, values, null, null);
    }


    /**
     * Creates a failed reading.
     *
     * @param problem the problem description
     * @param problemDetail additional detail for logging (must not contain source content)
     * @return the failed reading
     */
    public static MemoryInformation failure(String problem, String problemDetail) {
        return new MemoryInformation(null, null, problem, problemDetail);
    }


    /**
     * Returns whether this reading was produced successfully.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return problem == null;
    }


    /**
     * Returns the timestamp of this reading.
     *
     * @return the timestamp, or {@code null} if this is a failed reading
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }


    /**
     * Returns the parsed values per metric, indexed by
     * {@link com.github.toolarium.java.memory.monitor.dto.MemoryMetric#ordinal()}.
     * Metrics not present carry the sentinel value
     * {@link com.github.toolarium.java.memory.monitor.impl.SlidingWindow#ABSENT}.
     *
     * @return the values array, or {@code null} if this is a failed reading
     */
    public long[] getValues() {
        return values;
    }


    /**
     * Returns the problem description.
     *
     * @return the problem description, or {@code null} if this is a successful reading
     */
    public String getProblem() {
        return problem;
    }


    /**
     * Returns additional detail about the problem for logging.
     * This detail must never contain source content.
     *
     * @return the problem detail, or {@code null} if this is a successful reading
     */
    public String getProblemDetail() {
        return problemDetail;
    }


    /**
     * Returns true if the other object is a {@code MemoryInformation} with identical field values.
     *
     * @param o the object to compare
     * @return true if equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MemoryInformation)) {
            return false;
        }
        MemoryInformation other = (MemoryInformation) o;
        return Objects.equals(timestamp, other.timestamp)
                && Arrays.equals(values, other.values)
                && Objects.equals(problem, other.problem)
                && Objects.equals(problemDetail, other.problemDetail);
    }


    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(timestamp, problem, problemDetail);
        result = 31 * result + Arrays.hashCode(values);
        return result;
    }


    /**
     * Returns a human-readable representation of this reading.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        if (isSuccess()) {
            return "MemoryInformation{timestamp='" + timestamp + "', values=" + Arrays.toString(values) + '}';
        }
        return "MemoryInformation{problem='" + problem + "', detail='" + problemDetail + "'}";
    }
}
