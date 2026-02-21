package com.javaclaw.security;

import java.util.Map;

public interface ToolExecutor {
    ExecutionResult execute(String command, String workDir, long timeoutSeconds, String toolName);

    default ExecutionResult execute(String command, String workDir, long timeoutSeconds, String toolName, Map<String, String> env) {
        return execute(command, workDir, timeoutSeconds, toolName);
    }

    boolean isAvailable();
}
