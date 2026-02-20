package com.javaclaw.channels;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelRegistry {

    private final Map<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();

    public void register(ChannelAdapter adapter) {
        if (adapters.containsKey(adapter.id())) {
            throw new IllegalArgumentException("Duplicate channel adapter: " + adapter.id());
        }
        adapters.put(adapter.id(), adapter);
    }

    public ChannelAdapter get(String id) {
        return adapters.get(id);
    }

    public void startAll(MessageSink sink) {
        adapters.values().forEach(a -> a.start(sink));
    }

    public void stopAll() {
        adapters.values().forEach(ChannelAdapter::stop);
    }

    public void unregister(String id) {
        adapters.remove(id);
    }

    public boolean allStopped() {
        return adapters.isEmpty();
    }
}
