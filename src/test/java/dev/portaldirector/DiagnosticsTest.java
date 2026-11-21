package dev.portaldirector;

import dev.portaldirector.diagnostics.Diagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {

    private ServerMock server;
    private PortalDirectorPlugin plugin;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PortalDirectorPlugin.class);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void theReportCoversVersionsWhatIsRunningAndWhatIsLoaded() {
        String text = String.join("\n", new Diagnostics(plugin).lines());

        for (String heading : List.of("Versions", "Running now", "Loaded", "Saving", "Server", "Plugin work")) {
            assertTrue(text.contains(heading), heading);
        }

        assertTrue(text.contains("PortalDirector "));
        assertTrue(text.contains("FastAsyncWorldEdit: not installed"));
        assertTrue(text.contains("Job: none"));
        assertTrue(text.contains("Chunk tickets held by jobs: 0"));
        assertTrue(text.contains("Scenes: 0"));
    }

    @Test
    void timingsAndCountersShowUpOnceTheyAreRecorded() {
        plugin.metrics().record("generation.plan.tick", 3_000_000);
        plugin.metrics().count("scene.save.failed");

        String text = String.join("\n", new Diagnostics(plugin).lines());

        assertTrue(text.contains("generation.plan.tick"));
        assertTrue(text.contains("Failed saves: 1"));
    }

    @Test
    void theExportedFileHasNoPlayerNamesIdsOrFolderPaths() throws IOException {
        var player = server.addPlayer("SecretPlayerName");
        plugin.groups().create("NULL");
        plugin.groups().join(plugin.groups().get("NULL"), player.getUniqueId(), player.getName());

        Path file = new Diagnostics(plugin).export();
        String text = Files.readString(file);

        assertTrue(Files.exists(file));
        assertTrue(text.contains("Members: 1") || text.contains("members: 1"));
        assertFalse(text.contains("SecretPlayerName"));
        assertFalse(text.contains(player.getUniqueId().toString()));
        assertFalse(text.contains(plugin.getDataFolder().getAbsolutePath()));
        assertFalse(text.contains(System.getProperty("user.name")));
    }
}
