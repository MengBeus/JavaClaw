package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a single MCP Server subprocess, communicates via JSON-RPC 2.0 over stdio.
 * Uses Content-Length framing (like LSP) per MCP spec.
 */
public class McpClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final String name;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final AtomicInteger idSeq = new AtomicInteger(1);
    private final ConcurrentMap<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private Process process;
    private OutputStream out;
    private InputStream in;

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
        out = new BufferedOutputStream(process.getOutputStream());
        in = new BufferedInputStream(process.getInputStream());

        // Drain stderr in background
        Thread.startVirtualThread(() -> {
            try (var err = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[mcp:{}:stderr] {}", name, line);
                }
            } catch (IOException ignored) {}
        });

        // Background reader: dispatches responses to pending futures
        Thread.startVirtualThread(this::readLoop);

        initialize();
    }

    private void readLoop() {
        try {
            while (process.isAlive()) {
                var msg = readMessage();
                if (msg == null) break;
                if (msg.has("id") && !msg.get("id").isNull()) {
                    var future = pending.remove(msg.get("id").asInt());
                    if (future != null) future.complete(msg);
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) log.debug("[mcp:{}] read loop error: {}", name, e.getMessage());
        } finally {
            pending.values().forEach(f -> f.complete(null));
            pending.clear();
        }
    }

    private JsonNode readMessage() throws IOException {
        int contentLength = -1;
        String headerLine;
        while ((headerLine = readHeaderLine()) != null) {
            if (headerLine.isEmpty()) break;
            if (headerLine.startsWith("Content-Length: ")) {
                contentLength = Integer.parseInt(headerLine.substring(16).trim());
            }
        }
        if (contentLength < 0) return null;
        byte[] body = in.readNBytes(contentLength);
        if (body.length < contentLength) return null;
        return MAPPER.readTree(body);
    }

    private String readHeaderLine() throws IOException {
        var sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) return null;
            if (b == '\n' && prev == '\r') {
                sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) b);
            prev = b;
        }
    }

    private synchronized void writeMessage(JsonNode msg) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(msg);
        var header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(header);
        out.write(body);
        out.flush();
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

    private JsonNode sendRequest(String method, JsonNode params) throws IOException {
        int id = idSeq.getAndIncrement();
        var req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        writeMessage(req);

        try {
            var response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response == null) return null;
            if (response.has("error")) {
                log.warn("[mcp:{}] error: {}", name, response.get("error"));
                return null;
            }
            return response.get("result");
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("MCP request '" + method + "' timed out after " + REQUEST_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException | InterruptedException e) {
            pending.remove(id);
            throw new IOException("MCP request '" + method + "' failed: " + e.getMessage(), e);
        }
    }

    private void sendNotification(String method, JsonNode params) throws IOException {
        var req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        req.set("params", params);
        writeMessage(req);
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
