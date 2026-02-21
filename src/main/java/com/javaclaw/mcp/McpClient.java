package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a single MCP Server subprocess, communicates via JSON-RPC 2.0 over stdio.
 */
public class McpClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    public McpClient(String name, String command, List<String> args, Map<String, String> env) {
        this.name = name;
        this.command = command;
        this.args = args != null ? args : List.of();
        this.env = env != null ? env : Map.of();
    }

    public String name() { return name; }

    public void start() throws IOException {
        var cmd = new ArrayList<String>();
        cmd.add(command);
        cmd.addAll(args);

        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        env.forEach((k, v) -> pb.environment().put(k, v));

        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        // Drain stderr in background to prevent blocking
        Thread.startVirtualThread(() -> {
            try (var err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[mcp:{}:stderr] {}", name, line);
                }
            } catch (IOException ignored) {}
        });

        initialize();
    }

    private void initialize() throws IOException {
        var params = MAPPER.createObjectNode();
        params.putObject("clientInfo").put("name", "javaclaw").put("version", "1.0");
        params.putObject("capabilities");
        params.put("protocolVersion", "2024-11-05");

        var result = sendRequest("initialize", params);
        if (result == null) {
            throw new IOException("MCP server " + name + " did not respond to initialize");
        }
        // Send initialized notification
        sendNotification("notifications/initialized", MAPPER.createObjectNode());
        log.info("MCP server '{}' initialized", name);
    }

    public List<McpToolDef> listTools() throws IOException {
        var result = sendRequest("tools/list", MAPPER.createObjectNode());
        var tools = new ArrayList<McpToolDef>();
        if (result != null && result.has("tools")) {
            for (var t : result.get("tools")) {
                tools.add(new McpToolDef(
                    t.get("name").asText(),
                    t.path("description").asText(""),
                    t.get("inputSchema")
                ));
            }
        }
        return tools;
    }

    public JsonNode callTool(String toolName, JsonNode arguments) throws IOException {
        var params = MAPPER.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments != null ? arguments : MAPPER.createObjectNode());
        return sendRequest("tools/call", params);
    }

    private synchronized JsonNode sendRequest(String method, JsonNode params) throws IOException {
        int id = idSeq.getAndIncrement();
        var req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        writer.write(MAPPER.writeValueAsString(req));
        writer.newLine();
        writer.flush();

        // Read response lines until we get one with matching id
        String line;
        while ((line = reader.readLine()) != null) {
            var node = MAPPER.readTree(line);
            if (node.has("id") && node.get("id").asInt() == id) {
                if (node.has("error")) {
                    log.warn("[mcp:{}] error: {}", name, node.get("error"));
                    return null;
                }
                return node.get("result");
            }
            // Skip notifications from server
        }
        return null;
    }

    private synchronized void sendNotification(String method, JsonNode params) throws IOException {
        var req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.set("params", params);

        writer.write(MAPPER.writeValueAsString(req));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("MCP server '{}' stopped", name);
        }
    }

    public record McpToolDef(String name, String description, JsonNode inputSchema) {}
}
