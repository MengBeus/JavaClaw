package com.javaclaw.providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Decorator: retry per provider, then fallback to next provider.
 * Skips retries on non-retryable errors (4xx except 429/408).
 */
public class ReliableProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ReliableProvider.class);

    private final List<ModelProvider> providers;
    private final int maxRetries;
    private final long baseDelayMs;
    private final Map<String, List<String>> modelFallbacks;

    public ReliableProvider(List<ModelProvider> providers, int maxRetries, long baseDelayMs) {
        this(providers, maxRetries, baseDelayMs, Map.of());
    }

    public ReliableProvider(List<ModelProvider> providers, int maxRetries, long baseDelayMs,
                            Map<String, List<String>> modelFallbacks) {
        this.providers = List.copyOf(providers);
        this.maxRetries = maxRetries;
        this.baseDelayMs = Math.max(baseDelayMs, 50);
        this.modelFallbacks = Map.copyOf(modelFallbacks);
    }

    @Override
    public String id() { return "reliable"; }

    @Override
    public ChatResponse chat(ChatRequest request) {
        var models = modelChain(request.model());
        var failures = new ArrayList<String>();

        for (var model : models) {
            var req = withModel(request, model);
            for (var provider : providers) {
                try {
                    var resp = ResilientCall.execute(
                            () -> provider.chat(req), maxRetries, baseDelayMs);
                    if (!model.equals(request.model()) || providers.indexOf(provider) > 0) {
                        log.info("Recovered via provider={} model={}", provider.id(), model);
                    }
                    return resp;
                } catch (RuntimeException e) {
                    failures.add(provider.id() + "/" + model + ": " + rootMessage(e));
                    log.warn("Provider {} model {} failed, trying next", provider.id(), model);
                }
            }
        }
        throw new RuntimeException("All providers/models failed:\n" + String.join("\n", failures));
    }

    @Override
    public Iterator<ChatEvent> chatStream(ChatRequest request) {
        // Fallback: use chat() and wrap as single event
        var resp = chat(request);
        return List.of(new ChatEvent(resp.content(), true)).iterator();
    }

    private List<String> modelChain(String model) {
        var chain = new ArrayList<String>();
        chain.add(model);
        var fallbacks = modelFallbacks.get(model);
        if (fallbacks != null) chain.addAll(fallbacks);
        return chain;
    }

    private static ChatRequest withModel(ChatRequest req, String model) {
        return new ChatRequest(model, req.messages(), req.temperature(), req.tools());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
