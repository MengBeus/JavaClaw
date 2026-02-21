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
        try {
            return doChat(request);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

        var respBody = resp.body().trim();
        if (respBody.startsWith("{")) {
            return parseResponse(mapper.readTree(respBody));
        }
        return parseSSE(respBody);
    }

    private ChatResponse parseResponse(JsonNode root) {
        var choice = root.path("choices").path(0).path("message");
        var content = choice.path("content").asText(null);
        var model = root.path("model").asText(null);
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
        return new ChatResponse(model, content != null ? content : "", usage, toolCalls);
    }

    private ChatResponse parseSSE(String sse) throws Exception {
        var contentBuf = new StringBuilder();
        String model = null;
        Map<String, Integer> usage = Map.of("promptTokens", 0, "completionTokens", 0);
        // index -> (id, name, argsBuf)
        var toolCallMap = new LinkedHashMap<Integer, String[]>();
        var toolCallArgs = new LinkedHashMap<Integer, StringBuilder>();

        for (var line : sse.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            var data = line.substring(5).trim();
            if ("[DONE]".equals(data)) break;

            var node = mapper.readTree(data);
            if (model == null) model = node.path("model").asText(null);

            var u = node.path("usage");
            if (!u.isMissingNode() && u.has("prompt_tokens")) {
                usage = Map.of(
                        "promptTokens", u.path("prompt_tokens").asInt(0),
                        "completionTokens", u.path("completion_tokens").asInt(0));
            }

            var delta = node.path("choices").path(0).path("delta");
            var c = delta.path("content").asText(null);
            if (c != null) contentBuf.append(c);

            var tcs = delta.path("tool_calls");
            if (tcs.isArray()) {
                for (var tc : tcs) {
                    int idx = tc.path("index").asInt(0);
                    var id = tc.path("id").asText(null);
                    var fn = tc.path("function");
                    var name = fn.path("name").asText(null);
                    if (id != null && !toolCallMap.containsKey(idx)) {
                        toolCallMap.put(idx, new String[]{id, name});
                        toolCallArgs.put(idx, new StringBuilder());
                    }
                    var args = fn.path("arguments").asText(null);
                    if (args != null && toolCallArgs.containsKey(idx)) {
                        toolCallArgs.get(idx).append(args);
                    }
                }
            }
        }

        var toolCalls = new java.util.ArrayList<ToolCallInfo>();
        for (var entry : toolCallMap.entrySet()) {
            var v = entry.getValue();
            toolCalls.add(new ToolCallInfo(v[0], v[1],
                    toolCallArgs.get(entry.getKey()).toString()));
        }

        return new ChatResponse(model, contentBuf.toString(), usage, toolCalls);
    }
}
