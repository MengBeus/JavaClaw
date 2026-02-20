package com.javaclaw.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private final PromptBuilder builder = new PromptBuilder();

    @Test
    void buildsSystemAndUserMessage() {
        var messages = builder.build("hello", null);
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("hello", messages.get(1).get("content"));
    }

    @Test
    void includesHistory() {
        var history = List.of(
            Map.of("role", "user", "content", "hi"),
            Map.of("role", "assistant", "content", "hello")
        );
        var messages = builder.build("next", history);
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("hi", messages.get(1).get("content"));
        assertEquals("hello", messages.get(2).get("content"));
        assertEquals("next", messages.get(3).get("content"));
    }

    @Test
    void rejectsNullMessage() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(null, null));
    }

    @Test
    void rejectsBlankMessage() {
        assertThrows(IllegalArgumentException.class, () -> builder.build("  ", null));
    }

    @Test
    void handlesHistoryWithMissingKeys() {
        var history = List.of(Map.of("role", "user"));  // 缺 content
        var messages = builder.build("test", history);
        assertEquals(3, messages.size());
        assertNull(messages.get(1).get("content"));
    }

    @Test
    void handlesEmptyHistory() {
        var messages = builder.build("test", List.of());
        assertEquals(2, messages.size());
    }
}
