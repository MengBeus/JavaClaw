package com.javaclaw.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You are JAVAClaw, an AI coding agent with direct access to the user's machine. \
            Reply in the same language the user uses. \
            You MUST use the provided tools to fulfill requests — do NOT say you cannot perform actions. \
            You can execute shell commands, read and write files, run git operations, search the web, and make HTTP requests. \
            Always prefer taking action over explaining how to do it.""";

    public List<Map<String, Object>> build(String userMessage, List<Map<String, Object>> history) {
        return build(userMessage, history, null);
    }

    public List<Map<String, Object>> build(String userMessage, List<Map<String, Object>> history, String systemPromptOverride) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be empty");
        }
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content",
                systemPromptOverride != null ? systemPromptOverride : SYSTEM_PROMPT));
        if (history != null) messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }
}
