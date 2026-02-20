package com.javaclaw.gateway;

import com.javaclaw.agent.DefaultAgentOrchestrator;
import com.javaclaw.approval.ApprovalInterceptor;
import com.javaclaw.approval.CliApprovalStrategy;
import com.javaclaw.channels.ChannelAdapter;
import com.javaclaw.channels.ChannelRegistry;
import com.javaclaw.channels.CliAdapter;
import com.javaclaw.providers.DeepSeekProvider;
import com.javaclaw.providers.ProviderRouter;
import com.javaclaw.shared.config.ConfigLoader;
import com.javaclaw.security.DockerExecutor;
import com.javaclaw.security.RestrictedNativeExecutor;
import com.javaclaw.sessions.PostgresSessionStore;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.OutboundMessage;
import com.javaclaw.tools.FileReadTool;
import com.javaclaw.tools.FileWriteTool;
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

        // Tools
        var workDir = System.getProperty("user.dir");
        var docker = new DockerExecutor();
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
            approvalInterceptor.setDefault((name, arguments) -> true);
        } else if (config.allowNativeFallback()) {
            // Tier 2: 无 Docker + 配置了 allow-native-fallback，走正常审批
            log.warn("Docker unavailable, dangerous tools will require explicit approval");
            approvalInterceptor.setDefault(new CliApprovalStrategy(stdinReader, System.out));
        } else {
            // Tier 3: 无 Docker + 未配置，审批时额外警告无沙箱保护
            log.warn("Docker unavailable, dangerous tools will require explicit approval");
            var cliStrategy = new CliApprovalStrategy(stdinReader, System.out);
            approvalInterceptor.setDefault((name, arguments) -> {
                System.out.println("[无沙箱保护] 当前无 Docker 沙箱，命令将直接在宿主机执行");
                return cliStrategy.approve(name, arguments);
            });
        }

        // Session + Agent
        var sessionStore = new PostgresSessionStore(ctx.getBean(javax.sql.DataSource.class));
        var agent = new DefaultAgentOrchestrator(router, toolRegistry, workDir, sessionStore, approvalInterceptor);

        // Channel
        var registry = new ChannelRegistry();
        var cli = new CliAdapter(stdinReader);
        cli.onStop(() -> {
            registry.stopAll();
            ctx.close();
        });
        registry.register(cli);

        registry.startAll(msg -> {
            var response = agent.run(new AgentRequest(
                    msg.senderId(), msg.content(),
                    Map.of("userId", msg.senderId(), "channelId", msg.channelId())));
            ChannelAdapter ch = registry.get(msg.channelId());
            if (ch != null) {
                ch.send(new OutboundMessage(msg.channelId(), response.content(), Map.of()));
            }
        });
    }
}
