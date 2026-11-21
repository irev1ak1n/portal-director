package dev.portaldirector.util;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Small, thread-safe counters and timers for the plugin's own work. They cost a few nanoseconds per use, keep no history, and
 * feed /pd diagnostics. Nothing here touches the world.
 */
public final class Metrics {

    public record Timing(long count, long totalNanos, long maxNanos) {

        public double meanMillis() {
            return count == 0 ? 0 : totalNanos / 1_000_000.0 / count;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000.0;
        }

        public double totalMillis() {
            return totalNanos / 1_000_000.0;
        }
    }

    private static final class Timer {

        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();
        final AtomicLong max = new AtomicLong();
    }

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    public long start() {
        return System.nanoTime();
    }

    public void stop(String name, long startNanos) {
        record(name, System.nanoTime() - startNanos);
    }

    public void record(String name, long nanos) {
        Timer t = timers.computeIfAbsent(name, k -> new Timer());
        t.count.increment();
        t.total.add(nanos);
        t.max.accumulateAndGet(nanos, Math::max);
    }

    public void count(String name) {
        count(name, 1);
    }

    public void count(String name, long amount) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(amount);
    }

    public Timing timing(String name) {
        Timer t = timers.get(name);
        return t == null ? new Timing(0, 0, 0) : new Timing(t.count.sum(), t.total.sum(), t.max.get());
    }

    public long counter(String name) {
        LongAdder a = counters.get(name);
        return a == null ? 0 : a.sum();
    }

    /** All timers, A to Z. */
    public Map<String, Timing> timings() {
        Map<String, Timing> out = new TreeMap<>();
        for (String name : timers.keySet()) out.put(name, timing(name));
        return out;
    }

    /** All counters, A to Z. */
    public Map<String, Long> counters() {
        Map<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : counters.entrySet()) out.put(e.getKey(), e.getValue().sum());
        return out;
    }

    public void reset() {
        timers.clear();
        counters.clear();
    }
}
