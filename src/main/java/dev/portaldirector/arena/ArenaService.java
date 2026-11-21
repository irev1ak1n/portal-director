package dev.portaldirector.arena;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.db.Take;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * Saves a box of blocks with a take and puts it back between takes. It needs WorldEdit or FastAsyncWorldEdit and does nothing
 * without one. A restore is only for the saved box, only after the admin confirms, and never while portals are being built or
 * removed, a sequence runs, or a portal stands inside the box.
 */
public final class ArenaService {

    private final PortalDirectorPlugin pl;
    private final File folder;
    private ArenaBackend backend;
    private boolean working;

    public ArenaService(PortalDirectorPlugin pl) {
        this.pl = pl;
        this.folder = new File(pl.getDataFolder(), "arenas");
    }

    /** Looks for WorldEdit or FastAsyncWorldEdit. Called when the plugin starts and when asked again. */
    public void detect() {
        backend = null;
        String found = null;

        for (String plugin : new String[]{"FastAsyncWorldEdit", "WorldEdit"}) {
            var p = Bukkit.getPluginManager().getPlugin(plugin);

            if (p != null && p.isEnabled()) {
                found = plugin + " " + p.getPluginMeta().getVersion();

                break;
            }
        }

        if (found == null) return;

        try {
            backend = new WorldEditBackend(found);
        } catch (LinkageError e) {
            pl.getLogger().warning(found + " is installed but its API does not match what PortalDirector expects (" + e.getMessage() + "). Arena restore stays off.");
        }
    }

    /** For tests: use another backend. */
    public void backend(ArenaBackend other) {
        this.backend = other;
    }

    public String backendName() {
        return backend == null ? null : backend.name();
    }

    private long maxBlocks() {
        return Math.max(1, pl.getConfig().getLong("arena.max-blocks", 250_000L));
    }

    private ArenaBackend need() {
        if (backend == null) {
            throw new IllegalArgumentException("Arena restore needs WorldEdit or FastAsyncWorldEdit, and neither is installed. Everything else in /pd take works without it.");
        }

        return backend;
    }

    private static void permission(CommandSender s) {
        if (!s.hasPermission("portaldirector.arena")) throw new IllegalArgumentException("You need the portaldirector.arena permission for arenas.");
    }

    private Take take(String name) throws SQLException {
        return pl.takes().find(name);
    }

    private Path file(Take take) {
        return new File(folder, take.arena().snapshot()).toPath();
    }

    // Commands

    public void define(CommandSender s, String name, int[] c) throws SQLException {
        permission(s);
        Take take = take(name);
        World world = Bukkit.getWorld(take.world());
        if (world == null) throw new IllegalArgumentException("The world " + take.world() + " is not loaded.");

        Take.Arena arena = ArenaRules.box(world.getName(), c[0], c[1], c[2], c[3], c[4], c[5], ArenaRules.snapshotName(take.name()));
        String problem = ArenaRules.problem(arena, world.getMinHeight(), world.getMaxHeight(), maxBlocks());
        if (problem != null) throw new IllegalArgumentException("That arena cannot be used: " + problem + ".");

        pl.takes().update(take.withArena(arena, System.currentTimeMillis()));
        Msg.ok(s, "Arena of " + take.name() + ": " + arena.volume() + " blocks from " + arena.minX() + " " + arena.minY() + " " + arena.minZ() + " to " + arena.maxX() + " " + arena.maxY() + " " + arena.maxZ()
                + ". Nothing was saved yet: /pd take arena save " + take.name() + " stores the blocks as they are now.");
    }

    public void capture(CommandSender s, String name) throws SQLException {
        permission(s);
        ArenaBackend b = need();
        Take take = take(name);
        if (take.arena() == null) throw new IllegalArgumentException("Take " + take.name() + " has no arena. /pd take arena set " + take.name() + " <x1 y1 z1 x2 y2 z2>");

        requireQuiet();
        World world = world(take);
        working = true;

        try {
            b.capture(world, take.arena(), file(take));
            Msg.ok(s, "Saved the " + take.arena().volume() + " blocks of the arena of " + take.name() + " with " + b.name() + ". /pd take arena restore " + take.name() + " confirm puts them back.");
        } catch (Exception e) {
            throw new IllegalArgumentException("Saving the arena failed: " + e.getMessage());
        } finally {
            working = false;
        }
    }

    public void restore(CommandSender s, String name, boolean confirmed) throws SQLException {
        permission(s);
        ArenaBackend b = need();
        Take take = take(name);
        if (take.arena() == null) throw new IllegalArgumentException("Take " + take.name() + " has no arena.");
        if (!file(take).toFile().isFile()) throw new IllegalArgumentException("The arena of " + take.name() + " was never saved. /pd take arena save " + take.name());

        requireQuiet();
        World world = world(take);

        List<PortalSpec> inside = overlappingPortals(take.arena());
        if (!inside.isEmpty()) {
            throw new IllegalArgumentException(inside.size() + " portals of the scene stand inside the arena. Restoring would race PortalDirector's own block restore. /pd clear first, then restore.");
        }

        if (!confirmed) {
            Msg.err(s, "This replaces every block in the arena (" + take.arena().volume() + " blocks from " + take.arena().minX() + " " + take.arena().minY() + " " + take.arena().minZ() + " to "
                    + take.arena().maxX() + " " + take.arena().maxY() + " " + take.arena().maxZ() + " in " + take.arena().world() + ") with what was saved. Anything built there since is lost. Run /pd take arena restore " + take.name() + " confirm to do it.");

            return;
        }

        working = true;
        long start = System.currentTimeMillis();

        try {
            b.restore(world, take.arena(), file(take));
            Msg.ok(s, "Restored the arena of " + take.name() + " (" + take.arena().volume() + " blocks) in " + (System.currentTimeMillis() - start) + " ms with " + b.name() + ".");
        } catch (Exception e) {
            throw new IllegalArgumentException("Restoring the arena failed: " + e.getMessage() + ". Nothing else was changed.");
        } finally {
            working = false;
        }
    }

    public void info(CommandSender s, String name) throws SQLException {
        Take take = take(name);
        Take.Arena a = take.arena();

        if (a == null) {
            Msg.info(s, "Take " + take.name() + " has no arena. /pd take arena set " + take.name() + " <x1 y1 z1 x2 y2 z2>");

            return;
        }

        Msg.info(s, "Arena of " + take.name() + ": " + a.volume() + " blocks in " + a.world() + " from " + a.minX() + " " + a.minY() + " " + a.minZ() + " to " + a.maxX() + " " + a.maxY() + " " + a.maxZ()
                + ", " + (file(take).toFile().isFile() ? "saved" : "not saved yet") + ". Backend: " + (backend == null ? "none installed" : backend.name()) + ".");
    }

    public void clear(CommandSender s, String name) throws SQLException {
        permission(s);
        Take take = take(name);
        pl.takes().update(take.withArena(null, System.currentTimeMillis()));
        Msg.ok(s, "Take " + take.name() + " no longer has an arena. The saved file stays in plugins/PortalDirector/arenas.");
    }

    // Rules that need the server

    private World world(Take take) {
        World world = Bukkit.getWorld(take.arena().world());
        if (world == null) throw new IllegalArgumentException("The world " + take.arena().world() + " is not loaded.");

        return world;
    }

    private void requireQuiet() {
        if (working) throw new IllegalArgumentException("An arena is being saved or restored. Wait for it to finish.");
        if (pl.activeJob() != null) throw new IllegalArgumentException("Portals are being built or removed. Wait, or /pd stop.");
        if (pl.sequences().active() != null) throw new IllegalArgumentException("A sequence is running. /pd sequence stop first.");
    }

    private List<PortalSpec> overlappingPortals(Take.Arena arena) {
        List<PortalSpec> all = new java.util.ArrayList<>();

        for (Scene scene : pl.scenes().all()) {
            if (scene.worldName().equals(arena.world())) all.addAll(scene.portalList());
        }

        return ArenaRules.overlapping(arena, all);
    }

    /** Used by diagnostics. */
    public String status() {
        return backend == null ? "no WorldEdit or FastAsyncWorldEdit installed, arena restore is off" : backend.name();
    }
}
