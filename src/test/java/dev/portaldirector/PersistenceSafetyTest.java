package dev.portaldirector;

import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneSnapshot;
import dev.portaldirector.scene.SceneStore;
import dev.portaldirector.scene.SceneWriter;
import dev.portaldirector.util.AtomicFiles;
import dev.portaldirector.util.Metrics;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceSafetyTest {

    @TempDir
    File dir;

    private static Scene scene(String name, int portals) {
        Scene scene = new Scene(name, "world");

        for (int i = 1; i <= portals; i++) {
            PortalSpec p = new PortalSpec(i, i * 10, 100, 0, Axis.X, BlockFace.SOUTH, true);
            scene.addPortal(p, "p" + i);
            scene.blocks().record(p.x(), p.y(), p.z(), "minecraft:air", "minecraft:obsidian");
            scene.blocks().record(p.x() + 1, p.y(), p.z(), "minecraft:stone", "minecraft:nether_portal[axis=x]");
        }

        return scene;
    }

    private static SceneWriter writer(SceneStore store, Metrics metrics) {
        return new SceneWriter(store, Logger.getLogger("test"), metrics);
    }

    // Atomic files

    @Test
    void anAtomicWriteReplacesTheFileAndLeavesNoTemporaryFile() throws IOException {
        File target = new File(dir, "a.txt");
        AtomicFiles.writeString(target.toPath(), "one");
        AtomicFiles.writeString(target.toPath(), "two");

        assertEquals("two", Files.readString(target.toPath()));
        assertFalse(new File(dir, "a.txt.tmp").exists());
    }

    @Test
    void aFailedWriteLeavesTheOldFileUntouched() throws IOException {
        File target = new File(dir, "a.txt");
        AtomicFiles.writeString(target.toPath(), "old and complete");

        assertThrows(IOException.class, () -> AtomicFiles.write(target.toPath(), out -> {
            out.write("half of the new".getBytes(StandardCharsets.UTF_8));
            throw new IOException("disk full");
        }));

        assertEquals("old and complete", Files.readString(target.toPath()));
        assertFalse(new File(dir, "a.txt.tmp").exists(), "the half written file is removed");
    }

    @Test
    void anErrorInsideTheWriterAlsoLeavesTheOldFileUntouched() throws IOException {
        File target = new File(dir, "a.txt");
        AtomicFiles.writeString(target.toPath(), "kept");

        assertThrows(IllegalStateException.class, () -> AtomicFiles.write(target.toPath(), out -> {
            throw new IllegalStateException("bug");
        }));

        assertEquals("kept", Files.readString(target.toPath()));
    }

    @Test
    void missingFoldersAreCreated() throws IOException {
        File target = new File(dir, "deep/er/a.txt");
        AtomicFiles.writeString(target.toPath(), "x");

        assertEquals("x", Files.readString(target.toPath()));
    }

    // Scene snapshots

    @Test
    void aSceneSavedFromASnapshotLoadsBackTheSame() throws IOException {
        SceneStore store = new SceneStore(dir);
        store.save(scene("default", 5));

        Scene back = store.load("default");

        assertEquals(5, back.portals().size());
        assertEquals("p3", back.nameOf(3));
        assertEquals(10, back.blocks().size());
        assertEquals("minecraft:obsidian", back.blocks().get(10, 100, 0).placed());
    }

    @Test
    void aSnapshotIsNotChangedByLaterEditsToTheScene() throws IOException {
        SceneStore store = new SceneStore(dir);
        Scene scene = scene("default", 2);
        SceneSnapshot snapshot = SceneSnapshot.of(scene);

        scene.addPortal(new PortalSpec(3, 500, 100, 0, Axis.X, BlockFace.SOUTH, true), "later");
        scene.blocks().record(500, 100, 0, "minecraft:air", "minecraft:obsidian");
        store.save(snapshot);

        Scene back = store.load("default");

        assertEquals(2, back.portals().size());
        assertEquals(4, back.blocks().size());
    }

    // The background writer

    @Test
    void theWriterPutsASceneOnDiskAfterAFlush() throws IOException {
        SceneStore store = new SceneStore(dir);
        SceneWriter writer = writer(store, new Metrics());

        writer.submit(SceneSnapshot.of(scene("default", 4)));

        assertTrue(writer.flush(10_000));
        assertEquals(4, store.load("default").portals().size());

        writer.close();
    }

    @Test
    void manySavesInARowEndWithTheNewestScene() throws IOException {
        SceneStore store = new SceneStore(dir);
        Metrics metrics = new Metrics();
        SceneWriter writer = writer(store, metrics);

        for (int i = 1; i <= 60; i++) writer.submit(SceneSnapshot.of(scene("default", i)));

        writer.flush(30_000);

        assertEquals(60, store.load("default").portals().size());
        assertTrue(metrics.timing("scene.save.write").count() <= 60);

        writer.close();
    }

    @Test
    void aForgottenSceneIsNotBroughtBackByALateWrite() {
        SceneStore store = new SceneStore(dir);
        SceneWriter writer = writer(store, new Metrics());

        writer.submit(SceneSnapshot.of(scene("gone", 3)));
        writer.forget("gone");
        store.delete("gone");
        writer.flush(10_000);

        assertFalse(store.exists("gone"));

        writer.close();
    }

    @Test
    void afterCloseASaveIsWrittenOnTheCallingThread() throws IOException {
        SceneStore store = new SceneStore(dir);
        SceneWriter writer = writer(store, new Metrics());
        writer.close();

        writer.submit(SceneSnapshot.of(scene("late", 2)));

        assertEquals(2, store.load("late").portals().size(), "nothing is dropped after the thread stopped");
    }

    @Test
    void closingWaitsForSavesThatAreStillQueued() throws IOException {
        SceneStore store = new SceneStore(dir);
        SceneWriter writer = writer(store, new Metrics());

        for (int i = 1; i <= 30; i++) writer.submit(SceneSnapshot.of(scene("s" + (i % 3), i)));

        writer.close();

        assertTrue(store.exists("s0") && store.exists("s1") && store.exists("s2"));
        assertEquals(0, writer.pendingCount());
    }

    @Test
    void aFailedWriteIsCountedAndTheWriterKeepsWorking() throws IOException {
        File scenes = new File(dir, "scenes");
        SceneStore store = new SceneStore(scenes);
        Metrics metrics = new Metrics();
        SceneWriter writer = writer(store, metrics);

        assertTrue(scenes.delete());
        Files.writeString(scenes.toPath(), "a file where the folder should be");

        writer.submit(SceneSnapshot.of(scene("default", 2)));
        writer.flush(10_000);

        assertEquals(1, metrics.counter("scene.save.failed"));

        assertTrue(scenes.delete());
        assertTrue(scenes.mkdirs());

        writer.submit(SceneSnapshot.of(scene("default", 3)));
        writer.flush(10_000);

        assertEquals(3, store.load("default").portals().size());

        writer.close();
    }

    @Test
    void severalScenesAreWrittenIndependently() throws IOException {
        SceneStore store = new SceneStore(dir);
        SceneWriter writer = writer(store, new Metrics());

        writer.submit(SceneSnapshot.of(scene("a", 1)));
        writer.submit(SceneSnapshot.of(scene("b", 2)));
        writer.submit(SceneSnapshot.of(scene("c", 3)));
        writer.flush(10_000);

        assertEquals(1, store.load("a").portals().size());
        assertEquals(2, store.load("b").portals().size());
        assertEquals(3, store.load("c").portals().size());

        writer.close();
    }
}
