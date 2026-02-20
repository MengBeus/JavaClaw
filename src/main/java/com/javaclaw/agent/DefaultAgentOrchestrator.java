package com.javaclaw.agent;

import com.javaclaw.providers.ModelProvider;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.AgentResponse;
import com.javaclaw.tools.ToolContext;
import com.javaclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final AgentLoop agentLoop;
    private final Classifier classifier;
    private final ConcurrentHashMap<String, List<Map<String, Object>>> sessions = new ConcurrentHashMap<>();

    public DefaultAgentOrchestrator(ModelProvider provider, ToolRegistry toolRegistry, String workDir) {
        var ctx = new ToolContext(workDir, null, Set.of());
        this.agentLoop = new AgentLoop(provider, new PromptBuilder(), toolRegistry, ctx);
        this.classifier = new Classifier();
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        if (!classifier.needsLlm(request.message())) {
            return new AgentResponse(request.message(), List.of(), Map.of());
        }
        var history = sessions.computeIfAbsent(request.sessionId(), k -> new ArrayList<>());
        var response = agentLoop.execute(request.message(), history);
        history.add(Map.of("role", "user", "content", request.message()));
        history.add(Map.of("role", "assistant", "content", response.content()));
        return response;
    }
}
