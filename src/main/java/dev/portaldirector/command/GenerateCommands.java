package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.Bounds;
import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.FormationPlanner;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.GenerationMode;
import dev.portaldirector.gen.RevealOrder;
import dev.portaldirector.gen.GenerationJob;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Placement;
import dev.portaldirector.gen.PortalPlanner;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
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
        ALIASES.put("facing", "generate.facing");
        ALIASES.put("fuse", "tnt.fuse-seconds");
        ALIASES.put("tnt-limit", "tnt.max-active");
    }

    /** Names that used to be settings: typing one explains what replaced it. Never suggested. */
    private static final Map<String, String> GONE = new LinkedHashMap<>();

    static {
        GONE.put("height-min", "is now called altitude-min: /pd set altitude-min <blocks above the ground>");
        GONE.put("height-max", "is now called altitude-max: /pd set altitude-max <blocks above the ground>");
        GONE.put("min-y", "is now called world-y-min: /pd set world-y-min <world y>");
        GONE.put("max-y", "is now called world-y-max: /pd set world-y-max <world y>");
        GONE.put("orientation", "no longer exists. /pd set facing north|south|east|west picks the direction, and the frame turns to match");
        GONE.put("air-ratio", "no longer exists. /pd set mode mixed always builds about half floating and half ground portals");
        GONE.put("slope", "no longer exists. Ground portals choose suitable terrain by themselves");
        GONE.put("max-slope", "no longer exists. Ground portals choose suitable terrain by themselves");
    }

    private final PortalDirectorPlugin pl;
    private final SceneCommands scenes;

    GenerateCommands(PortalDirectorPlugin pl, SceneCommands scenes) {
        this.pl = pl;
        this.scenes = scenes;
    }

    private record Center(World world, int x, int y, int z, boolean pinned) {
    }

    private Center center(CommandSender s) {
        Settings st = pl.settings();
        Player p = s instanceof Player pp ? pp : null;
        if (st.centerPinned) {
            World w = Bukkit.getWorld(st.centerWorld == null ? "" : st.centerWorld);
            if (w == null) throw new IllegalArgumentException("The pinned center is in a world that is not loaded. Use /pd set center reset to build around yourself again.");
            int y = st.centerHasY ? (int) Math.floor(st.centerY) : p != null && p.getWorld() == w ? p.getLocation().getBlockY() : w.getSeaLevel() + 1;
            return new Center(w, (int) Math.floor(st.centerX), y, (int) Math.floor(st.centerZ), true);
        }
        if (p == null) throw new IllegalArgumentException("The console has no position. Pin a center first: /pd set center <x> <y> <z> <world>");
        Location l = p.getLocation();
        return new Center(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), false);
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
            case RANDOM -> "random: " + g.count + " portals within " + radiusText(g);
            case GRID -> "grid: " + g.gridRows + " rows x " + g.gridColumns + " columns = " + expectedCount(g) + " portals";
            case SQUARE -> "square: " + g.squarePerSide + " per side x 4 sides x " + g.squareLayers + " layer" + (g.squareLayers == 1 ? "" : "s") + " = " + expectedCount(g) + " portals";
            case WAVE -> "wave: " + g.waveRows + " rows x " + g.wavePerRow + " per row = " + expectedCount(g) + " portals";
        };
    }

    /** "100 blocks (105 effective with automatic 5% margin)". */
    static String radiusText(GenSettings g) {
        return g.radius + " blocks (" + g.effectiveRadius() + " effective with automatic " + GenSettings.RADIUS_MARGIN_PERCENT + "% margin)";
    }

    // /pd create [count] [more|replacement] [skip-blocked]; where portals stand is /pd set mode

    /** The words /pd create understands besides a count, A to Z. */
    static final List<String> CREATE_OPTIONS = List.of("more", "replacement", "skip-blocked");

    /** The create options as typed; a count is only for the random layout. Throws for anything else. */
    record CreateArgs(Integer count, boolean more, boolean replacement, boolean skipBlocked) {

        static CreateArgs parse(List<String> a) {
            Integer count = null;
            boolean more = false;
            boolean replacement = false;
            boolean skip = false;
            for (String t : a) {
                String l = t.toLowerCase(Locale.ROOT);
                if (l.equals("more")) {
                    more = true;
                } else if (l.equals("replacement")) {
                    replacement = true;
                } else if (l.equals("skip-blocked")) {
                    skip = true;
                } else if (l.matches("\\d{1,9}")) {
                    if (count != null) throw new IllegalArgumentException("Give the number of portals once: /pd create 30");
                    count = Integer.parseInt(l);
                } else if (l.equals("add") || l.equals("replace") || l.equals("partial")) {
                    String now = l.equals("add") ? "more" : l.equals("replace") ? "replacement" : "skip-blocked";
                    throw new IllegalArgumentException("'" + t + "' is now called '" + now + "': /pd create " + now);
                } else if (l.equals("air") || l.equals("ground") || l.equals("mixed")) {
                    throw new IllegalArgumentException("Where portals stand is a setting now: /pd set mode " + l + ", then /pd create.");
                } else {
                    throw new IllegalArgumentException("I did not understand '" + t + "'. /pd create takes a number (random layout only) and the options more, replacement and skip-blocked. "
                            + "Example: /pd create 30 more. /pd help create explains each one.");
                }
            }
            if (more && replacement) throw new IllegalArgumentException("Choose either 'more' (keep the existing portals) or 'replacement' (remove them first), not both.");
            return new CreateArgs(count, more, replacement, skip);
        }
    }

    Sub generate() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                CreateArgs args = CreateArgs.parse(a);
                Integer count = args.count();
                boolean add = args.more();
                boolean replace = args.replacement();
                boolean partial = args.skipBlocked();
                Settings st = pl.settings();
                Layout layout = st.gen.layout;
                String settingsProblem = st.gen.problem();
                if (settingsProblem != null) throw new IllegalArgumentException(settingsProblem);
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
                pl.saveSettings();
                Center c = center(s);
                Scene scene = pl.scenes().currentOrCreate(c.world());
                if (pl.activeJob() != null) throw pl.busy();
                if (!scene.portals().isEmpty() && !scene.worldName().equals(c.world().getName())) {
                    throw new IllegalArgumentException("The existing portals are in the world " + scene.worldName() + ", and you are in " + c.world().getName()
                            + ". Run /pd clear first, then create here.");
                }
                int expected = expectedCount(st.gen);
                if (!scene.portals().isEmpty() && !add && !replace) {
                    throw new IllegalArgumentException("There are already " + scene.portals().size() + " portals (/pd list shows where they are). "
                            + "To keep them and build " + expected + " more: /pd create more. "
                            + "To remove them and build new ones: /pd create replacement. "
                            + "To just remove them: /pd clear.");
                }
                if (layout == Layout.SQUARE && scene.square() != null && !replace) {
                    throw new IllegalArgumentException("There is already a square. /pd create replacement swaps it for a new one, /pd clear removes it.");
                }
                if (layout != Layout.RANDOM) requireInsideRadius(c, plan(c, 1));
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
                // Suggest what has not been typed yet; the word being typed is filtered by the caller.
                List<String> typed = a.subList(0, a.size() - 1).stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
                List<String> out = new ArrayList<>();
                if (pl.settings().gen.layout == Layout.RANDOM && typed.stream().noneMatch(t -> t.matches("\\d+"))) out.addAll(List.of("10", "30", "50", "100"));
                for (String o : CREATE_OPTIONS) {
                    boolean clash = (o.equals("more") && typed.contains("replacement")) || (o.equals("replacement") && typed.contains("more"));
                    if (!typed.contains(o) && !clash) out.add(o);
                }
                return out;
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
        GenerationJob job = new GenerationJob(pl, c.world(), scene, st, c.x(), c.z(), existing, result -> {
            pl.activeJob(null);
            pl.scenes().save(scene);
            if (result.failed()) {
                Msg.err(s, "Something went wrong while building (" + result.error() + "). The portals that were finished are kept.");
            } else if (result.cancelled()) {
                Msg.info(s, "Stopped. " + result.placed() + " of " + result.requested() + " portals were created and are kept; /pd clear removes them.");
            } else {
                Msg.ok(s, "Built " + result.placed() + " of " + result.requested() + " portals"
                        + (before > 0 ? " (" + scene.portals().size() + " in total)" : "") + ".");
                if (result.placed() < result.requested()) {
                    Msg.info(s, "Only " + result.placed() + " fit inside the radius of " + radiusText(st.gen) + ". " + shortfall(result.reasons()) + " Make room with /pd set radius " + Math.min(400, st.gen.radius * 3 / 2)
                            + ", /pd set spacing horizontal " + Math.max(2, st.gen.spacingH / 2) + ", or try /pd set mode air.");
                }
                Report.formation(s, pl, scene);
            }
        });
        if (job.chunkCount() > st.maxChunks) {
            throw new IllegalArgumentException("A radius of " + radiusText(st.gen) + " covers " + job.chunkCount() + " chunks, more than the limit of " + st.maxChunks
                    + ". Use a smaller radius, for example /pd set radius " + Math.max(8, st.gen.radius / 2) + ".");
        }
        job.autosave(() -> pl.scenes().save(scene));
        pl.activeJob(job);
        pl.scenes().select(scene);
        pl.saveSettings();
        Msg.ok(s, "Building " + st.gen.count + " " + st.gen.placement.name().toLowerCase(Locale.ROOT) + " portals (random layout) within " + radiusText(st.gen)
                + " of " + (c.pinned() ? "the pinned center " : "") + c.x() + ", " + c.z() + ". " + revealNote(st.gen));
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

    /** The portals a grid, wave or square would build around {@code c}. No world access, so it can run before anything is removed. */
    private List<PortalSpec> plan(Center c, int firstId) {
        GenSettings g = pl.settings().gen;
        BlockFace auto = BlockFace.SOUTH;
        int centerFloor = c.y() - 1;
        switch (g.layout) {
            case GRID -> {
                return FormationPlanner.grid(firstId, c.x(), c.z(), centerFloor, g.gridRows, g.gridColumns, g.spacingH, g.spacingV, g.facing, auto, new Random());
            }
            case WAVE -> {
                return FormationPlanner.wave(firstId, c.x(), c.z(), centerFloor, g.waveRows, g.wavePerRow, g.spacingH, g.spacingV, g.facing, auto, new Random());
            }
            default -> {
                int size = SquarePlanner.sizeFor(g.squarePerSide, g.spacingH);
                if (size < SquarePlanner.MIN_SIZE) {
                    throw new IllegalArgumentException("A square with " + g.squarePerSide + " portals per side and " + g.spacingH + " blocks between them is only " + size
                            + " blocks wide, too small to keep the middle open. Use /pd set square per-side or /pd set spacing horizontal with larger values (at least " + SquarePlanner.MIN_SIZE + " blocks wide).");
                }
                SquareFacing sf = g.facing == FacingMode.OUTWARD ? SquareFacing.OUTWARD : SquareFacing.INWARD;
                return FormationPlanner.square(firstId, c.x(), c.z(), centerFloor, g.squarePerSide, g.squareLayers, g.spacingH, g.spacingV, sf);
            }
        }
    }

    /** Every frame block must be within the radius of the center; otherwise nothing is built and the radius is not stretched. */
    private void requireInsideRadius(Center c, List<PortalSpec> plan) {
        GenSettings g = pl.settings().gen;
        int needed = 0;
        for (PortalSpec p : plan) needed = Math.max(needed, Bounds.radiusNeeded(p, c.x(), c.z()));
        if (needed > g.effectiveRadius()) {
            throw new IllegalArgumentException("The " + describeLayout(g) + " reaches " + needed + " blocks from the center, but the radius is " + radiusText(g)
                    + ", so nothing was built or removed. " + (needed <= 400 ? "Raise it with /pd set radius " + needed + ", or use " : "Use ")
                    + "fewer " + (g.layout == Layout.SQUARE ? "portals per side" : "rows or columns") + " or a smaller spacing.");
        }
    }

    private void startFormation(CommandSender s, Scene scene, Center c, boolean partial) {
        Settings st = pl.settings();
        GenSettings g = st.gen;
        int expected = expectedCount(g);
        if (scene.portals().size() + expected > st.maxPortals) {
            throw new IllegalArgumentException("That would make " + (scene.portals().size() + expected) + " portals, and at most " + st.maxPortals
                    + " can exist at once. Use fewer rows, columns or layers, or raise the limit with /pd set max-portals.");
        }
        boolean ground = g.placement != Placement.AIR;
        List<PortalSpec> layoutPlan = plan(c, scene.nextId());
        requireInsideRadius(c, layoutPlan);
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
        FormationPlanner.Fit fit = FormationPlanner.check(terrain, specs, g.worldYMin, g.worldYMax, existing, ground, PortalPlanner.MAX_SUPPORT, lowest);
        int total = specs.size();
        if (!fit.problems().isEmpty()) {
            for (String p : fit.problems().stream().limit(4).toList()) Msg.info(s, "  blocked - " + p);
            if (fit.problems().size() > 4) Msg.info(s, "  ... and " + (fit.problems().size() - 4) + " more");
            if (!partial) {
                throw new IllegalArgumentException(fit.problems().size() + " of the " + total + " portals cannot be built here, so nothing was built and nothing was changed. "
                        + "Move the center with /pd set center, try /pd set mode " + (ground ? "air" : "ground") + ", change the spacing or the world-y limits, or add 'skip-blocked' to build only the portals that fit.");
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
            BlockFace wall = randomFacing ? BlockFace.SOUTH : FormationPlanner.wallFacing(g.facing, BlockFace.SOUTH);
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
        return g.facing.label();
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
                String gone = a.size() > 1 ? GONE.get(first + "-" + a.get(1).toLowerCase(Locale.ROOT)) : null;
                if (gone == null) gone = GONE.get(first);
                if (gone != null) throw new IllegalArgumentException("'" + first + "' " + gone + ".");
                Found f = find(a);
                if (f == null) {
                    throw new IllegalArgumentException("There is no setting called '" + String.join(" ", a) + "'. /pd settings lists the names, and Tab completion after /pd set shows them.");
                }
                if (f.used() >= a.size()) {
                    Msg.info(s, shortName(f.option()) + " is " + f.option().display() + " (" + f.option().hint + "). Change it with /pd set " + shortName(f.option()) + " <value>.");
                    return;
                }
                String value = String.join(" ", a.subList(f.used(), a.size()));
                Layout layoutBefore = pl.settings().gen.layout;
                f.option().set(value);
                pl.saveSettings();
                Msg.ok(s, shortName(f.option()) + " is now " + f.option().display() + ".");
                if (f.option().key.equals("generate.generation") && pl.settings().gen.generation == GenerationMode.DELAYED) {
                    GenSettings gg = pl.settings().gen;
                    Msg.info(s, "Portals will now appear one by one, every " + trimSeconds(gg.delay) + " s, order " + gg.order.resolve(gg.layout).name().toLowerCase(Locale.ROOT).replace('_', '-') + ". Change the pace with /pd set generation delay 0.5.");
                }
                if (f.option().key.equals("generate.layout")) {
                    GenSettings lg = pl.settings().gen;
                    if (lg.layout == Layout.RANDOM && layoutBefore != Layout.RANDOM && lg.facing != FacingMode.RANDOM) {
                        lg.facing = FacingMode.RANDOM;
                        pl.saveSettings();
                        Msg.info(s, "Facing is random again: every portal of the random layout gets its own direction, about a quarter each. /pd set facing north|south|east|west makes them all face one way.");
                    } else if (!FacingMode.allowed(lg.facing, lg.layout)) {
                        FacingMode was = lg.facing;
                        lg.facing = FacingMode.defaultFor(lg.layout);
                        pl.saveSettings();
                        Msg.info(s, "Facing was " + was.label() + ", which the " + lg.layout.name().toLowerCase(Locale.ROOT) + " layout does not take, so it is now "
                                + lg.facing.label() + ". Choose another with /pd set facing <" + String.join("|", FacingMode.allowedFor(lg.layout).stream().map(FacingMode::label).toList()) + ">.");
                    }
                }
                if (f.option().key.equals("generate.placement") && pl.settings().gen.placement == Placement.MIXED) {
                    Msg.info(s, "Mixed builds about half floating and half ground portals (random layout; grid, wave and square stand on the ground).");
                }
                if (f.option().key.equals("generate.radius")) {
                    GenSettings rg = pl.settings().gen;
                    Msg.info(s, "Portals stay within " + rg.effectiveRadius() + " blocks of the center in every direction (x and z, automatic " + GenSettings.RADIUS_MARGIN_PERCENT + "% margin included).");
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
                    List<String> names = new ArrayList<>(List.of("altitude-max", "altitude-min", "count", "facing", "generation", "grid", "layout", "mode", "radius", "spacing", "square", "wave", "world-y-max", "world-y-min"));
                    for (Settings.Option o : pl.settings().options().values()) {
                        if (!o.key.startsWith("generate.")) names.add(o.key);
                    }
                    return names;
                }
                String first = a.get(0).toLowerCase(Locale.ROOT);
                if (a.size() == 2) {
                    switch (first) {
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
                if (f.option().key.equals("generate.facing")) sug = new ArrayList<>(FacingMode.allowedFor(pl.settings().gen.layout).stream().map(FacingMode::label).toList());
                if (sug.isEmpty()) sug.add(String.valueOf(f.option().get()));
                return sug;
            }
        };
    }

    private static String shortName(Settings.Option o) {
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (e.getValue().equals(o.key) && !e.getKey().equals("placement")) return e.getKey();
        }
        for (String prefix : new String[]{"spacing-", "grid-", "square-", "wave-", "generation-"}) {
            if (o.key.startsWith("generate." + prefix)) return o.key.replace("generate.", "").replaceFirst("-", " ");
        }
        return o.key.startsWith("generate.") ? o.key.substring("generate.".length()) : o.key;
    }

    /** One line of /pd settings: the name admins type, the value and what it does. */
    private void row(CommandSender s, String key) {
        Settings.Option o = pl.settings().options().get(key);
        Msg.line(s, String.format("    %-19s %-8s %s", shortName(o), o.display(), o.hint));
    }

    private void section(CommandSender s, String title, String... keys) {
        Msg.line(s, "  " + title);
        List<String> ordered = new ArrayList<>(List.of(keys));
        ordered.sort(Comparator.comparing(k -> shortName(pl.settings().options().get(k))));
        for (String k : ordered) row(s, k);
    }

    Sub settings() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                Settings st = pl.settings();
                String which = a.isEmpty() ? "generate" : a.get(0).toLowerCase(Locale.ROOT);
                if (!List.of("generate", "generation", "portals", "tnt", "all").contains(which)) {
                    throw new IllegalArgumentException("Pick one of: generate, tnt, all.");
                }
                Scene sc = pl.scenes().current();
                GenSettings g = st.gen;
                Msg.head(s, "Portal Director settings");
                Msg.line(s, "  Portals now: " + (sc == null ? 0 : sc.portals().size()) + "    Center: "
                        + (st.centerPinned ? (int) st.centerX + (st.centerHasY ? " " + (int) st.centerY : " (your height)") + " " + (int) st.centerZ + " in " + st.centerWorld + " (pinned)" : "wherever you stand"));
                if (which.equals("tnt")) {
                    tntRows(s, st);
                    return;
                }
                Msg.line(s, "  Layout: " + describeLayout(g) + "  (/pd create builds this)");
                Msg.line(s, "  Change with /pd set <name> <value>.");
                // Sections are listed A to Z, and so are the settings inside each one.
                Map<String, Runnable> sections = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                switch (g.layout) {
                    case GRID -> sections.put("Grid", () -> section(s, "Grid", "generate.layout", "generate.grid-rows", "generate.grid-columns"));
                    case SQUARE -> sections.put("Square", () -> section(s, "Square", "generate.layout", "generate.square-per-side", "generate.square-layers"));
                    case WAVE -> sections.put("Wave", () -> section(s, "Wave", "generate.layout", "generate.wave-rows", "generate.wave-per-row"));
                    default -> sections.put("Random", () -> section(s, "Random", "generate.layout", "generate.count"));
                }
                sections.put("Area", () -> {
                    Msg.line(s, "  Area");
                    Msg.line(s, "    Radius: " + radiusText(g));
                    Msg.line(s, "    Portals stay within x and z +/- " + g.effectiveRadius() + " of the center, frames included.");
                });
                sections.put("Placement", () -> section(s, "Placement", "generate.facing", "generate.placement"));
                sections.put("Spacing", () -> section(s, "Spacing", "generate.spacing-horizontal", "generate.spacing-vertical"));
                sections.put("Heights", () -> {
                    Msg.line(s, "  Heights (optional, the defaults suit most worlds)");
                    if (g.layout == Layout.RANDOM) {
                        row(s, "generate.altitude-max");
                        row(s, "generate.altitude-min");
                    }
                    row(s, "generate.world-y-max");
                    row(s, "generate.world-y-min");
                });
                sections.put("Timing", () -> {
                    section(s, "Timing", "generate.generation");
                    if (g.generation == GenerationMode.DELAYED) {
                        row(s, "generate.generation-delay");
                        row(s, "generate.generation-effects");
                        row(s, "generate.generation-order");
                    }
                });
                if (which.equals("all")) {
                    sections.put("Other layouts", () -> {
                        if (g.layout != Layout.RANDOM) {
                            section(s, "Other layouts", "generate.count", "generate.grid-columns", "generate.grid-rows", "generate.square-layers",
                                    "generate.square-per-side", "generate.wave-per-row", "generate.wave-rows");
                            row(s, "generate.altitude-max");
                            row(s, "generate.altitude-min");
                        } else {
                            section(s, "Other layouts", "generate.grid-columns", "generate.grid-rows", "generate.square-layers",
                                    "generate.square-per-side", "generate.wave-per-row", "generate.wave-rows");
                            Msg.line(s, "    (altitude settings are shown above)");
                        }
                    });
                    if (g.generation != GenerationMode.DELAYED) {
                        sections.put("Delayed generation", () -> section(s, "Delayed generation", "generate.generation-delay", "generate.generation-effects", "generate.generation-order"));
                    }
                    sections.put("Limits", () -> section(s, "Limits", "generate.blocks-per-tick", "generate.max-chunks", "generate.max-portals"));
                    sections.put("TNT", () -> tntRows(s, st));
                }
                sections.values().forEach(Runnable::run);
                if (!which.equals("all")) {
                    Msg.line(s, "  More: /pd settings tnt | all. Tips and examples: /pd help set");
                }
            }

            private void tntRows(CommandSender s, Settings st) {
                Msg.line(s, "  TNT");
                st.options().values().stream().filter(o -> o.key.startsWith("tnt.")).sorted(Comparator.comparing((Settings.Option o) -> o.key))
                        .forEach(o -> Msg.line(s, String.format("    %-24s %-8s %s", o.key, o.display(), o.hint)));
                Msg.line(s, "  " + (st.tntPreview ? "SAFE MODE is on (nothing explodes)." : "Real explosions are on.") + " Details: /pd tnt status");
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("all", "generate", "tnt") : List.of();
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
