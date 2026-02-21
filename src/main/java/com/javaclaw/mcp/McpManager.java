package com.javaclaw.mcp;

import com.javaclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads mcp-servers config, starts McpClients, registers their tools.
 */
public class McpManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private final List<McpClient> clients = new ArrayList<>();

    public void start(Map<String, Map<String, Object>> mcpServers, ToolRegistry registry) {
        if (mcpServers == null || mcpServers.isEmpty()) return;

        for (var entry : mcpServers.entrySet()) {
            var name = entry.getKey();
            var cfg = entry.getValue();
            try {
                if (cfg == null || !cfg.containsKey("command")) {
                    log.warn("MCP server '{}': missing required 'command' field, skipping", name);
                    continue;
                }
                var command = String.valueOf(cfg.get("command"));
                var rawArgs = cfg.getOrDefault("args", List.of());
                var args = rawArgs instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.<String>of();
                var rawEnv = cfg.getOrDefault("env", Map.of());
                var env = rawEnv instanceof Map<?,?> map
                        ? map.entrySet().stream().collect(
                            java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())))
                        : Map.<String, String>of();

                var client = new McpClient(name, command, args, env);
                client.start();
                clients.add(client);

                for (var def : client.listTools()) {
                    var bridge = new McpToolBridge(client, def);
                    registry.register(bridge);
                    log.info("Registered MCP tool: {}", bridge.name());
                }
            } catch (Exception e) {
                log.warn("Failed to start MCP server '{}': {}", name, e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        clients.forEach(McpClient::close);
        clients.clear();
    }
}
