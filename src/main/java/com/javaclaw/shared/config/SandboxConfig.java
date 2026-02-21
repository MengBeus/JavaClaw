package com.javaclaw.shared.config;

import java.util.List;

public record SandboxConfig(
    String memory,
    String cpus,
    int pidsLimit,
    long timeoutSeconds,
    List<String> networkWhitelist
) {
    public static SandboxConfig defaults() {
        return new SandboxConfig("256m", "0.5", 64, 30, List.of());
    }
}
