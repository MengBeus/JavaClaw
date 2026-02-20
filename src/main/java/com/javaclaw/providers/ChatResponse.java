package com.javaclaw.providers;

import java.util.Map;

public record ChatResponse(
    String content,
    Map<String, Integer> usage
) {}
