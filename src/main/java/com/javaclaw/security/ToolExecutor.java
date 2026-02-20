package com.javaclaw.security;

public interface ToolExecutor {
    ExecutionResult execute(String command, String workDir, long timeoutSeconds);
    boolean isAvailable();
}
