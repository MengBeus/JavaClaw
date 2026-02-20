package com.javaclaw.sessions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PostgresSessionStore implements SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DataSource dataSource;

    public PostgresSessionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(String sessionId, String userId, String channelId, List<Map<String, Object>> messages) {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertSession(conn, sessionId, userId, channelId);
                deleteMessages(conn, sessionId);
                insertMessages(conn, sessionId, messages);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session: " + sessionId, e);
        }
    }

    @Override
    public List<Map<String, Object>> load(String sessionId) {
        var sql = "SELECT role, content, tool_call_id, metadata FROM chat_messages WHERE session_id = ? ORDER BY id";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            var rs = ps.executeQuery();
            var messages = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                var msg = new LinkedHashMap<String, Object>();
                msg.put("role", rs.getString("role"));
                var content = rs.getString("content");
                if (content != null) msg.put("content", content);
                var toolCallId = rs.getString("tool_call_id");
                if (toolCallId != null) msg.put("tool_call_id", toolCallId);
                var metadata = rs.getString("metadata");
                if (metadata != null) {
                    var extra = MAPPER.readValue(metadata, Map.class);
                    msg.putAll(extra);
                }
                messages.add(msg);
            }
            return messages;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load session: " + sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    private void upsertSession(Connection conn, String sessionId, String userId, String channelId) throws SQLException {
        var sql = "INSERT INTO sessions (id, user_id, channel_id) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setString(2, userId);
            ps.setString(3, channelId);
            ps.executeUpdate();
        }
    }

    private void deleteMessages(Connection conn, String sessionId) throws SQLException {
        try (var ps = conn.prepareStatement("DELETE FROM chat_messages WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    private void insertMessages(Connection conn, String sessionId, List<Map<String, Object>> messages)
            throws SQLException, JsonProcessingException {
        var sql = "INSERT INTO chat_messages (session_id, role, content, tool_call_id, metadata) VALUES (?, ?, ?, ?, ?::jsonb)";
        try (var ps = conn.prepareStatement(sql)) {
            for (var msg : messages) {
                ps.setString(1, sessionId);
                ps.setString(2, (String) msg.get("role"));
                ps.setString(3, (String) msg.get("content"));
                ps.setString(4, (String) msg.get("tool_call_id"));
                var extra = extractMetadata(msg);
                ps.setString(5, extra.isEmpty() ? null : MAPPER.writeValueAsString(extra));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private Map<String, Object> extractMetadata(Map<String, Object> msg) {
        var extra = new LinkedHashMap<>(msg);
        extra.remove("role");
        extra.remove("content");
        extra.remove("tool_call_id");
        return extra;
    }
}
