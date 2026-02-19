package com.javaclaw.shared.model;

public record ChatMessage(
    String role,
    String content,
    String toolCallId
) {}
