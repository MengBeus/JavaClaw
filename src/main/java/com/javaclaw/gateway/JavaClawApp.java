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
import com.javaclaw.providers.ProviderRouter;
import com.javaclaw.shared.config.ConfigLoader;
import com.javaclaw.security.DockerExecutor;
import com.javaclaw.security.RestrictedNativeExecutor;
import com.javaclaw.sessions.PostgresSessionStore;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.OutboundMessage;
import com.javaclaw.tools.FileReadTool;
import com.javaclaw.tools.FileWriteTool;
import com.javaclaw.tools.MemoryRecallTool;
import com.javaclaw.tools.MemoryStoreTool;
import com.javaclaw.tools.ShellTool;
import com.javaclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;

@SpringBootApplication(scanBasePackages = "com.javaclaw")
public class JavaClawApp {

    private static final Logger log = LoggerFactory.getLogger(JavaClawApp.class);

    public static void main(String[] args) {
        var ctx = SpringApplication.run(JavaClawApp.class, args);
        var config = ConfigLoader.load();

        // Provider
        var apiKey = config.apiKeys().getOrDefault("deepseek", "");
        if (apiKey.isBlank()) {
            log.warn("DeepSeek API key not configured. Set api-keys.deepseek in ~/.javaclaw/config.yaml");
        }
        var router = new ProviderRouter();
        router.register(new DeepSeekProvider(apiKey));
        router.register(new OllamaProvider("qwen3:4b"));
        router.setPrimary("ollama");

        // Tools
        var workDir = System.getenv().getOrDefault("JAVACLAW_WORK_DIR", System.getProperty("user.home"));
        var docker = new DockerExecutor(config.sandbox());
        var dockerAvailable = docker.isAvailable();
        var executor = dockerAvailable ? docker
                : new RestrictedNativeExecutor(Set.of(workDir));
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ShellTool(executor));
        toolRegistry.register(new FileReadTool());
        toolRegistry.register(new FileWriteTool());

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
        var agent = new DefaultAgentOrchestrator(router, toolRegistry, workDir, sessionStore, approvalInterceptor, costTracker);

        // Memory
        var embeddingBaseUrl = config.apiKeys().getOrDefault("embedding-base-url", "http://localhost:11434/v1");
        var embeddingModel = config.apiKeys().getOrDefault("embedding-model", "nomic-embed-text");
        var embeddingService = new EmbeddingService(embeddingBaseUrl, apiKey, embeddingModel);
        var indexPath = System.getProperty("user.home") + "/.javaclaw/index";
        try {
            var memoryStore = new LuceneMemoryStore(embeddingService, indexPath);
            toolRegistry.register(new MemoryStoreTool(memoryStore));
            toolRegistry.register(new MemoryRecallTool(memoryStore));
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
            if (registry.allStopped()) {
                ctx.close();
            }
        });
        registry.register(cli);

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
}
