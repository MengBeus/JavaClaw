package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.shared.config.ToolsConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

public class HttpRequestTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "set-cookie", "authorization", "www-authenticate");

    private final SecurityPolicy securityPolicy;
    private final ToolsConfig.HttpRequestConfig config;

    public HttpRequestTool(SecurityPolicy securityPolicy, ToolsConfig.HttpRequestConfig config) {
        this.securityPolicy = securityPolicy;
        this.config = config;
    }

    @Override public String name() { return "http_request"; }

    @Override public String description() {
        return "Make HTTP requests. Methods: GET, POST, PUT, DELETE, PATCH, HEAD";
    }

    @Override public JsonNode inputSchema() {
        var props = MAPPER.createObjectNode();
        props.set("url", MAPPER.createObjectNode().put("type", "string"));
        props.set("method", MAPPER.createObjectNode().put("type", "string").put("default", "GET"));
        props.set("headers", MAPPER.createObjectNode().put("type", "object"));
        props.set("body", MAPPER.createObjectNode().put("type", "string"));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties", props)
                .set("required", MAPPER.createArrayNode().add("url"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            securityPolicy.checkRateLimit("http_request");

            var url = input.get("url").asText();
            securityPolicy.validateDomain(url);

            var method = input.path("method").asText("GET").toUpperCase();
            if (!ALLOWED_METHODS.contains(method)) {
                return new ToolResult("Unsupported method: " + method, true);
            }

            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()));

            // Headers
            var headers = input.path("headers");
            if (headers.isObject()) {
                var it = headers.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    builder.header(entry.getKey(), entry.getValue().asText());
                }
            }

            // Method + body
            var body = input.path("body").asText("");
            var bodyPub = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, bodyPub);

            var client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .build();

            // Manual redirect loop with per-hop SSRF validation
            var currentReq = builder.build();
            HttpResponse<String> response = null;
            for (int hops = 0; hops < 5; hops++) {
                response = client.send(currentReq, HttpResponse.BodyHandlers.ofString());
                int sc = response.statusCode();
                if (sc < 300 || sc >= 400) break;
                var location = response.headers().firstValue("location").orElse(null);
                if (location == null) break;
                var redirectUri = currentReq.uri().resolve(location);
                securityPolicy.validateDomain(redirectUri.toString());
                currentReq = HttpRequest.newBuilder(redirectUri)
                        .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                        .GET().build();
            }

            return new ToolResult(formatResponse(response), false);
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }

    private String formatResponse(HttpResponse<String> resp) {
        var sb = new StringBuilder();
        sb.append("HTTP ").append(resp.statusCode()).append("\n");

        resp.headers().map().forEach((k, vals) -> {
            if (!SENSITIVE_HEADERS.contains(k.toLowerCase())) {
                vals.forEach(v -> sb.append(k).append(": ").append(v).append("\n"));
            } else {
                sb.append(k).append(": [REDACTED]\n");
            }
        });

        sb.append("\n");
        var bodyStr = resp.body();
        if (bodyStr.length() > config.maxResponseSize()) {
            sb.append(bodyStr, 0, config.maxResponseSize());
            sb.append("\n[TRUNCATED]");
        } else {
            sb.append(bodyStr);
        }
        return sb.toString();
    }
}
