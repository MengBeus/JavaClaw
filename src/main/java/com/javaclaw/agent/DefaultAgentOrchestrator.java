package com.javaclaw.agent;

import com.javaclaw.providers.ModelProvider;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.AgentResponse;

public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final AgentLoop agentLoop;
    private final Classifier classifier;

    public DefaultAgentOrchestrator(ModelProvider provider) {
        this.agentLoop = new AgentLoop(provider, new PromptBuilder());
        this.classifier = new Classifier();
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        if (!classifier.needsLlm(request.message())) {
            return new AgentResponse(request.message(), java.util.List.of(), java.util.Map.of());
        }
        return agentLoop.execute(request.message(), null);
    }
}
