package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.GenerationJob;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Compass;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.BiomeSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Generating portals and the settings that shape them. */
final class GenerateCommands {

    /** Names admins type, mapped to the setting they change. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("mode", "generate.placement");
        ALIASES.put("placement", "generate.placement");
        ALIASES.put("count", "generate.count");
        ALIASES.put("radius", "generate.radius");
        ALIASES.put("spacing", "generate.spacing");
        ALIASES.put("layout", "generate.layout");
        ALIASES.put("orientation", "generate.orientation");
        ALIASES.put("facing", "generate.facing");
        ALIASES.put("air-ratio", "generate.air-ratio");
        ALIASES.put("slope", "generate.max-slope");
        ALIASES.put("min-y", "generate.min-y");
        ALIASES.put("max-y", "generate.max-y");
        ALIASES.put("height-min", "generate.height-min");
        ALIASES.put("height-max", "generate.height-max");
        ALIASES.put("fuse", "tnt.fuse-seconds");
        ALIASES.put("tnt-limit", "tnt.max-active");
    }

    /** Settings shown by /pd settings, in this order, with the name admins use. */
    private static final String[][] SHOWN = {
            {"count", "generate.count"}, {"mode", "generate.placement"}, {"radius", "generate.radius"},
            {"spacing", "generate.spacing"}, {"layout", "generate.layout"}, {"orientation", "generate.orientation"},
            {"facing", "generate.facing"}, {"height min", "generate.height-min"}, {"height max", "generate.height-max"},
            {"air-ratio", "generate.air-ratio"}, {"slope", "generate.max-slope"}, {"min-y", "generate.min-y"}, {"max-y", "generate.max-y"}};

    private final PortalDirectorPlugin pl;
    private final SceneCommands scenes;

    GenerateCommands(PortalDirectorPlugin pl, SceneCommands scenes) {
        this.pl = pl;
        this.scenes = scenes;
    }

    private record Center(World world, int x, int z, int dx, int dz, boolean pinned) {
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
            if (w == null) throw new IllegalArgumentException("The pinned center is in a world that is not loaded. Use /pd center reset to build around yourself again.");
            return new Center(w, (int) Math.floor(st.centerX), (int) Math.floor(st.centerZ), dx, dz, true);
        }
        if (p == null) throw new IllegalArgumentException("The console has no position. Pin a center first: /pd center <x> <z> <world>");
        Location l = p.getLocation();
        return new Center(l.getWorld(), l.getBlockX(), l.getBlockZ(), dx, dz, false);
    }

    // /pd generate [count] [ground|air|mixed] [add|replace]

    Sub generate() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Integer count = null;
                Placement placement = null;
                boolean add = false;
                boolean replace = false;
                for (String t : a) {
                    String l = t.toLowerCase(Locale.ROOT);
                    if (l.equals("add") || l.equals("append") || l.equals("more")) {
                        add = true;
                    } else if (l.equals("replace") || l.equals("new") || l.equals("again")) {
                        replace = true;
                    } else if (l.matches("\\d+")) {
                        count = Integer.parseInt(l);
                    } else {
                        placement = GenSettings.parse(Placement.class, l, null);
                        if (placement == null) {
                            throw new IllegalArgumentException("I did not understand '" + t + "'. Try /pd generate 30, /pd generate 30 air, or /pd generate 30 mixed add. "
                                    + "(ground, air or mixed picks the kind; add or replace says what to do with portals that already exist.)");
                        }
                    }
                }
                if (add && replace) throw new IllegalArgumentException("Choose either add (keep the existing portals) or replace (remove them first), not both.");
                Settings st = pl.settings();
                if (count != null && (count < 1 || count > st.maxPortals)) {
                    throw new IllegalArgumentException("A scene can hold 1 to " + st.maxPortals + " portals, so " + count + " is out of range.");
                }
                if (count != null) st.gen.count = count;
                if (placement != null) st.gen.placement = placement;
                pl.saveSettings();
                Center c = center(s);
                Scene scene = pl.scenes().currentOrCreate(c.world());
                if (pl.activeJob() != null) throw pl.busy();
                if (!scene.portals().isEmpty() && !scene.worldName().equals(c.world().getName())) {
                    throw new IllegalArgumentException("Scene " + scene.name() + " is in the world " + scene.worldName() + ", and you are in " + c.world().getName()
                            + ". Use /pd scene new <name> to start a separate scene here.");
                }
                if (!scene.portals().isEmpty() && !add && !replace) {
                    double[] from = Report.reference(s, scene);
                    PortalSpec nearest = scene.portals().values().stream()
                            .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.centerX() - from[0], p.centerZ() - from[1]))).orElseThrow();
                    throw new IllegalArgumentException("Scene " + scene.name() + " already has " + scene.portals().size() + " portals (the nearest is "
                            + Report.where(from, nearest) + " from " + (s instanceof Player ? "you" : "the middle") + "; /pd portal list shows them all, /pd portal tp nearest takes you there). "
                            + "To keep them and build " + st.gen.count + " more: /pd generate " + st.gen.count + " add. "
                            + "To remove them and build new ones: /pd generate " + st.gen.count + " replace. "
                            + "To keep them and start a separate scene: /pd scene new <name>.");
                }
                if (replace && !scene.portals().isEmpty()) {
                    Msg.info(s, "Removing the " + scene.portals().size() + " portals of scene " + scene.name() + " first...");
                    scenes.clear(s, scene, () -> startGeneration(s, scene, c, false));
                    return;
                }
                startGeneration(s, scene, c, add);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("10", "20", "30", "50", "100", "ground", "air", "mixed", "add", "replace");
                return List.of("ground", "air", "mixed", "add", "replace");
            }
        };
    }

    private void startGeneration(CommandSender s, Scene scene, Center c, boolean add) {
        if (pl.activeJob() != null) throw pl.busy();
        Settings st = pl.settings();
        if (scene.portals().size() + st.gen.count > st.maxPortals) {
            throw new IllegalArgumentException("That would make " + (scene.portals().size() + st.gen.count) + " portals, and a scene can hold at most " + st.maxPortals
                    + ". Build fewer, or change the limit with /pd set max-portals.");
        }
        scene.worldName(c.world().getName());
        List<PortalSpec> existing = new ArrayList<>(pl.scenes().portalsIn(c.world().getName()));
        int before = scene.portals().size();
        GenerationJob job = new GenerationJob(pl, c.world(), scene, st, c.x(), c.z(), c.dx(), c.dz(), existing, result -> {
            pl.activeJob(null);
            pl.scenes().save(scene);
            pl.groups().fillAll(scene);
            if (result.failed()) {
                Msg.err(s, "Something went wrong while building (" + result.error() + "). The portals that were finished are kept in scene " + scene.name() + ".");
            } else if (result.cancelled()) {
                Msg.info(s, "Cancelled. " + result.placed() + " portals were finished and kept.");
            } else {
                Msg.ok(s, "Built " + result.placed() + " of " + result.requested() + " portals in scene " + scene.name()
                        + (before > 0 ? " (" + scene.portals().size() + " in total)" : "") + ".");
                if (result.placed() < result.requested()) {
                    Msg.info(s, "Only " + result.placed() + " fit in this area. " + shortfall(result.reasons()) + " Make room with /pd set radius " + Math.min(400, st.gen.radius * 3 / 2)
                            + ", /pd set spacing " + Math.max(2, st.gen.spacing / 2) + ", or try /pd set mode air.");
                }
                Report.formation(s, pl, scene);
            }
        });
        if (job.chunkCount() > st.maxChunks) {
            throw new IllegalArgumentException("A radius of " + st.gen.radius + " blocks covers " + job.chunkCount() + " chunks, more than the limit of " + st.maxChunks
                    + ". Use a smaller radius, for example /pd set radius " + Math.max(8, st.gen.radius / 2) + ".");
        }
        pl.activeJob(job);
        pl.scenes().select(scene);
        pl.saveSettings();
        Msg.ok(s, "Building " + st.gen.count + " " + st.gen.placement.name().toLowerCase(Locale.ROOT) + " portals (" + st.gen.layout.name().toLowerCase(Locale.ROOT)
                + " layout) within " + st.gen.radius + " blocks of " + (c.pinned() ? "the pinned center " : "") + c.x() + ", " + c.z() + ". This takes a few seconds...");
        job.start();
    }

    private static String shortfall(Map<String, Integer> reasons) {
        if (reasons == null || reasons.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Spots skipped: ");
        reasons.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3)
                .forEach(e -> sb.append(e.getValue()).append(" (").append(e.getKey()).append("), "));
        sb.setLength(sb.length() - 2);
        return sb.append('.').toString();
    }

    // Presets

    Sub preset() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty() || a.get(0).equalsIgnoreCase("list")) {
                    Msg.head(s, "Ready-made scenes");
                    for (Map.Entry<String, Presets.Preset> e : Presets.ALL.entrySet()) {
                        Msg.cmd(s, "/pd preset " + e.getKey(), e.getValue().description());
                    }
                    Msg.line(s, "Add a number to change the portal count, for example /pd preset invasion 60.");
                    return;
                }
                Presets.Preset preset = Presets.ALL.get(a.get(0).toLowerCase(Locale.ROOT));
                if (preset == null) throw new IllegalArgumentException("There is no preset called " + a.get(0) + ". /pd preset list shows them.");
                Center c = center(s);
                String name = a.get(0).toLowerCase(Locale.ROOT);
                Scene scene = pl.scenes().get(name);
                if (scene != null && !scene.portals().isEmpty()) {
                    throw new IllegalArgumentException("A scene called " + name + " already exists with " + scene.portals().size()
                            + " portals. /pd scene select " + name + " switches to it; then /pd clear removes it so you can build the preset again.");
                }
                if (pl.activeJob() != null) throw pl.busy();
                if (scene == null) scene = pl.scenes().create(name, c.world());
                preset.apply().accept(pl.settings());
                if (a.size() > 1) {
                    try {
                        pl.settings().gen.count = Math.max(1, Math.min(pl.settings().maxPortals, Integer.parseInt(a.get(1))));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("'" + a.get(1) + "' is not a number of portals. Example: /pd preset " + name + " 40");
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

    // Settings

    private record Found(Settings.Option option, int used) {
    }

    /** Matches what the admin typed ("radius", "height min", "tnt fuse seconds") to a setting. */
    private Found find(List<String> tokens) {
        Map<String, Settings.Option> all = pl.settings().options();
        for (int k = Math.min(3, tokens.size()); k >= 1; k--) {
            String name = String.join("-", tokens.subList(0, k)).toLowerCase(Locale.ROOT);
            if (all.containsKey(name)) return new Found(all.get(name), k);
            String key = ALIASES.get(name);
            if (key != null) return new Found(all.get(key), k);
            List<Settings.Option> hits = new ArrayList<>();
            for (Settings.Option o : all.values()) {
                String flat = o.key.replace('.', '-');
                if (flat.equals(name) || flat.endsWith("-" + name)) hits.add(o);
            }
            if (hits.size() == 1) return new Found(hits.get(0), k);
        }
        return null;
    }

    Sub set() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty()) {
                    Msg.info(s, "Change a setting with /pd set <name> <value>, for example /pd set radius 50 or /pd set mode air. /pd settings shows all of them.");
                    return;
                }
                Found f = find(a);
                if (f == null) {
                    throw new IllegalArgumentException("There is no setting called '" + String.join(" ", a) + "'. /pd settings lists the names, and Tab completion after /pd set shows them.");
                }
                if (f.used() >= a.size()) {
                    Msg.info(s, shortName(f.option()) + " is " + f.option().get() + " (" + f.option().hint + "). Change it with /pd set " + shortName(f.option()) + " <value>.");
                    return;
                }
                String value = String.join(" ", a.subList(f.used(), a.size()));
                f.option().set(value);
                pl.saveSettings();
                Msg.ok(s, shortName(f.option()) + " is now " + f.option().get() + ".");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) {
                    List<String> names = new ArrayList<>(List.of("count", "mode", "radius", "spacing", "layout", "orientation", "facing", "height", "air-ratio", "min-y", "max-y", "slope"));
                    for (Settings.Option o : pl.settings().options().values()) {
                        if (!o.key.startsWith("generate.")) names.add(o.key);
                    }
                    return names;
                }
                if (a.get(0).equalsIgnoreCase("height") && a.size() == 2) return List.of("min", "max");
                Found f = find(a.subList(0, a.size() - 1));
                if (f == null || f.used() != a.size() - 1) return List.of();
                List<String> sug = new ArrayList<>(f.option().suggestions());
                if (sug.isEmpty()) sug.add(String.valueOf(f.option().get()));
                return sug;
            }
        };
    }

    private static String shortName(Settings.Option o) {
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (e.getValue().equals(o.key) && !e.getKey().equals("placement") && !e.getKey().startsWith("height-")) return e.getKey();
        }
        return o.key.startsWith("generate.height-") ? o.key.replace("generate.height-", "height ") : o.key;
    }

    Sub settings() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                String which = a.isEmpty() ? "generate" : a.get(0).toLowerCase(Locale.ROOT);
                Scene sc = pl.scenes().current();
                Msg.head(s, "Portal Director settings");
                Msg.line(s, "  Selected scene: " + (sc == null ? "none yet (the first /pd generate creates one)" : sc.name() + ", " + sc.portals().size() + " portals")
                        + "    Center: " + (st.centerPinned ? (int) st.centerX + ", " + (int) st.centerZ + " in " + st.centerWorld + " (pinned)" : "wherever you stand"));
                if (which.equals("generate") || which.equals("generation") || which.equals("portals")) {
                    Msg.line(s, "  Portal generation - change with /pd set <name> <value>:");
                    for (String[] row : SHOWN) {
                        Settings.Option o = st.options().get(row[1]);
                        Msg.line(s, String.format("    %-11s %-8s %s", row[0], o.get(), o.hint));
                    }
                    Msg.line(s, "  Height min/max are blocks ABOVE the ground for floating portals. min-y/max-y are absolute limits for every portal.");
                    Msg.line(s, "  More: /pd settings tnt | players | square | all");
                    return;
                }
                String prefix = switch (which) {
                    case "tnt" -> "tnt.";
                    case "players", "entrance", "group", "groups" -> "entrance.";
                    case "square" -> "square.";
                    case "all" -> "";
                    default -> throw new IllegalArgumentException("Pick one of: generate, tnt, players, square, all.");
                };
                for (Settings.Option o : st.options().values()) {
                    if (o.key.startsWith(prefix) || (which.equals("players") && o.key.startsWith("groups."))) {
                        Msg.line(s, String.format("    %-34s %-8s %s", o.key, o.get(), o.hint));
                    }
                }
                if (which.equals("tnt")) Msg.line(s, "  " + (st.tntPreview ? "SAFE MODE is on (nothing explodes)." : "Real explosions are on.") + " Details: /pd tnt status");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("generate", "tnt", "players", "square", "all") : List.of();
            }
        };
    }

    // Center

    Sub centerCmd() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                if (a.isEmpty() || a.get(0).equalsIgnoreCase("show")) {
                    Msg.info(s, st.centerPinned
                            ? "The center is pinned at " + (int) st.centerX + ", " + (int) st.centerZ + " in " + st.centerWorld + ". /pd center reset goes back to building around you."
                            : "Portals are built around wherever you stand when you run /pd generate. /pd center here pins the spot you are on now, /pd center <x> <z> pins coordinates.");
                    return;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                if (first.equals("reset") || first.equals("clear")) {
                    st.centerPinned = false;
                } else if (first.equals("here")) {
                    if (!(s instanceof Player p)) throw new IllegalArgumentException("Only players can use 'here'. From the console give coordinates: /pd center <x> <z> <world>");
                    pin(st, p.getWorld(), p.getLocation().getX(), p.getLocation().getZ());
                } else {
                    if (a.size() < 2) throw new IllegalArgumentException("Use /pd center here, /pd center <x> <z> [world], or /pd center reset.");
                    double x;
                    double z;
                    try {
                        x = Double.parseDouble(a.get(0));
                        z = Double.parseDouble(a.get(1));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Coordinates are numbers, like /pd center 100 -250.");
                    }
                    World w = a.size() > 2 ? Bukkit.getWorld(a.get(2)) : s instanceof Player p ? p.getWorld() : null;
                    if (w == null) throw new IllegalArgumentException("Say which world: /pd center " + a.get(0) + " " + a.get(1) + " <world>");
                    pin(st, w, x, z);
                }
                pl.saveSettings();
                Msg.ok(s, st.centerPinned ? "Center pinned at " + (int) st.centerX + ", " + (int) st.centerZ + " in " + st.centerWorld + "." : "Portals will be built around you again.");
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

    // Finding things

    Sub where() {
        return (s, a) -> {
            Scene sc = pl.scenes().current();
            if (sc == null) throw new IllegalArgumentException("There is no scene yet. /pd generate 30 builds portals around you.");
            Report.formation(s, pl, sc);
        };
    }

    /** /pd goto plains: a green, open place to test in. */
    Sub gotoCmd() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (!(s instanceof Player player)) throw new IllegalArgumentException("Only players can teleport.");
                if (a.isEmpty() || !a.get(0).equalsIgnoreCase("plains")) {
                    throw new IllegalArgumentException("Use /pd goto plains to be taken to the nearest grassy plains area.");
                }
                World w = player.getWorld();
                if (w.getEnvironment() != World.Environment.NORMAL) throw new IllegalArgumentException("Plains only exist in the overworld. Go there first.");
                Msg.info(s, "Looking for the nearest plains...");
                BiomeSearchResult r = w.locateNearestBiome(player.getLocation(), 6400, Biome.PLAINS, Biome.SUNFLOWER_PLAINS);
                if (r == null) throw new IllegalArgumentException("No plains within 6400 blocks. Try another spot with /tp first.");
                Location at = r.getLocation();
                int cx = at.getBlockX();
                int cz = at.getBlockZ();
                double fromX = player.getLocation().getX();
                double fromZ = player.getLocation().getZ();
                w.getChunkAtAsync(cx >> 4, cz >> 4, true).whenComplete((c, err) -> pl.getServer().getScheduler().runTask(pl, () -> {
                    if (!player.isOnline()) return;
                    BukkitTerrain t = new BukkitTerrain(w);
                    for (int ring = 0; ring <= 6; ring++) {
                        for (int dx = -ring; dx <= ring; dx++) {
                            for (int dz = -ring; dz <= ring; dz++) {
                                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                                int x = cx + dx * 6;
                                int z = cz + dz * 6;
                                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                                int top = t.topY(x, z);
                                if (top < w.getMinHeight() || t.isLiquid(x, top, z) || w.getBlockAt(x, top, z).getType() != Material.GRASS_BLOCK) continue;
                                player.teleportAsync(new Location(w, x + 0.5, top + 1, z + 0.5));
                                Msg.ok(player, "You are on grass in a plains area " + Compass.describe(x - fromX, z - fromZ) + " from where you were. Try /pd generate 30.");
                                return;
                            }
                        }
                    }
                    Msg.err(player, "I found plains near " + cx + ", " + cz + " but no dry grass to stand on. Try /tp " + cx + " 100 " + cz + " and look around.");
                }));
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("plains") : List.of();
            }
        };
    }

    // Older one-value commands, kept as shortcuts for /pd set

    Sub numeric(String key, String what, String example) {
        return shortcut(key, what);
    }

    Sub choice(String key, String what) {
        return shortcut(key, what);
    }

    private Sub shortcut(String key, String what) {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings.Option o = pl.settings().options().get(key);
                if (a.isEmpty()) {
                    Msg.info(s, what + " is " + o.get() + " (" + o.hint + "). Change it with /pd set " + shortName(o) + " <value>.");
                    return;
                }
                o.set(a.get(0));
                pl.saveSettings();
                Msg.ok(s, what + " is now " + o.get() + ".");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() != 1) return List.of();
                Settings.Option o = pl.settings().options().get(key);
                return o.suggestions().isEmpty() ? List.of(String.valueOf(o.get())) : o.suggestions();
            }
        };
    }

    Sub height() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                if (a.size() == 2 && (a.get(0).equalsIgnoreCase("min") || a.get(0).equalsIgnoreCase("max"))) {
                    st.options().get("generate.height-" + a.get(0).toLowerCase(Locale.ROOT)).set(a.get(1));
                    if (st.gen.heightMin > st.gen.heightMax) {
                        int tmp = st.gen.heightMin;
                        st.gen.heightMin = st.gen.heightMax;
                        st.gen.heightMax = tmp;
                    }
                    pl.saveSettings();
                    Msg.ok(s, "Floating portals now hang " + st.gen.heightMin + " to " + st.gen.heightMax + " blocks above the ground.");
                    return;
                }
                if (a.size() < 2) {
                    Msg.info(s, "Floating portals hang " + st.gen.heightMin + " to " + st.gen.heightMax + " blocks above the ground. Change it with /pd set height min 10 and /pd set height max 40.");
                    return;
                }
                int lo;
                int hi;
                try {
                    lo = Integer.parseInt(a.get(0));
                    hi = Integer.parseInt(a.get(1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Heights are whole numbers, for example /pd height 25 80 or /pd set height max 40.");
                }
                st.options().get("generate.height-min").set(String.valueOf(Math.min(lo, hi)));
                st.options().get("generate.height-max").set(String.valueOf(Math.max(lo, hi)));
                pl.saveSettings();
                Msg.ok(s, "Floating portals now hang " + st.gen.heightMin + " to " + st.gen.heightMax + " blocks above the ground.");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("min", "max", "10", "25");
                return a.size() == 2 ? List.of("20", "40", "80") : List.of();
            }
        };
    }
}
