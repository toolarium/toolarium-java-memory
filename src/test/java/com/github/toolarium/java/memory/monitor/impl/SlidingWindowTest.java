/*
 * SlidingWindowTest.java
 *
 * Copyright by toolarium, all rights reserved.
 */
package com.github.toolarium.java.memory.monitor.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;


/**
 * Tests the {@link SlidingWindow}.
 *
 * @author patrick
 */
public class SlidingWindowTest {
    private static final long ABSENT = SlidingWindow.ABSENT;


    /**
     * Test an empty window.
     */
    @Test
    public void testEmpty() {
        SlidingWindow window = new SlidingWindow(1000, 10, 2);
        assertEquals(0, window.size());
        assertArrayEquals(new long[0], window.getSortedValues(0));
        assertEquals(ABSENT, window.getLastValue(1));
    }


    /**
     * Test the values are returned sorted and the last value is the most recent one.
     */
    @Test
    public void testSortedAndLastValue() {
        SlidingWindow window = new SlidingWindow(1000, 10, 1);
        window.add(0, new long[] {30});
        window.add(1, new long[] {10});
        window.add(2, new long[] {20});
        assertEquals(3, window.size());
        assertArrayEquals(new long[] {10, 20, 30}, window.getSortedValues(0));
        assertEquals(20, window.getLastValue(0));
    }


    /**
     * Test readings older than the window are evicted.
     */
    @Test
    public void testEviction() {
        SlidingWindow window = new SlidingWindow(1000, 10, 1);
        window.add(0, new long[] {1});
        window.add(500, new long[] {2});
        window.add(1000, new long[] {3});
        assertEquals(3, window.size());

        window.add(1200, new long[] {4});
        assertArrayEquals(new long[] {2, 3, 4}, window.getSortedValues(0));

        window.evict(5000);
        assertEquals(0, window.size());
        assertEquals(ABSENT, window.getLastValue(0));
    }


    /**
     * Test the oldest reading is dropped when the capacity is reached.
     */
    @Test
    public void testCapacity() {
        SlidingWindow window = new SlidingWindow(1000, 3, 1);
        for (int i = 1; i <= 5; i++) {
            window.add(i, new long[] {i});
        }
        assertEquals(3, window.size());
        assertArrayEquals(new long[] {3, 4, 5}, window.getSortedValues(0));
        assertEquals(5, window.getLastValue(0));
    }


    /**
     * Test absent values are skipped and a column is allocated on its first value.
     */
    @Test
    public void testAbsentValues() {
        SlidingWindow window = new SlidingWindow(1000, 10, 2);
        window.add(0, new long[] {1, ABSENT});
        window.add(1, new long[] {2, 20});
        window.add(2, new long[] {3, ABSENT});
        assertArrayEquals(new long[] {1, 2, 3}, window.getSortedValues(0));
        assertArrayEquals(new long[] {20}, window.getSortedValues(1));
        assertEquals(20, window.getLastValue(1));
    }


    /**
     * Test the nearest-rank quantile.
     */
    @Test
    public void testValueAtQuantile() {
        long[] values = new long[100];
        for (int i = 0; i < values.length; i++) {
            values[i] = i + 1;
        }
        assertEquals(1, SlidingWindow.valueAtQuantile(values, 0.0));
        assertEquals(50, SlidingWindow.valueAtQuantile(values, 0.5));
        assertEquals(95, SlidingWindow.valueAtQuantile(values, 0.95));
        assertEquals(99, SlidingWindow.valueAtQuantile(values, 0.99));
        assertEquals(100, SlidingWindow.valueAtQuantile(values, 1.0));
        assertEquals(7, SlidingWindow.valueAtQuantile(new long[] {7}, 0.99));
    }


    /**
     * Test invalid constructor arguments are rejected.
     */
    @Test
    public void testInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindow(0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindow(1000, 0, 1));
    }
}
