package dev.antiafk.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeTest {

    @Test
    void fixedTime() {
        TimeRange range = TimeRange.parse("10m");
        assertTrue(range.isFixed());
        assertEquals(600_000, range.pickMillis(new Random()));
        assertEquals("10m", range.toString());
    }

    @Test
    void range() {
        TimeRange range = TimeRange.parse("6m-10m");
        assertFalse(range.isFixed());
        assertEquals(Duration.ofMinutes(6), range.min());
        assertEquals(Duration.ofMinutes(10), range.max());
        assertEquals("6m - 10m", range.toString());
    }

    @Test
    void rangeAllowsSpacesAndMixedFormats() {
        TimeRange range = TimeRange.parse(" 5m30s - 600 ");
        assertEquals(Duration.ofSeconds(330), range.min());
        assertEquals(Duration.ofSeconds(600), range.max());
    }

    @Test
    void picksSpreadAcrossTheRange() {
        TimeRange range = TimeRange.parse("6m-10m");
        Random random = new Random(1);
        long lowest = Long.MAX_VALUE, highest = 0;
        for (int i = 0; i < 10_000; i++) {
            long pick = range.pickMillis(random);
            assertTrue(pick >= 360_000 && pick <= 600_000);
            lowest = Math.min(lowest, pick);
            highest = Math.max(highest, pick);
        }
        assertTrue(lowest < 365_000 && highest > 595_000, "picks should cover the whole range");
    }

    @Test
    void rejectsBadRanges() {
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("10m-6m"));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("6m-"));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("-10m"));
        assertThrows(IllegalArgumentException.class, () -> TimeRange.parse("soon"));
    }
}
