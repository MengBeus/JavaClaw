package com.javaclaw.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.tools.Tool;
import com.javaclaw.tools.ToolContext;
import com.javaclaw.tools.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalInterceptorTest {

    @DangerousOperation(reason = "test")
    static class DangerousTool implements Tool {
        @Override public String name() { return "dangerous"; }
        @Override public String description() { return ""; }
        @Override public JsonNode inputSchema() { return null; }
        @Override public ToolResult execute(ToolContext ctx, JsonNode input) { return null; }
    }

    static class SafeTool implements Tool {
        @Override public String name() { return "safe"; }
        @Override public String description() { return ""; }
        @Override public JsonNode inputSchema() { return null; }
        @Override public ToolResult execute(ToolContext ctx, JsonNode input) { return null; }
    }

    @Test
    void allowsSafeToolWithoutStrategy() {
        var interceptor = new ApprovalInterceptor();
        assertTrue(interceptor.check(new SafeTool(), "{}", "cli"));
    }

    @Test
    void deniesDangerousToolWhenNoStrategy() {
        var interceptor = new ApprovalInterceptor();
        assertFalse(interceptor.check(new DangerousTool(), "{}", "cli"));
    }

    @Test
    void approvesDangerousToolWhenStrategyReturnsTrue() {
        var interceptor = new ApprovalInterceptor();
        interceptor.setDefault((name, args) -> true);
        assertTrue(interceptor.check(new DangerousTool(), "{}", "cli"));
    }

    @Test
    void deniesDangerousToolWhenStrategyReturnsFalse() {
        var interceptor = new ApprovalInterceptor();
        interceptor.setDefault((name, args) -> false);
        assertFalse(interceptor.check(new DangerousTool(), "{}", "cli"));
    }

    @Test
    void usesChannelSpecificStrategy() {
        var interceptor = new ApprovalInterceptor();
        interceptor.setDefault((name, args) -> false);
        interceptor.register("special", (name, args) -> true);

        assertFalse(interceptor.check(new DangerousTool(), "{}", "cli"));
        assertTrue(interceptor.check(new DangerousTool(), "{}", "special"));
    }
}
