package dev.portaldirector.db;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Records the start and end of long operations without ever making the caller wait. If the database is off, every call does
 * nothing. At start-up, operations that never ended are reported once.
 */
public final class Journal {

    /** Handle for one recorded operation. */
    public static final class Token {

        private final CompletableFuture<Long> id;

        private Token(CompletableFuture<Long> id) {
            this.id = id;
        }
    }

    private static final Token NONE = new Token(null);

    private final Database db;
    private final Logger log;
    private volatile int interruptedAtStart;

    public Journal(Database db, Logger log) {
        this.db = db;
        this.log = log;
    }

    public Token begin(String kind, String detail) {
        if (!db.available()) return NONE;

        return new Token(db.write(c -> OperationLog.begin(c, kind, detail, System.currentTimeMillis())));
    }

    public void end(Token token, String status) {
        if (token == null || token.id == null || !db.available()) return;

        token.id.thenCompose(id -> db.write(c -> {
            OperationLog.end(c, id, status, System.currentTimeMillis());

            return null;
        })).exceptionally(e -> {
            log.fine("Could not record the end of an operation: " + e.getMessage());

            return null;
        });
    }

    /** Marks what was cut off by a crash or a stop, says so once in the log, and trims the old history. */
    public void recover() {
        if (!db.available()) return;

        long now = System.currentTimeMillis();

        try {
            List<OperationLog.Entry> cut = db.await(db.write(c -> {
                List<OperationLog.Entry> entries = OperationLog.markInterrupted(c, now);
                TakeRepository.markInterrupted(c, now);
                OperationLog.trim(c);

                return entries;
            }));

            interruptedAtStart = cut.size();

            if (!cut.isEmpty()) {
                StringBuilder what = new StringBuilder();

                for (OperationLog.Entry e : cut) what.append(what.isEmpty() ? "" : ", ").append(e.kind()).append(e.detail().isEmpty() ? "" : " (" + e.detail() + ")");

                log.warning(cut.size() + " operation" + (cut.size() == 1 ? " was" : "s were") + " cut off when the server last stopped: " + what
                        + ". Scenes are saved as they go, so /pd list shows what exists and /pd clear puts the ground back.");
            }
        } catch (SQLException | RuntimeException e) {
            log.warning("Could not check for interrupted operations: " + e.getMessage());
        }
    }

    public int interruptedAtStart() {
        return interruptedAtStart;
    }
}
