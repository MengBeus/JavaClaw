package com.javaclaw.sessions;

import java.util.List;
import java.util.Map;

public interface SessionStore {
    void save(String sessionId, String userId, String channelId, List<Map<String, Object>> messages);
    List<Map<String, Object>> load(String sessionId);
    void delete(String sessionId);
}
