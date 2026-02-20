package com.javaclaw.gateway;

import com.javaclaw.agent.DefaultAgentOrchestrator;
import com.javaclaw.channels.ChannelRegistry;
import com.javaclaw.channels.CliAdapter;
import com.javaclaw.providers.DeepSeekProvider;
import com.javaclaw.providers.ProviderRouter;
import com.javaclaw.shared.config.ConfigLoader;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.OutboundMessage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication(scanBasePackages = "com.javaclaw")
public class JavaClawApp {
    public static void main(String[] args) {
        var ctx = SpringApplication.run(JavaClawApp.class, args);
        var config = ConfigLoader.load();

        // Provider
        var router = new ProviderRouter();
        var apiKey = config.apiKeys().getOrDefault("deepseek", "");
        router.register(new DeepSeekProvider(apiKey));

        // Agent
        var agent = new DefaultAgentOrchestrator(router);

        // Channel
        var registry = new ChannelRegistry();
        var cli = new CliAdapter();
        registry.register(cli);

        registry.startAll(msg -> {
            var response = agent.run(new AgentRequest(
                    msg.senderId(), msg.content(), Map.of()));
            cli.send(new OutboundMessage(msg.channelId(), response.content(), Map.of()));
        });
    }
}
