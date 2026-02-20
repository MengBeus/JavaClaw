package com.javaclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.approval.ApprovalInterceptor;
import com.javaclaw.providers.ChatRequest;
import com.javaclaw.providers.ChatResponse;
import com.javaclaw.providers.ModelProvider;
import com.javaclaw.providers.ToolCallInfo;
import com.javaclaw.tools.Tool;
import com.javaclaw.tools.ToolContext;
import com.javaclaw.tools.ToolRegistry;
import com.javaclaw.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import com.javaclaw.providers.ChatEvent;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void returnsDirectResponseWhenNoToolCalls() {
        var provider = stubProvider(new ChatResponse("hello", Map.of()));
        var loop = new AgentLoop(provider, new PromptBuilder(), null, "/tmp", null);

        var result = loop.execute("hi", new ArrayList<>(), "s1", "cli");

        assertEquals("hello", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void executesToolCallAndReturnsResult() {
        var toolCall = new ToolCallInfo("tc1", "echo", "{\"text\":\"world\"}");
        var responses = List.of(
                new ChatResponse("", Map.of(), List.of(toolCall)),
                new ChatResponse("got it: world", Map.of())
        );
        var provider = sequentialProvider(responses);

        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        var loop = new AgentLoop(provider, new PromptBuilder(), registry, "/tmp", null);
        var result = loop.execute("say world", new ArrayList<>(), "s1", "cli");

        assertEquals("got it: world", result.content());
        assertEquals(1, result.toolCalls().size());
        assertEquals("echo", result.toolCalls().get(0).get("tool"));
    }

    @Test
    void deniedToolReturnsMarker() {
        var toolCall = new ToolCallInfo("tc1", "echo", "{\"text\":\"x\"}");
        var responses = List.of(
                new ChatResponse("", Map.of(), List.of(toolCall)),
                new ChatResponse("denied", Map.of())
        );
        var provider = sequentialProvider(responses);

        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        var interceptor = new ApprovalInterceptor();
        interceptor.setDefault((name, args) -> false);

        var loop = new AgentLoop(provider, new PromptBuilder(), registry, "/tmp", interceptor);
        var result = loop.execute("do it", new ArrayList<>(), "s1", "cli");

        var output = (String) result.toolCalls().get(0).get("output");
        assertTrue(output.startsWith("[DENIED]"));
    }

    @Test
    void appendsAllMessagesToHistory() {
        var toolCall = new ToolCallInfo("tc1", "echo", "{\"text\":\"hi\"}");
        var responses = List.of(
                new ChatResponse("", Map.of(), List.of(toolCall)),
                new ChatResponse("done", Map.of())
        );
        var provider = sequentialProvider(responses);

        var registry = new ToolRegistry();
        registry.register(new EchoTool());

        var history = new ArrayList<Map<String, Object>>();
        var loop = new AgentLoop(provider, new PromptBuilder(), registry, "/tmp", null);
        loop.execute("test", history, "s1", "cli");

        // history should have: user, assistant(tool_calls), tool, final assistant
        assertEquals(4, history.size());
        assertEquals("user", history.get(0).get("role"));
        assertEquals("assistant", history.get(1).get("role"));
        assertEquals("tool", history.get(2).get("role"));
        assertEquals("assistant", history.get(3).get("role"));
    }

    // --- helpers ---

    @com.javaclaw.approval.DangerousOperation(reason = "test")
    static class EchoTool implements Tool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public JsonNode inputSchema() {
            return MAPPER.createObjectNode().put("type", "object");
        }
        @Override public ToolResult execute(ToolContext ctx, JsonNode input) {
            return new ToolResult(input.path("text").asText(), false);
        }
    }

    private ModelProvider stubProvider(ChatResponse response) {
        return new ModelProvider() {
            @Override public String id() { return "stub"; }
            @Override public ChatResponse chat(ChatRequest req) { return response; }
            @Override public Iterator<ChatEvent> chatStream(ChatRequest req) { return null; }
        };
    }

    private ModelProvider sequentialProvider(List<ChatResponse> responses) {
        return new ModelProvider() {
            private int idx = 0;
            @Override public String id() { return "seq"; }
            @Override public ChatResponse chat(ChatRequest req) { return responses.get(idx++); }
            @Override public Iterator<ChatEvent> chatStream(ChatRequest req) { return null; }
        };
    }
}
