package com.javaclaw.agent;

import com.javaclaw.approval.ApprovalInterceptor;
import com.javaclaw.memory.MemoryStore;
import com.javaclaw.observability.CostTracker;
import com.javaclaw.providers.ModelProvider;
import com.javaclaw.sessions.SessionStore;
import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.AgentResponse;
import com.javaclaw.skills.SkillRegistry;
import com.javaclaw.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final AgentLoop agentLoop;
    private final Classifier classifier;
    private final SessionStore sessionStore;
    private final CostTracker costTracker;
    private SkillRegistry skillRegistry;

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
    }

    public void setMemoryStore(MemoryStore memoryStore) {
        this.agentLoop.setMemoryStore(memoryStore);
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
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
        // Skill detection: /trigger message → override system prompt + tool subset
        String systemPrompt = null;
        List<String> allowedTools = null;
        var message = request.message();
        if (skillRegistry != null) {
            var skill = skillRegistry.match(message);
            if (skill != null) {
                systemPrompt = skill.systemPrompt();
                allowedTools = skill.tools();
                // Strip /trigger prefix from message
                var parts = message.split("\\s", 2);
                message = parts.length > 1 ? parts[1] : "请根据你的角色开始对话";
            }
        }

        var response = agentLoop.execute(message, history, request.sessionId(), channelId, userId,
                systemPrompt, allowedTools);
        sessionStore.save(request.sessionId(), userId, channelId, history);
        if (costTracker != null && response.usage() != null) {
            var model = response.model() != null ? response.model() : "unknown";
            costTracker.record(request.sessionId(), model, model,
                    response.usage().getOrDefault("promptTokens", 0),
                    response.usage().getOrDefault("completionTokens", 0));
        }
        return response;
    }
}
