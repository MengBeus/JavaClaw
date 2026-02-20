package com.javaclaw.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class OpenAiCompatibleProvider implements ModelProvider {

    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    protected OpenAiCompatibleProvider(String apiKey, String baseUrl, String defaultModel) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return ResilientCall.execute(() -> doChat(request));
    }

    @Override
    public Iterator<ChatEvent> chatStream(ChatRequest request) {
        ChatResponse resp = chat(request);
        return List.of(new ChatEvent(resp.content(), true)).iterator();
    }

    private ChatResponse doChat(ChatRequest request) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model() != null ? request.model() : defaultModel);
        body.put("messages", request.messages());
        body.put("temperature", request.temperature());
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools());
        }

        var json = mapper.writeValueAsString(body);

        var httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        var resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("LLM API error " + resp.statusCode() + ": " + resp.body());
        }

        return parseResponse(mapper.readTree(resp.body()));
    }

    private ChatResponse parseResponse(JsonNode root) {
        var choice = root.path("choices").path(0).path("message");
        var content = choice.path("content").asText(null);
        var u = root.path("usage");
        var usage = Map.of(
                "promptTokens", u.path("prompt_tokens").asInt(0),
                "completionTokens", u.path("completion_tokens").asInt(0)
        );
        var toolCalls = new java.util.ArrayList<ToolCallInfo>();
        var tcNode = choice.path("tool_calls");
        if (tcNode.isArray()) {
            for (var tc : tcNode) {
                var fn = tc.path("function");
                toolCalls.add(new ToolCallInfo(
                        tc.path("id").asText(),
                        fn.path("name").asText(),
                        fn.path("arguments").asText("")));
            }
        }
        return new ChatResponse(content != null ? content : "", usage, toolCalls);
    }
}
