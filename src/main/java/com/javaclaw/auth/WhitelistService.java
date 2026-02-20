package com.javaclaw.auth;

import javax.sql.DataSource;
import java.sql.SQLException;

public class WhitelistService {

    private final DataSource dataSource;

    public WhitelistService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isWhitelisted(String userId, String channel) {
        var sql = "SELECT 1 FROM whitelist WHERE user_id = ? AND channel = ?";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, channel);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check whitelist", e);
        }
    }

    public void add(String userId, String channel) {
        var sql = "INSERT INTO whitelist (user_id, channel) VALUES (?, ?) ON CONFLICT DO NOTHING";
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, channel);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add to whitelist", e);
        }
    }
}
