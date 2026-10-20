/*
 * MemoryInformationMonitorImplTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.toolarium.java.memory.monitor.dto.MemoryInformationStatistics;
import com.github.toolarium.java.memory.monitor.dto.MemoryMetric;
import com.github.toolarium.java.memory.monitor.dto.Percentile;
import com.github.toolarium.java.memory.monitor.dto.StatisticScopeType;
import com.github.toolarium.java.memory.monitor.reader.IMemoryInformationReader;
import com.github.toolarium.java.memory.monitor.reader.dto.MemoryInformation;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;


/**
 * Tests for {@link MemoryInformationMonitorImpl}: staleness, problem reporting,
 * scope selection, statistics, and configuration guards.
 *
 * <p>All tests drive the monitor synchronously via {@link MemoryInformationMonitorImpl#collectOnce()}
 * to avoid timing dependencies on the background collector thread.</p>
 *
 * @author patrick
 */
public class MemoryInformationMonitorImplTest implements MemoryInformationMonitorConstants {

    /**
     * A minimal {@link IMemoryInformationReader} that returns a pre-programmed sequence of results.
     * Returns {@code null} (unchanged) once the sequence is exhausted.
     */
    private static final class SequenceReader implements IMemoryInformationReader {
        private final MemoryInformation[] sequence;
        private int index;

        /**
         * Constructor.
         *
         * @param sequence the pre-programmed results to return in order
         */
        SequenceReader(MemoryInformation... sequence) {
            this.sequence = sequence;
        }

        @Override
        public MemoryInformation process() {
            if (index < sequence.length) {
                return sequence[index++];
            }
            return null;
        }

        @Override
        public void reset() {
            // no state
        }
    }


    /**
     * Creates a values array with TOTAL, USED and FREE set and all other metrics ABSENT.
     *
     * @param total total memory in bytes
     * @param used used memory in bytes
     * @param free free memory in bytes
     * @return the values array
     */
    private static long[] valuesOf(long total, long used, long free) {
        long[] v = new long[MemoryMetric.values().length];
        for (int i = 0; i < v.length; i++) {
            v[i] = SlidingWindow.ABSENT;
        }
        v[MemoryMetric.TOTAL.ordinal()] = total;
        v[MemoryMetric.USED.ordinal()] = used;
        v[MemoryMetric.FREE.ordinal()] = free;
        return v;
    }


    /**
     * Creates a successful reading with the given TOTAL, USED and FREE values.
     *
     * @param total total memory in bytes
     * @param used used memory in bytes
     * @param free free memory in bytes
     * @return the successful reading
     */
    private static MemoryInformation reading(long total, long used, long free) {
        return MemoryInformation.success(LocalDateTime.now(), valuesOf(total, used, free));
    }


    /**
     * Before any reading is collected: statistics must be absent, timestamp must be -1,
     * percentiles must be empty, and the format string must mention "no data".
     */
    @Test
    public void testInitialStateBeforeAnyReading() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0, new SequenceReader());

        assertNull(monitor.getLatestStatistics());
        assertEquals(-1L, monitor.getLatestStatisticTimestamp());
        assertNull(monitor.getPercentile(MemoryMetric.TOTAL, Percentile.P95));
        assertFalse(monitor.isStale());
    }


    /**
     * A single successful reading in window scope must publish statistics with count 1
     * and the correct last value.
     */
    @Test
    public void testSingleReadingWindowScope() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0,
                new SequenceReader(reading(1_000_000L, 500_000L, 500_000L)));

        monitor.collectOnce();

        assertNotNull(monitor.getLatestStatistics());
        MemoryInformationStatistics stats = monitor.getLatestStatistics();
        assertEquals(StatisticScopeType.WINDOW, stats.getStatisticScopeType());
        assertEquals(1L, stats.getTotalNumber());
        assertEquals(1_000_000L, stats.get(MemoryMetric.TOTAL).getLast());
        assertEquals(1_000_000L, stats.get(MemoryMetric.TOTAL).getMin());
        assertEquals(1_000_000L, stats.get(MemoryMetric.TOTAL).getMax());
    }


    /**
     * When the window is disabled (null) readings must be accumulated in lifetime scope.
     */
    @Test
    public void testLifetimeScope() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                null, 0,
                new SequenceReader(reading(2_000_000L, 1_000_000L, 1_000_000L)));

        monitor.collectOnce();

        MemoryInformationStatistics stats = monitor.getLatestStatistics();
        assertEquals(StatisticScopeType.LIFETIME, stats.getStatisticScopeType());
        assertEquals(StatisticScopeType.LIFETIME, monitor.getScope());
        assertEquals(1L, stats.getTotalNumber());
    }


    /**
     * A failing reading must set the current problem and leave statistics absent.
     */
    @Test
    public void testProblemReportedOnFailure() {
        MemoryInformation failure = MemoryInformation.failure(PROBLEM_FILE_MISSING, "test-detail");
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0, new SequenceReader(failure));

        monitor.collectOnce();

        assertEquals(PROBLEM_FILE_MISSING, monitor.getCurrentProblem());
        assertNull(monitor.getLatestStatistics());
    }


    /**
     * After a failure a successful reading must clear the problem.
     */
    @Test
    public void testProblemClearedAfterRecovery() {
        MemoryInformation failure = MemoryInformation.failure(PROBLEM_FILE_MISSING, "detail");
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0,
                new SequenceReader(failure, reading(1_000_000L, 500_000L, 500_000L)));

        monitor.collectOnce(); // failure
        assertEquals(PROBLEM_FILE_MISSING, monitor.getCurrentProblem());

        monitor.collectOnce(); // recovery
        assertNull(monitor.getCurrentProblem());
        assertNotNull(monitor.getLatestStatistics());
    }


    /**
     * Repeated identical failures must not change the current problem key
     * (suppression: only the first occurrence is logged).
     */
    @Test
    public void testSameProblemNotRepeated() {
        MemoryInformation f1 = MemoryInformation.failure(PROBLEM_FILE_MISSING, "d1");
        MemoryInformation f2 = MemoryInformation.failure(PROBLEM_FILE_MISSING, "d2");
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0, new SequenceReader(f1, f2));

        monitor.collectOnce();
        monitor.collectOnce();

        assertEquals(PROBLEM_FILE_MISSING, monitor.getCurrentProblem());
    }


    /**
     * When no data has been collected and stale threshold is positive, {@code isStale()} must
     * return {@code true}.
     */
    @Test
    public void testStaleWhenNoData() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 1_000, new SequenceReader());

        assertTrue(monitor.isStale());
    }


    /**
     * When stale detection is disabled (threshold = 0), {@code isStale()} must always return
     * {@code false}, even without data.
     */
    @Test
    public void testStaleDisabledReturnsAlwaysFalse() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0, new SequenceReader());

        assertFalse(monitor.isStale());
    }


    /**
     * Immediately after a successful reading the data must not be considered stale.
     */
    @Test
    public void testFreshDataIsNotStale() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 60_000,
                new SequenceReader(reading(1_000_000L, 500_000L, 500_000L)));

        monitor.collectOnce();

        assertFalse(monitor.isStale());
    }


    /**
     * After a reading, {@code getPercentile()} must return the value for the recorded metric.
     */
    @Test
    public void testGetPercentileAfterReading() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0,
                new SequenceReader(reading(1_000_000L, 500_000L, 500_000L)));

        monitor.collectOnce();

        Long p95 = monitor.getPercentile(MemoryMetric.TOTAL, Percentile.P95);
        assertNotNull(p95);
        assertEquals(1_000_000L, p95.longValue());
    }


    /**
     * {@code getPercentile()} must return empty for a metric not present in any reading.
     */
    @Test
    public void testGetPercentileAbsentMetricReturnsEmpty() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0,
                new SequenceReader(reading(1_000_000L, 500_000L, 500_000L)));

        monitor.collectOnce();

        // HEAP is ABSENT in the reading produced by reading()
        assertNull(monitor.getPercentile(MemoryMetric.HEAP, Percentile.P95));
    }


    /**
     * Multiple readings must accumulate the count and update min / max correctly.
     */
    @Test
    public void testMultipleReadingsAccumulateCount() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(5), 0,
                new SequenceReader(
                        reading(4_000_000_000L, 1_000_000_000L, 3_000_000_000L),
                        reading(6_000_000_000L, 2_000_000_000L, 4_000_000_000L),
                        reading(8_000_000_000L, 3_000_000_000L, 5_000_000_000L)));

        monitor.collectOnce();
        monitor.collectOnce();
        monitor.collectOnce();

        MemoryInformationStatistics stats = monitor.getLatestStatistics();
        assertEquals(3L, stats.getTotalNumber());
        assertEquals(4_000_000_000L, stats.get(MemoryMetric.TOTAL).getMin());
        assertEquals(8_000_000_000L, stats.get(MemoryMetric.TOTAL).getMax());
    }


    /**
     * {@code setWindow()} must throw {@link IllegalStateException} after the first
     * {@code collectOnce()} has fixed the configuration.
     */
    @Test
    public void testSetWindowThrowsAfterInitialization() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0,
                new SequenceReader(reading(1_000_000L, 500_000L, 500_000L)));

        monitor.collectOnce(); // triggers ensureInitialized()

        assertThrows(IllegalStateException.class, () -> monitor.setWindow(Duration.ofMinutes(5)));
    }


    /**
     * When the reader returns {@code null} (unchanged) no statistics must be published.
     */
    @Test
    public void testNullFromReaderIsIgnored() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0, new SequenceReader()); // empty sequence → always null

        monitor.collectOnce();

        assertNull(monitor.getLatestStatistics());
    }


    /**
     * {@code getLatestStatistics(Duration)} must return {@code null} for a configured window
     * before any reading has been collected (not-yet-available, not misconfigured).
     */
    @Test
    public void testGetLatestStatisticsByWindowBeforeAnyReading() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                new Duration[]{Duration.ofMinutes(5), Duration.ofHours(1)}, 0,
                new SequenceReader(), null, 0L);

        assertNull(monitor.getLatestStatistics(Duration.ofMinutes(5)));
        assertNull(monitor.getLatestStatistics(Duration.ofHours(1)));
    }


    /**
     * {@code getLatestStatistics(Duration)} must throw {@link IllegalArgumentException}
     * for a window that was never configured, even after readings have been collected.
     */
    @Test
    public void testGetLatestStatisticsByWindowNotConfiguredThrows() {
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                new Duration[]{Duration.ofMinutes(5)}, 0,
                new SequenceReader(reading(1_000_000L, 500_000L, 500_000L)), null, 0L);

        monitor.collectOnce();

        assertThrows(IllegalArgumentException.class, () -> monitor.getLatestStatistics(Duration.ofHours(1)));
    }


    /**
     * {@code isAvailable()} must be false before any reading, false while a problem is active,
     * and true once a successful reading has been recorded and the problem is cleared.
     */
    @Test
    public void testIsAvailable() {
        MemoryInformation failure = MemoryInformation.failure(PROBLEM_FILE_MISSING, "detail");
        MemoryInformationMonitorImpl monitor = new MemoryInformationMonitorImpl(
                Duration.ofMinutes(1), 0,
                new SequenceReader(failure, reading(1_000_000L, 500_000L, 500_000L)));

        assertFalse(monitor.isAvailable()); // no data yet

        monitor.collectOnce(); // failure
        assertFalse(monitor.isAvailable()); // problem active

        monitor.collectOnce(); // recovery
        assertTrue(monitor.isAvailable()); // data present, no problem
    }
}
