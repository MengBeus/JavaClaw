package com.javaclaw.providers;

import java.util.Iterator;

public interface ModelProvider {
    String id();
    ChatResponse chat(ChatRequest request);
    Iterator<ChatEvent> chatStream(ChatRequest request);
}
