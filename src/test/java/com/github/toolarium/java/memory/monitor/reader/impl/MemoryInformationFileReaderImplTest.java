/*
 * MemoryInformationFileReaderImplTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.reader.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.impl.MemoryInformationMonitorConstants;
import com.github.toolarium.java.memory.monitor.impl.SlidingWindow;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/**
 * Tests for {@link MemoryInformationFileReaderImpl} covering error paths, edge cases
 * and the two supported file formats.
 *
 * @author patrick
 */
public class MemoryInformationFileReaderImplTest implements MemoryInformationMonitorConstants {

    @TempDir
    private Path tempDir;


    /**
     * Writes content to a temp file with a specific last-modified time.
     *
     * @param name the file name within the temp directory
     * @param content the UTF-8 content to write
     * @param modTimeMs the last-modified time in milliseconds
     * @return the path of the written file
     * @throws IOException if the file cannot be written
     */
    private Path write(String name, String content, long modTimeMs) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(modTimeMs));
        return file;
    }


    /**
     * A missing file must produce a PROBLEM_FILE_MISSING failure.
     */
    @Test
    public void testMissingFile() {
        Path missing = tempDir.resolve("does-not-exist.out");
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(missing);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_FILE_MISSING, result.getProblem());
    }


    /**
     * A file exceeding MAX_FILE_SIZE must produce a PROBLEM_TOO_LARGE failure.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testFileTooLarge() throws IOException {
        byte[] bytes = new byte[MemoryInformationFileReaderImpl.MAX_FILE_SIZE + 1];
        Path file = tempDir.resolve("toolarge.out");
        Files.write(file, bytes);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_TOO_LARGE, result.getProblem());
    }


    /**
     * An empty file must produce a PROBLEM_FORMAT failure.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testEmptyFile() throws IOException {
        Path file = tempDir.resolve("empty.out");
        Files.write(file, new byte[0]);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_FORMAT, result.getProblem());
    }


    /**
     * An unparseable timestamp must produce a PROBLEM_FORMAT failure.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testBadTimestamp() throws IOException {
        Path file = write("bad-ts.out", "not-a-date 99:99:99 1000 2000 3000", 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_FORMAT, result.getProblem());
    }


    /**
     * The default format (single data line without a header) must be parsed correctly.
     * Columns: date time total label used label free [label process].
     * JVM metrics must be ABSENT since they are not present in the default format.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testDefaultFormat() throws IOException {
        String content = "2024-01-15 10:00:00 8589934592 kB 4294967296 kB 4294967296";
        Path file = write("default.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(8589934592L, result.getValues()[MemoryMetric.TOTAL.ordinal()]);
        assertEquals(4294967296L, result.getValues()[MemoryMetric.USED.ordinal()]);
        assertEquals(4294967296L, result.getValues()[MemoryMetric.FREE.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.HEAP.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.NON_HEAP.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.JIT.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.JVM_TOTAL.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.THREAD.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.GC.ordinal()]);
    }


    /**
     * A non-numeric value in the data line must produce a PROBLEM_NOT_NUMERIC failure.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testNonNumericValue() throws IOException {
        String content = "Timestamp Total Used Free\n2024-01-15 10:00:00 NOTANUMBER 4294967296 4294967296";
        Path file = write("non-numeric.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_NOT_NUMERIC, result.getProblem());
    }


    /**
     * A negative byte value must produce a PROBLEM_OUT_OF_RANGE failure.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testOutOfRangeValue() throws IOException {
        String content = "Timestamp Total Used Free\n2024-01-15 10:00:00 -1 4294967296 4294967296";
        Path file = write("oor.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_OUT_OF_RANGE, result.getProblem());
    }


    /**
     * A header missing any of Total, Used or Free must produce a PROBLEM_HEADER failure.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testMissingRequiredColumns() throws IOException {
        String content = "Timestamp Total Used\n2024-01-15 10:00:00 8589934592 4294967296";
        Path file = write("no-free.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals(PROBLEM_HEADER, result.getProblem());
    }


    /**
     * When only a subset of the JVM columns is present all JVM metrics must be ABSENT
     * (all-or-nothing rule).
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testPartialJvmColumnsAreDropped() throws IOException {
        // Heap present but NonHeap and Code absent → all JVM must be ABSENT
        String content = "Timestamp Total Used Free Heap\n"
                + "2024-01-15 10:00:00 8589934592 4294967296 4294967296 536870912";
        Path file = write("partial-jvm.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(8589934592L, result.getValues()[MemoryMetric.TOTAL.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.JVM_TOTAL.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.HEAP.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.NON_HEAP.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.THREAD.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.GC.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.JIT.ordinal()]);
    }


    /**
     * All three JVM columns present must produce values for Heap, NonHeap and Code.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testAllJvmColumnsPresent() throws IOException {
        // cb-meminfo.sh output: %-24s timestamp, %-15s per data column, raw bytes (-b flag)
        String content = "Timestamp               Total          Used           Free           Heap           Non-Heap       Code           \n"
                + "2024-01-15 10:00:00     8589934592     4294967296     4294967296     536870912      67108864       33554432       ";
        Path file = write("all-jvm.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(536870912L, result.getValues()[MemoryMetric.HEAP.ordinal()]);
        assertEquals(67108864L, result.getValues()[MemoryMetric.NON_HEAP.ordinal()]);
        assertEquals(33554432L, result.getValues()[MemoryMetric.JIT.ordinal()]);
        // JVM-Total, Thread, GC not in this header → must be ABSENT
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.JVM_TOTAL.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.THREAD.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.GC.ordinal()]);
    }


    /**
     * Full file format (cb-meminfo.sh v1.7.x, legacy): all columns including JVM-Total, Thread,
     * GC and the legacy {@code Code} column name must produce values for all metrics.
     * {@code Heap-Used} and {@code Non-Heap-Used} must be ABSENT (not in this header).
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testFullFormat() throws IOException {
        // Exact cb-meminfo.sh v1.7.x output: -ts -pid 1 -jvm -b
        // %-24s timestamp header/data, %-15s per data column, raw bytes
        String content = "Timestamp               Total          Used           Free           Process[1]     JVM-Total      Heap           Non-Heap       Thread         GC             Code           \n"
                + "2026-10-18 14:00:40     16847208448    9663676416     7183532032     524288000      268435456      134217728      52428800       2097152        5242880        4194304        ";
        Path file = write("full-format.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(16847208448L, result.getValues()[MemoryMetric.TOTAL.ordinal()]);
        assertEquals(9663676416L, result.getValues()[MemoryMetric.USED.ordinal()]);
        assertEquals(7183532032L, result.getValues()[MemoryMetric.FREE.ordinal()]);
        assertEquals(524288000L, result.getValues()[MemoryMetric.PROCESS.ordinal()]);
        assertEquals(268435456L, result.getValues()[MemoryMetric.JVM_TOTAL.ordinal()]);
        assertEquals(134217728L, result.getValues()[MemoryMetric.HEAP.ordinal()]);
        assertEquals(52428800L, result.getValues()[MemoryMetric.NON_HEAP.ordinal()]);
        assertEquals(2097152L, result.getValues()[MemoryMetric.THREAD.ordinal()]);
        assertEquals(5242880L, result.getValues()[MemoryMetric.GC.ordinal()]);
        assertEquals(4194304L, result.getValues()[MemoryMetric.JIT.ordinal()]);
        // v1.7.x has no Heap-Used / Non-Heap-Used columns
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.HEAP_USED.ordinal()]);
        assertEquals(SlidingWindow.ABSENT, result.getValues()[MemoryMetric.NON_HEAP_USED.ordinal()]);
    }


    /**
     * Full file format (cb-meminfo.sh v1.8.0+, with {@code -pid}): includes {@code Heap-Used}
     * and {@code Non-Heap-Used} columns obtained via {@code jstat}. All metrics must be present.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testFullFormatV18WithHeapUsed() throws IOException {
        // Exact cb-meminfo.sh v1.8.0 output: -ts -pid 100 -jvm -b
        // %-24s timestamp header/data, %-15s per data column, raw bytes
        String content = "Timestamp               Total          Used           Free           Process[100]   JVM-Total      Heap           Non-Heap       Heap-Used      Non-Heap-Used  Thread         GC             JIT            \n"
                + "2026-10-20 14:27:16     16619597824    1656737792     14962860032    123084800      134166528      29360128       5656576        8071372        29521100       2422784        0              9834496        ";
        Path file = write("full-format-v18.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(16619597824L, result.getValues()[MemoryMetric.TOTAL.ordinal()]);
        assertEquals(1656737792L, result.getValues()[MemoryMetric.USED.ordinal()]);
        assertEquals(14962860032L, result.getValues()[MemoryMetric.FREE.ordinal()]);
        assertEquals(123084800L, result.getValues()[MemoryMetric.PROCESS.ordinal()]);
        assertEquals(134166528L, result.getValues()[MemoryMetric.JVM_TOTAL.ordinal()]);
        assertEquals(29360128L, result.getValues()[MemoryMetric.HEAP.ordinal()]);
        assertEquals(5656576L, result.getValues()[MemoryMetric.NON_HEAP.ordinal()]);
        assertEquals(8071372L, result.getValues()[MemoryMetric.HEAP_USED.ordinal()]);
        assertEquals(29521100L, result.getValues()[MemoryMetric.NON_HEAP_USED.ordinal()]);
        assertEquals(2422784L, result.getValues()[MemoryMetric.THREAD.ordinal()]);
        assertEquals(0L, result.getValues()[MemoryMetric.GC.ordinal()]);
        assertEquals(9834496L, result.getValues()[MemoryMetric.JIT.ordinal()]);
    }


    /**
     * A column name with a bracketed PID suffix such as {@code Process[1234]} must be
     * matched to the {@code Process} metric.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testColumnNameWithPidSuffix() throws IOException {
        String content = "Timestamp Total Used Free Process[1234]\n"
                + "2024-01-15 10:00:00 8589934592 4294967296 4294967296 102400000";
        Path file = write("pid-suffix.out", content, 1L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(102400000L, result.getValues()[MemoryMetric.PROCESS.ordinal()]);
    }


    /**
     * When the file's last-modified time has not changed the reader must return {@code null}
     * without re-reading the file.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testUnchangedModTimeReturnsNull() throws IOException {
        String content = "Timestamp Total Used Free\n2024-01-15 10:00:00 8589934592 4294967296 4294967296";
        Path file = write("same-modtime.out", content, 1_000L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        assertNotNull(reader.process()); // first read
        assertNull(reader.process());    // same modtime → null
    }


    /**
     * When the file's last-modified time changed but the in-file timestamp is the same
     * the reader must return {@code null} to signal no new data.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testUnchangedInFileTimestampReturnsNull() throws IOException {
        String content = "Timestamp Total Used Free\n2024-01-15 10:00:00 8589934592 4294967296 4294967296";
        Path file = write("same-ts.out", content, 1_000L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        assertNotNull(reader.process());

        // touch the file (new modtime) but keep the same in-file timestamp
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000L));
        assertNull(reader.process());
    }


    /**
     * When the header changes between readings the column cache must be invalidated and
     * the new column order must be applied correctly.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testHeaderCacheInvalidatedOnChange() throws IOException {
        String content1 = "Timestamp Total Used Free\n2024-01-15 10:00:00 8589934592 4294967296 4294967296";
        Path file = write("header-change.out", content1, 1_000L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        assertTrue(reader.process().isSuccess());

        // rewrite with swapped column order
        String content2 = "Timestamp Used Total Free\n2024-01-15 10:00:01 4294967296 8589934592 4294967296";
        Files.write(file, content2.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000L));

        MemoryInformation result = reader.process();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(8589934592L, result.getValues()[MemoryMetric.TOTAL.ordinal()]);
        assertEquals(4294967296L, result.getValues()[MemoryMetric.USED.ordinal()]);
    }


    /**
     * After {@code reset()} the reader must re-read a file it had already cached, even
     * when the last-modified time has not changed.
     *
     * @throws IOException if the temp file cannot be written
     */
    @Test
    public void testResetClearsState() throws IOException {
        String content = "Timestamp Total Used Free\n2024-01-15 10:00:00 8589934592 4294967296 4294967296";
        Path file = write("reset.out", content, 1_000L);
        MemoryInformationFileReaderImpl reader = new MemoryInformationFileReaderImpl(file);
        assertNotNull(reader.process()); // first read → success
        assertNull(reader.process());    // unchanged → null

        reader.reset();

        // same file, same modtime — but after reset the reader must re-read it
        assertNotNull(reader.process());
    }
}
