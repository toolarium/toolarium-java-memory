/*
 * MemoryInformationMonitorTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.toolarium.java.memory.monitor.IMemoryInformationMonitor;
import com.github.toolarium.java.memory.monitor.MemoryMonitorFactory;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetricStatistics;
import com.github.toolarium.java.memory.monitor.dto.StatisticScopeType;
import com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;


/**
 * Illustrates usage of {@link IMemoryInformationMonitor} via {@link MemoryMonitorFactory}.
 *
 * @author patrick
 */
public class MemoryMonitorFactoryTest {

    private static final String TEMP_SUFFIX = ".out";

    /**
     * Demonstrates obtaining the default monitor singleton via the factory.
     */
    @Test
    public void testFactoryReturnsSingleton() {
        IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().getMemoryInformationMonitor();
        assertNotNull(monitor);
        assertFalse(monitor.isRunning());
        assertEquals(monitor, MemoryMonitorFactory.getInstance().getMemoryInformationMonitor(), "Factory must return the same default instance");
    }


    /**
     * Demonstrates creating a monitor with explicit configuration via the builder.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testBuilderConfiguration() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-test", TEMP_SUFFIX);
        try {
            IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
                    .memoryInformationFile(tempFile)
                    .window(Duration.ofSeconds(30))
                    .staleThresholdMilliseconds(10_000)
                    .build();

            assertEquals(Duration.ofSeconds(30), monitor.getWindow());
            assertEquals(StatisticScopeType.WINDOW, monitor.getScope());
            assertFalse(monitor.isRunning());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }


    /**
     * Demonstrates parsing a header-based memory information file and reading statistics.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testHeaderBasedContentParsing() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-test", TEMP_SUFFIX);
        try {
            MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                    .newBuilder()
                    .memoryInformationFile(tempFile)
                    .window(Duration.ofMinutes(1))
                    .build();

            // Header-based format: Total, Used and Free values in bytes
            String content = "Timestamp Total Used Free\n"
                    + "2024-01-15 10:00:00 8589934592 4294967296 4294967296";
            writeContent(tempFile, content, 1_000_000L);
            monitor.collectOnce();

            assertNotNull(monitor.getLatestStatistics(), "A new reading should have been recorded");
            MemoryMetricStatistics totalStats = monitor.getLatestStatistics().get(MemoryMetric.TOTAL);
            assertNotNull(totalStats, "TOTAL metric must be present");
            assertEquals(8589934592L, totalStats.getLast());
            assertEquals(1, totalStats.getCount());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }


    /**
     * Demonstrates that duplicate timestamps are not recorded twice.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testDuplicateTimestampSkipped() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-test", TEMP_SUFFIX);
        try {
            MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                    .newBuilder()
                    .memoryInformationFile(tempFile)
                    .build();

            String content = "Timestamp Total Used Free\n"
                    + "2024-01-15 10:00:00 8589934592 4294967296 4294967296";

            writeContent(tempFile, content, 1_000_000L);
            monitor.collectOnce();
            assertNotNull(monitor.getLatestStatistics(), "First reading must be recorded");

            // Write the same content again with a new lastModifiedTime — same in-file timestamp must be skipped
            writeContent(tempFile, content, 2_000_000L);
            monitor.collectOnce();

            MemoryMetricStatistics totalStats = monitor.getLatestStatistics().get(MemoryMetric.TOTAL);
            assertNotNull(totalStats, "TOTAL metric must have data");
            assertEquals(1L, totalStats.getCount(), "Same in-file timestamp must not be recorded again");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }


    /**
     * Demonstrates sliding-window statistics over multiple readings.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testSlidingWindowStatistics() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-test", TEMP_SUFFIX);
        try {
            MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                    .newBuilder()
                    .memoryInformationFile(tempFile)
                    .window(Duration.ofMinutes(1))
                    .build();

            String header = "Timestamp Total Used Free\n";
            long[] totals = {4_000_000_000L, 6_000_000_000L, 8_000_000_000L};
            for (int i = 0; i < totals.length; i++) {
                String content = header + "2024-01-15 10:00:0" + i + " " + totals[i] + " 1000000000 1000000000";
                writeContent(tempFile, content, 1_000_000L + i);
                monitor.collectOnce();
            }

            assertNotNull(monitor.getLatestStatistics());
            MemoryMetricStatistics window = monitor.getLatestStatistics().get(MemoryMetric.TOTAL);
            assertNotNull(window);
            assertEquals(3, window.getCount());
            assertEquals(4_000_000_000L, window.getMin());
            assertEquals(8_000_000_000L, window.getMax());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }


    /**
     * Writes content to a file and sets its {@code lastModifiedTime} to a specific value
     * so that each test write is seen as a distinct file change by the parser.
     *
     * @param file the target file
     * @param content the content to write
     * @param modTimeMs the last-modified time in milliseconds to set on the file
     * @throws IOException if the file cannot be written
     */
    private void writeContent(Path file, String content, long modTimeMs) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(modTimeMs));
    }


    /**
     * Demonstrates multiple concurrent windows: a single file read feeds all windows.
     * Both a short window and a long window must reflect the same readings.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testMultipleWindows() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-multiwindow", TEMP_SUFFIX);
        try {
            MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                    .newBuilder()
                    .memoryInformationFile(tempFile)
                    .windows(Duration.ofMinutes(5), Duration.ofHours(1))
                    .build();

            String header = "Timestamp Total Used Free\n";
            long[] totals = {4_000_000_000L, 6_000_000_000L, 8_000_000_000L};
            for (int i = 0; i < totals.length; i++) {
                String content = header + "2024-01-15 10:00:0" + i + " " + totals[i] + " 1000000000 1000000000";
                writeContent(tempFile, content, 1_000_000L + i);
                monitor.collectOnce();
            }

            // primary window (5 min) via getLatestStatistics()
            assertNotNull(monitor.getLatestStatistics());
            assertEquals(Duration.ofMinutes(5), monitor.getLatestStatistics().getWindowSize());
            assertEquals(3, monitor.getLatestStatistics().get(MemoryMetric.TOTAL).getCount());

            // same data visible in the 1-hour window
            assertNotNull(monitor.getLatestStatistics(Duration.ofHours(1)));
            assertEquals(Duration.ofHours(1), monitor.getLatestStatistics(Duration.ofHours(1)).getWindowSize());
            assertEquals(3, monitor.getLatestStatistics(Duration.ofHours(1)).get(MemoryMetric.TOTAL).getCount());
            assertEquals(4_000_000_000L, monitor.getLatestStatistics(Duration.ofHours(1)).get(MemoryMetric.TOTAL).getMin());
            assertEquals(8_000_000_000L, monitor.getLatestStatistics(Duration.ofHours(1)).get(MemoryMetric.TOTAL).getMax());

            // unconfigured window throws IllegalArgumentException
            assertThrows(IllegalArgumentException.class, () -> monitor.getLatestStatistics(Duration.ofMinutes(10)));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }


    /**
     * {@code windows()} must throw {@link IllegalArgumentException} when called with
     * an empty array or {@code null}, since at least one window must be configured.
     */
    @Test
    public void testWindowsBuilderValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> MemoryMonitorFactory.getInstance().newBuilder().windows());
        assertThrows(IllegalArgumentException.class,
                () -> MemoryMonitorFactory.getInstance().newBuilder().windows((Duration[]) null));
    }


    /**
     * Demonstrates stale detection: disabled when staleThresholdMilliseconds is 0.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testStaleDetectionDisabled() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-test", TEMP_SUFFIX);
        try {
            IMemoryInformationMonitor monitor = MemoryMonitorFactory.getInstance().newBuilder()
                    .memoryInformationFile(tempFile)
                    .staleThresholdMilliseconds(0)
                    .build();

            assertFalse(monitor.isStale(), "Stale detection disabled (threshold=0), must return false");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }


    /**
     * Demonstrates the {@code jvmReader()} builder path: the monitor reads JVM heap and
     * non-heap metrics directly from {@link java.lang.management.MemoryMXBean}.
     */
    @Test
    public void testJvmReaderBuilder() {
        MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                .newBuilder()
                .jvmReader()
                .build();

        monitor.collectOnce();

        assertNotNull(monitor.getLatestStatistics(), "JVM reader must produce a reading");
        assertNotNull(monitor.getLatestStatistics().get(MemoryMetric.HEAP), "Heap metric must be present when using jvmReader");
        assertNotNull(monitor.getLatestStatistics().get(MemoryMetric.NON_HEAP), "NonHeap metric must be present when using jvmReader");
    }


    /**
     * Demonstrates the {@code reader(IMemoryInformationReader)} builder path: a custom reader
     * is used and its data reaches the statistics.
     */
    @Test
    public void testCustomReaderBuilder() {
        long[] values = new long[MemoryMetric.values().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = SlidingWindow.ABSENT;
        }
        values[MemoryMetric.TOTAL.ordinal()] = 9_000_000_000L;
        values[MemoryMetric.USED.ordinal()] = 4_000_000_000L;
        values[MemoryMetric.FREE.ordinal()] = 5_000_000_000L;
        MemoryInformation reading = MemoryInformation.success(LocalDateTime.now(), values);

        IMemoryInformationReader customReader = new IMemoryInformationReader() {
            private boolean consumed;

            @Override
            public MemoryInformation process() {
                if (!consumed) {
                    consumed = true;
                    return reading;
                }
                return null;
            }

            @Override
            public void reset() {
                // no state
            }
        };

        MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                .newBuilder()
                .reader(customReader)
                .build();

        monitor.collectOnce();

        assertNotNull(monitor.getLatestStatistics(), "Custom reader data must reach statistics");
        MemoryMetricStatistics totalStats = monitor.getLatestStatistics().get(MemoryMetric.TOTAL);
        assertNotNull(totalStats, "TOTAL metric must be present");
        assertEquals(9_000_000_000L, totalStats.getLast());
    }


    /**
     * Demonstrates that {@code fileReader()} resets a previously set JVM reader back to
     * the file-based reader. With the default file path absent on this system, the monitor
     * must report a problem and not produce statistics.
     *
     * @throws IOException if the temp file cannot be created or deleted
     */
    @Test
    public void testFileReaderBuilderPath() throws IOException {
        Path tempFile = Files.createTempFile("meminfo-filereader-test", TEMP_SUFFIX);
        try {
            MemoryInformationMonitorImpl monitor = (MemoryInformationMonitorImpl) MemoryMonitorFactory.getInstance()
                    .newBuilder()
                    .jvmReader()
                    .fileReader()                        // resets back to file reader
                    .memoryInformationFile(tempFile)
                    .build();

            // empty file → PROBLEM_FORMAT failure, no statistics
            monitor.collectOnce();
            assertNull(monitor.getLatestStatistics(), "Empty file must not produce statistics");
            assertNotNull(monitor.getCurrentProblem(), "A problem must be reported for an empty file");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
