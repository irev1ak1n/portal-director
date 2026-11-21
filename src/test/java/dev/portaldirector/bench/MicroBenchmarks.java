package dev.portaldirector.bench;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneStore;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Timing of the pure parts of the plugin: planning, saving a scene and filtering an explosion. Not part of the normal test
 * run. Start it with {@code gradlew benchmark -PbenchLabel=before}; the numbers go to build/benchmarks.
 */
@Tag("benchmark")
class MicroBenchmarks {

    @TempDir
    File dir;

    private static final int[] SIZES = {100, 250, 500, 1000};
    private static final int RUNS = 7;
    private final List<String> lines = new ArrayList<>();

    private static double medianMillis(Supplier<Long> once) {
        for (int i = 0; i < 3; i++) once.get();
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) times[i] = once.get();
        Arrays.sort(times);
        return times[RUNS / 2] / 1_000_000.0;
    }

    private void report(String text) {
        System.out.println(text);
        lines.add(text);
    }

    private void write(String name) throws IOException {
        Path out = Path.of("build", "benchmarks");
        Files.createDirectories(out);
        String label = System.getProperty("bench.label", "run");
        Files.write(out.resolve(name + "-" + label + ".txt"), lines);
    }

    private static GenSettings floating(int count) {
        GenSettings s = new GenSettings();
        s.count = count;
        s.radius = 150;
        s.placement = Placement.AIR;
        s.layout = Layout.RANDOM;
        s.facing = FacingMode.RANDOM;
        s.altitudeMin = 10;
        s.altitudeMax = 60;
        return s;
    }

    @Test
    void planningTimeByPortalCount() throws IOException {
        report("Planning random floating portals over flat ground, median of " + RUNS + " runs");
        for (int n : SIZES) {
            int[] placed = new int[1];
            double ms = medianMillis(() -> {
                long start = System.nanoTime();
                PortalPlanner.Session session = new PortalPlanner.Session(new BenchTerrain(), floating(n), 0, 0, List.of(), 1, new Random(42));
                while (!session.done()) session.step(500);
                placed[0] = session.placed().size();
                return System.nanoTime() - start;
            });
            report(String.format("  %4d portals asked, %4d placed: %8.1f ms", n, placed[0], ms));
        }
        write("planning");
    }

    private static Scene sceneWith(int portals, int blocksPerPortal) {
        Scene scene = new Scene("bench", "world");
        Random rnd = new Random(7);
        for (int i = 1; i <= portals; i++) {
            PortalSpec p = new PortalSpec(i, (i % 40) * 16, 100 + (i / 40) * 8, (i / 40) * 16, Axis.X, BlockFace.SOUTH, true);
            scene.addPortal(p);
            for (int b = 0; b < blocksPerPortal; b++) {
                scene.blocks().record(p.x() + b % 4, p.y() + b / 20, p.z() + (b / 4) % 5, "minecraft:air", b % 3 == 0 ? "minecraft:obsidian" : "minecraft:nether_portal[axis=x]");
            }
        }
        return scene;
    }

    @Test
    void sceneSaveTime() throws IOException {
        report("Saving a scene to disk (what runs on the main thread today), 60 blocks per portal, median of " + RUNS + " runs");
        SceneStore store = new SceneStore(dir);
        for (int n : new int[]{30, 100, 250, 500}) {
            Scene scene = sceneWith(n, 60);
            double ms = medianMillis(() -> {
                long start = System.nanoTime();
                try {
                    store.save(scene);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return System.nanoTime() - start;
            });
            long bytes = new File(dir, "bench.blocks").length() + new File(dir, "bench.yml").length();
            report(String.format("  %4d portals, %6d blocks, %6d KB: %7.1f ms", n, n * 60, bytes / 1024, ms));
        }
        write("scene-save");
    }

    @Test
    void explosionFilterTime() throws IOException {
        report("Checking one explosion of 120 blocks against every portal (terrain damage on), median of " + RUNS + " runs");
        for (int n : SIZES) {
            List<PortalSpec> portals = new ArrayList<>(sceneWith(n, 0).portals().values());
            int[][] blocks = new int[120][];
            Random rnd = new Random(3);
            PortalSpec near = portals.get(n / 2);
            for (int i = 0; i < blocks.length; i++) blocks[i] = new int[]{near.x() + rnd.nextInt(9) - 4, near.y() + rnd.nextInt(9) - 4, near.z() + rnd.nextInt(9) - 4};
            int[] kept = new int[1];
            double ms = medianMillis(() -> {
                long start = System.nanoTime();
                int count = 0;
                for (int[] b : blocks) {
                    boolean inside = false;
                    for (PortalSpec p : portals) {
                        if (p.contains(b[0], b[1], b[2])) {
                            inside = true;
                            break;
                        }
                    }
                    if (!inside) count++;
                }
                kept[0] = count;
                return System.nanoTime() - start;
            });
            report(String.format("  %4d portals: %8.3f ms per explosion (%d blocks stay in the blast)", n, ms, kept[0]));
        }
        write("explosion");
    }
}
