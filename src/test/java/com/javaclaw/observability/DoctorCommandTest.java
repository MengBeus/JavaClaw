package com.javaclaw.observability;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoctorCommandTest {

    @Test
    void reportsPostgresOkWhenConnectionSucceeds() throws Exception {
        var rs = mock(ResultSet.class);
        var ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        var conn = mock(Connection.class);
        when(conn.prepareStatement("SELECT 1")).thenReturn(ps);
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);

        var doctor = new DoctorCommand(ds, "http://localhost:0");
        var result = doctor.run();

        assertTrue(result.contains("[OK] PostgreSQL connection"));
        assertTrue(result.contains("[OK] Java"));
    }

    @Test
    void reportsPostgresFailWhenConnectionThrows() throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new RuntimeException("refused"));

        var doctor = new DoctorCommand(ds, "http://localhost:0");
        var result = doctor.run();

        assertTrue(result.contains("[FAIL] PostgreSQL: refused"));
    }
}
