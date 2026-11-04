package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.FormationPlanner;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.GenerationMode;
import dev.portaldirector.gen.RevealOrder;
import dev.portaldirector.gen.GenerationJob;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.gen.SquarePlanner;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SquareInfo;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/** Generating portals and the settings that shape them. */
final class GenerateCommands {

    /** Names admins type, mapped to the setting they change. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("mode", "generate.placement");
        ALIASES.put("placement", "generate.placement");
        ALIASES.put("count", "generate.count");
        ALIASES.put("radius", "generate.radius");
        ALIASES.put("layout", "generate.layout");
        ALIASES.put("generation", "generate.generation");
        ALIASES.put("orientation", "generate.orientation");
        ALIASES.put("facing", "generate.facing");
        ALIASES.put("air-ratio", "generate.air-ratio");
        ALIASES.put("slope", "generate.max-slope");
        ALIASES.put("min-y", "generate.min-y");
        ALIASES.put("max-y", "generate.max-y");
        ALIASES.put("fuse", "tnt.fuse-seconds");
        ALIASES.put("tnt-limit", "tnt.max-active");
    }

    /** What /pd settings shows for each layout: {name admins type, setting key}. */
    private static final String[] SPACING = {"spacing horizontal", "generate.spacing-horizontal", "spacing vertical", "generate.spacing-vertical"};
    private static final String[] LIMITS = {"min-y", "generate.min-y", "max-y", "generate.max-y"};

    private final PortalDirectorPlugin pl;
    private final SceneCommands scenes;

    GenerateCommands(PortalDirectorPlugin pl, SceneCommands scenes) {
        this.pl = pl;
        this.scenes = scenes;
    }

    private record Center(World world, int x, int y, int z, BlockFace facingAuto, boolean pinned) {
    }

    private Center center(CommandSender s) {
        Settings st = pl.settings();
        Player p = s instanceof Player pp ? pp : null;
        BlockFace auto = p == null ? BlockFace.SOUTH : p.getFacing().getOppositeFace();
        if (st.centerPinned) {
            World w = Bukkit.getWorld(st.centerWorld == null ? "" : st.centerWorld);
            if (w == null) throw new IllegalArgumentException("The pinned center is in a world that is not loaded. Use /pd set center reset to build around yourself again.");
            int y = st.centerHasY ? (int) Math.floor(st.centerY) : p != null && p.getWorld() == w ? p.getLocation().getBlockY() : w.getSeaLevel() + 1;
            return new Center(w, (int) Math.floor(st.centerX), y, (int) Math.floor(st.centerZ), auto, true);
        }
        if (p == null) throw new IllegalArgumentException("The console has no position. Pin a center first: /pd set center <x> <y> <z> <world>");
        Location l = p.getLocation();
        return new Center(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), auto, false);
    }

    /** How many portals the current layout settings make. */
    static int expectedCount(GenSettings g) {
        return switch (g.layout) {
            case RANDOM -> g.count;
            case GRID -> FormationPlanner.gridCount(g.gridRows, g.gridColumns);
            case SQUARE -> FormationPlanner.squareCount(g.squarePerSide, g.squareLayers);
            case WAVE -> FormationPlanner.waveCount(g.waveRows, g.wavePerRow);
        };
    }

    private static String describeLayout(GenSettings g) {
        return switch (g.layout) {
            case RANDOM -> "random: " + g.count + " portals within " + g.radius + " blocks";
            case GRID -> "grid: " + g.gridRows + " rows x " + g.gridColumns + " columns = " + expectedCount(g) + " portals";
            case SQUARE -> "square: " + g.squarePerSide + " per side x 4 sides x " + g.squareLayers + " layer" + (g.squareLayers == 1 ? "" : "s") + " = " + expectedCount(g) + " portals";
            case WAVE -> "wave: " + g.waveRows + " rows x " + g.wavePerRow + " per row = " + expectedCount(g) + " portals";
        };
    }

    // /pd create [count] [ground|air|mixed] [add|replace] [partial]

    Sub generate() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Integer count = null;
                Placement placement = null;
                boolean add = false;
                boolean replace = false;
                boolean partial = false;
                for (String t : a) {
                    String l = t.toLowerCase(Locale.ROOT);
                    if (l.equals("add") || l.equals("append") || l.equals("more")) {
                        add = true;
                    } else if (l.equals("replace") || l.equals("new") || l.equals("again")) {
                        replace = true;
                    } else if (l.equals("partial")) {
                        partial = true;
                    } else if (l.matches("\\d+")) {
                        count = Integer.parseInt(l);
                    } else {
                        placement = GenSettings.parse(Placement.class, l, null);
                        if (placement == null) {
                            throw new IllegalArgumentException("I did not understand '" + t + "'. Try /pd create, /pd create 50 (random layout), /pd create air, or /pd create add. "
                                    + "(ground, air or mixed picks where portals stand; add or replace says what to do with portals that already exist.)");
                        }
                    }
                }
                if (add && replace) throw new IllegalArgumentException("Choose either add (keep the existing portals) or replace (remove them first), not both.");
                Settings st = pl.settings();
                Layout layout = st.gen.layout;
                if (count != null && layout != Layout.RANDOM) {
                    throw new IllegalArgumentException("The " + layout.name().toLowerCase(Locale.ROOT) + " layout works out the number of portals itself ("
                            + describeLayout(st.gen) + "). Change it with " + layoutHint(layout) + ", or use /pd set layout random to choose a number.");
                }
                if (count != null && (count < 1 || count > st.maxPortals)) {
                    throw new IllegalArgumentException("Between 1 and " + st.maxPortals + " portals can exist at once, so " + count + " is out of range.");
                }
                if (st.gen.generation == GenerationMode.DELAYED) {
                    String problem = RevealOrder.problem(layout, st.gen.order);
                    if (problem != null) throw new IllegalArgumentException(problem);
                }
                if (count != null) st.gen.count = count;
                if (placement != null) st.gen.placement = placement;
                pl.saveSettings();
                Center c = center(s);
                Scene scene = pl.scenes().currentOrCreate(c.world());
                if (pl.activeJob() != null) throw pl.busy();
                if (!scene.portals().isEmpty() && !scene.worldName().equals(c.world().getName())) {
                    throw new IllegalArgumentException("The existing portals are in the world " + scene.worldName() + ", and you are in " + c.world().getName()
                            + ". Run /pd clear first, then generate here.");
                }
                int expected = expectedCount(st.gen);
                if (!scene.portals().isEmpty() && !add && !replace) {
                    throw new IllegalArgumentException("There are already " + scene.portals().size() + " portals (/pd list shows where they are). "
                            + "To keep them and build " + expected + " more: /pd create add. "
                            + "To remove them and build new ones: /pd create replace. "
                            + "To just remove them: /pd clear.");
                }
                if (layout == Layout.SQUARE && scene.square() != null && !replace) {
                    throw new IllegalArgumentException("There is already a square. /pd create replace swaps it for a new one, /pd clear removes it.");
                }
                final boolean partialFinal = partial;
                final boolean addFinal = add;
                if (replace && !scene.portals().isEmpty()) {
                    Msg.info(s, "Removing the " + scene.portals().size() + " existing portals first...");
                    scenes.clear(s, scene, () -> begin(s, scene, c, false, partialFinal));
                    return;
                }
                begin(s, scene, c, addFinal, partialFinal);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1 && pl.settings().gen.layout == Layout.RANDOM) return List.of("10", "30", "50", "100", "ground", "air", "mixed", "add", "replace", "partial");
                return List.of("ground", "air", "mixed", "add", "replace", "partial");
            }
        };
    }

    private static String layoutHint(Layout l) {
        return switch (l) {
            case GRID -> "/pd set grid rows <n> and /pd set grid columns <n>";
            case SQUARE -> "/pd set square per-side <n> and /pd set square layers <n>";
            case WAVE -> "/pd set wave rows <n> and /pd set wave per-row <n>";
            case RANDOM -> "/pd set count <n>";
        };
    }

    private void begin(CommandSender s, Scene scene, Center c, boolean add, boolean partial) {
        if (pl.settings().gen.layout == Layout.RANDOM) startGeneration(s, scene, c, add);
        else startFormation(s, scene, c, partial);
    }

    // The random layout

    private void startGeneration(CommandSender s, Scene scene, Center c, boolean add) {
        if (pl.activeJob() != null) throw pl.busy();
        Settings st = pl.settings();
        if (scene.portals().size() + st.gen.count > st.maxPortals) {
            throw new IllegalArgumentException("That would make " + (scene.portals().size() + st.gen.count) + " portals, and at most " + st.maxPortals
                    + " can exist at once. Build fewer, or raise the limit with /pd set max-portals.");
        }
        scene.worldName(c.world().getName());
        List<PortalSpec> existing = new ArrayList<>(pl.scenes().portalsIn(c.world().getName()));
        int before = scene.portals().size();
        int dx = c.facingAuto().getModX();
        int dz = c.facingAuto().getModZ();
        GenerationJob job = new GenerationJob(pl, c.world(), scene, st, c.x(), c.z(), dx, dz, existing, result -> {
            pl.activeJob(null);
            pl.scenes().save(scene);
            pl.groups().fillAll(scene);
            if (result.failed()) {
                Msg.err(s, "Something went wrong while building (" + result.error() + "). The portals that were finished are kept.");
            } else if (result.cancelled()) {
                Msg.info(s, "Stopped. " + result.placed() + " of " + result.requested() + " portals were created and are kept; /pd clear removes them.");
            } else {
                Msg.ok(s, "Built " + result.placed() + " of " + result.requested() + " portals"
                        + (before > 0 ? " (" + scene.portals().size() + " in total)" : "") + ".");
                if (result.placed() < result.requested()) {
                    Msg.info(s, "Only " + result.placed() + " fit in this area. " + shortfall(result.reasons()) + " Make room with /pd set radius " + Math.min(400, st.gen.radius * 3 / 2)
                            + ", /pd set spacing horizontal " + Math.max(2, st.gen.spacingH / 2) + ", or try /pd set mode air.");
                }
                Report.formation(s, pl, scene);
            }
        });
        if (job.chunkCount() > st.maxChunks) {
            throw new IllegalArgumentException("A radius of " + st.gen.radius + " blocks covers " + job.chunkCount() + " chunks, more than the limit of " + st.maxChunks
                    + ". Use a smaller radius, for example /pd set radius " + Math.max(8, st.gen.radius / 2) + ".");
        }
        job.autosave(() -> pl.scenes().save(scene));
        pl.activeJob(job);
        pl.scenes().select(scene);
        pl.saveSettings();
        Msg.ok(s, "Building " + st.gen.count + " " + st.gen.placement.name().toLowerCase(Locale.ROOT) + " portals (random layout) within " + st.gen.radius
                + " blocks of " + (c.pinned() ? "the pinned center " : "") + c.x() + ", " + c.z() + ". " + revealNote(st.gen));
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

    // Grid, square and wave

    private void startFormation(CommandSender s, Scene scene, Center c, boolean partial) {
        Settings st = pl.settings();
        GenSettings g = st.gen;
        int expected = expectedCount(g);
        if (scene.portals().size() + expected > st.maxPortals) {
            throw new IllegalArgumentException("That would make " + (scene.portals().size() + expected) + " portals, and at most " + st.maxPortals
                    + " can exist at once. Use fewer rows, columns or layers, or raise the limit with /pd set max-portals.");
        }
        boolean ground = g.placement != Placement.AIR;
        BlockFace auto = c.facingAuto();
        int centerFloor = c.y() - 1;
        int firstId = scene.nextId();
        List<PortalSpec> plan;
        switch (g.layout) {
            case GRID -> plan = FormationPlanner.grid(firstId, c.x(), c.z(), centerFloor, g.gridRows, g.gridColumns, g.spacingH, g.spacingV, g.facing, auto, new Random());
            case WAVE -> plan = FormationPlanner.wave(firstId, c.x(), c.z(), centerFloor, g.waveRows, g.wavePerRow, g.spacingH, g.spacingV, g.facing, auto, new Random());
            default -> {
                int size = SquarePlanner.sizeFor(g.squarePerSide, g.spacingH);
                if (size < SquarePlanner.MIN_SIZE) {
                    throw new IllegalArgumentException("A square with " + g.squarePerSide + " portals per side and " + g.spacingH + " blocks between them is only " + size
                            + " blocks wide, too small to keep the middle open. Use /pd set square per-side or /pd set spacing horizontal with larger values (at least " + SquarePlanner.MIN_SIZE + " blocks wide).");
                }
                SquareFacing sf = g.facing == FacingMode.OUTWARD ? SquareFacing.OUTWARD : SquareFacing.INWARD;
                plan = FormationPlanner.square(firstId, c.x(), c.z(), centerFloor, g.squarePerSide, g.squareLayers, g.spacingH, g.spacingV, sf);
            }
        }
        List<PortalSpec> layoutPlan = plan;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PortalSpec p : layoutPlan) {
            int[] b = p.box();
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[3]);
            minZ = Math.min(minZ, b[2]);
            maxZ = Math.max(maxZ, b[5]);
        }
        int chunks = (((maxX >> 4) - (minX >> 4)) + 3) * (((maxZ >> 4) - (minZ >> 4)) + 3);
        if (chunks > st.maxChunks) {
            throw new IllegalArgumentException("That formation covers about " + chunks + " chunks, more than the limit of " + st.maxChunks + ". Use fewer columns, rows or a smaller spacing.");
        }
        Msg.ok(s, "Planning " + describeLayout(g) + ", spacing " + g.spacingH + " across / " + g.spacingV + " vertical, facing " + facingLabel(g)
                + ", " + (ground ? "standing on the ground" : "floating around height " + c.y()) + ", around " + c.x() + ", " + c.z() + ". Checking the area...");
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = (minX - 8) >> 4; cx <= (maxX + 8) >> 4; cx++) {
            for (int cz = (minZ - 8) >> 4; cz <= (maxZ + 8) >> 4; cz++) loads.add(c.world().getChunkAtAsync(cx, cz, true));
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) ->
                pl.getServer().getScheduler().runTask(pl, () -> {
                    try {
                        buildFormation(s, scene, c, layoutPlan, ground, partial);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        Msg.err(s, e.getMessage());
                    }
                }));
    }

    private void buildFormation(CommandSender s, Scene scene, Center c, List<PortalSpec> plan, boolean ground, boolean partial) {
        if (pl.activeJob() != null) throw pl.busy();
        Settings st = pl.settings();
        GenSettings g = st.gen;
        BukkitTerrain terrain = new BukkitTerrain(c.world());
        List<PortalSpec> specs = plan;
        int lowest = FormationPlanner.lowestFloor(specs);
        if (ground) {
            int top = Integer.MIN_VALUE;
            for (PortalSpec p : specs) {
                if (p.y() != lowest) continue;
                for (int i = 0; i < PortalSpec.WIDTH; i++) top = Math.max(top, terrain.topY(p.cellX(i), p.cellZ(i)));
            }
            if (top >= c.world().getMinHeight()) {
                specs = FormationPlanner.shift(specs, top - lowest);
                lowest = top;
            }
        }
        List<PortalSpec> existing = new ArrayList<>(pl.scenes().portalsIn(c.world().getName()));
        FormationPlanner.Fit fit = FormationPlanner.check(terrain, specs, g.minY, g.maxY, existing, ground, g.maxSlope + 1, lowest);
        int total = specs.size();
        if (!fit.problems().isEmpty()) {
            for (String p : fit.problems().stream().limit(4).toList()) Msg.info(s, "  blocked - " + p);
            if (fit.problems().size() > 4) Msg.info(s, "  ... and " + (fit.problems().size() - 4) + " more");
            if (!partial) {
                throw new IllegalArgumentException(fit.problems().size() + " of the " + total + " portals cannot be built here, so nothing was built and nothing was changed. "
                        + "Move the center with /pd set center, try /pd set mode " + (ground ? "air" : "ground") + ", change the spacing, or add 'partial' to build only the portals that fit.");
            }
        }
        if (fit.usable().isEmpty()) throw new IllegalArgumentException("Nothing fits here.");
        scene.worldName(c.world().getName());
        if (g.layout == Layout.SQUARE) {
            SquareFacing sf = g.facing == FacingMode.OUTWARD ? SquareFacing.OUTWARD : SquareFacing.INWARD;
            scene.square(new SquareInfo(c.x(), c.z(), SquarePlanner.sizeFor(g.squarePerSide, g.spacingH), g.squarePerSide, g.squareLayers, g.spacingH, sf,
                    new ArrayList<>(specs.stream().map(PortalSpec::id).toList())));
        }
        pl.scenes().select(scene);
        pl.saveSettings();
        List<PortalSpec> ordered = fit.usable();
        if (g.generation == GenerationMode.DELAYED) {
            boolean randomFacing = g.facing == FacingMode.RANDOM;
            BlockFace wall = randomFacing ? BlockFace.SOUTH : FormationPlanner.wallFacing(g.facing, c.facingAuto());
            RevealOrder.Info info = new RevealOrder.Info(wall, g.layout == Layout.WAVE ? g.wavePerRow : g.gridColumns, g.squarePerSide, g.squareLayers, c.x(), c.z());
            java.util.Set<Integer> keep = new java.util.HashSet<>();
            fit.usable().forEach(p -> keep.add(p.id()));
            ordered = RevealOrder.arrange(g.layout, g.order, specs, info, new Random()).stream().filter(p -> keep.contains(p.id())).toList();
            Map<Integer, PortalSpec> byId = new java.util.HashMap<>();
            fit.usable().forEach(p -> byId.put(p.id(), p));
            ordered = ordered.stream().map(p -> byId.get(p.id())).toList();
        }
        Msg.ok(s, "Building " + fit.usable().size() + " of " + total + " portals. " + revealNote(g));
        GenerationJob job = new GenerationJob(pl, c.world(), scene, st, ordered, result -> {
            pl.activeJob(null);
            pl.groups().fillAll(scene);
            pl.scenes().save(scene);
            if (result.cancelled()) {
                if (result.placed() == 0) scene.square(null);
                pl.scenes().save(scene);
                Msg.info(s, "Stopped. " + result.placed() + " of " + total + " portals were created and are kept; /pd clear removes them.");
            } else {
                Msg.ok(s, "Built " + result.placed() + " of " + total + " expected portals.");
                Report.formation(s, pl, scene);
            }
        });
        job.autosave(() -> pl.scenes().save(scene));
        pl.activeJob(job);
        job.start();
    }

    /** One sentence about how the portals will appear. */
    private static String revealNote(GenSettings g) {
        if (g.generation != GenerationMode.DELAYED) return "This takes a few seconds...";
        return "They appear one by one, a new one every " + trimSeconds(g.delay) + " s, " + g.order.resolve(g.layout).name().toLowerCase(Locale.ROOT).replace('_', '-')
                + (g.effects ? ", with sound and particles" : "") + ". /pd stop cancels and keeps what exists.";
    }

    private static String facingLabel(GenSettings g) {
        if (g.layout == Layout.SQUARE) return g.facing == FacingMode.OUTWARD ? "outward" : "inward";
        return switch (g.facing) {
            case NORTH, SOUTH, EAST, WEST, RANDOM -> g.facing.name().toLowerCase(Locale.ROOT);
            default -> "you";
        };
    }

    private static String trimSeconds(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // Settings

    private record Found(Settings.Option option, int used) {
    }

    /** Matches what the admin typed ("radius", "height min", "grid rows") to a setting. */
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
                    Msg.info(s, "Change a setting with /pd set <name> <value>, for example /pd set layout grid or /pd set grid rows 4. /pd settings shows all of them.");
                    return;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                if (first.equals("spacing") && (a.size() < 2 || !List.of("horizontal", "vertical").contains(a.get(1).toLowerCase(Locale.ROOT)))) {
                    Settings.Option h = pl.settings().options().get("generate.spacing-horizontal");
                    Settings.Option v = pl.settings().options().get("generate.spacing-vertical");
                    throw new IllegalArgumentException("Spacing has two parts. Horizontal (" + h.get() + " now) is the gap between portals side by side: /pd set spacing horizontal 10. "
                            + "Vertical (" + v.get() + " now) is the gap between rows and layers above each other: /pd set spacing vertical 5.");
                }
                if (List.of("rows", "columns", "per-side", "layers", "per-row").contains(first)) {
                    throw new IllegalArgumentException("Say which layout it is for: /pd set grid rows 4, /pd set grid columns 5, /pd set square per-side 5, /pd set square layers 3, "
                            + "/pd set wave rows 4 or /pd set wave per-row 5.");
                }
                Found f = find(a);
                if (f == null) {
                    throw new IllegalArgumentException("There is no setting called '" + String.join(" ", a) + "'. /pd settings lists the names, and Tab completion after /pd set shows them.");
                }
                if (f.used() >= a.size()) {
                    Msg.info(s, shortName(f.option()) + " is " + f.option().display() + " (" + f.option().hint + "). Change it with /pd set " + shortName(f.option()) + " <value>.");
                    return;
                }
                String value = String.join(" ", a.subList(f.used(), a.size()));
                f.option().set(value);
                pl.saveSettings();
                Msg.ok(s, shortName(f.option()) + " is now " + f.option().display() + ".");
                if (f.option().key.equals("generate.generation") && pl.settings().gen.generation == GenerationMode.DELAYED) {
                    GenSettings gg = pl.settings().gen;
                    Msg.info(s, "Portals will now appear one by one, every " + trimSeconds(gg.delay) + " s, order " + gg.order.resolve(gg.layout).name().toLowerCase(Locale.ROOT).replace('_', '-') + ". Change the pace with /pd set generation delay 0.5.");
                }
                if (f.option().key.equals("generate.generation-order") || f.option().key.equals("generate.layout")) {
                    String problem = RevealOrder.problem(pl.settings().gen.layout, pl.settings().gen.order);
                    if (problem != null) Msg.info(s, "Note: " + problem);
                }
                if (f.option().key.equals("generate.layout") || f.option().key.startsWith("generate.grid-") || f.option().key.startsWith("generate.square-")
                        || f.option().key.startsWith("generate.wave-")) {
                    Msg.info(s, "/pd create will now build a " + describeLayout(pl.settings().gen) + ".");
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) {
                    List<String> names = new ArrayList<>(List.of("layout", "generation", "grid", "square", "wave", "count", "mode", "radius", "spacing", "orientation", "facing", "height", "air-ratio", "min-y", "max-y", "slope"));
                    for (Settings.Option o : pl.settings().options().values()) {
                        if (!o.key.startsWith("generate.")) names.add(o.key);
                    }
                    return names;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                if (a.size() == 2) {
                    switch (first) {
                        case "height":
                            return List.of("min", "max");
                        case "spacing":
                            return List.of("horizontal", "vertical");
                        case "grid":
                            return List.of("rows", "columns");
                        case "square":
                            return List.of("per-side", "layers");
                        case "wave":
                            return List.of("rows", "per-row");
                        case "generation":
                            return List.of("instant", "delayed", "delay", "order", "effects");
                        default:
                    }
                }
                if (first.equals("generation") && a.size() == 3 && a.get(1).equalsIgnoreCase("delay")) return List.of("0.2", "0.5", "1", "2");
                Found f = find(a.subList(0, a.size() - 1));
                if (f == null || f.used() != a.size() - 1) return List.of();
                List<String> sug = new ArrayList<>(f.option().suggestions());
                if (f.option().key.equals("generate.facing")) sug = new ArrayList<>(List.of("north", "south", "east", "west", "random", "inward", "outward", "auto"));
                if (sug.isEmpty()) sug.add(String.valueOf(f.option().get()));
                return sug;
            }
        };
    }

    private static String shortName(Settings.Option o) {
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (e.getValue().equals(o.key) && !e.getKey().equals("placement") && !e.getKey().startsWith("height-")) return e.getKey();
        }
        for (String prefix : new String[]{"spacing-", "height-", "grid-", "square-", "wave-", "generation-"}) {
            if (o.key.startsWith("generate." + prefix)) return o.key.replace("generate.", "").replaceFirst("-", " ");
        }
        return o.key;
    }

    Sub settings() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                String which = a.isEmpty() ? "generate" : a.get(0).toLowerCase(Locale.ROOT);
                Scene sc = pl.scenes().current();
                Msg.head(s, "Portal Director settings");
                Msg.line(s, "  Portals now: " + (sc == null ? 0 : sc.portals().size()) + "    Center: "
                        + (st.centerPinned ? (int) st.centerX + (st.centerHasY ? " " + (int) st.centerY : " (your height)") + " " + (int) st.centerZ + " in " + st.centerWorld + " (pinned)" : "wherever you stand"));
                if (which.equals("generate") || which.equals("generation") || which.equals("portals")) {
                    GenSettings g = st.gen;
                    Msg.line(s, "  Layout: " + describeLayout(g) + "  (/pd create builds this)");
                    List<String[]> rows = new ArrayList<>();
                    rows.add(new String[]{"layout", "generate.layout"});
                    switch (g.layout) {
                        case GRID -> {
                            rows.add(new String[]{"grid rows", "generate.grid-rows"});
                            rows.add(new String[]{"grid columns", "generate.grid-columns"});
                        }
                        case SQUARE -> {
                            rows.add(new String[]{"square per-side", "generate.square-per-side"});
                            rows.add(new String[]{"square layers", "generate.square-layers"});
                        }
                        case WAVE -> {
                            rows.add(new String[]{"wave rows", "generate.wave-rows"});
                            rows.add(new String[]{"wave per-row", "generate.wave-per-row"});
                        }
                        default -> {
                            rows.add(new String[]{"count", "generate.count"});
                            rows.add(new String[]{"radius", "generate.radius"});
                        }
                    }
                    rows.add(new String[]{"mode", "generate.placement"});
                    rows.add(new String[]{SPACING[0], SPACING[1]});
                    rows.add(new String[]{SPACING[2], SPACING[3]});
                    rows.add(new String[]{"facing", "generate.facing"});
                    if (g.layout == Layout.RANDOM) {
                        rows.add(new String[]{"orientation", "generate.orientation"});
                        rows.add(new String[]{"height min", "generate.height-min"});
                        rows.add(new String[]{"height max", "generate.height-max"});
                        rows.add(new String[]{"air-ratio", "generate.air-ratio"});
                        rows.add(new String[]{"slope", "generate.max-slope"});
                    }
                    rows.add(new String[]{LIMITS[0], LIMITS[1]});
                    rows.add(new String[]{LIMITS[2], LIMITS[3]});
                    rows.add(new String[]{"generation", "generate.generation"});
                    if (g.generation == GenerationMode.DELAYED) {
                        rows.add(new String[]{"generation delay", "generate.generation-delay"});
                        rows.add(new String[]{"generation order", "generate.generation-order"});
                        rows.add(new String[]{"generation effects", "generate.generation-effects"});
                    }
                    Msg.line(s, "  Change with /pd set <name> <value>:");
                    for (String[] row : rows) {
                        Settings.Option o = st.options().get(row[1]);
                        Msg.line(s, String.format("    %-19s %-8s %s", row[0], o.display(), o.hint));
                    }
                    switch (g.layout) {
                        case GRID -> Msg.line(s, "  A vertical wall: rows go up, columns go sideways. facing north|south|east|west picks the direction it faces (random turns each portal).");
                        case SQUARE -> Msg.line(s, "  Square perimeters around an open middle, stacked in layers. facing inward|outward.");
                        case WAVE -> Msg.line(s, "  Rows that rise and fall like a wave, every other row shifted. facing north|south|east|west.");
                        default -> Msg.line(s, "  Height min/max are blocks ABOVE the ground for floating portals.");
                    }
                    Msg.line(s, "  Spacing: horizontal is the gap side by side, vertical the gap between rows and layers. Other layouts: /pd help layouts. Reveal one by one: /pd set generation delayed.");
                    Msg.line(s, "  More: /pd settings tnt | players | all");
                    return;
                }
                String prefix = switch (which) {
                    case "tnt" -> "tnt.";
                    case "players", "entrance", "group", "groups" -> "entrance.";
                    case "all" -> "";
                    default -> throw new IllegalArgumentException("Pick one of: generate, tnt, players, all.");
                };
                for (Settings.Option o : st.options().values()) {
                    if (o.key.startsWith(prefix) || (which.equals("players") && o.key.startsWith("groups."))) {
                        Msg.line(s, String.format("    %-34s %-8s %s", o.key, o.display(), o.hint));
                    }
                }
                if (which.equals("tnt")) Msg.line(s, "  " + (st.tntPreview ? "SAFE MODE is on (nothing explodes)." : "Real explosions are on.") + " Details: /pd tnt status");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("generate", "tnt", "players", "all") : List.of();
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
                            ? "The center is pinned at " + (int) st.centerX + (st.centerHasY ? " " + (int) st.centerY : "") + " " + (int) st.centerZ + " in " + st.centerWorld + ". /pd set center reset goes back to building around you."
                            : "Portals are built around wherever you stand when you run /pd create. /pd set center here pins the spot you are on now, /pd set center <x> <y> <z> pins coordinates.");
                    return;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                if (first.equals("reset") || first.equals("clear")) {
                    st.centerPinned = false;
                } else if (first.equals("here")) {
                    if (!(s instanceof Player p)) throw new IllegalArgumentException("Only players can use 'here'. From the console give coordinates: /pd set center <x> <y> <z> <world>");
                    pin(st, p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ(), true);
                } else {
                    boolean withY = a.size() >= 3 && a.get(2).matches("-?\\d+(\\.\\d+)?");
                    if (a.size() < 2) throw new IllegalArgumentException("Use /pd set center here, /pd set center <x> <y> <z>, or /pd set center reset.");
                    double x;
                    double y = 0;
                    double z;
                    try {
                        x = Double.parseDouble(a.get(0));
                        if (withY) {
                            y = Double.parseDouble(a.get(1));
                            z = Double.parseDouble(a.get(2));
                        } else {
                            z = Double.parseDouble(a.get(1));
                        }
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Coordinates are numbers, like /pd set center 150 100 -200.");
                    }
                    int worldIndex = withY ? 3 : 2;
                    World w = a.size() > worldIndex ? Bukkit.getWorld(a.get(worldIndex)) : s instanceof Player p ? p.getWorld() : null;
                    if (w == null) throw new IllegalArgumentException("Say which world: /pd set center " + String.join(" ", a.subList(0, withY ? 3 : 2)) + " <world>");
                    pin(st, w, x, y, z, withY);
                }
                pl.saveSettings();
                Msg.ok(s, st.centerPinned ? "Center pinned at " + (int) st.centerX + (st.centerHasY ? " " + (int) st.centerY : "") + " " + (int) st.centerZ + " in " + st.centerWorld + "." : "Portals will be built around you again.");
            }

            private void pin(Settings st, World w, double x, double y, double z, boolean hasY) {
                st.centerPinned = true;
                st.centerWorld = w.getName();
                st.centerX = x;
                st.centerY = y;
                st.centerHasY = hasY;
                st.centerZ = z;
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("here", "reset", "show");
                if (a.size() == 4) return Bukkit.getWorlds().stream().map(World::getName).toList();
                return List.of();
            }
        };
    }
}
