package com.javaclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.approval.DangerousOperation;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.shared.config.ToolsConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.Set;

@DangerousOperation(reason = "Browser navigation may access external resources")
public class BrowserTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_ACTIONS = Set.of("navigate", "screenshot");

    private final SecurityPolicy securityPolicy;
    private final ToolsConfig.BrowserConfig config;

    public BrowserTool(SecurityPolicy securityPolicy, ToolsConfig.BrowserConfig config) {
        this.securityPolicy = securityPolicy;
        this.config = config;
    }

    @Override public String name() { return "browser"; }

    @Override public String description() {
        return "Browse web pages. Actions: navigate (get text content), screenshot (capture page image).";
    }

    @Override public JsonNode inputSchema() {
        var props = MAPPER.createObjectNode();
        props.set("url", MAPPER.createObjectNode().put("type", "string").put("description", "URL to visit (HTTPS only)"));
        props.set("action", MAPPER.createObjectNode().put("type", "string").put("description", "navigate or screenshot"));
        return MAPPER.createObjectNode()
                .put("type", "object")
                .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties", props)
                .set("required", MAPPER.createArrayNode().add("url").add("action"));
    }

    @Override
    public ToolResult execute(ToolContext ctx, JsonNode input) {
        try {
            securityPolicy.checkRateLimit("browser");

            var url = input.path("url").asText("");
            if (url.isBlank()) return new ToolResult("url is required", true);
            if (!url.startsWith("https://")) return new ToolResult("Only HTTPS URLs are allowed", true);

            securityPolicy.validateDomain(url);

            var action = input.path("action").asText("");
            if (!ALLOWED_ACTIONS.contains(action)) {
                return new ToolResult("Unsupported action: " + action + ". Use 'navigate' or 'screenshot'.", true);
            }

            try (var pw = Playwright.create();
                 var browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
                var page = browser.newPage();
                page.setDefaultTimeout(config.timeoutSeconds() * 1000.0);
                page.navigate(url);

                if ("screenshot".equals(action)) {
                    var path = Path.of(ctx.workDir(), "screenshot-" + System.currentTimeMillis() + ".png");
                    page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));
                    return new ToolResult("Screenshot saved: " + path, false);
                }

                var text = page.innerText("body");
                if (text.length() > 50_000) {
                    text = text.substring(0, 50_000) + "\n[TRUNCATED]";
                }
                return new ToolResult(text, false);
            }
        } catch (SecurityException e) {
            return new ToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new ToolResult("Browser failed: " + e.getMessage(), true);
        }
    }
}
