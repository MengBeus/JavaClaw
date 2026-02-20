package com.javaclaw.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsConfigTest {

    @Test
    void registersAllMeters() {
        var config = new MetricsConfig();
        assertNotNull(config.registry());
        assertNotNull(config.llmLatency());
        assertNotNull(config.llmCalls());
        assertNotNull(config.toolExecutions());
        assertNotNull(config.tokensUsed());
    }

    @Test
    void countersIncrementCorrectly() {
        var config = new MetricsConfig();
        config.llmCalls().increment();
        config.llmCalls().increment();
        assertEquals(2.0, config.llmCalls().count());
    }
}
