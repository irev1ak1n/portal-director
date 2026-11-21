package dev.portaldirector.scene;

import dev.portaldirector.util.Metrics;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Writes scene snapshots on one background thread. It never touches the world. If a scene is saved again before the first
 * write started, only the newest snapshot is written. After {@link #close} a save is written on the calling thread, so
 * nothing is ever dropped.
 */
public final class SceneWriter {

    private final SceneStore store;
    private final Logger log;
    private final Metrics metrics;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private final Map<String, SceneSnapshot> pending = new HashMap<>();
    private final Set<String> queued = new HashSet<>();
    private boolean closed;

    public SceneWriter(SceneStore store, Logger log, Metrics metrics) {
        this.store = store;
        this.log = log;
        this.metrics = metrics;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PortalDirector-IO");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(SceneSnapshot snapshot) {
        synchronized (lock) {
            if (closed) {
                write(snapshot);
                return;
            }

            pending.put(snapshot.name(), snapshot);

            if (!queued.add(snapshot.name())) return;
        }

        executor.execute(() -> drain(snapshot.name()));
    }

    private void drain(String name) {
        while (true) {
            SceneSnapshot next;

            synchronized (lock) {
                next = pending.remove(name);

                if (next == null) {
                    queued.remove(name);
                    return;
                }
            }

            write(next);
        }
    }

    private void write(SceneSnapshot snapshot) {
        long start = metrics.start();

        try {
            store.save(snapshot);
        } catch (IOException | RuntimeException e) {
            metrics.count("scene.save.failed");
            log.severe("Could not save scene " + snapshot.name() + ": " + e.getMessage());
        }

        metrics.stop("scene.save.write", start);
    }

    /** Waits until every save handed in so far is on disk. Returns false if that took longer than the timeout. */
    public boolean flush(long timeoutMillis) {
        try {
            executor.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /** Forgets a scene that is being deleted or renamed, so a late write cannot bring its files back. */
    public void forget(String name) {
        synchronized (lock) {
            pending.remove(name);
        }

        flush(10_000);
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Finishes what is queued and stops the thread. Later saves are written directly. */
    public void close() {
        synchronized (lock) {
            closed = true;
        }

        flush(30_000);
        executor.shutdown();

        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
