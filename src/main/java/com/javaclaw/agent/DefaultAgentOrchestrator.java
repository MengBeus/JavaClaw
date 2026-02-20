package com.javaclaw.agent;

import com.javaclaw.approval.ApprovalInterceptor;
import com.javaclaw.observability.CostTracker;
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
    private final CostTracker costTracker;
    private final String modelId;

    public DefaultAgentOrchestrator(ModelProvider provider, ToolRegistry toolRegistry,
                                    String workDir, SessionStore sessionStore,
                                    ApprovalInterceptor approvalInterceptor) {
        this(provider, toolRegistry, workDir, sessionStore, approvalInterceptor, null);
    }

    public DefaultAgentOrchestrator(ModelProvider provider, ToolRegistry toolRegistry,
                                    String workDir, SessionStore sessionStore,
                                    ApprovalInterceptor approvalInterceptor,
                                    CostTracker costTracker) {
        this.agentLoop = new AgentLoop(provider, new PromptBuilder(), toolRegistry, workDir, approvalInterceptor);
        this.classifier = new Classifier();
        this.sessionStore = sessionStore;
        this.costTracker = costTracker;
        this.modelId = provider.id();
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        if (!classifier.needsLlm(request.message())) {
            return new AgentResponse(request.message(), List.of(), Map.of());
        }
        var history = new ArrayList<>(sessionStore.load(request.sessionId()));
        var ctx = request.context() != null ? request.context() : Map.<String, Object>of();
        var userId = (String) ctx.get("userId");
        var channelId = (String) ctx.get("channelId");
        var response = agentLoop.execute(request.message(), history, request.sessionId(), channelId, userId);
        sessionStore.save(request.sessionId(), userId, channelId, history);
        if (costTracker != null && response.usage() != null) {
            costTracker.record(request.sessionId(), modelId, modelId,
                    response.usage().getOrDefault("promptTokens", 0),
                    response.usage().getOrDefault("completionTokens", 0));
        }
        return response;
    }
}
