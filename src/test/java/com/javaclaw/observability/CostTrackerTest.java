package com.javaclaw.observability;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostTrackerTest {

    @Test
    void recordInsertsUsageRow() throws Exception {
        var ps = mock(PreparedStatement.class);
        var conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);

        var tracker = new CostTracker(ds);
        tracker.record("s1", "deepseek", "deepseek-chat", 1000, 500);

        verify(ps).setString(1, "s1");
        verify(ps).setString(2, "deepseek");
        verify(ps).setString(3, "deepseek-chat");
        verify(ps).setInt(4, 1000);
        verify(ps).setInt(5, 500);
        verify(ps).executeUpdate();
    }

    @Test
    void dailySummaryReturnsCounts() throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(2000);
        when(rs.getInt(2)).thenReturn(1000);
        when(rs.getBigDecimal(3)).thenReturn(BigDecimal.valueOf(0.42));
        var ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        var conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);

        var tracker = new CostTracker(ds);
        var summary = tracker.dailySummary();

        assertEquals(2000, summary.get("promptTokens"));
        assertEquals(1000, summary.get("completionTokens"));
        assertEquals(BigDecimal.valueOf(0.42), summary.get("costUsd"));
    }
}
