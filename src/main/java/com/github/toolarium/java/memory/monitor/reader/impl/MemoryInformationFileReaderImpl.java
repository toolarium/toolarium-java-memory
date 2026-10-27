/*
 * MemoryInformationFileReaderImpl.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader.impl;

import com.github.toolarium.common.statistic.PercentileHistogram;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.impl.MemoryInformationMonitorConstants;
import com.github.toolarium.java.memory.monitor.impl.SlidingWindow;
import com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.regex.Pattern;


/**
 * Reads and parses the memory information file in the header-based and default format.
 *
 * <p>Two file formats are supported:</p>
 * <ul>
 *   <li><strong>Header-based:</strong> a header line starting with {@code Timestamp} followed by a data line
 *       {@code <date> <time> <values...>}. Columns are resolved by name
 *       ({@link MemoryMetric#getColumnName()}; a suffix in brackets such as {@code Process[123]} is ignored),
 *       the column order is not relevant and unknown columns are ignored.
 *       {@code Total}, {@code Used} and {@code Free} are required; core JVM metrics ({@code Heap},
 *       {@code Non-Heap}, {@code Code}) are tracked only when all three are present; extended JVM
 *       metrics ({@code JVM-Total}, {@code Thread}, {@code GC}) are optional.</li>
 *   <li><strong>Default:</strong> a single data line
 *       {@code <date> <time> <total> <label> <used> <label> <free> [<label> <process>]}.</li>
 * </ul>
 *
 * <p>Only a regular file of at most {@value #MAX_FILE_SIZE} bytes is read. Symbolic links, pipes
 * and devices are rejected. The file content is never written to the log.</p>
 *
 * <p>This implementation is stateful: it caches the last seen timestamp (for change detection)
 * and the resolved column mapping (for performance). The file path is fixed at construction time.</p>
 *
 * @author patrick
 */
public class MemoryInformationFileReaderImpl implements IMemoryInformationReader, MemoryInformationMonitorConstants {

    /** Maximum file size in bytes accepted by this reader. */
    public static final int MAX_FILE_SIZE = 1024;

    private static final String HEADER_PREFIX = "Timestamp";
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r?\\n");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final int TIMESTAMP_FIELDS = 2;
    private static final int DEFAULT_MIN_FIELDS = 7;

    private static final int NOT_MAPPED = -1;
    private static final long MAX_VALID_BYTES = PercentileHistogram.MAX_VALUE;
    private static final MemoryMetric[] METRICS = MemoryMetric.values();
    private static final int METRIC_COUNT = METRICS.length;
    private static final int[] DEFAULT_COLUMNS = buildDefaultColumns();

    private final Path file;
    private LocalDateTime lastTimestamp;
    private FileTime lastModifiedTime;
    private String cachedHeader;
    private int[] cachedColumns;
    private int cachedFieldCount;


    /**
     * Constructor.
     *
     * @param file the memory information file to read
     */
    public MemoryInformationFileReaderImpl(Path file) {
        this.file = file;
    }


    /**
     * Reads and parses the memory information file.
     *
     * <p>Returns {@code null} when the file content is unchanged (same {@code lastModifiedTime}
     * as the last successful read, or the thread was interrupted). Returns a
     * {@link MemoryInformation#isSuccess() failing} reading when the file is missing, unreadable,
     * or contains invalid data. Returns a successful reading when new valid data is found.</p>
     *
     * @return the parsed reading, or {@code null} if the content is unchanged
     */
    @Override
    public MemoryInformation process() {
        FileTime pendingModifiedTime;
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return MemoryInformation.failure(PROBLEM_NOT_REGULAR_FILE, file.toString());
            }
            if (attributes.size() > MAX_FILE_SIZE) {
                return MemoryInformation.failure(PROBLEM_TOO_LARGE, MAX_FILE_SIZE + " bytes limit");
            }
            pendingModifiedTime = attributes.lastModifiedTime();
            if (pendingModifiedTime.equals(lastModifiedTime)) {
                return null;
            }
        } catch (NoSuchFileException e) {
            return MemoryInformation.failure(PROBLEM_FILE_MISSING, file.toString());
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return MemoryInformation.failure(PROBLEM_READ_ERROR, e.getClass().getSimpleName());
        }

        String content;
        try {
            content = readBounded();
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return MemoryInformation.failure(PROBLEM_READ_ERROR, e.getClass().getSimpleName());
        }

        if (content == null) {
            return MemoryInformation.failure(PROBLEM_FORMAT, "empty or oversized");
        }

        lastModifiedTime = pendingModifiedTime;
        return parseContent(content);
    }


    /**
     * Resets the internal state (last seen timestamp, last modified time, cached column mappings).
     */
    @Override
    public void reset() {
        lastTimestamp = null;
        lastModifiedTime = null;
        cachedHeader = null;
        cachedColumns = null;
        cachedFieldCount = 0;
    }


    /**
     * Reads the file content with a bounded read to prevent OOM from oversized files.
     * Symbolic links are not followed. Returns the trimmed content, or {@code null} if empty.
     *
     * @return the trimmed content, or {@code null} if empty or oversized
     * @throws IOException if the file cannot be read
     */
    private String readBounded() throws IOException {
        byte[] buf = new byte[MAX_FILE_SIZE + 1];
        int length = 0;
        try (InputStream is = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            while (length < buf.length) {
                int read = is.read(buf, length, buf.length - length);
                if (read < 0) {
                    break;
                }
                length += read;
            }
        }
        if (length == 0 || length > MAX_FILE_SIZE) {
            return null;
        }

        String content = new String(buf, 0, length, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return null;
        }
        return content;
    }


    /**
     * Parses the trimmed file content. Returns {@code null} if the timestamp is unchanged.
     *
     * @param content the trimmed file content
     * @return the parsed reading, or {@code null} if unchanged
     */
    private MemoryInformation parseContent(String content) {
        String[] lines = NEWLINE_PATTERN.split(content);
        String header = null;
        String dataLine;
        if (lines.length >= 2 && lines[0].trim().startsWith(HEADER_PREFIX)) {
            header = lines[0].trim();
            dataLine = lines[1].trim();
        } else {
            dataLine = lines[0].trim();
        }

        String[] parts = WHITESPACE_PATTERN.split(dataLine);
        if (dataLine.isEmpty() || parts.length < TIMESTAMP_FIELDS) {
            return MemoryInformation.failure(PROBLEM_FORMAT, "missing timestamp");
        }

        LocalDateTime timestamp = parseTimestamp(parts[0], parts[1]);
        if (timestamp == null) {
            return MemoryInformation.failure(PROBLEM_FORMAT, "unparseable timestamp");
        }
        if (timestamp.equals(lastTimestamp)) {
            return null;
        }

        MemoryInformation result = parseValues(header, parts, timestamp);
        if (result.isSuccess()) {
            lastTimestamp = timestamp;
        }
        return result;
    }


    /**
     * Parses the date and time fields from the data line into a {@link LocalDateTime}.
     * The expected format is {@code uuuu-MM-dd HH:mm:ss}.
     *
     * @param date the date field (e.g. {@code 2024-01-15})
     * @param time the time field (e.g. {@code 10:00:00})
     * @return the parsed {@link LocalDateTime}, or {@code null} if the fields cannot be parsed
     */
    private LocalDateTime parseTimestamp(String date, String time) {
        try {
            return LocalDateTime.parse(date + " " + time, TIMESTAMP_FORMATTER);
        } catch (DateTimeParseException e) {
            // the exception message may contain file content; intentionally not logged
            return null;
        }
    }


    /**
     * Parses and validates the values of a data line.
     *
     * @param header the header line, or {@code null} for the default format
     * @param parts the fields of the data line
     * @param timestamp the timestamp extracted and parsed from the data line
     * @return a successful reading with the parsed values, or a failed reading with a problem description
     */
    private MemoryInformation parseValues(String header, String[] parts, LocalDateTime timestamp) {
        int[] columns;
        if (header != null) {
            columns = resolveColumns(header);
            if (columns[MemoryMetric.TOTAL.ordinal()] == NOT_MAPPED
                    || columns[MemoryMetric.USED.ordinal()] == NOT_MAPPED
                    || columns[MemoryMetric.FREE.ordinal()] == NOT_MAPPED) {
                return MemoryInformation.failure(PROBLEM_HEADER, "header changed or incomplete");
            }
            if (parts.length != cachedFieldCount) {
                return MemoryInformation.failure(PROBLEM_FORMAT, "expected " + cachedFieldCount + " fields according to the header, found " + parts.length);
            }
        } else {
            columns = DEFAULT_COLUMNS;
            if (parts.length < DEFAULT_MIN_FIELDS) {
                return MemoryInformation.failure(PROBLEM_FORMAT, "expected at least " + DEFAULT_MIN_FIELDS + " fields, found " + parts.length);
            }
        }

        long[] values = new long[METRIC_COUNT];
        Arrays.fill(values, SlidingWindow.ABSENT);
        for (MemoryMetric metric : METRICS) {
            int col = metric.ordinal();
            int index = columns[col];
            if (index != NOT_MAPPED && index < parts.length) {
                try {
                    values[col] = Long.parseLong(parts[index]);
                } catch (NumberFormatException e) {
                    // the exception message may contain file content; intentionally not logged
                    return MemoryInformation.failure(PROBLEM_NOT_NUMERIC, "column " + metric.getColumnName());
                }

                if (!isValidBytes(values[col])) {
                    return MemoryInformation.failure(PROBLEM_OUT_OF_RANGE, "column " + metric.getColumnName());
                }
            }
        }

        // Core JVM metrics (Heap, Non-Heap, JIT) are all-or-nothing: if any of them is not
        // declared in the header, all JVM metrics are cleared. Extended JVM metrics
        // (JVM-Total, Heap-Used, Non-Heap-Used, Thread, GC) are optional and tracked independently when present.
        boolean coreJvmComplete = columns[MemoryMetric.HEAP.ordinal()] != NOT_MAPPED
                && columns[MemoryMetric.NON_HEAP.ordinal()] != NOT_MAPPED
                && columns[MemoryMetric.JIT.ordinal()] != NOT_MAPPED;
        if (!coreJvmComplete) {
            for (MemoryMetric metric : METRICS) {
                if (metric.isJvm()) {
                    values[metric.ordinal()] = SlidingWindow.ABSENT;
                }
            }
        }
        return MemoryInformation.success(timestamp, values);
    }


    /**
     * Resolves the data field index of each metric from the header names.
     * The result is cached as long as the header does not change.
     *
     * @param header the header line
     * @return the data field index per metric, {@value #NOT_MAPPED} if not present
     */
    private int[] resolveColumns(String header) {
        if (header.equals(cachedHeader)) {
            return cachedColumns;
        }

        String[] names = WHITESPACE_PATTERN.split(header);
        int[] columns = new int[METRIC_COUNT];
        Arrays.fill(columns, NOT_MAPPED);

        // header index 0 is "Timestamp" which spans TIMESTAMP_FIELDS data fields
        int offset = TIMESTAMP_FIELDS - 1;
        for (int i = 1; i < names.length; i++) {
            String name = names[i];
            int bracket = name.indexOf('[');
            if (bracket >= 0) {
                name = name.substring(0, bracket);
            }

            for (MemoryMetric metric : METRICS) {
                if (metric.matchesColumnName(name) && columns[metric.ordinal()] == NOT_MAPPED) {
                    columns[metric.ordinal()] = i + offset;
                }
            }
        }

        cachedHeader = header;
        cachedColumns = columns;
        cachedFieldCount = names.length + offset;
        return columns;
    }


    /**
     * Builds the column index array for the default file format.
     *
     * @return the column index array; {@value #NOT_MAPPED} for metrics not present in the default format
     */
    private static int[] buildDefaultColumns() {
        int[] columns = new int[MemoryMetric.values().length];
        Arrays.fill(columns, NOT_MAPPED);
        columns[MemoryMetric.TOTAL.ordinal()] = 2;
        columns[MemoryMetric.USED.ordinal()] = 4;
        columns[MemoryMetric.FREE.ordinal()] = 6;
        columns[MemoryMetric.PROCESS.ordinal()] = 8;
        return columns;
    }


    /**
     * Returns {@code true} when the value is within the valid byte range.
     *
     * @param value the value to validate
     * @return true if valid
     */
    private static boolean isValidBytes(long value) {
        return value >= 0 && value <= MAX_VALID_BYTES;
    }
}
