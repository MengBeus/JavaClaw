package com.javaclaw.agent;

import com.javaclaw.shared.model.AgentRequest;
import com.javaclaw.shared.model.AgentResponse;

public interface AgentOrchestrator {
    AgentResponse run(AgentRequest request);
}
