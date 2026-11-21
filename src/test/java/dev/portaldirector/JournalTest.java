package dev.portaldirector;

import dev.portaldirector.db.Database;
import dev.portaldirector.db.Journal;
import dev.portaldirector.db.OperationLog;
import dev.portaldirector.util.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class JournalTest {

    @TempDir
    File dir;

    private Database db;

    @BeforeEach
    void open() {
        db = Database.open(new File(dir, "pd.db").toPath(), Logger.getLogger("test"), new Metrics());
    }

    @AfterEach
    void close() {
        db.close();
    }

    private List<OperationLog.Entry> recent() throws SQLException {
        return db.await(db.read(c -> OperationLog.recent(c, 10)));
    }

    @Test
    void anOperationIsRecordedFromStartToEnd() throws SQLException {
        Journal journal = new Journal(db, Logger.getLogger("test"));
        Journal.Token token = journal.begin("create", "30 portals");

        journal.end(token, "DONE");
        db.await(db.read(c -> 1));

        assertEquals("DONE", recent().get(0).status());
    }

    @Test
    void whatWasLeftRunningIsCountedAtStart() throws SQLException {
        db.await(db.write(c -> OperationLog.begin(c, "clear", "all", 1)));
        Journal journal = new Journal(db, Logger.getLogger("test"));

        journal.recover();

        assertEquals(1, journal.interruptedAtStart());
        assertEquals("INTERRUPTED", recent().get(0).status());
    }

    @Test
    void aDatabaseThatIsOffMakesEveryCallDoNothing() {
        Database off = Database.disabled(Logger.getLogger("test"), new Metrics(), "turned off");
        Journal journal = new Journal(off, Logger.getLogger("test"));

        assertDoesNotThrow(() -> {
            journal.end(journal.begin("create", "x"), "DONE");
            journal.recover();
        });
        assertEquals(0, journal.interruptedAtStart());
        assertEquals("turned off", off.status().detail());
        off.close();
    }
}
