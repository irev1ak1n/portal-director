package dev.portaldirector.db;

import dev.portaldirector.util.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * One SQLite file with one connection that lives on one background thread. Every query runs there, so the main thread never
 * waits for the disk and there is never more than one writer. A broken or too-new file switches the database off instead of
 * stopping the plugin.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlTask<T> {

        T run(Connection connection) throws SQLException;
    }

    public record Status(boolean available, String detail, int schemaVersion, boolean recovered, long fileBytes, int queued) {
    }

    private final Path file;
    private final Logger log;
    private final Metrics metrics;
    private final List<Migrations.Step> steps;
    private final ExecutorService executor;
    private final AtomicInteger queued = new AtomicInteger();
    private Connection connection;
    private volatile boolean available;
    private volatile boolean closed;
    private volatile boolean recovered;
    private volatile int schemaVersion;
    private volatile String detail = "not opened yet";

    private Database(Path file, Logger log, Metrics metrics, List<Migrations.Step> steps) {
        this.file = file;
        this.log = log;
        this.metrics = metrics;
        this.steps = steps;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PortalDirector-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static Database open(Path file, Logger log, Metrics metrics) {
        return open(file, log, metrics, Migrations.ALL);
    }

    /** A database that is switched off on purpose. Every task fails with the reason. */
    public static Database disabled(Logger log, Metrics metrics, String reason) {
        Database db = new Database(null, log, metrics, Migrations.ALL);
        db.detail = reason;

        return db;
    }

    /** Opens the file, repairs what can be repaired and brings the schema up to date. Always returns; check {@link #available()}. */
    public static Database open(Path file, Logger log, Metrics metrics, List<Migrations.Step> steps) {
        Database db = new Database(file, log, metrics, steps);

        try {
            db.executor.submit(db::initialise).get(20, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            db.fail("could not start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            db.fail("start was interrupted");
        }

        return db;
    }

    // Starting up, on the database thread

    private void initialise() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            fail("the SQLite driver is not on this server");

            return;
        }

        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connect();

            if (!healthy()) {
                quarantine("it failed its integrity check");
                connect();
            }
        } catch (SQLException | IOException e) {
            log.warning("The database could not be opened (" + e.getMessage() + "). Moving it aside and starting a new one.");

            try {
                closeConnection();
                quarantine("it could not be opened");
                connect();
            } catch (SQLException | IOException again) {
                fail("could not create a database: " + again.getMessage());

                return;
            }
        }

        try {
            migrate();
        } catch (SQLException e) {
            fail("the schema update failed and was undone: " + e.getMessage());
            closeConnection();
        }
    }

    private void connect() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());

        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA synchronous=NORMAL");
        }
    }

    private boolean healthy() {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA quick_check")) {
            return r.next() && "ok".equalsIgnoreCase(r.getString(1));
        } catch (SQLException e) {
            return false;
        }
    }

    private void quarantine(String why) throws IOException {
        closeConnection();

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = file.getFileName().toString();

        for (String suffix : List.of("", "-wal", "-shm")) {
            Path part = file.resolveSibling(name + suffix);

            if (Files.exists(part)) Files.move(part, file.resolveSibling(name + ".corrupt-" + stamp + suffix));
        }

        recovered = true;
        log.warning("The database was moved to " + name + ".corrupt-" + stamp + " because " + why + ". A new one was created; nothing else was changed.");
    }

    private void migrate() throws SQLException {
        int current = userVersion();
        int latest = steps.get(steps.size() - 1).version();

        if (current > latest) {
            fail("it was made by a newer PortalDirector (schema " + current + ", this version knows " + latest + ") and was left alone");
            closeConnection();

            return;
        }

        for (Migrations.Step step : steps) {
            if (step.version() <= current) continue;

            connection.setAutoCommit(false);

            try (Statement s = connection.createStatement()) {
                for (String sql : step.statements()) s.execute(sql);

                s.execute("PRAGMA user_version=" + step.version());
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(true);

                throw e;
            }

            connection.setAutoCommit(true);
            log.info("Database schema updated to version " + step.version() + ".");
        }

        schemaVersion = userVersion();
        available = true;
        detail = "ok";
    }

    private int userVersion() throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    private void fail(String reason) {
        available = false;
        detail = reason;
        log.warning("The database is switched off: " + reason + ". Features that need it will say so; everything else works.");
    }

    private void closeConnection() {
        if (connection == null) return;

        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing more can be done
        }

        connection = null;
    }

    // Using it

    public boolean available() {
        return available && !closed;
    }

    public Status status() {
        long bytes = 0;

        try {
            if (file != null && Files.exists(file)) bytes = Files.size(file);
        } catch (IOException ignored) {
            // size is only informational
        }

        return new Status(available(), closed ? "closed" : detail, schemaVersion, recovered, bytes, queued.get());
    }

    /** Runs a query on the database thread. Nothing is changed unless the task changes it itself. */
    public <T> CompletableFuture<T> read(SqlTask<T> task) {
        return submit(false, task);
    }

    /** Runs the task in one transaction: everything it does is kept, or none of it. */
    public <T> CompletableFuture<T> write(SqlTask<T> task) {
        return submit(true, task);
    }

    private <T> CompletableFuture<T> submit(boolean transaction, SqlTask<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();

        if (!available()) {
            result.completeExceptionally(new IllegalStateException("The database is not available: " + status().detail()));

            return result;
        }

        queued.incrementAndGet();

        try {
            executor.execute(() -> {
                long start = metrics.start();

                try {
                    result.complete(transaction ? runInTransaction(task) : task.run(connection));
                } catch (SQLException | RuntimeException e) {
                    metrics.count("db.failed");
                    result.completeExceptionally(e);
                } finally {
                    queued.decrementAndGet();
                    metrics.stop("db.task", start);
                }
            });
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            result.completeExceptionally(new IllegalStateException("The database is closed"));
        }

        return result;
    }

    private <T> T runInTransaction(SqlTask<T> task) throws SQLException {
        connection.setAutoCommit(false);

        try {
            T value = task.run(connection);
            connection.commit();

            return value;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();

            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** Waits for a query and returns its answer. For tests and shutdown; the main thread should use the futures instead. */
    public <T> T await(CompletableFuture<T> future) throws SQLException {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException sql) throw sql;
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;

            throw new SQLException(e.getCause());
        } catch (TimeoutException e) {
            throw new SQLException("The database took too long to answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new SQLException("Interrupted while waiting for the database");
        }
    }

    /** Finishes what is queued, folds the write-ahead log into the file and closes the connection. */
    public void close() {
        if (closed) return;

        closed = true;

        try {
            executor.submit(() -> {
                if (connection == null) return;

                try (Statement s = connection.createStatement()) {
                    s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // the file is still consistent without it
                }

                closeConnection();
            }).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warning("The database did not close cleanly: " + e.getMessage());
        }

        executor.shutdown();
    }
}
