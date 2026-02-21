package com.javaclaw.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionTrackerTest {

    @Test
    void tracksWithinLimit() {
        var tracker = new ActionTracker(5);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) tracker.track("shell");
        });
        assertEquals(5, tracker.count("shell"));
    }

    @Test
    void throwsWhenLimitExceeded() {
        var tracker = new ActionTracker(3);
        tracker.track("shell");
        tracker.track("shell");
        tracker.track("shell");
        assertThrows(SecurityException.class, () -> tracker.track("shell"));
    }

    @Test
    void tracksPerToolIndependently() {
        var tracker = new ActionTracker(2);
        tracker.track("shell");
        tracker.track("shell");
        assertDoesNotThrow(() -> tracker.track("file_read"));
        assertThrows(SecurityException.class, () -> tracker.track("shell"));
    }

    @Test
    void countReturnsZeroForUnknownTool() {
        var tracker = new ActionTracker(10);
        assertEquals(0, tracker.count("unknown"));
    }
}
