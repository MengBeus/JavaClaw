package com.javaclaw.agent;

import com.javaclaw.providers.ModelProvider;
import com.javaclaw.sessions.SessionStore;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.AgentResponse;
import com.javaclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final AgentLoop agentLoop;
    private final Classifier classifier;
    private final SessionStore sessionStore;

    public DefaultAgentOrchestrator(ModelProvider provider, ToolRegistry toolRegistry,
                                    String workDir, SessionStore sessionStore) {
        this.agentLoop = new AgentLoop(provider, new PromptBuilder(), toolRegistry, workDir);
        this.classifier = new Classifier();
        this.sessionStore = sessionStore;
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        if (!classifier.needsLlm(request.message())) {
            return new AgentResponse(request.message(), List.of(), Map.of());
        }
        var history = new ArrayList<>(sessionStore.load(request.sessionId()));
        var response = agentLoop.execute(request.message(), history, request.sessionId());
        history.add(Map.of("role", "user", "content", request.message()));
        history.add(Map.of("role", "assistant", "content", response.content()));
        sessionStore.save(request.sessionId(), null, null, history);
        return response;
    }
}
