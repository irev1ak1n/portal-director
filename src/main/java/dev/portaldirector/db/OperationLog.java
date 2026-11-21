package dev.portaldirector.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A short history of long operations (building, removing, sequences, takes). An operation that is still marked running after a
 * restart was cut off, so it is marked interrupted and reported once. The scene files stay the source of truth for blocks.
 */
public final class OperationLog {

    public record Entry(long id, String kind, String detail, long startedMs, Long finishedMs, String status) {
    }

    /** Old entries beyond this many are deleted when the plugin starts. */
    public static final int KEEP = 500;

    private OperationLog() {
    }

    public static long begin(Connection c, String kind, String detail, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO operations (kind, detail, started_ms, status) VALUES (?, ?, ?, 'RUNNING')", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, detail);
            ps.setLong(3, now);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();

                return keys.getLong(1);
            }
        }
    }

    public static void end(Connection c, long id, String status, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE operations SET status = ?, finished_ms = ? WHERE id = ? AND status = 'RUNNING'")) {
            ps.setString(1, status);
            ps.setLong(2, now);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Marks operations that never finished. Returns them, oldest first, so the plugin can say what was cut off. */
    public static List<Entry> markInterrupted(Connection c, long now) throws SQLException {
        List<Entry> cut = new ArrayList<>();

        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id, kind, detail, started_ms FROM operations WHERE status = 'RUNNING' ORDER BY id")) {
            while (r.next()) cut.add(new Entry(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), now, "INTERRUPTED"));
        }

        try (PreparedStatement ps = c.prepareStatement("UPDATE operations SET status = 'INTERRUPTED', finished_ms = ? WHERE status = 'RUNNING'")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }

        return cut;
    }

    public static List<Entry> recent(Connection c, int limit) throws SQLException {
        List<Entry> out = new ArrayList<>();

        try (PreparedStatement ps = c.prepareStatement("SELECT id, kind, detail, started_ms, finished_ms, status FROM operations ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);

            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    long finished = r.getLong(5);
                    out.add(new Entry(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), r.wasNull() ? null : finished, r.getString(6)));
                }
            }
        }

        return out;
    }

    /** Deletes all but the newest {@link #KEEP} entries. */
    public static void trim(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DELETE FROM operations WHERE id <= (SELECT COALESCE(MAX(id), 0) - " + KEEP + " FROM operations)");
        }
    }
}
