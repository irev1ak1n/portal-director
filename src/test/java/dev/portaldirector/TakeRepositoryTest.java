package dev.portaldirector;

import dev.portaldirector.db.Database;
import dev.portaldirector.db.OperationLog;
import dev.portaldirector.db.Take;
import dev.portaldirector.db.TakeRepository;
import dev.portaldirector.util.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TakeRepositoryTest {

    @TempDir
    File dir;

    private Database db;

    @BeforeEach
    void open() {
        db = Database.open(new File(dir, "pd.db").toPath(), Logger.getLogger("test"), new Metrics());

        assertTrue(db.available());
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static Take full(String name) {
        return new Take(0, name, "world", "default", "sky-army", new Take.Target(10, 120, -30, "north"), "NULL",
                Map.of("layout", "random", "radius", "100", "placement", "air"),
                Map.of("radius", "80", "group", "NULL"),
                List.of(new Take.Assignment("22222222-2222-2222-2222-222222222222", "alex", null),
                        new Take.Assignment("11111111-1111-1111-1111-111111111111", "Steve", "portal-1")),
                List.of(new Take.Position("11111111-1111-1111-1111-111111111111", "world", 1.5, 64, -2.5, 90f, 10f)),
                new Take.Arena("world", -20, 60, -20, 20, 100, 20, "take-arena.schem"),
                1000, 2000);
    }

    private static Take bare(String name) {
        return new Take(0, name, "world", "default", null, null, null, Map.of(), Map.of(), List.of(), List.of(), null, 1, 1);
    }

    private long count(String table) throws SQLException {
        return db.await(db.read(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
                r.next();

                return r.getLong(1);
            }
        }));
    }

    @Test
    void aSavedTakeComesBackWithEveryDetail() throws SQLException {
        long id = db.await(db.write(c -> TakeRepository.save(c, full("Scene1"))));
        Take back = db.await(db.read(c -> TakeRepository.find(c, "scene1"))).orElseThrow();

        assertEquals(full("Scene1").withId(id), back);
    }

    @Test
    void aTakeWithNothingOptionalComesBackWithNulls() throws SQLException {
        db.await(db.write(c -> TakeRepository.save(c, bare("empty"))));
        Take back = db.await(db.read(c -> TakeRepository.find(c, "EMPTY"))).orElseThrow();

        assertNull(back.sequence());
        assertNull(back.target());
        assertNull(back.group());
        assertNull(back.arena());
        assertTrue(back.assignments().isEmpty() && back.positions().isEmpty() && back.formation().isEmpty());
    }

    @Test
    void namesAreListedAToZIgnoringCase() throws SQLException {
        for (String n : List.of("beta", "Alpha", "gamma", "Delta")) db.await(db.write(c -> TakeRepository.save(c, bare(n))));

        assertEquals(List.of("Alpha", "beta", "Delta", "gamma"), db.await(db.read(TakeRepository::names)));
    }

    @Test
    void savingTheSameNameAgainReplacesTheDetailsAndKeepsTheOriginalCreationTime() throws SQLException {
        long first = db.await(db.write(c -> TakeRepository.save(c, full("take"))));
        Take changed = new Take(0, "TAKE", "other", "scene2", null, null, null, Map.of("layout", "grid"), Map.of(), List.of(), List.of(), null, 9999, 5000);
        long second = db.await(db.write(c -> TakeRepository.save(c, changed)));
        Take back = db.await(db.read(c -> TakeRepository.find(c, "take"))).orElseThrow();

        assertEquals(first, second, "a name that differs only in capitals is the same take");
        assertEquals(1, count("takes"));
        assertEquals("scene2", back.scene());
        assertEquals(Map.of("layout", "grid"), back.formation());
        assertTrue(back.assignments().isEmpty() && back.positions().isEmpty(), "old details are gone");
        assertEquals(1000, back.createdMs());
        assertEquals(5000, back.updatedMs());
    }

    @Test
    void deletingATakeDeletesAllOfItsDetailsToo() throws SQLException {
        long id = db.await(db.write(c -> TakeRepository.save(c, full("gone"))));
        db.await(db.write(c -> TakeRepository.startRun(c, id, "PREPARED", 5)));

        boolean first = db.await(db.write(c -> TakeRepository.delete(c, "GONE")));
        boolean second = db.await(db.write(c -> TakeRepository.delete(c, "gone")));

        assertTrue(first);
        assertFalse(second);

        for (String table : List.of("takes", "take_settings", "take_assignments", "take_positions", "take_runs")) assertEquals(0, count(table), table);
    }

    @Test
    void aSaveThatFailsHalfWayChangesNothing() throws SQLException {
        Take twice = new Take(0, "broken", "world", "default", null, null, null, Map.of("a", "b"), Map.of(),
                List.of(new Take.Assignment("same", "A", null), new Take.Assignment("same", "B", null)), List.of(), null, 1, 1);

        assertThrows(SQLException.class, () -> db.await(db.write(c -> TakeRepository.save(c, twice))));
        assertEquals(0, count("takes"));
        assertEquals(0, count("take_settings"));
    }

    @Test
    void aTakeNameMustBeBetweenOneAndThirtyTwoCharacters() {
        assertThrows(SQLException.class, () -> db.await(db.write(c -> TakeRepository.save(c, bare("")))));
        assertThrows(SQLException.class, () -> db.await(db.write(c -> TakeRepository.save(c, bare("x".repeat(33))))));
    }

    // Runs

    @Test
    void runsAreListedNewestFirstWithTheirPortals() throws SQLException {
        long id = db.await(db.write(c -> TakeRepository.save(c, bare("run"))));
        long a = db.await(db.write(c -> TakeRepository.startRun(c, id, "RUNNING", 10)));
        long b = db.await(db.write(c -> TakeRepository.startRun(c, id, "PREPARED", 20)));

        db.await(db.write(c -> {
            TakeRepository.setRunStatus(c, a, "FINISHED", List.of("sky-army-1", "sky-army-2"), 15L);

            return null;
        }));

        List<TakeRepository.Run> runs = db.await(db.read(c -> TakeRepository.runs(c, id, 10)));

        assertEquals(List.of(b, a), runs.stream().map(TakeRepository.Run::id).toList());
        assertEquals("PREPARED", runs.get(0).status());
        assertNull(runs.get(0).finishedMs());
        assertEquals(List.of("sky-army-1", "sky-army-2"), runs.get(1).portals());
        assertEquals(15L, runs.get(1).finishedMs());
    }

    @Test
    void anUnknownRunStatusIsRefusedByTheDatabase() throws SQLException {
        long id = db.await(db.write(c -> TakeRepository.save(c, bare("run"))));

        assertThrows(SQLException.class, () -> db.await(db.write(c -> TakeRepository.startRun(c, id, "BOGUS", 1))));
    }

    @Test
    void onlyRunsThatWereStillRunningAreMarkedInterrupted() throws SQLException {
        long id = db.await(db.write(c -> TakeRepository.save(c, bare("run"))));
        long running = db.await(db.write(c -> TakeRepository.startRun(c, id, "RUNNING", 10)));
        long prepared = db.await(db.write(c -> TakeRepository.startRun(c, id, "PREPARED", 11)));

        int marked = db.await(db.write(c -> TakeRepository.markInterrupted(c, 99)));

        assertEquals(1, marked);

        List<TakeRepository.Run> runs = db.await(db.read(c -> TakeRepository.runs(c, id, 10)));

        assertEquals("PREPARED", runs.stream().filter(r -> r.id() == prepared).findFirst().orElseThrow().status());
        assertEquals("INTERRUPTED", runs.stream().filter(r -> r.id() == running).findFirst().orElseThrow().status());
    }

    // The operations history

    @Test
    void anOperationIsRecordedWhileItRunsAndWhenItEnds() throws SQLException {
        long id = db.await(db.write(c -> OperationLog.begin(c, "create", "30 portals", 100)));

        assertEquals("RUNNING", db.await(db.read(c -> OperationLog.recent(c, 5))).get(0).status());

        db.await(db.write(c -> {
            OperationLog.end(c, id, "DONE", 200);

            return null;
        }));

        OperationLog.Entry entry = db.await(db.read(c -> OperationLog.recent(c, 5))).get(0);

        assertEquals("DONE", entry.status());
        assertEquals(200L, entry.finishedMs());
        assertEquals("30 portals", entry.detail());
    }

    @Test
    void operationsLeftRunningAreMarkedInterruptedOnceAndReported() throws SQLException {
        db.await(db.write(c -> OperationLog.begin(c, "clear", "all", 1)));
        long done = db.await(db.write(c -> OperationLog.begin(c, "create", "x", 2)));
        db.await(db.write(c -> {
            OperationLog.end(c, done, "DONE", 3);

            return null;
        }));

        List<OperationLog.Entry> cut = db.await(db.write(c -> OperationLog.markInterrupted(c, 50)));

        assertEquals(1, cut.size());
        assertEquals("clear", cut.get(0).kind());
        assertTrue(db.await(db.write(c -> OperationLog.markInterrupted(c, 60))).isEmpty(), "nothing is reported twice");
    }

    @Test
    void anOperationThatAlreadyEndedCannotEndAgainAsSomethingElse() throws SQLException {
        long id = db.await(db.write(c -> OperationLog.begin(c, "create", "x", 1)));
        db.await(db.write(c -> {
            OperationLog.end(c, id, "DONE", 2);
            OperationLog.end(c, id, "FAILED", 3);

            return null;
        }));

        assertEquals("DONE", db.await(db.read(c -> OperationLog.recent(c, 1))).get(0).status());
    }

    @Test
    void theHistoryIsTrimmedToTheNewestEntries() throws SQLException {
        for (int i = 0; i < OperationLog.KEEP + 40; i++) {
            int n = i;
            db.await(db.write(c -> OperationLog.begin(c, "k" + n, "", n)));
        }

        db.await(db.write(c -> {
            OperationLog.trim(c);

            return null;
        }));

        assertEquals(OperationLog.KEEP, count("operations"));
        assertEquals("k" + (OperationLog.KEEP + 39), db.await(db.read(c -> OperationLog.recent(c, 1))).get(0).kind());
    }
}
