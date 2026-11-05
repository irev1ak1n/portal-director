package dev.portaldirector;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.GenerationMode;
import dev.portaldirector.gen.RevealOrder;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Placement;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** All persistent settings. Every simple option is registered once and is reachable with /pd set. */
public final class Settings {

    /** One named, validated setting. */
    public static final class Option {
        public final String key;
        public final String hint;
        private final Supplier<Object> getter;
        private final Consumer<String> setter;
        private final List<String> suggestions;

        Option(String key, String hint, Supplier<Object> getter, Consumer<String> setter, List<String> suggestions) {
            this.key = key;
            this.hint = hint;
            this.getter = getter;
            this.setter = setter;
            this.suggestions = suggestions;
        }

        public Object get() {
            return getter.get();
        }

        /** The value as shown to admins: on / off for switches, no trailing .0 for whole numbers. */
        public String display() {
            Object v = getter.get();
            if (v instanceof Boolean b) return b ? "on" : "off";
            if (v instanceof Double d && d == Math.floor(d)) return String.valueOf(d.longValue());
            return String.valueOf(v);
        }

        public void set(String value) {
            try {
                setter.accept(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key.substring(key.indexOf('.') + 1).replace('-', ' ') + ": " + e.getMessage());
            }
        }

        public List<String> suggestions() {
            return suggestions;
        }
    }

    public final GenSettings gen = new GenSettings();

    public int blocksPerTick = 400;
    public int plannerBudget = 80;
    public int chunkLoadsAtOnce = 6;
    public int maxChunks = 900;
    public int maxPortals = 500;

    public int tntPerPortal = 1;
    public double tntSpeed = 1.3;
    public double tntLift = 0.25;
    public int tntWaves = 1;
    public int tntWaveDelayTicks = 100;
    public int tntStaggerTicks = 0;
    public int tntIntervalTicks = 4;
    public boolean tntPreview = false;
    public boolean protectTerrain = true;
    public boolean protectPortals = true;
    public int tntMaxActive = 300;
    public int tntLifetimeSeconds = 60;
    public int tntSpawnsPerTick = 60;
    public double tntFuseSeconds = 3.0;

    public String centerWorld;
    public double centerX;
    public double centerZ;
    public double centerY;
    public boolean centerHasY;
    public boolean centerPinned;

    private final Map<String, Option> options = new LinkedHashMap<>();
    /** True while a config file is being read; the min/max checks wait until both values are in. */
    private boolean loading;

    public Settings() {
        register();
    }

    public Map<String, Option> options() {
        return options;
    }

    /** Old key to new key: a saved value moves to the new name and the old one disappears. */
    private static final String[][] RENAMED = {
            {"generate.height-min", "generate.altitude-min"},
            {"generate.height-max", "generate.altitude-max"},
            {"generate.min-y", "generate.world-y-min"},
            {"generate.max-y", "generate.world-y-max"},
    };

    /** Settings that no longer exist; they are dropped from the config file. */
    private static final String[] REMOVED = {
            "generate.radius-margin", "generate.air-ratio", "generate.orientation", "generate.max-slope", "entrance", "groups", "square",
    };

    public void load(FileConfiguration c) {
        // Older configs had a single generate.spacing: it becomes the horizontal spacing.
        if (c.contains("generate.spacing") && !c.contains("generate.spacing-horizontal", true)) {
            c.set("generate.spacing-horizontal", c.get("generate.spacing"));
        }
        c.set("generate.spacing", null);
        for (String[] r : RENAMED) {
            if (c.contains(r[0], true) && !c.contains(r[1], true)) c.set(r[1], c.get(r[0]));
            c.set(r[0], null);
        }
        for (String key : REMOVED) c.set(key, null);
        loading = true;
        try {
            for (Option o : options.values()) {
                if (c.contains(o.key)) {
                    try {
                        o.set(String.valueOf(c.get(o.key)));
                    } catch (IllegalArgumentException ignored) {
                        // keep the default for an invalid value
                    }
                }
            }
        } finally {
            loading = false;
        }
        normalize();
        centerPinned = c.getBoolean("center.pinned", false);
        centerWorld = c.getString("center.world");
        centerX = c.getDouble("center.x");
        centerZ = c.getDouble("center.z");
        centerHasY = c.contains("center.y");
        centerY = c.getDouble("center.y");
    }

    /** Repairs combinations an edited or older config file can hold: reversed limits, a facing the layout does not take. */
    private void normalize() {
        if (gen.altitudeMin > gen.altitudeMax) {
            int t = gen.altitudeMin;
            gen.altitudeMin = gen.altitudeMax;
            gen.altitudeMax = t;
        }
        if (gen.worldYMin > gen.worldYMax) {
            int t = gen.worldYMin;
            gen.worldYMin = gen.worldYMax;
            gen.worldYMax = t;
        }
        if (!FacingMode.allowed(gen.facing, gen.layout)) gen.facing = FacingMode.defaultFor(gen.layout);
    }

    public void save(FileConfiguration c) {
        for (Option o : options.values()) c.set(o.key, o.get());
        c.set("center.pinned", centerPinned);
        c.set("center.world", centerWorld);
        c.set("center.x", centerX);
        c.set("center.z", centerZ);
        c.set("center.y", centerHasY ? centerY : null);
    }

    private void register() {
        intOpt("generate.count", "how many portals the random layout builds", 1, 1000, () -> gen.count, v -> gen.count = v);
        intOpt("generate.radius", "how far portals spread north, south, east and west (5% is added automatically)", 8, 400, () -> gen.radius, v -> gen.radius = v);
        intOpt("generate.spacing-horizontal", "least empty blocks between portal frames, side by side", 1, 100, () -> gen.spacingH, v -> gen.spacingH = v);
        intOpt("generate.spacing-vertical", "least empty blocks between portals above each other", 1, 100, () -> gen.spacingV, v -> gen.spacingV = v);
        enumOpt("generate.placement", Placement.class, () -> gen.placement, v -> gen.placement = v);
        enumOpt("generate.layout", Layout.class, () -> gen.layout, v -> gen.layout = v);
        facingOpt();
        intOpt("generate.altitude-min", "lowest floating portal, in blocks above the ground", 2, 300, () -> gen.altitudeMin, v -> gen.altitudeMin = v,
                () -> gen.altitudeMin > gen.altitudeMax ? "cannot be above altitude-max (" + gen.altitudeMax + "). Raise altitude-max first" : null);
        intOpt("generate.altitude-max", "highest floating portal, in blocks above the ground", 2, 300, () -> gen.altitudeMax, v -> gen.altitudeMax = v,
                () -> gen.altitudeMax < gen.altitudeMin ? "cannot be below altitude-min (" + gen.altitudeMin + "). Lower altitude-min first" : null);
        intOpt("generate.world-y-min", "lowest world y any portal frame may use", -64, 320, () -> gen.worldYMin, v -> gen.worldYMin = v,
                () -> gen.worldYMin > gen.worldYMax ? "cannot be above world-y-max (" + gen.worldYMax + "). Raise world-y-max first" : null);
        intOpt("generate.world-y-max", "highest world y any portal frame may use", -64, 320, () -> gen.worldYMax, v -> gen.worldYMax = v,
                () -> gen.worldYMax < gen.worldYMin ? "cannot be below world-y-min (" + gen.worldYMin + "). Lower world-y-min first" : null);
        enumOpt("generate.generation", GenerationMode.class, () -> gen.generation, v -> gen.generation = v);
        dblOpt("generate.generation-delay", "seconds between portals appearing when generation is delayed (0.05 to 60)", 0.05, 60, () -> gen.delay, v -> gen.delay = v);
        enumOpt("generate.generation-order", RevealOrder.class, () -> gen.order, v -> gen.order = v);
        boolOpt("generate.generation-effects", "a soft portal sound and particles as each portal appears (delayed generation)", () -> gen.effects, v -> gen.effects = v);
        intOpt("generate.grid-rows", "grid: rows stacked upward", 1, 20, () -> gen.gridRows, v -> gen.gridRows = v);
        intOpt("generate.grid-columns", "grid: portals side by side in each row", 1, 40, () -> gen.gridColumns, v -> gen.gridColumns = v);
        intOpt("generate.square-per-side", "square: portals on each of the four sides", 2, 40, () -> gen.squarePerSide, v -> gen.squarePerSide = v);
        intOpt("generate.square-layers", "square: identical squares stacked upward", 1, 10, () -> gen.squareLayers, v -> gen.squareLayers = v);
        intOpt("generate.wave-rows", "wave: rows stacked upward", 1, 20, () -> gen.waveRows, v -> gen.waveRows = v);
        intOpt("generate.wave-per-row", "wave: portals in each row", 1, 40, () -> gen.wavePerRow, v -> gen.wavePerRow = v);
        intOpt("generate.blocks-per-tick", "blocks placed per tick", 20, 5000, () -> blocksPerTick, v -> blocksPerTick = v);
        intOpt("generate.max-chunks", "refuse areas needing more chunks than this", 16, 5000, () -> maxChunks, v -> maxChunks = v);
        intOpt("generate.max-portals", "most portals that can exist at once", 1, 5000, () -> maxPortals, v -> maxPortals = v);



        intOpt("tnt.per-portal", "minecarts per portal per wave", 1, 50, () -> tntPerPortal, v -> tntPerPortal = v);
        dblOpt("tnt.speed", "launch speed in blocks per tick", 0.1, 5, () -> tntSpeed, v -> tntSpeed = v);
        dblOpt("tnt.lift", "upward launch speed", 0, 2, () -> tntLift, v -> tntLift = v);
        intOpt("tnt.waves", "waves per launch", 1, 100, () -> tntWaves, v -> tntWaves = v);
        intOpt("tnt.wave-delay-ticks", "ticks between waves", 1, 72000, () -> tntWaveDelayTicks, v -> tntWaveDelayTicks = v);
        intOpt("tnt.stagger-ticks", "ticks between portals inside a wave, 0 for all at once", 0, 200, () -> tntStaggerTicks, v -> tntStaggerTicks = v);
        intOpt("tnt.interval-ticks", "ticks between minecarts from the same portal", 1, 100, () -> tntIntervalTicks, v -> tntIntervalTicks = v);
        boolOpt("tnt.preview", "spawn minecarts that never explode", () -> tntPreview, v -> tntPreview = v);
        boolOpt("tnt.protect-terrain", "explosions hurt entities but break no blocks", () -> protectTerrain, v -> protectTerrain = v);
        boolOpt("tnt.protect-portals", "portals survive explosions even when terrain damage is on", () -> protectPortals, v -> protectPortals = v);
        intOpt("tnt.max-active", "most minecarts launched in total, exploded or not", 1, 5000, () -> tntMaxActive, v -> tntMaxActive = v);
        intOpt("tnt.lifetime-seconds", "minecarts that have not exploded are removed after this", 5, 3600, () -> tntLifetimeSeconds, v -> tntLifetimeSeconds = v);
        intOpt("tnt.spawns-per-tick", "most minecarts spawned in a single tick", 1, 500, () -> tntSpawnsPerTick, v -> tntSpawnsPerTick = v);
        dblOpt("tnt.fuse-seconds", "seconds after launch until a minecart explodes, 0 for vanilla behaviour (explodes only when it crashes)", 0, 60, () -> tntFuseSeconds, v -> tntFuseSeconds = v);
    }

    private void intOpt(String key, String hint, int min, int max, Supplier<Integer> get, Consumer<Integer> set) {
        intOpt(key, hint, min, max, get, set, () -> null);
    }

    /** An integer setting whose value must also agree with another one; {@code check} returns the problem or null. */
    private void intOpt(String key, String hint, int min, int max, Supplier<Integer> get, Consumer<Integer> set, Supplier<String> check) {
        options.put(key, new Option(key, hint, get::get, s -> {
            int v;
            try {
                v = (int) Math.round(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "' is not a number");
            }
            if (v < min || v > max) throw new IllegalArgumentException("must be between " + min + " and " + max);
            int old = get.get();
            set.accept(v);
            String problem = loading ? null : check.get();
            if (problem != null) {
                set.accept(old);
                throw new IllegalArgumentException(problem);
            }
        }, List.of()));
    }

    private void dblOpt(String key, String hint, double min, double max, Supplier<Double> get, Consumer<Double> set) {
        options.put(key, new Option(key, hint, get::get, s -> {
            double v;
            try {
                v = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "' is not a number");
            }
            if (v < min || v > max) throw new IllegalArgumentException("must be between " + shown(min) + " and " + shown(max));
            set.accept(v);
        }, List.of()));
    }

    private static String shown(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void boolOpt(String key, String hint, Supplier<Boolean> get, Consumer<Boolean> set) {
        options.put(key, new Option(key, hint, get::get, s -> {
            String t = s.toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("on") || t.equals("yes")) set.accept(true);
            else if (t.equals("false") || t.equals("off") || t.equals("no")) set.accept(false);
            else throw new IllegalArgumentException("use on or off");
        }, List.of("on", "off")));
    }

    /** Facing takes compass directions and random everywhere, inward and outward only for the square layout. */
    private void facingOpt() {
        List<String> names = new ArrayList<>();
        for (FacingMode m : FacingMode.values()) names.add(m.label());
        options.put("generate.facing", new Option("generate.facing", "north | south | east | west | random, or inward | outward for the square",
                () -> gen.facing.label(), s -> {
            FacingMode v = GenSettings.parse(FacingMode.class, s, null);
            if (v == null) throw new IllegalArgumentException("use one of: north, south, east, west, random, inward, outward");
            if (!loading && !FacingMode.allowed(v, gen.layout)) {
                GenSettings probe = gen.copy();
                probe.facing = v;
                throw new IllegalArgumentException(probe.facingProblem());
            }
            gen.facing = v;
        }, names));
    }

    private <E extends Enum<E>> void enumOpt(String key, Class<E> type, Supplier<E> get, Consumer<E> set) {
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) names.add(e.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        options.put(key, new Option(key, String.join(" | ", names), () -> get.get().name().toLowerCase(Locale.ROOT).replace('_', '-'), s -> {
            E v = GenSettings.parse(type, s, null);
            if (v == null) throw new IllegalArgumentException("use one of: " + String.join(", ", names));
            set.accept(v);
        }, names));
    }
}
