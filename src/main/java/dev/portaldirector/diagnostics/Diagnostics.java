package dev.portaldirector.diagnostics;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.gen.ChunkHold;
import dev.portaldirector.group.Group;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.AtomicFiles;
import dev.portaldirector.util.Job;
import dev.portaldirector.util.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What an administrator wants to know when something feels slow or wrong: versions, what is running, how much is loaded and how
 * long the plugin's own work takes. The exported file leaves out player names, ids, addresses and folder paths.
 */
public final class Diagnostics {

    private final PortalDirectorPlugin pl;

    public Diagnostics(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private static <T> String safe(Supplier<T> value) {
        try {
            T v = value.get();

            return v == null ? "unavailable" : String.valueOf(v);
        } catch (RuntimeException | LinkageError e) {
            return "unavailable";
        }
    }

    private static String millis(double value) {
        return String.format("%.2f ms", value);
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();

        versions(out);
        running(out);
        loaded(out);
        persistence(out);
        server(out);
        timings(out);

        return out;
    }

    private void versions(List<String> out) {
        out.add("Versions");
        out.add("  PortalDirector " + pl.getPluginMeta().getVersion());
        out.add("  Server " + safe(Bukkit::getVersion));
        out.add("  Java " + System.getProperty("java.version"));

        for (String name : List.of("FastAsyncWorldEdit", "WorldEdit")) {
            var plugin = Bukkit.getPluginManager().getPlugin(name);
            out.add("  " + name + ": " + (plugin == null ? "not installed" : plugin.isEnabled() ? "installed " + plugin.getPluginMeta().getVersion() : "installed but disabled"));
        }
    }

    private void running(List<String> out) {
        Job job = pl.activeJob();
        var sequences = pl.sequences();

        out.add("Running now");
        out.add("  Job: " + (job == null ? "none" : job.describe()));
        out.add("  Sequence: " + (sequences == null || sequences.active() == null ? "none" : sequences.active().describe()));
        out.add("  Players held by a sequence: " + (sequences == null ? 0 : sequences.heldCount()));
        out.add("  TNT launches running: " + pl.tnt().runningSequences() + ", minecarts alive: " + pl.tnt().activeCount());
        out.add("  Chunk tickets held by jobs: " + ChunkHold.total());
    }

    private void loaded(List<String> out) {
        int portals = 0;
        int blocks = 0;

        for (Scene scene : pl.scenes().all()) {
            portals += scene.portals().size();
            blocks += scene.blocks().size();
        }

        int members = 0;

        for (Group g : pl.groups().all()) members += g.members().size();

        out.add("Loaded");
        out.add("  Scenes: " + pl.scenes().all().size() + ", portals: " + portals + ", changed blocks remembered: " + blocks);
        out.add("  Groups: " + pl.groups().all().size() + ", members: " + members);
        out.add("  Nether partners: " + pl.netherLinks().count());
        out.add("  TNT launched: " + pl.tnt().launchedTotal() + " of at most " + pl.settings().tntMaxActive);
    }

    private void persistence(List<String> out) {
        var writer = pl.sceneWriter();
        Metrics.Timing write = pl.metrics().timing("scene.save.write");
        Metrics.Timing main = pl.metrics().timing("scene.save.main");

        out.add("Saving");
        out.add("  Scene files: " + (writer == null ? "written on the main thread" : "written on a background thread, " + writer.pendingCount() + " waiting"));
        out.add("  Main thread time per save: " + millis(main.meanMillis()) + " on average, " + millis(main.maxMillis()) + " at most (" + main.count() + " saves)");

        if (writer != null) {
            out.add("  Disk time per save: " + millis(write.meanMillis()) + " on average, " + millis(write.maxMillis()) + " at most");
        }

        out.add("  Failed saves: " + pl.metrics().counter("scene.save.failed"));

        var db = pl.database().status();
        out.add("  Database: " + (db.available() ? "ok, schema " + db.schemaVersion() + ", " + (db.fileBytes() / 1024) + " KB, " + db.queued() + " waiting" : "off (" + db.detail() + ")")
                + (db.recovered() ? ", a damaged file was moved aside at start" : ""));
        out.add("  Operations cut off at the last start: " + pl.journal().interruptedAtStart());
    }

    private void server(List<String> out) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        int entities = 0;
        int chunks = 0;
        boolean counted = true;

        for (World w : Bukkit.getWorlds()) {
            try {
                entities += w.getEntityCount();
                chunks += w.getChunkCount();
            } catch (RuntimeException e) {
                counted = false;
            }
        }

        out.add("Server");
        out.add("  Tick time: " + safe(() -> millis(Bukkit.getAverageTickTime())) + " on average, " + safe(() -> millis(worstTick())) + " worst of the last 100 ticks");
        out.add("  TPS: " + safe(() -> String.format("%.1f", Bukkit.getTPS()[0])));
        out.add("  Memory: " + used + " MB used of " + max + " MB");
        out.add("  Entities: " + (counted ? entities : "unavailable") + ", chunks loaded: " + (counted ? chunks : "unavailable"));
    }

    private static double worstTick() {
        long worst = 0;

        for (long t : Bukkit.getTickTimes()) worst = Math.max(worst, t);

        return worst / 1_000_000.0;
    }

    private void timings(List<String> out) {
        Map<String, Metrics.Timing> timings = pl.metrics().timings();

        out.add("Plugin work (since start or /pd diagnostics reset)");

        if (timings.isEmpty()) out.add("  nothing recorded yet");

        for (Map.Entry<String, Metrics.Timing> e : timings.entrySet()) {
            Metrics.Timing t = e.getValue();
            out.add(String.format("  %-24s %7d runs, %8.3f ms average, %8.3f ms worst, %9.1f ms in all", e.getKey(), t.count(), t.meanMillis(), t.maxMillis(), t.totalMillis()));
        }

        for (Map.Entry<String, Long> e : pl.metrics().counters().entrySet()) {
            out.add(String.format("  %-24s %7d", e.getKey(), e.getValue()));
        }
    }

    /** Writes the report to plugins/PortalDirector/diagnostics-<time>.txt and returns the file. */
    public Path export() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = pl.getDataFolder().toPath().resolve("diagnostics-" + stamp + ".txt");
        List<String> body = new ArrayList<>();

        body.add("PortalDirector diagnostics report, " + stamp);
        body.add("It contains versions, counts and timings. It has no player names, ids, addresses or folder paths.");
        body.add("");
        body.addAll(lines());

        AtomicFiles.writeString(file, String.join(System.lineSeparator(), body) + System.lineSeparator());

        return file;
    }
}
