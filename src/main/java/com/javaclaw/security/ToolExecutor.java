package com.javaclaw.security;

public interface ToolExecutor {
    String execute(String command, String workDir, long timeoutSeconds);
    boolean isAvailable();
}
