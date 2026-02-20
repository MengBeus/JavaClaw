package com.javaclaw.providers;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProviderRouter implements ModelProvider {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();
    private String primaryId;

    public void register(ModelProvider provider) {
        providers.put(provider.id(), provider);
        if (primaryId == null) primaryId = provider.id();
    }

    public void setPrimary(String id) {
        if (!providers.containsKey(id)) throw new IllegalArgumentException("Unknown provider: " + id);
        this.primaryId = id;
    }

    @Override
    public String id() {
        return "router";
    }

    private ModelProvider resolve() {
        ModelProvider p = primaryId != null ? providers.get(primaryId) : null;
        if (p == null) throw new IllegalStateException("No provider registered");
        return p;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return resolve().chat(request);
    }

    @Override
    public Iterator<ChatEvent> chatStream(ChatRequest request) {
        return resolve().chatStream(request);
    }
}
