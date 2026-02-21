package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.shared.config.ToolsConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class WebSearchTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.+?)</a>");
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]+class=\"result__snippet\"[^>]*>(.+?)</a>");
    private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");

    private final SecurityPolicy securityPolicy;
    private final ToolsConfig.WebSearchConfig config;

    public WebSearchTool(SecurityPolicy securityPolicy, ToolsConfig.WebSearchConfig config) {
        this.securityPolicy = securityPolicy;
        this.config = config;
    }

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "Search the web using DuckDuckGo. Returns titles, URLs and snippets.";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode()
                                .set("query", MAPPER.createObjectNode()
                                        .put("type", "string")
                                        .put("description", "search query")))
                .set("required", MAPPER.createArrayNode().add("query"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            securityPolicy.checkRateLimit("web_search");
            if (!"duckduckgo".equalsIgnoreCase(config.provider())) {
                return new ToolResult("Unsupported search provider: " + config.provider() + ". Only 'duckduckgo' is supported.", true);
            }
            var query = input.path("query").asText("");
            if (query.isBlank()) return new ToolResult("query is required", true);

            var encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var url = "https://html.duckduckgo.com/html/?q=" + encoded;

            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("User-Agent", "JavaClaw/1.0")
                    .GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                return new ToolResult("Search failed: HTTP " + resp.statusCode(), true);
            }

            return new ToolResult(parseResults(resp.body()), false);
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult("Search failed: " + e.getMessage(), true);
        }
    }

    private String parseResults(String html) {
        var results = new ArrayList<String>();
        var linkMatcher = RESULT_PATTERN.matcher(html);
        var snippetMatcher = SNIPPET_PATTERN.matcher(html);

        while (linkMatcher.find() && results.size() < config.maxResults()) {
            var href = linkMatcher.group(1);
            var title = stripTags(linkMatcher.group(2));
            var snippet = snippetMatcher.find() ? stripTags(snippetMatcher.group(1)) : "";
            results.add((results.size() + 1) + ". " + title + "\n   " + href + "\n   " + snippet);
        }

        return results.isEmpty() ? "No results found." : String.join("\n\n", results);
    }

    private String stripTags(String s) {
        return TAG_STRIP.matcher(s).replaceAll("").strip();
    }
}
