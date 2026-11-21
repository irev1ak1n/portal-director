package dev.portaldirector.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes takes. Every method takes the connection of a running {@link Database} task, so a service decides which steps
 * share one transaction. Statements are always prepared; no value is ever pasted into SQL.
 */
public final class TakeRepository {

    public record Run(long id, long takeId, long startedMs, Long finishedMs, String status, List<String> portals) {
    }

    private TakeRepository() {
    }

    // Takes

    /** Saves a take under its name, replacing an older one of the same name with all of its details. Returns its id. */
    public static long save(Connection c, Take take) throws SQLException {
        Optional<Long> existing = idOf(c, take.name());
        long id;

        if (existing.isPresent()) {
            id = existing.get();
            update(c, id, take);

            for (String table : List.of("take_settings", "take_assignments", "take_positions")) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE take_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
        } else {
            id = insert(c, take);
        }

        insertDetails(c, id, take);

        return id;
    }

    private static Optional<Long> idOf(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM takes WHERE name = ?")) {
            ps.setString(1, name);

            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? Optional.of(r.getLong(1)) : Optional.empty();
            }
        }
    }

    private static long insert(Connection c, Take t) throws SQLException {
        String sql = "INSERT INTO takes (name, world, scene, sequence, target_x, target_y, target_z, target_facing, group_name, "
                + "arena_world, arena_min_x, arena_min_y, arena_min_z, arena_max_x, arena_max_y, arena_max_z, arena_snapshot, created_ms, updated_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindTake(ps, t, 1);
            ps.setLong(18, t.createdMs());
            ps.setLong(19, t.updatedMs());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();

                return keys.getLong(1);
            }
        }
    }

    private static void update(Connection c, long id, Take t) throws SQLException {
        String sql = "UPDATE takes SET name = ?, world = ?, scene = ?, sequence = ?, target_x = ?, target_y = ?, target_z = ?, target_facing = ?, group_name = ?, "
                + "arena_world = ?, arena_min_x = ?, arena_min_y = ?, arena_min_z = ?, arena_max_x = ?, arena_max_y = ?, arena_max_z = ?, arena_snapshot = ?, updated_ms = ? WHERE id = ?";

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bindTake(ps, t, 1);
            ps.setLong(18, t.updatedMs());
            ps.setLong(19, id);
            ps.executeUpdate();
        }
    }

    private static void bindTake(PreparedStatement ps, Take t, int from) throws SQLException {
        int i = from;
        ps.setString(i++, t.name());
        ps.setString(i++, t.world());
        ps.setString(i++, t.scene());
        setNullable(ps, i++, t.sequence());

        if (t.target() == null) {
            for (int k = 0; k < 4; k++) ps.setNull(i++, k == 3 ? Types.VARCHAR : Types.INTEGER);
        } else {
            ps.setInt(i++, t.target().x());
            ps.setInt(i++, t.target().y());
            ps.setInt(i++, t.target().z());
            setNullable(ps, i++, t.target().facing());
        }

        setNullable(ps, i++, t.group());

        Take.Arena a = t.arena();

        if (a == null) {
            ps.setNull(i++, Types.VARCHAR);

            for (int k = 0; k < 6; k++) ps.setNull(i++, Types.INTEGER);

            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setString(i++, a.world());
            ps.setInt(i++, a.minX());
            ps.setInt(i++, a.minY());
            ps.setInt(i++, a.minZ());
            ps.setInt(i++, a.maxX());
            ps.setInt(i++, a.maxY());
            ps.setInt(i++, a.maxZ());
            setNullable(ps, i, a.snapshot());
        }
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, value);
    }

    private static void insertDetails(Connection c, long id, Take t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO take_settings (take_id, scope, key, value) VALUES (?, ?, ?, ?)")) {
            addSettings(ps, id, "formation", t.formation());
            addSettings(ps, id, "sequence", t.sequenceSettings());
            ps.executeBatch();
        }

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO take_assignments (take_id, player_uuid, player_name, portal) VALUES (?, ?, ?, ?)")) {
            for (Take.Assignment a : t.assignments()) {
                ps.setLong(1, id);
                ps.setString(2, a.uuid());
                ps.setString(3, a.name());
                setNullable(ps, 4, a.portal());
                ps.addBatch();
            }

            ps.executeBatch();
        }

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO take_positions (take_id, player_uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (Take.Position p : t.positions()) {
                ps.setLong(1, id);
                ps.setString(2, p.uuid());
                ps.setString(3, p.world());
                ps.setDouble(4, p.x());
                ps.setDouble(5, p.y());
                ps.setDouble(6, p.z());
                ps.setFloat(7, p.yaw());
                ps.setFloat(8, p.pitch());
                ps.addBatch();
            }

            ps.executeBatch();
        }
    }

    private static void addSettings(PreparedStatement ps, long id, String scope, Map<String, String> values) throws SQLException {
        for (Map.Entry<String, String> e : values.entrySet()) {
            ps.setLong(1, id);
            ps.setString(2, scope);
            ps.setString(3, e.getKey());
            ps.setString(4, e.getValue());
            ps.addBatch();
        }
    }

    public static Optional<Take> find(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM takes WHERE name = ?")) {
            ps.setString(1, name);

            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) return Optional.empty();

                long id = r.getLong("id");
                Take.Target target = r.getObject("target_x") == null ? null
                        : new Take.Target(r.getInt("target_x"), r.getInt("target_y"), r.getInt("target_z"), r.getString("target_facing"));
                Take.Arena arena = r.getString("arena_world") == null ? null
                        : new Take.Arena(r.getString("arena_world"), r.getInt("arena_min_x"), r.getInt("arena_min_y"), r.getInt("arena_min_z"),
                        r.getInt("arena_max_x"), r.getInt("arena_max_y"), r.getInt("arena_max_z"), r.getString("arena_snapshot"));

                return Optional.of(new Take(id, r.getString("name"), r.getString("world"), r.getString("scene"), r.getString("sequence"), target,
                        r.getString("group_name"), settings(c, id, "formation"), settings(c, id, "sequence"), assignments(c, id), positions(c, id),
                        arena, r.getLong("created_ms"), r.getLong("updated_ms")));
            }
        }
    }

    private static Map<String, String> settings(Connection c, long id, String scope) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();

        try (PreparedStatement ps = c.prepareStatement("SELECT key, value FROM take_settings WHERE take_id = ? AND scope = ? ORDER BY key")) {
            ps.setLong(1, id);
            ps.setString(2, scope);

            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) out.put(r.getString(1), r.getString(2));
            }
        }

        return out;
    }

    private static List<Take.Assignment> assignments(Connection c, long id) throws SQLException {
        List<Take.Assignment> out = new ArrayList<>();

        try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, player_name, portal FROM take_assignments WHERE take_id = ? ORDER BY player_name COLLATE NOCASE")) {
            ps.setLong(1, id);

            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) out.add(new Take.Assignment(r.getString(1), r.getString(2), r.getString(3)));
            }
        }

        return out;
    }

    private static List<Take.Position> positions(Connection c, long id) throws SQLException {
        List<Take.Position> out = new ArrayList<>();

        try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, world, x, y, z, yaw, pitch FROM take_positions WHERE take_id = ? ORDER BY player_uuid")) {
            ps.setLong(1, id);

            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.add(new Take.Position(r.getString(1), r.getString(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getFloat(6), r.getFloat(7)));
                }
            }
        }

        return out;
    }

    /** Names of every saved take, A to Z. */
    public static List<String> names(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();

        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT name FROM takes ORDER BY name COLLATE NOCASE")) {
            while (r.next()) out.add(r.getString(1));
        }

        return out;
    }

    public static boolean delete(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM takes WHERE name = ?")) {
            ps.setString(1, name);

            return ps.executeUpdate() > 0;
        }
    }

    // Runs

    public static long startRun(Connection c, long takeId, String status, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO take_runs (take_id, started_ms, status) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, takeId);
            ps.setLong(2, now);
            ps.setString(3, status);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();

                return keys.getLong(1);
            }
        }
    }

    public static void setRunStatus(Connection c, long runId, String status, List<String> portals, Long finishedMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE take_runs SET status = ?, portals = ?, finished_ms = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, String.join(",", portals));

            if (finishedMs == null) ps.setNull(3, Types.INTEGER);
            else ps.setLong(3, finishedMs);

            ps.setLong(4, runId);
            ps.executeUpdate();
        }
    }

    /** The newest runs of a take, newest first. */
    public static List<Run> runs(Connection c, long takeId, int limit) throws SQLException {
        List<Run> out = new ArrayList<>();

        try (PreparedStatement ps = c.prepareStatement("SELECT id, take_id, started_ms, finished_ms, status, portals FROM take_runs WHERE take_id = ? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, takeId);
            ps.setInt(2, limit);

            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    long finished = r.getLong(4);
                    Long finishedMs = r.wasNull() ? null : finished;
                    String portals = r.getString(6);
                    out.add(new Run(r.getLong(1), r.getLong(2), r.getLong(3), finishedMs, r.getString(5), portals.isEmpty() ? List.of() : List.of(portals.split(","))));
                }
            }
        }

        return out;
    }

    /** Runs that were still going when the server stopped are marked interrupted. Returns how many. */
    public static int markInterrupted(Connection c, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE take_runs SET status = 'INTERRUPTED', finished_ms = ? WHERE status = 'RUNNING'")) {
            ps.setLong(1, now);

            return ps.executeUpdate();
        }
    }
}
