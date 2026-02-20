package com.javaclaw.memory;

import java.util.List;
import java.util.Map;

public interface MemoryStore {
    void store(String content, Map<String, Object> metadata);
    List<MemoryResult> recall(String query, int topK);
    void forget(String memoryId);
}
