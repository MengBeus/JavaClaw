package com.javaclaw.gateway;

import com.javaclaw.agent.DefaultAgentOrchestrator;
import com.javaclaw.channels.ChannelAdapter;
import com.javaclaw.channels.ChannelRegistry;
import com.javaclaw.channels.CliAdapter;
import com.javaclaw.providers.DeepSeekProvider;
import com.javaclaw.providers.ProviderRouter;
import com.javaclaw.shared.config.ConfigLoader;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

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

        // Agent
        var agent = new DefaultAgentOrchestrator(router);

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
