package com.javaclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.providers.ChatRequest;
import com.javaclaw.providers.ChatResponse;
import com.javaclaw.providers.ModelProvider;
import com.javaclaw.shared.model.AgentResponse;
import com.javaclaw.tools.Tool;
import com.javaclaw.tools.ToolContext;
import com.javaclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentLoop {

    private static final int MAX_TOOL_ROUNDS = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelProvider provider;
    private final PromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final String workDir;

    public AgentLoop(ModelProvider provider, PromptBuilder promptBuilder,
                     ToolRegistry toolRegistry, String workDir) {
        this.provider = provider;
        this.promptBuilder = promptBuilder;
        this.toolRegistry = toolRegistry;
        this.workDir = workDir;
    }

    public AgentResponse execute(String userMessage, List<Map<String, Object>> history, String sessionId) {
        var messages = promptBuilder.build(userMessage, history);
        var tools = buildToolsDef();
        var allToolCalls = new ArrayList<Map<String, Object>>();
        history.add(Map.of("role", "user", "content", userMessage));

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            var resp = provider.chat(new ChatRequest(null, messages, 0.7, tools));
            if (!resp.hasToolCalls()) {
                history.add(Map.of("role", "assistant", "content", resp.content()));
                return new AgentResponse(resp.content(), allToolCalls, resp.usage());
            }
            var assistantMsg = buildAssistantMsg(resp);
            messages.add(assistantMsg);
            history.add(assistantMsg);
            for (var tc : resp.toolCalls()) {
                var result = executeTool(tc.name(), tc.arguments(), sessionId);
                var toolMsg = Map.<String, Object>of("role", "tool", "tool_call_id", tc.id(), "content", result);
                messages.add(toolMsg);
                history.add(toolMsg);
                allToolCalls.add(Map.of("tool", tc.name(), "input", tc.arguments(), "output", result));
            }
        }
        var finalResp = provider.chat(new ChatRequest(null, messages, 0.7));
        history.add(Map.of("role", "assistant", "content", finalResp.content()));
        return new AgentResponse(finalResp.content(), allToolCalls, finalResp.usage());
    }

    private List<Map<String, Object>> buildToolsDef() {
        if (toolRegistry == null) return null;
        var tools = new ArrayList<Map<String, Object>>();
        for (Tool t : toolRegistry.all()) {
            var fn = new LinkedHashMap<String, Object>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", MAPPER.convertValue(t.inputSchema(), Map.class));
            tools.add(Map.of("type", "function", "function", fn));
        }
        return tools.isEmpty() ? null : tools;
    }

    private Map<String, Object> buildAssistantMsg(ChatResponse resp) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "assistant");
        msg.put("content", resp.content() != null ? resp.content() : "");
        var tcList = new ArrayList<Map<String, Object>>();
        for (var tc : resp.toolCalls()) {
            tcList.add(Map.of(
                    "id", tc.id(),
                    "type", "function",
                    "function", Map.of("name", tc.name(), "arguments", tc.arguments())));
        }
        msg.put("tool_calls", tcList);
        return msg;
    }

    private String executeTool(String name, String argsJson, String sessionId) {
        if (toolRegistry == null) return "[ERROR] No tools registered";
        var tool = toolRegistry.get(name);
        if (tool == null) return "[ERROR] Unknown tool: " + name;
        try {
            var ctx = new ToolContext(workDir, sessionId, Set.of());
            var input = MAPPER.readTree(argsJson);
            var result = tool.execute(ctx, input);
            return result.isError() ? "[ERROR] " + result.output() : result.output();
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }
}
