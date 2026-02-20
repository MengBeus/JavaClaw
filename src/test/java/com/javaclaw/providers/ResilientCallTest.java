package com.javaclaw.providers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResilientCallTest {

    @Test
    void successOnFirstAttempt() {
        var result = ResilientCall.execute(() -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void retriesOnFailureThenSucceeds() {
        var attempts = new AtomicInteger(0);
        var result = ResilientCall.execute(() -> {
            if (attempts.incrementAndGet() < 3) throw new RuntimeException("fail");
            return "recovered";
        });
        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void exhaustsRetriesAndThrows() {
        var attempts = new AtomicInteger(0);
        var ex = assertThrows(RuntimeException.class, () ->
            ResilientCall.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("always fail");
            })
        );
        assertEquals(3, attempts.get());
        assertTrue(ex.getMessage().contains("All retries exhausted"));
    }

    @Test
    void appliesExponentialBackoff() {
        var attempts = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        assertThrows(RuntimeException.class, () ->
            ResilientCall.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("fail");
            })
        );
        long elapsed = System.currentTimeMillis() - start;
        // 退避：500ms + 1000ms = 1500ms 最小
        assertTrue(elapsed >= 1400, "Expected >= 1400ms backoff, got " + elapsed + "ms");
    }
}
