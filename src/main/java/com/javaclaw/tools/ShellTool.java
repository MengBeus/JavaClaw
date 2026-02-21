package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.approval.DangerousOperation;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.security.ToolExecutor;
import com.javaclaw.shared.config.SandboxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DangerousOperation(reason = "Executes arbitrary shell commands")
public class ShellTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ToolExecutor executor;
    private final long timeoutSeconds;
    private final SecurityPolicy securityPolicy;

    public ShellTool(ToolExecutor executor, SecurityPolicy securityPolicy) {
        this(executor, SandboxConfig.defaults().timeoutSeconds(), securityPolicy);
    }

    public ShellTool(ToolExecutor executor, long timeoutSeconds, SecurityPolicy securityPolicy) {
        this.executor = executor;
        this.timeoutSeconds = timeoutSeconds;
        this.securityPolicy = securityPolicy;
    }

    @Override public String name() { return "shell"; }

    @Override public String description() {
        return "Execute a shell command";
    }

    @Override public JsonNode inputSchema() {
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                        MAPPER.createObjectNode().set("command",
                                MAPPER.createObjectNode().put("type", "string")))
                .set("required", MAPPER.createArrayNode().add("command"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            securityPolicy.checkRateLimit("shell");
            var command = input.get("command").asText();
            var risk = securityPolicy.classifyCommand(command);
            log.info("Shell command risk={}: {}", risk, command);
            var env = securityPolicy.sanitizedEnv();
            var result = executor.execute(command, ctx.workDir(), timeoutSeconds, "shell", env);
            return new ToolResult(result.output(), result.isError());
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult(e.getMessage(), true);
        }
    }
}
