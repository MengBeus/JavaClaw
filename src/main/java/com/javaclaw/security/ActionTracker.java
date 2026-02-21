package com.javaclaw.security;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ActionTracker {

    private static final long WINDOW_SECONDS = 3600;
    private final int maxActionsPerHour;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> windows = new ConcurrentHashMap<>();

    public ActionTracker(int maxActionsPerHour) {
        this.maxActionsPerHour = maxActionsPerHour;
    }

    public void track(String toolName) {
        var deque = windows.computeIfAbsent(toolName, k -> new ConcurrentLinkedDeque<>());
        var cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
        if (deque.size() >= maxActionsPerHour) {
            throw new SecurityException("Rate limit exceeded for tool '" + toolName + "': max " + maxActionsPerHour + " actions per hour");
        }
        deque.addLast(Instant.now());
    }

    public int count(String toolName) {
        var deque = windows.get(toolName);
        if (deque == null) return 0;
        var cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst();
        }
        return deque.size();
    }
}
