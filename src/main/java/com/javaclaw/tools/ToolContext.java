package com.javaclaw.tools;

import java.util.Set;

public record ToolContext(String workDir, String sessionId, Set<String> permissions) {}
