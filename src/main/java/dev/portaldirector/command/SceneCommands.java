package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.RemovalJob;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** The commands that act on the portals Portal Director has built: clear, stop and list. */
final class SceneCommands {

    private final PortalDirectorPlugin pl;

    SceneCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private Scene requireCurrent() {
        Scene s = pl.scenes().current();
        if (s == null) throw new IllegalArgumentException("There are no portals yet. /pd create 30 builds some.");
        return s;
    }

    void clear(CommandSender s, Scene sc) {
        clear(s, sc, null);
    }

    /** Removes the portals' blocks; {@code after} runs once everything has been restored. */
    void clear(CommandSender s, Scene sc, Runnable after) {
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("The world " + sc.worldName() + " is not loaded.");
        if (sc.portals().isEmpty() && sc.blocks().isEmpty()) {
            Msg.info(s, "There is nothing to remove.");
            if (after != null) after.run();
            return;
        }
        pl.tnt().stop();
        RemovalJob job = new RemovalJob(pl, w, sc, pl.settings().blocksPerTick, result -> {
            pl.activeJob(null);
            if (result.cancelled()) {
                Msg.info(s, "Stopped. " + result.restored() + " blocks restored; run /pd clear again to finish.");
            } else {
                pl.netherLinks().unlinkScene(sc.name());
                pl.groups().forgetAllPortals();
                sc.clearPortals();
                sc.square(null);
                Msg.ok(s, "Cleared: " + result.restored() + " blocks restored"
                        + (result.kept() > 0 ? ", " + result.kept() + " left alone because they were changed since." : "."));
            }
            pl.scenes().save(sc);
            if (!result.cancelled() && after != null) {
                try {
                    after.run();
                } catch (IllegalArgumentException | IllegalStateException e) {
                    Msg.err(s, e.getMessage());
                }
            }
        });
        pl.activeJob(job);
        Msg.ok(s, "Removing the portals and putting the terrain back (" + sc.blocks().size() + " blocks). Blocks you changed since are left alone.");
        job.start();
    }

    Sub clearShortcut() {
        return (s, a) -> clear(s, requireCurrent());
    }

    Sub stop() {
        return (s, a) -> {
            if (pl.activeJob() == null) {
                Msg.info(s, "Nothing is running. (TNT launches are stopped with /pd tnt stop.)");
            } else {
                pl.activeJob().cancel();
                Msg.ok(s, "Stopping: " + pl.activeJob().describe() + ".");
            }
        };
    }

    /** /pd list: the id and coordinates of every portal. */
    Sub portals() {
        return (s, a) -> {
            Scene sc = pl.scenes().current();
            if (sc == null || sc.portals().isEmpty()) {
                Msg.info(s, "There are no portals yet. /pd create 30 builds some.");
                return;
            }
            int page = 1;
            if (!a.isEmpty()) {
                try {
                    page = Math.max(1, Integer.parseInt(a.get(0)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Use /pd list for the first page or /pd list 2 for the second.");
                }
            }
            int per = 25;
            List<PortalSpec> all = new ArrayList<>(sc.portals().values());
            int pages = Math.max(1, (all.size() + per - 1) / per);
            page = Math.min(page, pages);
            Msg.head(s, all.size() + " portals" + (pages > 1 ? " (page " + page + " of " + pages + ", /pd list " + Math.min(pages, page + 1) + " for more)" : ""));
            for (PortalSpec p : all.subList((page - 1) * per, Math.min(all.size(), page * per))) {
                Msg.line(s, String.format("  %-24s %d %d %d   %s", sc.nameOf(p.id()), p.anchorX(), p.anchorY(), p.anchorZ(), p.facing().name().toLowerCase(java.util.Locale.ROOT)));
            }
        };
    }
}
