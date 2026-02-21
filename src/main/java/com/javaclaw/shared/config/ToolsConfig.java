package com.javaclaw.shared.config;

import java.util.Set;

public record ToolsConfig(
    HttpRequestConfig httpRequest,
    WebSearchConfig webSearch,
    SecurityConfig security
) {
    public record HttpRequestConfig(boolean enabled, Set<String> allowedDomains,
                                     int timeoutSeconds, int maxResponseSize) {
        public static HttpRequestConfig defaults() {
            return new HttpRequestConfig(true, Set.of(), 30, 1_048_576);
        }
    }

    public record WebSearchConfig(boolean enabled, String provider,
                                   int maxResults, int timeoutSeconds) {
        public static WebSearchConfig defaults() {
            return new WebSearchConfig(true, "duckduckgo", 5, 15);
        }
    }

    public record SecurityConfig(int maxActionsPerHour, boolean workspaceOnly) {
        public static SecurityConfig defaults() {
            return new SecurityConfig(120, true);
        }
    }

    public static ToolsConfig defaults() {
        return new ToolsConfig(
            HttpRequestConfig.defaults(),
            WebSearchConfig.defaults(),
            SecurityConfig.defaults()
        );
    }
}
