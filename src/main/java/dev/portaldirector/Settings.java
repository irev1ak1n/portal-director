package dev.portaldirector;

import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.GenSettings;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Orientation;
import dev.portaldirector.gen.Placement;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
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

        public void set(String value) {
            setter.accept(value);
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

    public boolean blockVanillaTravel = true;
    public boolean stagingInvisible = false;
    public boolean freezeStaged = true;
    public int walkTicks = 26;
    public double walkSpeed = 0.22;
    public int fallProtectionSeconds = 20;
    public int waveSize = 5;
    public boolean returnOnReset = true;
    public boolean autoAssignJoiners = true;
    public boolean announceJoin = true;

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
    public int tntFuseTicks = -1;

    public String centerWorld;
    public double centerX;
    public double centerZ;
    public boolean centerPinned;

    public Location staging;

    private final Map<String, Option> options = new LinkedHashMap<>();

    public Settings() {
        register();
    }

    public Map<String, Option> options() {
        return options;
    }

    public void load(FileConfiguration c) {
        for (Option o : options.values()) {
            if (c.contains(o.key)) {
                try {
                    o.set(String.valueOf(c.get(o.key)));
                } catch (IllegalArgumentException ignored) {
                    // keep the default for an invalid value
                }
            }
        }
        centerPinned = c.getBoolean("center.pinned", false);
        centerWorld = c.getString("center.world");
        centerX = c.getDouble("center.x");
        centerZ = c.getDouble("center.z");
        ConfigurationSection st = c.getConfigurationSection("entrance.staging-location");
        staging = null;
        if (st != null) {
            World w = Bukkit.getWorld(st.getString("world", ""));
            if (w != null) {
                staging = new Location(w, st.getDouble("x"), st.getDouble("y"), st.getDouble("z"),
                        (float) st.getDouble("yaw"), (float) st.getDouble("pitch"));
            }
        }
    }

    public void save(FileConfiguration c) {
        for (Option o : options.values()) c.set(o.key, o.get());
        c.set("center.pinned", centerPinned);
        c.set("center.world", centerWorld);
        c.set("center.x", centerX);
        c.set("center.z", centerZ);
        c.set("entrance.staging-location", null);
        if (staging != null && staging.getWorld() != null) {
            c.set("entrance.staging-location.world", staging.getWorld().getName());
            c.set("entrance.staging-location.x", staging.getX());
            c.set("entrance.staging-location.y", staging.getY());
            c.set("entrance.staging-location.z", staging.getZ());
            c.set("entrance.staging-location.yaw", staging.getYaw());
            c.set("entrance.staging-location.pitch", staging.getPitch());
        }
    }

    private void register() {
        intOpt("generate.count", "portals per generate", 1, 1000, () -> gen.count, v -> gen.count = v);
        intOpt("generate.radius", "blocks around the center", 8, 400, () -> gen.radius, v -> gen.radius = v);
        intOpt("generate.spacing", "empty blocks between portals", 0, 100, () -> gen.spacing, v -> gen.spacing = v);
        enumOpt("generate.placement", Placement.class, () -> gen.placement, v -> gen.placement = v);
        dblOpt("generate.air-ratio", "share of floating portals in mixed mode (0 to 1)", 0, 1, () -> gen.airRatio, v -> gen.airRatio = v);
        intOpt("generate.height-min", "lowest floating portal, blocks above ground", 2, 300, () -> gen.heightMin, v -> gen.heightMin = v);
        intOpt("generate.height-max", "highest floating portal, blocks above ground", 2, 300, () -> gen.heightMax, v -> gen.heightMax = v);
        enumOpt("generate.orientation", Orientation.class, () -> gen.orientation, v -> gen.orientation = v);
        enumOpt("generate.layout", Layout.class, () -> gen.layout, v -> gen.layout = v);
        enumOpt("generate.facing", FacingMode.class, () -> gen.facing, v -> gen.facing = v);
        intOpt("generate.max-slope", "tallest step in the ground a ground portal may stand on", 0, 10, () -> gen.maxSlope, v -> gen.maxSlope = v);
        intOpt("generate.blocks-per-tick", "blocks placed per tick", 20, 5000, () -> blocksPerTick, v -> blocksPerTick = v);
        intOpt("generate.max-chunks", "refuse areas needing more chunks than this", 16, 5000, () -> maxChunks, v -> maxChunks = v);
        intOpt("generate.max-portals", "portals allowed in one scene", 1, 5000, () -> maxPortals, v -> maxPortals = v);

        boolOpt("groups.auto-assign", "give joining players the next free portal", () -> autoAssignJoiners, v -> autoAssignJoiners = v);
        boolOpt("groups.announce-join", "one action bar line when joining via the join block", () -> announceJoin, v -> announceJoin = v);

        boolOpt("entrance.block-vanilla-travel", "scene portals never send players to the Nether", () -> blockVanillaTravel, v -> blockVanillaTravel = v);
        boolOpt("entrance.staging-invisible", "staged players are invisible until released", () -> stagingInvisible, v -> stagingInvisible = v);
        boolOpt("entrance.freeze-staged", "staged players cannot move", () -> freezeStaged, v -> freezeStaged = v);
        intOpt("entrance.walk-ticks", "how long players are walked forward after release", 0, 200, () -> walkTicks, v -> walkTicks = v);
        dblOpt("entrance.walk-speed", "blocks per tick while walking out", 0.05, 0.5, () -> walkSpeed, v -> walkSpeed = v);
        intOpt("entrance.fall-protection-seconds", "fall damage immunity after release, 0 to disable", 0, 300, () -> fallProtectionSeconds, v -> fallProtectionSeconds = v);
        intOpt("entrance.wave-size", "players per wave for staggered releases", 1, 1000, () -> waveSize, v -> waveSize = v);
        boolOpt("entrance.return-on-reset", "reset sends players back to where they were", () -> returnOnReset, v -> returnOnReset = v);

        intOpt("tnt.per-portal", "minecarts per portal per wave", 1, 50, () -> tntPerPortal, v -> tntPerPortal = v);
        dblOpt("tnt.speed", "launch speed in blocks per tick", 0.1, 5, () -> tntSpeed, v -> tntSpeed = v);
        dblOpt("tnt.lift", "upward launch speed", 0, 2, () -> tntLift, v -> tntLift = v);
        intOpt("tnt.waves", "waves per launch", 1, 100, () -> tntWaves, v -> tntWaves = v);
        intOpt("tnt.wave-delay-ticks", "ticks between waves", 1, 72000, () -> tntWaveDelayTicks, v -> tntWaveDelayTicks = v);
        intOpt("tnt.stagger-ticks", "ticks between portals inside a wave, 0 for all at once", 0, 200, () -> tntStaggerTicks, v -> tntStaggerTicks = v);
        intOpt("tnt.interval-ticks", "ticks between minecarts from the same portal", 1, 100, () -> tntIntervalTicks, v -> tntIntervalTicks = v);
        boolOpt("tnt.preview", "spawn minecarts that never explode", () -> tntPreview, v -> tntPreview = v);
        boolOpt("tnt.protect-terrain", "explosions hurt entities but break no blocks", () -> protectTerrain, v -> protectTerrain = v);
        boolOpt("tnt.protect-portals", "scene portals survive explosions even when terrain damage is on", () -> protectPortals, v -> protectPortals = v);
        intOpt("tnt.max-active", "most minecarts alive at once", 1, 5000, () -> tntMaxActive, v -> tntMaxActive = v);
        intOpt("tnt.lifetime-seconds", "minecarts that have not exploded are removed after this", 5, 3600, () -> tntLifetimeSeconds, v -> tntLifetimeSeconds = v);
        intOpt("tnt.spawns-per-tick", "most minecarts spawned in a single tick", 1, 500, () -> tntSpawnsPerTick, v -> tntSpawnsPerTick = v);
        intOpt("tnt.fuse-ticks", "light the minecart after launch (ticks), -1 for vanilla behaviour", -1, 600, () -> tntFuseTicks, v -> tntFuseTicks = v);
    }

    private void intOpt(String key, String hint, int min, int max, Supplier<Integer> get, Consumer<Integer> set) {
        options.put(key, new Option(key, hint, get::get, s -> {
            int v;
            try {
                v = (int) Math.round(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "' is not a number");
            }
            if (v < min || v > max) throw new IllegalArgumentException("must be between " + min + " and " + max);
            set.accept(v);
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
            if (v < min || v > max) throw new IllegalArgumentException("must be between " + min + " and " + max);
            set.accept(v);
        }, List.of()));
    }

    private void boolOpt(String key, String hint, Supplier<Boolean> get, Consumer<Boolean> set) {
        options.put(key, new Option(key, hint, get::get, s -> {
            String t = s.toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("on") || t.equals("yes")) set.accept(true);
            else if (t.equals("false") || t.equals("off") || t.equals("no")) set.accept(false);
            else throw new IllegalArgumentException("use on or off");
        }, List.of("on", "off")));
    }

    private <E extends Enum<E>> void enumOpt(String key, Class<E> type, Supplier<E> get, Consumer<E> set) {
        List<String> names = new ArrayList<>();
        for (E e : type.getEnumConstants()) names.add(e.name().toLowerCase(Locale.ROOT));
        options.put(key, new Option(key, String.join(" | ", names), () -> get.get().name().toLowerCase(Locale.ROOT), s -> {
            E v = GenSettings.parse(type, s, null);
            if (v == null) throw new IllegalArgumentException("use one of: " + String.join(", ", names));
            set.accept(v);
        }, names));
    }
}
