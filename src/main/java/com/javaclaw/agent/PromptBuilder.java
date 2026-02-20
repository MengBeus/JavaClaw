package com.javaclaw.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PromptBuilder {

    private static final String SYSTEM_PROMPT = "You are JAVAClaw, a helpful AI assistant.";

    public List<Map<String, String>> build(String userMessage, List<Map<String, String>> history) {
        var messages = new ArrayList<Map<String, String>>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        if (history != null) messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }
}
