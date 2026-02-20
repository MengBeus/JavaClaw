package com.javaclaw.providers;

public record ChatEvent(
    String delta,
    boolean done
) {}
