package com.javaclaw.shared.config;

import java.util.List;
import java.util.Map;

public record JavaClawConfig(
    int serverPort,
    String primaryProvider,
    List<String> fallbackProviders,
    Map<String, String> database,
    Map<String, String> apiKeys,
    boolean allowNativeFallback,
    String telegramBotToken,
    String discordBotToken,
    Map<String, Map<String, Object>> mcpServers,
    SandboxConfig sandbox,
    ToolsConfig tools
) {}
