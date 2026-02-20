package com.javaclaw.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PromptBuilder {

    private static final String SYSTEM_PROMPT = "You are JAVAClaw, a helpful AI assistant. Reply in the same language the user uses.";

    public List<Map<String, Object>> build(String userMessage, List<Map<String, Object>> history) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be empty");
        }
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        if (history != null) messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }
}
