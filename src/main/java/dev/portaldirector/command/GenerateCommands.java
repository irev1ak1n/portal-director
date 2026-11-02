package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.GenerationJob;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Generator commands: generate, the one-value setters, center, presets, settings. */
final class GenerateCommands {

    private final PortalDirectorPlugin pl;

    GenerateCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private record Center(World world, int x, int z, int dx, int dz) {
    }

    private Center center(CommandSender s) {
        Settings st = pl.settings();
        int dx = 0;
        int dz = 1;
        Player p = s instanceof Player pp ? pp : null;
        if (p != null) {
            BlockFace f = p.getFacing().getOppositeFace();
            dx = f.getModX();
            dz = f.getModZ();
        }
        if (st.centerPinned) {
            World w = Bukkit.getWorld(st.centerWorld == null ? "" : st.centerWorld);
            if (w == null) throw new IllegalArgumentException("The pinned center is in a world that is not loaded. Use /pd center reset.");
            return new Center(w, (int) Math.floor(st.centerX), (int) Math.floor(st.centerZ), dx, dz);
        }
        if (p == null) throw new IllegalArgumentException("From the console, set a center first: /pd center <x> <z> <world>");
        Location l = p.getLocation();
        return new Center(l.getWorld(), l.getBlockX(), l.getBlockZ(), dx, dz);
    }

    // /pd generate [count] [ground|air|mixed] [add]

    Sub generate() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Integer count = null;
                Placement placement = null;
                boolean add = false;
                for (String t : a) {
                    String l = t.toLowerCase(Locale.ROOT);
                    if (l.equals("add") || l.equals("append")) {
                        add = true;
                    } else if (l.matches("\\d+")) {
                        count = Integer.parseInt(l);
                    } else {
                        placement = GenSettings.parse(Placement.class, l, null);
                        if (placement == null) {
                            throw new IllegalArgumentException("Unknown option '" + t + "'. Try /pd generate 30 mixed (ground, air or mixed), add keeps existing portals.");
                        }
                    }
                }
                if (count != null) pl.settings().gen.count = clamp(count, 1, pl.settings().maxPortals);
                if (placement != null) pl.settings().gen.placement = placement;
                pl.saveSettings();
                Center c = center(s);
                Scene scene = pl.scenes().currentOrCreate(c.world());
                startGeneration(s, scene, c, add);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("10", "20", "30", "50", "100", "ground", "air", "mixed", "add")
                        : List.of("ground", "air", "mixed", "add");
            }
        };
    }

    private void startGeneration(CommandSender s, Scene scene, Center c, boolean add) {
        if (pl.activeJob() != null) {
            throw new IllegalArgumentException("Busy: " + pl.activeJob().describe() + ". Wait for it, or /pd stop.");
        }
        Settings st = pl.settings();
        if (!scene.portals().isEmpty() && !scene.worldName().equals(c.world().getName())) {
            throw new IllegalArgumentException("Scene " + scene.name() + " is in world " + scene.worldName()
                    + ". Start a new one with /pd scene new <name>.");
        }
        if (!add && !scene.portals().isEmpty()) {
            throw new IllegalArgumentException("Scene " + scene.name() + " already has " + scene.portals().size()
                    + " portals. Add more with /pd generate " + st.gen.count + " add, or start over with /pd clear.");
        }
        if (scene.portals().size() + st.gen.count > st.maxPortals) {
            throw new IllegalArgumentException("That would be more than " + st.maxPortals + " portals in one scene.");
        }
        scene.worldName(c.world().getName());
        List<PortalSpec> existing = new ArrayList<>(pl.scenes().portalsIn(c.world().getName()));
        GenerationJob job = new GenerationJob(pl, c.world(), scene, st, c.x(), c.z(), c.dx(), c.dz(), existing, result -> {
            pl.activeJob(null);
            pl.scenes().save(scene);
            pl.groups().fillAll(scene);
            if (result.failed()) {
                Msg.err(s, "Generation stopped: " + result.error() + ". What was finished is kept in scene " + scene.name() + ".");
            } else if (result.cancelled()) {
                Msg.info(s, "Cancelled. " + result.placed() + " portals were finished.");
            } else {
                String ids = scene.portals().isEmpty() ? "none" : "ids 1-" + scene.portals().lastKey();
                Msg.ok(s, "Done: " + result.placed() + " of " + result.requested() + " portals placed in scene "
                        + scene.name() + " (" + ids + ").");
                if (result.placed() < result.requested()) {
                    Msg.info(s, "No more room for the rest. Try a larger /pd radius, a smaller /pd spacing, or fewer portals.");
                }
            }
        });
        if (job.chunkCount() > st.maxChunks) {
            throw new IllegalArgumentException("A radius of " + st.gen.radius + " touches " + job.chunkCount()
                    + " chunks, more than the limit of " + st.maxChunks + ". Lower /pd radius.");
        }
        pl.activeJob(job);
        pl.scenes().select(scene);
        pl.saveSettings();
        Msg.ok(s, "Generating " + st.gen.count + " " + st.gen.placement.name().toLowerCase(Locale.ROOT) + " portals ("
                + st.gen.layout.name().toLowerCase(Locale.ROOT) + ", radius " + st.gen.radius + ") around "
                + c.x() + ", " + c.z() + " in " + c.world().getName() + ". This runs over a few seconds.");
        job.start();
    }

    // Preset

    Sub preset() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty() || a.get(0).equalsIgnoreCase("list")) {
                    Msg.head(s, "Presets");
                    for (Map.Entry<String, Presets.Preset> e : Presets.ALL.entrySet()) {
                        Msg.cmd(s, "/pd preset " + e.getKey(), e.getValue().description());
                    }
                    Msg.line(s, "Add a number to change the portal count, for example /pd preset invasion 60.");
                    return;
                }
                Presets.Preset preset = Presets.ALL.get(a.get(0).toLowerCase(Locale.ROOT));
                if (preset == null) {
                    throw new IllegalArgumentException("No preset called " + a.get(0) + ". Try /pd preset list.");
                }
                Center c = center(s);
                String name = a.get(0).toLowerCase(Locale.ROOT);
                Scene scene = pl.scenes().get(name);
                if (scene != null && !scene.portals().isEmpty()) {
                    throw new IllegalArgumentException("Scene " + name + " already has portals. Use /pd scene clear " + name
                            + " first, or /pd scene load " + name + " to use it.");
                }
                if (pl.activeJob() != null) {
                    throw new IllegalArgumentException("Busy: " + pl.activeJob().describe() + ".");
                }
                if (scene == null) scene = pl.scenes().create(name, c.world());
                preset.apply().accept(pl.settings());
                if (a.size() > 1) {
                    try {
                        pl.settings().gen.count = clamp(Integer.parseInt(a.get(1)), 1, pl.settings().maxPortals);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("'" + a.get(1) + "' is not a number of portals.");
                    }
                }
                startGeneration(s, scene, c, false);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? new ArrayList<>(Presets.ALL.keySet()) : List.of();
            }
        };
    }

    // Setters

    Sub numeric(String key, String what, String example) {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings.Option o = pl.settings().options().get(key);
                if (a.isEmpty()) {
                    Msg.info(s, what + " is " + o.get() + ". Change it with /pd " + example);
                    return;
                }
                o.set(a.get(0));
                pl.saveSettings();
                Msg.ok(s, what + " set to " + o.get() + ".");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of(String.valueOf(pl.settings().options().get(key).get())) : List.of();
            }
        };
    }

    Sub choice(String key, String what) {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings.Option o = pl.settings().options().get(key);
                if (a.isEmpty()) {
                    Msg.info(s, what + " is " + o.get() + ". Options: " + o.hint);
                    return;
                }
                o.set(a.get(0));
                pl.saveSettings();
                Msg.ok(s, what + " set to " + o.get() + ".");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? pl.settings().options().get(key).suggestions() : List.of();
            }
        };
    }

    Sub height() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                if (a.size() < 2) {
                    Msg.info(s, "Floating portals hang " + st.gen.heightMin + " to " + st.gen.heightMax
                            + " blocks above the ground. Change it with /pd height <min> <max>");
                    return;
                }
                int lo;
                int hi;
                try {
                    lo = Integer.parseInt(a.get(0));
                    hi = Integer.parseInt(a.get(1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Heights are whole numbers, like /pd height 25 80.");
                }
                st.options().get("generate.height-min").set(String.valueOf(Math.min(lo, hi)));
                st.options().get("generate.height-max").set(String.valueOf(Math.max(lo, hi)));
                pl.saveSettings();
                Msg.ok(s, "Floating portals will hang " + st.gen.heightMin + " to " + st.gen.heightMax + " blocks above the ground.");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("10", "25") : a.size() == 2 ? List.of("50", "80") : List.of();
            }
        };
    }

    Sub centerCmd() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                if (a.isEmpty() || a.get(0).equalsIgnoreCase("show")) {
                    Msg.info(s, st.centerPinned
                            ? "Center is pinned at " + (int) st.centerX + ", " + (int) st.centerZ + " in " + st.centerWorld
                            + ". /pd center reset goes back to your position."
                            : "Center is wherever you stand when you generate. Pin one with /pd center here or /pd center <x> <z>.");
                    return;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                if (first.equals("reset") || first.equals("clear")) {
                    st.centerPinned = false;
                } else if (first.equals("here")) {
                    if (!(s instanceof Player p)) throw new IllegalArgumentException("Only players can use 'here'.");
                    pin(st, p.getWorld(), p.getLocation().getX(), p.getLocation().getZ());
                } else {
                    if (a.size() < 2) throw new IllegalArgumentException("Use /pd center here, /pd center <x> <z> [world] or /pd center reset.");
                    double x;
                    double z;
                    try {
                        x = Double.parseDouble(a.get(0));
                        z = Double.parseDouble(a.get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Coordinates must be numbers.");
                    }
                    World w = a.size() > 2 ? Bukkit.getWorld(a.get(2)) : s instanceof Player p ? p.getWorld() : null;
                    if (w == null) throw new IllegalArgumentException("Name a loaded world: /pd center <x> <z> <world>");
                    pin(st, w, x, z);
                }
                pl.saveSettings();
                Msg.ok(s, st.centerPinned ? "Center pinned at " + (int) st.centerX + ", " + (int) st.centerZ + " in " + st.centerWorld + "."
                        : "Center is your own position again.");
            }

            private void pin(Settings st, World w, double x, double z) {
                st.centerPinned = true;
                st.centerWorld = w.getName();
                st.centerX = x;
                st.centerZ = z;
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("here", "reset", "show");
                if (a.size() == 3) return Bukkit.getWorlds().stream().map(World::getName).toList();
                return List.of();
            }
        };
    }

    Sub settings() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                boolean all = !a.isEmpty() && a.get(0).equalsIgnoreCase("all");
                Msg.head(s, "Generator settings");
                for (Settings.Option o : st.options().values()) {
                    if (!all && !o.key.startsWith("generate.")) continue;
                    Msg.line(s, "  " + o.key.replace("generate.", "") + " = " + o.get());
                }
                if (!all) Msg.line(s, "More settings: /pd settings all. Change any with /pd set <name> <value>.");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("all") : List.of();
            }
        };
    }

    Sub set() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.size() < 2) throw new IllegalArgumentException("Use /pd set <name> <value>. /pd settings all lists the names.");
                Settings.Option o = find(a.get(0));
                if (o == null) throw new IllegalArgumentException("No setting called " + a.get(0) + ". /pd settings all lists them.");
                o.set(a.get(1));
                pl.saveSettings();
                Msg.ok(s, o.key + " is now " + o.get() + ".");
            }

            private Settings.Option find(String name) {
                Map<String, Settings.Option> all = pl.settings().options();
                if (all.containsKey(name)) return all.get(name);
                for (Settings.Option o : all.values()) {
                    if (o.key.endsWith("." + name)) return o;
                }
                return null;
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return new ArrayList<>(pl.settings().options().keySet());
                if (a.size() == 2) {
                    Settings.Option o = find(a.get(0));
                    return o == null ? List.of() : o.suggestions();
                }
                return List.of();
            }
        };
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
