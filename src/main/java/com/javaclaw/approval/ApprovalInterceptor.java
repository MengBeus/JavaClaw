package com.javaclaw.approval;

import com.javaclaw.tools.Tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApprovalInterceptor {

    private final Map<String, ApprovalStrategy> strategies = new ConcurrentHashMap<>();
    private ApprovalStrategy defaultStrategy;

    public void register(String channelId, ApprovalStrategy strategy) {
        strategies.put(channelId, strategy);
    }

    public void setDefault(ApprovalStrategy strategy) {
        this.defaultStrategy = strategy;
    }

    public boolean check(Tool tool, String arguments, String channelId, String senderId) {
        if (!tool.getClass().isAnnotationPresent(DangerousOperation.class)) {
            return true;
        }
        var strategy = strategies.get(channelId);
        if (strategy == null) {
            strategy = strategies.get(channelId.split(":")[0]);
        }
        if (strategy == null) {
            strategy = defaultStrategy;
        }
        if (strategy == null) return false;
        return strategy.approve(tool.name(), arguments, channelId, senderId);
    }
}
