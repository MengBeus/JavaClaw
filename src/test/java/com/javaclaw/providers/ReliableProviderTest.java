package com.javaclaw.providers;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReliableProviderTest {

    static class MockProvider implements ModelProvider {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        final int failUntil;
        final String error;

        MockProvider(String name, int failUntil, String error) {
            this.name = name;
            this.failUntil = failUntil;
            this.error = error;
        }

        @Override public String id() { return name; }

        @Override
        public ChatResponse chat(ChatRequest request) {
            int attempt = calls.incrementAndGet();
            if (attempt <= failUntil) throw new RuntimeException(error);
            return new ChatResponse(request.model(), "ok from " + name, Map.of(), List.of());
        }

        @Override
        public Iterator<ChatEvent> chatStream(ChatRequest request) {
            return List.of(new ChatEvent("stream", true)).iterator();
        }
    }

    private ChatRequest req(String model) {
        return new ChatRequest(model, List.of(), 0.0);
    }

    @Test
    void fallsBackToSecondProvider() {
        var p1 = new MockProvider("p1", Integer.MAX_VALUE, "500 down");
        var p2 = new MockProvider("p2", 0, "");
        var reliable = new ReliableProvider(List.of(p1, p2), 0, 1);

        var resp = reliable.chat(req("test"));
        assertEquals("ok from p2", resp.content());
        assertEquals(1, p1.calls.get());
        assertEquals(1, p2.calls.get());
    }

    @Test
    void retriesThenRecovers() {
        var p = new MockProvider("p1", 1, "503 temporary");
        var reliable = new ReliableProvider(List.of(p), 2, 1);

        var resp = reliable.chat(req("test"));
        assertEquals("ok from p1", resp.content());
        assertTrue(p.calls.get() >= 2);
    }

    @Test
    void allFailsThrowsAggregated() {
        var p1 = new MockProvider("p1", Integer.MAX_VALUE, "500 err");
        var p2 = new MockProvider("p2", Integer.MAX_VALUE, "502 err");
        var reliable = new ReliableProvider(List.of(p1, p2), 0, 1);

        var ex = assertThrows(RuntimeException.class, () -> reliable.chat(req("m")));
        assertTrue(ex.getMessage().contains("All providers/models failed"));
    }

    @Test
    void modelFallbackTriesNextModel() {
        // Provider fails on model-a, succeeds on model-b
        var p = new ModelProvider() {
            @Override public String id() { return "p"; }
            @Override public ChatResponse chat(ChatRequest r) {
                if ("model-a".equals(r.model())) throw new RuntimeException("500 unavailable");
                return new ChatResponse(r.model(), "ok", Map.of(), List.of());
            }
            @Override public Iterator<ChatEvent> chatStream(ChatRequest r) { return null; }
        };
        var reliable = new ReliableProvider(
                List.of(p), 0, 1,
                Map.of("model-a", List.of("model-b")));

        var resp = reliable.chat(req("model-a"));
        assertEquals("model-b", resp.model());
    }

    @Test
    void skipsRetriesOnNonRetryable() {
        var p1 = new MockProvider("p1", Integer.MAX_VALUE, "401 Unauthorized");
        var p2 = new MockProvider("p2", 0, "");
        var reliable = new ReliableProvider(List.of(p1, p2), 5, 1);

        var resp = reliable.chat(req("test"));
        assertEquals("ok from p2", resp.content());
        // p1 should be called only once (non-retryable skips retries)
        assertEquals(1, p1.calls.get());
    }
}
