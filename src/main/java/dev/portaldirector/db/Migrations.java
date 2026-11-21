package dev.portaldirector.db;

import java.util.List;

/**
 * The database schema, one step per version. A step only ever adds to the one before it, and the version is kept in SQLite's
 * own user_version field. Never edit a step that has been released: add a new one.
 */
public final class Migrations {

    /** One version of the schema: its number and the statements that build it from the version before. */
    public record Step(int version, List<String> statements) {
    }

    private Migrations() {
    }

    public static final List<Step> ALL = List.of(
            new Step(1, List.of(
                    """
                    CREATE TABLE takes (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(name) BETWEEN 1 AND 32),
                        world TEXT NOT NULL,
                        scene TEXT NOT NULL,
                        sequence TEXT,
                        target_x INTEGER,
                        target_y INTEGER,
                        target_z INTEGER,
                        target_facing TEXT,
                        group_name TEXT,
                        arena_world TEXT,
                        arena_min_x INTEGER,
                        arena_min_y INTEGER,
                        arena_min_z INTEGER,
                        arena_max_x INTEGER,
                        arena_max_y INTEGER,
                        arena_max_z INTEGER,
                        arena_snapshot TEXT,
                        created_ms INTEGER NOT NULL,
                        updated_ms INTEGER NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE take_settings (
                        take_id INTEGER NOT NULL REFERENCES takes(id) ON DELETE CASCADE,
                        scope TEXT NOT NULL CHECK (scope IN ('formation', 'sequence')),
                        key TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (take_id, scope, key)
                    )
                    """,
                    """
                    CREATE TABLE take_assignments (
                        take_id INTEGER NOT NULL REFERENCES takes(id) ON DELETE CASCADE,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        portal TEXT,
                        PRIMARY KEY (take_id, player_uuid)
                    )
                    """,
                    """
                    CREATE TABLE take_positions (
                        take_id INTEGER NOT NULL REFERENCES takes(id) ON DELETE CASCADE,
                        player_uuid TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        PRIMARY KEY (take_id, player_uuid)
                    )
                    """,
                    """
                    CREATE TABLE take_runs (
                        id INTEGER PRIMARY KEY,
                        take_id INTEGER NOT NULL REFERENCES takes(id) ON DELETE CASCADE,
                        started_ms INTEGER NOT NULL,
                        finished_ms INTEGER,
                        status TEXT NOT NULL CHECK (status IN ('PREPARED', 'RUNNING', 'STOPPED', 'FINISHED', 'RESET', 'INTERRUPTED')),
                        portals TEXT NOT NULL DEFAULT ''
                    )
                    """,
                    """
                    CREATE TABLE operations (
                        id INTEGER PRIMARY KEY,
                        kind TEXT NOT NULL,
                        detail TEXT NOT NULL DEFAULT '',
                        started_ms INTEGER NOT NULL,
                        finished_ms INTEGER,
                        status TEXT NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'CANCELLED', 'FAILED', 'INTERRUPTED'))
                    )
                    """,
                    "CREATE INDEX operations_status ON operations(status)",
                    "CREATE INDEX take_runs_take ON take_runs(take_id)"
            ))
    );

    public static int latest() {
        return ALL.get(ALL.size() - 1).version();
    }
}
