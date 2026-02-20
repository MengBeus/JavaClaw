package com.javaclaw.agent;

import com.javaclaw.providers.ChatRequest;
import com.javaclaw.providers.ModelProvider;
import com.javaclaw.shared.model.AgentResponse;

import java.util.List;
import java.util.Map;

public class AgentLoop {

    private final ModelProvider provider;
    private final PromptBuilder promptBuilder;

    public AgentLoop(ModelProvider provider, PromptBuilder promptBuilder) {
        this.provider = provider;
        this.promptBuilder = promptBuilder;
    }

    public AgentResponse execute(String userMessage, List<Map<String, String>> history) {
        var messages = promptBuilder.build(userMessage, history);
        var resp = provider.chat(new ChatRequest(null, messages, 0.7));
        return new AgentResponse(resp.content(), List.of(), resp.usage());
    }
}
