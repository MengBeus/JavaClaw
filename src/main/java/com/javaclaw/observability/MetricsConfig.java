package com.javaclaw.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class MetricsConfig {

    private final MeterRegistry registry;

    public MetricsConfig() {
        this.registry = new SimpleMeterRegistry();
    }

    public MeterRegistry registry() { return registry; }

    public Timer llmLatency() {
        return Timer.builder("javaclaw.llm.latency").register(registry);
    }

    public Counter llmCalls() {
        return Counter.builder("javaclaw.llm.calls").register(registry);
    }

    public Counter toolExecutions() {
        return Counter.builder("javaclaw.tool.executions").register(registry);
    }

    public Counter tokensUsed() {
        return Counter.builder("javaclaw.tokens.total").register(registry);
    }
}
