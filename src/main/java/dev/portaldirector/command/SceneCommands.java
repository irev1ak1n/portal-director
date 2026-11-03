package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.gen.GenerationJob;
import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.RemovalJob;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneManager;
import dev.portaldirector.util.Msg;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Scene commands plus the shortcuts clear, stop, tp and list. */
final class SceneCommands {

    private final PortalDirectorPlugin pl;

    SceneCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub scene() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "info" : a.get(0).toLowerCase(Locale.ROOT);
                List<String> rest = a.isEmpty() ? List.of() : a.subList(1, a.size());
                switch (sub) {
                    case "list" -> list(s);
                    case "new", "create" -> create(s, rest);
                    case "save" -> save(s, rest);
                    case "load", "use", "select" -> load(s, rest);
                    case "info" -> info(s, rest.isEmpty() ? pl.scenes().current() : named(rest.get(0)));
                    case "clear" -> clear(s, rest.isEmpty() ? requireCurrent() : named(rest.get(0)));
                    case "rebuild", "regenerate" -> rebuild(s, rest.isEmpty() ? requireCurrent() : named(rest.get(0)));
                    case "delete" -> delete(s, rest);
                    default -> help(s);
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("list", "new", "select", "info", "clear", "rebuild", "save", "delete");
                if (a.size() == 2 && List.of("load", "select", "use", "info", "clear", "rebuild", "delete", "save").contains(a.get(0).toLowerCase(Locale.ROOT))) {
                    return sceneNames();
                }
                return List.of();
            }
        };
    }

    private void help(CommandSender s) {
        Msg.head(s, "Scenes");
        Msg.line(s, "A scene is one set of portals. You always work on the selected scene (shown in /pd settings).");
        Msg.cmd(s, "/pd scene list", "all scenes, the selected one is marked");
        Msg.cmd(s, "/pd scene new <name>", "start an empty scene and select it");
        Msg.cmd(s, "/pd scene select <name>", "switch to another scene");
        Msg.cmd(s, "/pd scene info", "what is in the selected scene");
        Msg.cmd(s, "/pd clear", "remove the selected scene's portals and put the terrain back");
        Msg.cmd(s, "/pd scene rebuild", "rebuild portals that were damaged");
        Msg.cmd(s, "/pd scene save <name>", "rename the selected scene");
        Msg.cmd(s, "/pd scene delete <name>", "forget an empty scene");
    }

    List<String> sceneNames() {
        return pl.scenes().all().stream().map(Scene::name).toList();
    }

    private Scene named(String name) {
        Scene s = pl.scenes().get(name);
        if (s == null) throw new IllegalArgumentException("No scene called " + name + ". /pd scene list shows them.");
        return s;
    }

    private Scene requireCurrent() {
        Scene s = pl.scenes().current();
        if (s == null) throw new IllegalArgumentException("There is no scene yet. Run /pd generate first.");
        return s;
    }

    private void list(CommandSender s) {
        Msg.head(s, "Scenes");
        if (pl.scenes().all().isEmpty()) Msg.line(s, "  none yet. /pd generate 30 makes the first one, /pd scene new <name> makes an empty one.");
        for (Scene sc : pl.scenes().all()) {
            boolean cur = sc == pl.scenes().current();
            Msg.line(s, (cur ? " > " : "   ") + sc.name() + "  " + sc.portals().size() + " portals in " + sc.worldName() + (cur ? "   (selected)" : ""));
        }
        Msg.line(s, "  Switch with /pd scene select <name>.");
    }

    private void create(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Give it a name: /pd scene new invasion");
        World w = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        Scene sc = pl.scenes().create(a.get(0), w);
        pl.scenes().select(sc);
        pl.saveSettings();
        Msg.ok(s, "Scene " + sc.name() + " created and selected for " + w.getName() + ".");
    }

    private void save(CommandSender s, List<String> a) {
        Scene cur = requireCurrent();
        if (!a.isEmpty() && !a.get(0).equalsIgnoreCase(cur.name())) {
            String old = cur.name();
            pl.scenes().rename(cur, a.get(0));
            Msg.ok(s, "Scene " + old + " saved as " + cur.name() + ".");
        } else {
            pl.scenes().save(cur);
            Msg.ok(s, "Scene " + cur.name() + " saved.");
        }
        pl.saveSettings();
    }

    private void load(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Which scene? /pd scene load <name>");
        Scene sc = named(a.get(0));
        pl.scenes().select(sc);
        pl.saveSettings();
        Msg.ok(s, "Scene " + sc.name() + " loaded: " + sc.portals().size() + " portals.");
        int missing = missingPortals(sc);
        if (missing > 0) {
            Msg.info(s, missing + " portals are not in the world right now. /pd scene rebuild puts them back.");
        }
    }

    /** Portals whose interior is not an activated portal, among those in loaded chunks. */
    private int missingPortals(Scene sc) {
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) return 0;
        int missing = 0;
        for (PortalSpec p : sc.portals().values()) {
            int[] c = p.interiorBlocks().get(0);
            if (!w.isChunkLoaded(c[0] >> 4, c[2] >> 4)) continue;
            if (w.getBlockAt(c[0], c[1], c[2]).getType() != Material.NETHER_PORTAL) missing++;
        }
        return missing;
    }

    private void info(CommandSender s, Scene sc) {
        if (sc == null) throw new IllegalArgumentException("There is no scene yet. Run /pd generate first.");
        long floating = sc.portals().values().stream().filter(PortalSpec::floating).count();
        long axisX = sc.portals().values().stream().filter(p -> p.axis() == Axis.X).count();
        Msg.head(s, "Scene " + sc.name() + (sc == pl.scenes().current() ? " (current)" : ""));
        Msg.line(s, "  World: " + sc.worldName());
        Msg.line(s, "  Portals: " + sc.portals().size() + " (" + floating + " floating, " + (sc.portals().size() - floating)
                + " on the ground; " + axisX + " along X, " + (sc.portals().size() - axisX) + " along Z)");
        Msg.line(s, "  Changed blocks tracked for removal: " + sc.blocks().size());
        for (Map.Entry<String, TreeMap<Integer, UUID>> e : sc.assignments().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Group g = pl.groups().get(e.getKey());
            Msg.line(s, "  Group " + (g == null ? e.getKey() : g.name()) + ": " + e.getValue().size() + " players assigned, "
                    + (sc.portals().size() - e.getValue().size()) + " portals empty");
        }
        Msg.line(s, "  Generator: " + pl.settings().gen.count + " x " + pl.settings().gen.placement.name().toLowerCase(Locale.ROOT)
                + ", " + pl.settings().gen.layout.name().toLowerCase(Locale.ROOT) + ", radius " + pl.settings().gen.radius
                + ", spacing " + pl.settings().gen.spacing + " (/pd settings for all)");
        int missing = missingPortals(sc);
        if (missing > 0) Msg.line(s, "  " + missing + " portals are missing from the loaded world. /pd scene rebuild fixes that.");
    }

    void clear(CommandSender s, Scene sc) {
        clear(s, sc, null);
    }

    /** Removes the scene's blocks; {@code after} runs once everything has been restored. */
    void clear(CommandSender s, Scene sc, Runnable after) {
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        if (sc.portals().isEmpty() && sc.blocks().isEmpty()) {
            Msg.info(s, "Scene " + sc.name() + " has nothing to remove.");
            if (after != null) after.run();
            return;
        }
        pl.tnt().stop();
        pl.entrance().resetScene(sc);
        RemovalJob job = new RemovalJob(pl, w, sc, pl.settings().blocksPerTick, result -> {
            pl.activeJob(null);
            if (result.cancelled()) {
                Msg.info(s, "Stopped. " + result.restored() + " blocks restored; run /pd clear again to finish.");
            } else {
                sc.portals().clear();
                sc.assignments().clear();
                sc.square(null);
                Msg.ok(s, "Scene " + sc.name() + " cleared: " + result.restored() + " blocks restored"
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
        Msg.ok(s, "Removing the portals of scene " + sc.name() + " and putting the terrain back (" + sc.blocks().size() + " blocks). Blocks you changed since are left alone.");
        job.start();
    }

    private void rebuild(CommandSender s, Scene sc) {
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        if (sc.portals().isEmpty()) throw new IllegalArgumentException("Scene " + sc.name() + " has no portals to rebuild.");
        GenerationJob job = new GenerationJob(pl, w, sc, pl.settings(), result -> {
            pl.activeJob(null);
            pl.scenes().save(sc);
            Msg.ok(s, "Rebuilt " + result.placed() + " portals in scene " + sc.name() + ".");
        });
        pl.activeJob(job);
        Msg.ok(s, "Rebuilding " + sc.portals().size() + " portals...");
        job.start();
    }

    private void delete(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Which scene? /pd scene delete <name>");
        Scene sc = named(a.get(0));
        if (!sc.portals().isEmpty() || !sc.blocks().isEmpty()) {
            throw new IllegalArgumentException("Scene " + sc.name() + " still has portals in the world. Run /pd scene clear " + sc.name() + " first.");
        }
        pl.scenes().delete(sc);
        pl.saveSettings();
        Msg.ok(s, "Scene " + sc.name() + " deleted.");
    }

    // Shortcuts

    Sub clearShortcut() {
        return (s, a) -> clear(s, requireCurrent());
    }

    Sub stop() {
        return (s, a) -> {
            if (pl.activeJob() == null) {
                Msg.info(s, "Nothing is running. (TNT waves are stopped with /pd tnt stop.)");
            } else {
                pl.activeJob().cancel();
                Msg.ok(s, "Stopping: " + pl.activeJob().describe() + ".");
            }
        };
    }

    /** /pd list: the id and coordinates of every portal in the selected scene. */
    Sub portals() {
        return (s, a) -> {
            Scene sc = pl.scenes().current();
            if (sc == null || sc.portals().isEmpty()) {
                Msg.info(s, "There are no portals yet" + (sc == null ? "" : " in scene " + sc.name()) + ". /pd generate 30 builds some.");
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
            Msg.head(s, "Scene " + sc.name() + ": " + all.size() + " portals" + (pages > 1 ? " (page " + page + " of " + pages + ", /pd list " + Math.min(pages, page + 1) + " for more)" : ""));
            for (PortalSpec p : all.subList((page - 1) * per, Math.min(all.size(), page * per))) {
                Msg.line(s, "  #" + p.id() + "   " + p.anchorX() + " " + p.anchorY() + " " + p.anchorZ());
            }
        };
    }
}
