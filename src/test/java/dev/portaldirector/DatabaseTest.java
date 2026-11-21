package dev.portaldirector;

import dev.portaldirector.db.Database;
import dev.portaldirector.db.Migrations;
import dev.portaldirector.util.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @TempDir
    File dir;

    private final List<Database> opened = new ArrayList<>();

    private Path file() {
        return new File(dir, "portaldirector.db").toPath();
    }

    private Database open() {
        return open(Migrations.ALL);
    }

    private Database open(List<Migrations.Step> steps) {
        Database db = Database.open(file(), Logger.getLogger("test"), new Metrics(), steps);
        opened.add(db);

        return db;
    }

    @AfterEach
    void close() {
        for (Database db : opened) db.close();
    }

    private static int scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();

            return r.getInt(1);
        }
    }

    private static int ask(Database db, String sql) throws SQLException {
        return db.await(db.read(c -> scalar(c, sql)));
    }

    private int rawScalar(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath())) {
            return scalar(c, sql);
        }
    }

    // Opening and migrating

    @Test
    void aNewDatabaseIsCreatedAtTheLatestSchema() {
        Database db = open();

        assertTrue(db.available());
        assertEquals(Migrations.latest(), db.status().schemaVersion());
        assertEquals("ok", db.status().detail());
        assertFalse(db.status().recovered());
        assertTrue(Files.exists(file()));
    }

    @Test
    void everyExpectedTableExists() throws SQLException {
        Database db = open();

        int tables = db.await(db.read(c -> scalar(c, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN "
                + "('takes', 'take_settings', 'take_assignments', 'take_positions', 'take_runs', 'operations')")));

        assertEquals(6, tables);
    }

    @Test
    void reopeningKeepsTheDataAndDoesNotRunMigrationsAgain() throws SQLException {
        Database first = open();
        first.await(first.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO operations (kind, started_ms, status) VALUES ('build', 1, 'DONE')");
            }

            return null;
        }));
        first.close();

        Database second = open();

        assertEquals(Migrations.latest(), second.status().schemaVersion());
        assertEquals(1, ask(second, "SELECT count(*) FROM operations"));
    }

    @Test
    void aDatabaseFromANewerVersionIsLeftAloneAndSwitchedOff() throws SQLException, IOException {
        open().close();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }

        byte[] before = Files.readAllBytes(file());
        Database db = open();

        assertFalse(db.available());
        assertTrue(db.status().detail().contains("newer"), db.status().detail());
        assertArrayEquals(before, Files.readAllBytes(file()), "the file is not touched");
        assertTrue(db.read(c -> 1).isCompletedExceptionally());
    }

    @Test
    void aCorruptFileIsMovedAsideAndANewDatabaseStarts() throws IOException {
        byte[] garbage = "this is definitely not a sqlite database, it is just text".repeat(40).getBytes();
        Files.write(file(), garbage);

        Database db = open();

        assertTrue(db.available());
        assertTrue(db.status().recovered());

        File[] moved = dir.listFiles((d, n) -> n.startsWith("portaldirector.db.corrupt-"));

        assertNotNull(moved);
        assertEquals(1, moved.length);
        assertArrayEquals(garbage, Files.readAllBytes(moved[0].toPath()), "the damaged file is kept for inspection");
    }

    @Test
    void aMigrationThatFailsIsUndoneAndTheOldVersionStays() throws SQLException {
        Migrations.Step one = new Migrations.Step(1, List.of("CREATE TABLE a (id INTEGER PRIMARY KEY, v TEXT)"));
        Database first = open(List.of(one));
        first.await(first.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO a (v) VALUES ('keep me')");
            }

            return null;
        }));
        first.close();

        Migrations.Step broken = new Migrations.Step(2, List.of("CREATE TABLE b (id INTEGER PRIMARY KEY)", "INSERT INTO a (v, nope) VALUES ('x', 'y')"));
        Database second = open(List.of(one, broken));

        assertFalse(second.available());
        assertTrue(second.status().detail().contains("undone"), second.status().detail());
        assertEquals(1, rawScalar("PRAGMA user_version"));
        assertEquals(0, rawScalar("SELECT count(*) FROM sqlite_master WHERE name = 'b'"), "the half done step left nothing behind");
        assertEquals(1, rawScalar("SELECT count(*) FROM a"));
    }

    @Test
    void aStepThatWorksLaterRunsOnTheExistingData() throws SQLException {
        Migrations.Step one = new Migrations.Step(1, List.of("CREATE TABLE a (id INTEGER PRIMARY KEY, v TEXT)"));
        Database first = open(List.of(one));
        first.await(first.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO a (v) VALUES ('old row')");
            }

            return null;
        }));
        first.close();

        Migrations.Step two = new Migrations.Step(2, List.of("ALTER TABLE a ADD COLUMN extra TEXT DEFAULT 'new'"));
        Database second = open(List.of(one, two));

        assertTrue(second.available());
        assertEquals(2, second.status().schemaVersion());
        assertEquals(1, ask(second, "SELECT count(*) FROM a WHERE v = 'old row' AND extra = 'new'"));
    }

    @Test
    void dataCommittedBeforeAnUncleanStopIsStillThere() throws SQLException {
        Database first = open();
        first.await(first.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO operations (kind, started_ms, status) VALUES ('cut off', 5, 'RUNNING')");
            }

            return null;
        }));

        Database second = open();

        assertTrue(second.available());
        assertEquals(1, ask(second, "SELECT count(*) FROM operations WHERE kind = 'cut off'"));
    }

    // Running tasks

    @Test
    void tasksRunOneAfterTheOtherOnTheDatabaseThread() throws SQLException {
        Database db = open();
        List<String> threads = new ArrayList<>();
        List<CompletableFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            int n = i;
            futures.add(db.write(c -> {
                threads.add(Thread.currentThread().getName());

                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO operations (kind, started_ms, status) VALUES ('n" + n + "', " + n + ", 'DONE')");
                }

                return n;
            }));
        }

        for (int i = 0; i < 100; i++) assertEquals(i, db.await(futures.get(i)));

        assertEquals(1, threads.stream().distinct().count());
        assertEquals("PortalDirector-DB", threads.get(0));
        assertEquals(100, ask(db, "SELECT count(*) FROM operations"));
        assertEquals("n0", db.await(db.read(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT kind FROM operations ORDER BY id LIMIT 1")) {
                r.next();

                return r.getString(1);
            }
        })));
    }

    @Test
    void aWriteThatFailsHalfWayChangesNothingAndLaterWorkStillRuns() throws SQLException {
        Database db = open();

        CompletableFuture<Void> bad = db.write(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO operations (kind, started_ms, status) VALUES ('first half', 1, 'DONE')");
                s.execute("INSERT INTO operations (kind, started_ms, status) VALUES ('second half', 2, 'NOT A STATUS')");
            }

            return null;
        });

        assertThrows(SQLException.class, () -> db.await(bad));
        assertEquals(0, ask(db, "SELECT count(*) FROM operations"), "the first half was rolled back too");
        int seven = db.await(db.read(c -> 7));

        assertEquals(7, seven, "the thread and the connection are still fine");
    }

    @Test
    void closingFinishesQueuedWorkAndLaterTasksFailCleanly() throws SQLException {
        Database db = open();
        List<CompletableFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 40; i++) {
            int n = i;
            futures.add(db.write(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO operations (kind, started_ms, status) VALUES ('q" + n + "', " + n + ", 'DONE')");
                }

                return n;
            }));
        }

        db.close();

        for (CompletableFuture<Integer> f : futures) assertFalse(f.isCompletedExceptionally());

        assertTrue(db.read(c -> 1).isCompletedExceptionally());
        assertEquals(40, rawScalar("SELECT count(*) FROM operations"));
        assertFalse(db.available());
    }

    @Test
    void theStatusReportsSizeAndQueue() {
        Database db = open();

        assertTrue(db.status().fileBytes() > 0);
        assertEquals(0, db.status().queued());
    }
}
