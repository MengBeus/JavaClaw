package com.javaclaw.memory;

import java.util.Map;

public record MemoryResult(String id, String content, double score, Map<String, Object> metadata) {}
