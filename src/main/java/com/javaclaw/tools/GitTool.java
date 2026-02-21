package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.approval.DangerousOperation;
import com.javaclaw.security.SecurityPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@DangerousOperation(reason = "Git write operations modify repository state")
public class GitTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_OPS = Set.of(
            "status", "diff", "log", "show", "branch", "add", "commit");
    private static final Pattern UNSAFE = Pattern.compile("[;|&$`>\\n]");

    private final SecurityPolicy securityPolicy;

    public GitTool(SecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

    @Override public String name() { return "git"; }

    @Override public String description() {
        return "Run git commands. Supported operations: status, diff, log, show, branch, add, commit";
    }

    @Override public JsonNode inputSchema() {
        var props = MAPPER.createObjectNode();
        props.set("operation", MAPPER.createObjectNode().put("type", "string")
                .put("description", "Git operation: status, diff, log, show, branch, add, commit"));
        props.set("args", MAPPER.createObjectNode().put("type", "string")
                .put("description", "Additional arguments"));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties", props)
                .set("required", MAPPER.createArrayNode().add("operation"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            securityPolicy.checkRateLimit("git");
            var op = input.path("operation").asText("");
            if (!ALLOWED_OPS.contains(op)) {
                return new ToolResult("Unsupported git operation: " + op, true);
            }
            var args = input.path("args").asText("");
            if (UNSAFE.matcher(args).find()) {
                return new ToolResult("Unsafe characters in arguments", true);
            }

            var cmd = new ArrayList<String>();
            cmd.add("git");
            cmd.add(op);
            if (!args.isBlank()) {
                for (var a : args.strip().split("\\s+")) {
                    cmd.add(a);
                }
            }

            var pb = new ProcessBuilder(cmd);
            pb.directory(new File(ctx.workDir()));
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var stdout = new java.io.ByteArrayOutputStream();
            var reader = Thread.startVirtualThread(() -> {
                try { proc.getInputStream().transferTo(stdout); } catch (Exception ignored) {}
            });
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                reader.join(5000);
                return new ToolResult("[TIMEOUT] git command exceeded 30s", true);
            }
            reader.join(5000);
            return new ToolResult(stdout.toString(), proc.exitValue() != 0);
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
