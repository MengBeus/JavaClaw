package com.javaclaw.gateway;

import com.javaclaw.agent.DefaultAgentOrchestrator;
import com.javaclaw.approval.ApprovalInterceptor;
import com.javaclaw.approval.CliApprovalStrategy;
import com.javaclaw.approval.TelegramApprovalStrategy;
import com.javaclaw.approval.DiscordApprovalStrategy;
import com.javaclaw.channels.ChannelAdapter;
import com.javaclaw.channels.ChannelRegistry;
import com.javaclaw.channels.CliAdapter;
import com.javaclaw.channels.TelegramAdapter;
import com.javaclaw.channels.DiscordAdapter;
import com.javaclaw.auth.PairingService;
import com.javaclaw.auth.WhitelistService;
import com.javaclaw.mcp.McpManager;
import com.javaclaw.memory.EmbeddingService;
import com.javaclaw.memory.LuceneMemoryStore;
import com.javaclaw.skills.SkillLoader;
import com.javaclaw.skills.SkillRegistry;
import com.javaclaw.observability.CostTracker;
import com.javaclaw.observability.DoctorCommand;
import com.javaclaw.providers.DeepSeekProvider;
import com.javaclaw.providers.OllamaProvider;
import com.javaclaw.providers.OpenAiProvider;
import com.javaclaw.providers.ReliableProvider;
import com.javaclaw.shared.config.ConfigLoader;
import com.javaclaw.security.DockerExecutor;
import com.javaclaw.security.RestrictedNativeExecutor;
import com.javaclaw.security.SecurityPolicy;
import com.javaclaw.sessions.PostgresSessionStore;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.OutboundMessage;
import com.javaclaw.tools.FileReadTool;
import com.javaclaw.tools.FileWriteTool;
import com.javaclaw.tools.GitTool;
import com.javaclaw.tools.HttpRequestTool;
import com.javaclaw.tools.MemoryForgetTool;
import com.javaclaw.tools.MemoryRecallTool;
import com.javaclaw.tools.MemoryStoreTool;
import com.javaclaw.tools.ShellTool;
import com.javaclaw.tools.ToolRegistry;
import com.javaclaw.tools.BrowserTool;
import com.javaclaw.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.javaclaw.providers.ModelProvider;
import sun.misc.Signal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootApplication(scanBasePackages = "com.javaclaw")
public class JavaClawApp {

    private static final Logger log = LoggerFactory.getLogger(JavaClawApp.class);
    private static final long CTRL_C_CONFIRM_WINDOW_MS = 2_000;

    public static void main(String[] args) {
        var ctx = SpringApplication.run(JavaClawApp.class, args);
        var config = ConfigLoader.load();

        // Provider — build chain from config
        var providerIds = new ArrayList<String>();
        providerIds.add(config.primaryProvider());
        if (config.fallbackProviders() != null) providerIds.addAll(config.fallbackProviders());

        var providerList = new ArrayList<ModelProvider>();
        for (var pid : providerIds) {
            var p = createProvider(pid, config);
            if (p != null) providerList.add(p);
        }
        if (providerList.isEmpty()) {
            log.error("No providers configured. Set providers.primary in ~/.javaclaw/config.yaml");
            System.exit(1);
        }
        var reliable = new ReliableProvider(providerList, 2, 500);

        // Tools
        var workDir = System.getenv().getOrDefault("JAVACLAW_WORK_DIR", System.getProperty("user.home"));
        var toolsConfig = config.tools();
        var securityPolicy = new SecurityPolicy(
                java.nio.file.Path.of(workDir),
                toolsConfig.security().workspaceOnly(),
                toolsConfig.security().maxActionsPerHour(),
                toolsConfig.httpRequest().allowedDomains());
        var docker = new DockerExecutor(config.sandbox());
        var dockerAvailable = docker.isAvailable();
        var executor = dockerAvailable ? docker
                : new RestrictedNativeExecutor(Set.of(workDir));
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ShellTool(executor, config.sandbox().timeoutSeconds(), securityPolicy));
        toolRegistry.register(new FileReadTool(securityPolicy));
        toolRegistry.register(new FileWriteTool(securityPolicy));
        toolRegistry.register(new GitTool(securityPolicy));
        if (toolsConfig.httpRequest().enabled()) {
            toolRegistry.register(new HttpRequestTool(securityPolicy, toolsConfig.httpRequest()));
        }
        if (toolsConfig.webSearch().enabled()) {
            toolRegistry.register(new WebSearchTool(securityPolicy, toolsConfig.webSearch()));
        }
        if (toolsConfig.browser().enabled()) {
            toolRegistry.register(new BrowserTool(securityPolicy, toolsConfig.browser()));
        }

        // Sandbox + Approval (三级策略)
        var stdinReader = new BufferedReader(new InputStreamReader(System.in));
        var approvalInterceptor = new ApprovalInterceptor();
        if (dockerAvailable) {
            // Tier 1: Docker 沙箱可用，危险工具自动放行
            approvalInterceptor.setDefault((name, arguments, chId, sId) -> true);
        } else if (config.allowNativeFallback()) {
            // Tier 2: 无 Docker + 配置了 allow-native-fallback，走正常审批
            log.warn("Docker unavailable, dangerous tools will require explicit approval");
            approvalInterceptor.setDefault(new CliApprovalStrategy(stdinReader, System.out));
        } else {
            // Tier 3: 无 Docker + 未配置，审批时额外警告无沙箱保护
            log.warn("Docker unavailable, dangerous tools will require explicit approval");
            var cliStrategy = new CliApprovalStrategy(stdinReader, System.out);
            approvalInterceptor.setDefault((name, arguments, chId, sId) -> {
                System.out.println("[无沙箱保护] 当前无 Docker 沙箱，命令将直接在宿主机执行");
                return cliStrategy.approve(name, arguments, chId, sId);
            });
        }

        // Session + Agent
        var dataSource = ctx.getBean(javax.sql.DataSource.class);
        var sessionStore = new PostgresSessionStore(dataSource);
        var costTracker = new CostTracker(dataSource);
        var doctor = new DoctorCommand(dataSource, config.apiKeys().getOrDefault("embedding-base-url", ""));
        var agent = new DefaultAgentOrchestrator(reliable, toolRegistry, workDir, sessionStore, approvalInterceptor, costTracker);

        // Memory
        var embeddingBaseUrl = config.apiKeys().getOrDefault("embedding-base-url", "http://localhost:11434/v1");
        var embeddingModel = config.apiKeys().getOrDefault("embedding-model", "nomic-embed-text");
        var embeddingApiKey = config.apiKeys().getOrDefault("embedding-api-key", "");
        var embeddingService = new EmbeddingService(embeddingBaseUrl, embeddingApiKey, embeddingModel);
        var indexPath = System.getProperty("user.home") + "/.javaclaw/index";
        try {
            var memoryStore = new LuceneMemoryStore(embeddingService, indexPath);
            toolRegistry.register(new MemoryStoreTool(memoryStore, securityPolicy));
            toolRegistry.register(new MemoryRecallTool(memoryStore, securityPolicy));
            toolRegistry.register(new MemoryForgetTool(memoryStore, securityPolicy));
            agent.setMemoryStore(memoryStore);
            ctx.registerShutdownHook();
            Runtime.getRuntime().addShutdownHook(new Thread(memoryStore::close, "memory-close"));
            log.info("Memory store enabled at {}", indexPath);
        } catch (Exception e) {
            log.warn("Memory store unavailable: {}", e.getMessage());
        }

        // MCP
        var mcpManager = new McpManager();
        mcpManager.start(config.mcpServers(), toolRegistry);
        Runtime.getRuntime().addShutdownHook(new Thread(mcpManager::close, "mcp-close"));

        // Skills
        var skillDir = java.nio.file.Path.of(System.getProperty("user.home"), ".javaclaw", "skills");
        var skillRegistry = new SkillRegistry();
        for (var skill : SkillLoader.loadFrom(skillDir)) {
            skillRegistry.register(skill);
            log.info("Registered skill: /{}", skill.trigger());
        }
        agent.setSkillRegistry(skillRegistry);

        // Auth
        var pairingService = new PairingService();
        var whitelist = new WhitelistService(dataSource);

        // Channel
        var registry = new ChannelRegistry();
        var cli = new CliAdapter(stdinReader);
        cli.onStop(() -> {
            registry.unregister(cli.id());
        });
        registry.register(cli);
        installCtrlCShutdownHandler(registry, ctx);

        // Telegram（配置了 bot-token 才启动）
        TelegramAdapter telegramAdapter = null;
        var telegramToken = config.telegramBotToken();
        if (telegramToken != null && !telegramToken.isBlank()) {
            telegramAdapter = new TelegramAdapter(telegramToken);
            registry.register(telegramAdapter);
            log.info("Telegram channel enabled");
        }

        // Discord（配置了 bot-token 才启动）
        DiscordAdapter discordAdapter = null;
        var discordToken = config.discordBotToken();
        if (discordToken != null && !discordToken.isBlank()) {
            discordAdapter = new DiscordAdapter(discordToken);
            registry.register(discordAdapter);
            log.info("Discord channel enabled");
        }

        registry.startAll(msg -> {
            var adapterId = msg.channelId().split(":")[0];
            ChannelAdapter ch = registry.get(adapterId);
            if (ch == null) return;

            // CLI 跳过认证；远程通道需要白名单
            if (!"cli".equals(adapterId)) {
                if (!whitelist.isWhitelisted(msg.senderId(), adapterId)) {
                    // 未授权用户：尝试配对码
                    if (msg.content().matches("\\d{6}")) {
                        if (pairingService.consumeCode(msg.content().trim(), adapterId)) {
                            whitelist.add(msg.senderId(), adapterId);
                            ch.send(new OutboundMessage(msg.channelId(), "配对成功，已加入白名单。", Map.of()));
                        } else {
                            ch.send(new OutboundMessage(msg.channelId(), "配对码无效或已过期。", Map.of()));
                        }
                    } else {
                        ch.send(new OutboundMessage(msg.channelId(),
                                "未授权。请先在 CLI 执行 /pair " + adapterId + " 获取配对码，然后发送到此对话。", Map.of()));
                    }
                    return;
                }
            }

            // CLI /doctor 命令
            if ("cli".equals(adapterId) && "/doctor".equals(msg.content().trim())) {
                ch.send(new OutboundMessage(msg.channelId(), doctor.run(), Map.of()));
                return;
            }

            // CLI /pair 命令
            if ("cli".equals(adapterId) && msg.content().startsWith("/pair ")) {
                var target = msg.content().substring(6).trim();
                var code = pairingService.generateCode(target);
                ch.send(new OutboundMessage(msg.channelId(), "配对码: " + code + " (请在 " + target + " 中发送此码)", Map.of()));
                return;
            }

            var response = agent.run(new AgentRequest(
                    msg.senderId(), msg.content(),
                    Map.of("userId", msg.senderId(), "channelId", msg.channelId())));
            ch.send(new OutboundMessage(msg.channelId(), response.content(), Map.of()));
        });

        // Channel-specific approval strategies (only when no Docker sandbox)
        if (!dockerAvailable) {
            if (telegramAdapter != null) {
                var tgStrategy = new TelegramApprovalStrategy(telegramAdapter.getTelegramClient());
                telegramAdapter.setApprovalStrategy(tgStrategy);
                approvalInterceptor.register("telegram", tgStrategy);
            }
            if (discordAdapter != null) {
                var dcStrategy = new DiscordApprovalStrategy(discordAdapter.getJda());
                discordAdapter.setApprovalStrategy(dcStrategy);
                approvalInterceptor.register("discord", dcStrategy);
            }
        }
    }

    private static void installCtrlCShutdownHandler(ChannelRegistry registry, ConfigurableApplicationContext ctx) {
        try {
            var lastCtrlCAt = new AtomicLong(0L);
            Signal.handle(new Signal("INT"), sig -> {
                long now = System.currentTimeMillis();
                long prev = lastCtrlCAt.get();
                if (prev > 0 && now - prev <= CTRL_C_CONFIRM_WINDOW_MS) {
                    System.out.println();
                    log.warn("Ctrl+C confirmed; shutting down all channels and exiting");
                    registry.stopAll();
                    ctx.close();
                    return;
                }
                lastCtrlCAt.set(now);
                System.out.println();
                System.out.println("Press Ctrl+C again within 2s to stop all channels and exit.");
            });
        } catch (Throwable t) {
            log.warn("Ctrl+C double-press handler unavailable: {}", t.getMessage());
        }
    }

    private static ModelProvider createProvider(String id, com.javaclaw.shared.config.JavaClawConfig config) {
        if (id.startsWith("ollama/")) {
            return new OllamaProvider(id.substring(7));
        }
        if ("ollama".equals(id)) {
            return new OllamaProvider("qwen3:4b");
        }
        if (id.startsWith("openai/")) {
            var key = config.apiKeys().getOrDefault("openai", "");
            var baseUrl = config.apiKeys().getOrDefault("openai-base-url", "https://api.openai.com/v1");
            if (key.isBlank()) {
                LoggerFactory.getLogger(JavaClawApp.class)
                        .warn("Skipping {} — no api-keys.openai configured", id);
                return null;
            }
            return new OpenAiProvider(key, baseUrl, id.substring(7));
        }
        if (id.startsWith("deepseek")) {
            var key = config.apiKeys().getOrDefault("deepseek", "");
            if (key.isBlank()) {
                LoggerFactory.getLogger(JavaClawApp.class)
                        .warn("Skipping {} — no api-keys.deepseek configured", id);
                return null;
            }
            return new DeepSeekProvider(key);
        }
        LoggerFactory.getLogger(JavaClawApp.class).warn("Unknown provider: {}", id);
        return null;
    }
}
