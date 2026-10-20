/*
 * MemoryInformationMonitorConstants.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl;

import com.github.toolarium.common.statistic.PercentileHistogram;


/**
 * Constants used for formatting memory information statistics.
 *
 * @author patrick
 */
public interface MemoryInformationMonitorConstants {

    /** Label for the average value. */
    String LABEL_AVG = "Avg";

    /** Label for the minimum value. */
    String LABEL_MIN = "Min";

    /** Label for the maximum value. */
    String LABEL_MAX = "Max";

    /** Label for the spread (P95 - Median). */
    String LABEL_SPREAD = "Spread";

    /** Problem: memory information file does not exist yet. */
    String PROBLEM_FILE_MISSING = "memory information file does not exist (yet)";

    /** Problem: file is not a regular file. */
    String PROBLEM_NOT_REGULAR_FILE = "memory information file is not a regular file, symbolic links, pipes and devices are not read";

    /** Problem: file exceeds maximum expected size. */
    String PROBLEM_TOO_LARGE = "memory information file exceeds the maximum expected size";

    /** Problem: I/O error reading the file. */
    String PROBLEM_READ_ERROR = "error reading memory information file";

    /** Problem: unexpected file format. */
    String PROBLEM_FORMAT = "unexpected memory information format";

    /** Problem: header lacks required columns. */
    String PROBLEM_HEADER = "memory information header lacks the required columns Total, Used and Free";

    /** Problem: non-numeric value in file. */
    String PROBLEM_NOT_NUMERIC = "memory information contains a non-numeric value";

    /** Problem: values out of valid byte range. */
    String PROBLEM_OUT_OF_RANGE = "memory information values out of the valid range (0 to " + PercentileHistogram.MAX_VALUE + " bytes)";

    /** Problem: unexpected error in collector. */
    String PROBLEM_UNEXPECTED = "unexpected collector error";
}
