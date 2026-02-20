package com.javaclaw.security;

public record ExecutionResult(String output, int exitCode) {
    public boolean isError() {
        return exitCode != 0 || output.startsWith("[BLOCKED]") || output.startsWith("[TIMEOUT]");
    }
}
