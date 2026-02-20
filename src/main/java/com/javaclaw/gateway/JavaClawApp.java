package com.javaclaw.gateway;

import com.javaclaw.agent.DefaultAgentOrchestrator;
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
        var executor = docker.isAvailable() ? docker
                : new RestrictedNativeExecutor(Set.of(workDir));
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ShellTool(executor));
        toolRegistry.register(new FileReadTool());
        toolRegistry.register(new FileWriteTool());

        // Session + Agent
        var sessionStore = new PostgresSessionStore(ctx.getBean(javax.sql.DataSource.class));
        var agent = new DefaultAgentOrchestrator(router, toolRegistry, workDir, sessionStore);

        // Channel
        var registry = new ChannelRegistry();
        var cli = new CliAdapter();
        cli.onStop(() -> {
            registry.stopAll();
            ctx.close();
        });
        registry.register(cli);

        registry.startAll(msg -> {
            var response = agent.run(new AgentRequest(
                    msg.senderId(), msg.content(), Map.of()));
            ChannelAdapter ch = registry.get(msg.channelId());
            if (ch != null) {
                ch.send(new OutboundMessage(msg.channelId(), response.content(), Map.of()));
            }
        });
    }
}
