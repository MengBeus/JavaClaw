package com.javaclaw.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    // price per 1M tokens (USD)
    private static final Map<String, double[]> PRICING = Map.of(
            "deepseek-chat", new double[]{0.14, 0.28},
            "qwen3:4b", new double[]{0.0, 0.0}
    );

    private final DataSource dataSource;

    public CostTracker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void record(String sessionId, String provider, String model,
                       int promptTokens, int completionTokens) {
        var prices = PRICING.getOrDefault(model, new double[]{0, 0});
        var cost = (promptTokens * prices[0] + completionTokens * prices[1]) / 1_000_000.0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO llm_usage (session_id, provider, model, prompt_tokens, completion_tokens, cost_usd) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, provider);
            ps.setString(3, model);
            ps.setInt(4, promptTokens);
            ps.setInt(5, completionTokens);
            ps.setBigDecimal(6, BigDecimal.valueOf(cost));
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to record usage", e);
        }
    }

    public Map<String, Object> dailySummary() {
        return summary("created_at >= CURRENT_DATE");
    }

    public Map<String, Object> monthlySummary() {
        return summary("created_at >= date_trunc('month', CURRENT_DATE)");
    }

    private Map<String, Object> summary(String whereClause) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(prompt_tokens),0), COALESCE(SUM(completion_tokens),0), COALESCE(SUM(cost_usd),0) FROM llm_usage WHERE " + whereClause);
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                return Map.of(
                        "promptTokens", rs.getInt(1),
                        "completionTokens", rs.getInt(2),
                        "costUsd", rs.getBigDecimal(3));
            }
        } catch (Exception e) {
            log.error("Failed to query usage summary", e);
        }
        return Map.of();
    }
}
