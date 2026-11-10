package dev.portaldirector.sequence;

import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.tnt.TntDirector;
import dev.portaldirector.util.Msg;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/** Floating portals appear one by one and each fires its own random number of TNT minecarts. */
final class TntStrike extends Sequence {

    TntStrike(SequenceManager mgr) {
        super(mgr);
    }

    @Override
    public String name() {
        return "tnt-strike";
    }

    @Override
    public String title() {
        return "TNT Strike";
    }

    @Override
    public String summary() {
        return "Floating portals appear one by one, and each fires a random number of TNT minecarts outward.";
    }

    @Override
    public List<SeqOption> options() {
        return sorted(List.of(
                SeqOption.whole("height-range", 30, 5, 150, "portals float this many blocks above and below the center's height"),
                SeqOption.decimal("launch-delay-max", 0.7, 0.05, 30, "most seconds between a portal's minecarts (and before its first)"),
                SeqOption.decimal("launch-delay-min", 0.2, 0.05, 30, "fewest seconds between a portal's minecarts (and before its first)"),
                SeqOption.decimal("portal-delay-max", 0.55, 0.05, 30, "most seconds between two portals appearing"),
                SeqOption.decimal("portal-delay-min", 0.15, 0.05, 30, "fewest seconds between two portals appearing"),
                SeqOption.whole("portals", 20, 1, 200, "how many floating portals to make"),
                SeqOption.whole("radius", 100, 10, 400, "how far from the center portals may float"),
                SeqOption.whole("tnt-max", 10, 1, 50, "most minecarts one portal fires"),
                SeqOption.whole("tnt-min", 5, 1, 50, "fewest minecarts one portal fires"),
                SeqOption.choice("tnt-mode", "safe", List.of("real", "safe"), "safe: minecarts fly but never explode; real: they explode")));
    }

    @Override
    public List<String[]> pairs() {
        return List.of(new String[]{"launch-delay-min", "launch-delay-max"}, new String[]{"portal-delay-min", "portal-delay-max"}, new String[]{"tnt-min", "tnt-max"});
    }

    @Override
    public String usage() {
        return "run <x y z | here | player> [world]";
    }

    @Override
    public String example() {
        return "/pd sequence tnt-strike run here";
    }

    @Override
    public List<String> facts() {
        return List.of(
                "No player is moved. Only the minecarts launch, outward from each portal's facing.",
                "TNT is involved. Safe mode (the default) never explodes; real mode explodes using your /pd tnt fuse, terrain-damage and portal-protection.",
                "Every minecart counts against /pd tnt limit, which is checked before anything starts. Portal travel is not involved.");
    }

    @Override
    public List<String> runTab(CommandSender s, List<String> a) {
        return targetTab(s, a, i -> i == 0 ? worlds() : List.of());
    }

    @Override
    public void run(CommandSender s, List<String> a) {
        Target t = target(s, a, "Where? /pd sequence tnt-strike run <x y z | here | player>. Example: /pd sequence tnt-strike run here");
        World world = worldFor(t, a, t.next());
        if (world.getEnvironment() != World.Environment.NORMAL) throw new IllegalArgumentException("TNT Strike floats in the Overworld, and " + world.getName() + " is not one.");
        requireIdle();
        int radius = whole("radius");
        if (FloatingRun.chunksFor(radius) > pl.settings().maxChunks) {
            throw new IllegalArgumentException("A radius of " + radius + " covers " + FloatingRun.chunksFor(radius) + " chunks, more than the limit of " + pl.settings().maxChunks
                    + ". Use a smaller radius: /pd sequence tnt-strike set radius " + Math.max(10, FloatingRun.maxRadiusFor(pl.settings().maxChunks)));
        }
        Scene scene = pl.scenes().currentOrCreate(world);
        if (!scene.portals().isEmpty() && !scene.worldName().equals(world.getName())) {
            throw new IllegalArgumentException("The existing portals are in world " + scene.worldName() + ". Run /pd clear first to build in " + world.getName() + ".");
        }
        int room = pl.settings().maxPortals - scene.portals().size();
        int portals = Math.min(whole("portals"), room);
        if (portals < 1) throw new IllegalArgumentException("There are already " + scene.portals().size() + " portals, the most allowed (/pd set max-portals). /pd clear removes them.");

        // Work out every minecart now, so a limit that is too low is reported before anything is built.
        RandomGenerator rnd = new Random();
        int[] counts = new int[portals];
        int total = 0;
        for (int i = 0; i < portals; i++) {
            counts[i] = SeqTimeline.between(rnd, whole("tnt-min"), whole("tnt-max"));
            total += counts[i];
        }
        TntDirector tnt = pl.tnt();
        if (total > tnt.remainingLaunches()) {
            throw new IllegalArgumentException("This run would launch " + total + " TNT minecarts (" + portals + " portals, " + whole("tnt-min") + " to " + whole("tnt-max")
                    + " each), but the launch limit of " + pl.settings().tntMaxActive + " has only " + tnt.remainingLaunches() + " left (" + tnt.launchedTotal() + " already launched). "
                    + "Nothing was built. Raise it with /pd tnt limit " + (tnt.launchedTotal() + total) + ", start the count again with /pd tnt clear, or use fewer: /pd sequence tnt-strike set portals <n>.");
        }
        boolean safe = value("tnt-mode").equals("safe");
        Strategy strategy = new Strategy(portals, radius, whole("height-range"), counts, total, safe,
                SeqTimeline.Range.seconds(decimal("portal-delay-min"), decimal("portal-delay-max")),
                SeqTimeline.Range.seconds(decimal("launch-delay-min"), decimal("launch-delay-max")));
        Msg.ok(s, "TNT Strike: " + portals + " floating portals around " + t.x() + " " + t.y() + " " + t.z() + ", radius " + radius + ", planning " + total + " minecarts ("
                + (safe ? "safe mode: they will not explode" : "REAL mode: they explode") + "). " + tnt.launchedTotal() + " of " + pl.settings().tntMaxActive + " already used of the launch limit.");
        new FloatingRun(pl, mgr, s, name(), world, scene, t.x(), t.y(), t.z(), strategy).start();
    }

    private final class Strategy implements FloatingRun.Strategy {
        private final int portals;
        private final int radius;
        private final int halfHeight;
        private final int[] counts;
        private final int planned;
        private final boolean safe;
        private final SeqTimeline.Range portalGap;
        private final SeqTimeline.Range launchDelay;
        private int scheduled;
        private int launched;
        private int skipped;

        Strategy(int portals, int radius, int halfHeight, int[] counts, int planned, boolean safe, SeqTimeline.Range portalGap, SeqTimeline.Range launchDelay) {
            this.portals = portals;
            this.radius = radius;
            this.halfHeight = halfHeight;
            this.counts = counts;
            this.planned = planned;
            this.safe = safe;
            this.portalGap = portalGap;
            this.launchDelay = launchDelay;
        }

        @Override
        public int need() {
            return portals;
        }

        @Override
        public String prefix() {
            return "tnt-strike";
        }

        @Override
        public int radius() {
            return radius;
        }

        @Override
        public int halfHeight() {
            return halfHeight;
        }

        @Override
        public void begin(FloatingRun run) {
        }

        @Override
        public List<SeqTimeline.Event> timeline(FloatingRun run, List<PortalSpec> placed, RandomGenerator rnd) {
            int n = placed.size();
            int[] use = java.util.Arrays.copyOf(counts, n);
            for (int c : use) scheduled += c;
            int[] portalTick = SeqTimeline.portalTicks(n, portalGap, rnd);
            return SeqTimeline.tnt(portalTick, use, launchDelay, rnd);
        }

        @Override
        public void onEvent(FloatingRun run, SeqTimeline.Event e) {
            if (e.kind() != SeqTimeline.Kind.LAUNCH || !run.isBuilt(e.index())) return;
            TntDirector.Launch r = pl.tnt().launchOne(run.world(), run.portal(e.index()), safe);
            if (r == TntDirector.Launch.LAUNCHED) {
                launched++;
                pl.getLogger().info("tnt-strike tick " + run.currentTick() + ": minecart from " + run.portalName(e.index()) + " facing "
                        + run.portal(e.index()).facing().name().toLowerCase(java.util.Locale.ROOT));
            } else {
                skipped++;
            }
        }

        @Override
        public String progress(FloatingRun run) {
            return run.builtCount() + "/" + run.portalCount() + " portals, " + launched + "/" + scheduled + " minecarts launched";
        }

        @Override
        public void finish(FloatingRun run, boolean cancelled, String error) {
            CommandSender s = run.sender();
            if (error != null && error.startsWith("No floating portal")) {
                Msg.err(s, "TNT Strike could not start: " + error);
            } else if (error != null) {
                Msg.err(s, "TNT Strike failed (" + error + "). " + run.builtCount() + " portals and " + launched + " minecarts are out; /pd clear removes the portals, /pd tnt clear the minecarts.");
            } else if (cancelled) {
                Msg.info(s, "TNT Strike stopped after " + launched + " minecarts. The " + run.builtCount() + " portals stay (/pd clear removes them); minecarts already out keep going (/pd tnt clear removes them).");
            } else {
                Msg.ok(s, "TNT Strike done: " + run.portalCount() + " portals fired " + launched + " TNT minecarts (" + (safe ? "safe mode" : "real mode") + ").");
                if (run.portalCount() < portals) Msg.info(s, "Only " + run.portalCount() + " of " + portals + " portals fit around that center.");
                if (skipped > 0) Msg.err(s, skipped + " minecarts were not launched (the launch limit was reached by something else, or their chunk was not loaded).");
                Msg.info(s, pl.tnt().launchedTotal() + " of " + pl.settings().tntMaxActive + " minecarts of the launch limit are used (/pd tnt status; /pd tnt clear starts the count again).");
            }
        }
    }
}
