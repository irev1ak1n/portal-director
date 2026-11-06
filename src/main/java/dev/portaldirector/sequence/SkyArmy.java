package dev.portaldirector.sequence;

import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Floating portals appear one by one, and every group member appears inside one of them. */
final class SkyArmy extends Sequence {

    SkyArmy(SequenceManager mgr) {
        super(mgr);
    }

    @Override
    public String name() {
        return "sky-army";
    }

    @Override
    public String title() {
        return "Sky Army";
    }

    @Override
    public String summary() {
        return "Floating portals appear one by one, and every player of your group appears inside one.";
    }

    @Override
    public List<SeqOption> options() {
        return sorted(List.of(
                SeqOption.text("group", "", "the group that appears (empty: the only group)"),
                SeqOption.whole("height-range", 30, 5, 150, "portals float this many blocks above and below the center's height"),
                SeqOption.decimal("player-delay-max", 0.5, 0.05, 30, "most seconds before a player appears in their portal"),
                SeqOption.decimal("player-delay-min", 0.1, 0.05, 30, "fewest seconds before a player appears in their portal"),
                SeqOption.decimal("portal-delay-max", 0.55, 0.05, 30, "most seconds between two portals appearing"),
                SeqOption.decimal("portal-delay-min", 0.15, 0.05, 30, "fewest seconds between two portals appearing"),
                SeqOption.whole("radius", 100, 10, 400, "how far from the center portals may float (grows if they do not all fit)")));
    }

    @Override
    public List<String[]> pairs() {
        return List.of(new String[]{"player-delay-min", "player-delay-max"}, new String[]{"portal-delay-min", "portal-delay-max"});
    }

    @Override
    public String usage() {
        return "run <x y z | here> [world]";
    }

    @Override
    public String example() {
        return "/pd sequence sky-army run here";
    }

    @Override
    public List<String> facts() {
        return List.of(
                "Players are teleported automatically, one by one, into the purple middle of their portal, facing outward.",
                "They stay in the Overworld and cannot enter the Nether while they wait: /pd sequence stop sends them back where they were.",
                "No TNT. Portal travel is switched off for the players while they wait.");
    }

    @Override
    public List<String> runTab(CommandSender s, List<String> a) {
        return targetTab(s, a, i -> i == 0 ? worlds() : List.of());
    }

    @Override
    public void run(CommandSender s, List<String> a) {
        Target t = target(s, a, "Where? /pd sequence sky-army run <x y z | here>. Example: /pd sequence sky-army run here");
        World world = worldFor(t, a, t.next());
        if (world.getEnvironment() != World.Environment.NORMAL) throw new IllegalArgumentException("Sky Army floats in the Overworld, and " + world.getName() + " is not one.");
        requireIdle();
        Group g = resolveGroup();
        List<Player> players = online(g);
        if (players.isEmpty()) {
            throw new IllegalArgumentException("None of the " + g.members().size() + " members of " + g.name() + " is online" + (g.members().isEmpty() ? " (the group has no members: /pd group assign-to-group <player> " + g.name() + ")" : "") + ".");
        }
        int radius = whole("radius");
        if (FloatingRun.chunksFor(radius) > pl.settings().maxChunks) {
            throw new IllegalArgumentException("A radius of " + radius + " covers " + FloatingRun.chunksFor(radius) + " chunks, more than the limit of " + pl.settings().maxChunks
                    + ". Use a smaller radius: /pd sequence sky-army set radius " + Math.max(10, FloatingRun.maxRadiusFor(pl.settings().maxChunks)));
        }
        Scene scene = pl.scenes().currentOrCreate(world);
        if (!scene.portals().isEmpty() && !scene.worldName().equals(world.getName())) {
            throw new IllegalArgumentException("The existing portals are in world " + scene.worldName() + ". Run /pd clear first to build in " + world.getName() + ".");
        }
        int room = pl.settings().maxPortals - scene.portals().size();
        if (room < 1) throw new IllegalArgumentException("There are already " + scene.portals().size() + " portals, the most allowed (/pd set max-portals). /pd clear removes them.");
        int need = Math.min(players.size(), room);
        Strategy strategy = new Strategy(g, players, need, radius, whole("height-range"),
                SeqTimeline.Range.seconds(decimal("portal-delay-min"), decimal("portal-delay-max")),
                SeqTimeline.Range.seconds(decimal("player-delay-min"), decimal("player-delay-max")));
        Msg.ok(s, "Sky Army: planning " + need + " floating portals for " + players.size() + " players of " + g.name() + " around " + t.x() + " " + t.y() + " " + t.z()
                + ", radius " + radius + ". If they do not all fit, the area, height and gaps widen automatically.");
        if (need < players.size()) Msg.info(s, "Only " + room + " more portals are allowed, so some players will share a portal.");
        new FloatingRun(pl, mgr, s, name(), world, scene, t.x(), t.y(), t.z(), strategy).start();
    }

    private final class Strategy implements FloatingRun.Strategy {
        private final Group group;
        private final List<UUID> ids = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final Map<UUID, Location> homes = new LinkedHashMap<>();
        private final int need;
        private final int radius;
        private final int halfHeight;
        private final SeqTimeline.Range portalGap;
        private final SeqTimeline.Range playerDelay;
        private int[] playerPortal = new int[0];
        private int[] occupantsOf = new int[0];
        private int[] slot = new int[0];
        private final List<UUID> placedPlayers = new ArrayList<>();
        private int missing;

        Strategy(Group group, List<Player> players, int need, int radius, int halfHeight, SeqTimeline.Range portalGap, SeqTimeline.Range playerDelay) {
            this.group = group;
            this.need = need;
            this.radius = radius;
            this.halfHeight = halfHeight;
            this.portalGap = portalGap;
            this.playerDelay = playerDelay;
            for (Player p : players) {
                ids.add(p.getUniqueId());
                names.add(p.getName());
            }
        }

        @Override
        public int need() {
            return need;
        }

        @Override
        public String prefix() {
            return "sky-army";
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
            // The players are hidden from everyone else until each appears in their portal.
            for (UUID id : ids) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                homes.put(id, p.getLocation().clone());
                pl.cinematic().conceal(p);
            }
        }

        @Override
        public List<SeqTimeline.Event> timeline(FloatingRun run, List<PortalSpec> placed, RandomGenerator rnd) {
            int n = ids.size();
            // A random order, so the same people are not always first.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) order.add(i);
            java.util.Collections.shuffle(order, new java.util.Random(rnd.nextLong()));
            List<UUID> ids2 = new ArrayList<>();
            List<String> names2 = new ArrayList<>();
            for (int i : order) {
                ids2.add(ids.get(i));
                names2.add(names.get(i));
            }
            ids.clear();
            ids.addAll(ids2);
            names.clear();
            names.addAll(names2);
            playerPortal = SkyPlanner.assign(n, placed.size(), rnd);
            occupantsOf = new int[placed.size()];
            slot = new int[n];
            for (int p = 0; p < n; p++) slot[p] = occupantsOf[playerPortal[p]]++;
            int[] portalTick = SeqTimeline.portalTicks(placed.size(), portalGap, rnd);
            return SeqTimeline.sky(portalTick, playerPortal, playerDelay, rnd);
        }

        @Override
        public void onEvent(FloatingRun run, SeqTimeline.Event e) {
            if (e.kind() != SeqTimeline.Kind.PLAYER) return;
            int portal = playerPortal[e.index()];
            if (!run.isBuilt(portal)) return;
            UUID id = ids.get(e.index());
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                missing++;
                return;
            }
            PortalSpec spec = run.portal(portal);
            double[] at = SkyPlanner.stand(spec, slot[e.index()], occupantsOf[portal]);
            mgr.hold(id);
            p.teleport(new Location(run.world(), at[0], spec.standY(), at[1], spec.yaw(), 0f));
            p.setFallDistance(0f);
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            pl.cinematic().reveal(p);
            placedPlayers.add(id);
            pl.getLogger().info("sky-army tick " + run.currentTick() + ": " + p.getName() + " appears in " + run.portalName(portal));
        }

        @Override
        public String progress(FloatingRun run) {
            return run.builtCount() + "/" + run.portalCount() + " portals, " + placedPlayers.size() + "/" + ids.size() + " players";
        }

        @Override
        public void finish(FloatingRun run, boolean cancelled, String error) {
            CommandSender s = run.sender();
            if (cancelled || placedPlayers.isEmpty()) {
                // Everyone goes back and everything is undone.
                for (UUID id : ids) {
                    mgr.release(id);
                    Location home = homes.get(id);
                    Player p = Bukkit.getPlayer(id);
                    if (placedPlayers.contains(id) && p != null && home != null && home.getWorld() != null) {
                        p.teleport(home);
                        p.setFallDistance(0f);
                    }
                }
                if (error != null && error.startsWith("No floating portal")) Msg.err(s, "Sky Army could not start: " + error);
                else if (error != null) Msg.err(s, "Sky Army failed (" + error + "). " + placedPlayers.size() + " players were moved back; the " + run.builtCount() + " portals built stay (/pd clear removes them).");
                else Msg.info(s, "Sky Army stopped. " + run.builtCount() + " portals stay (/pd clear removes them); players are back where they were.");
                return;
            }
            // Players who never made it (offline) are shown again; the rest stay in their portals.
            for (UUID id : ids) {
                if (!placedPlayers.contains(id)) mgr.release(id);
            }
            Map<UUID, Location> staged = new LinkedHashMap<>();
            for (UUID id : placedPlayers) staged.put(id, homes.get(id));
            int shared = 0;
            for (int c : occupantsOf) shared += Math.max(0, c - 1);
            mgr.staged(new SequenceManager.Staged("sky-army", staged, run.portalCount(), shared));
            boolean all = placedPlayers.size() == ids.size();
            if (all) {
                Msg.ok(s, "Sky Army ready: all " + ids.size() + " players of " + group.name() + " stand in " + run.portalCount() + " floating portals"
                        + (shared > 0 ? " (" + shared + " share a portal because only " + run.portalCount() + " fit)" : ", one each") + ".");
            } else {
                Msg.err(s, "Sky Army is only partly ready: " + placedPlayers.size() + " of " + ids.size() + " players are staged; " + missing + " left or went offline before their turn.");
            }
            if (run.portalCount() < need) {
                Msg.info(s, "Only " + run.portalCount() + " of the " + need + " portals fit, even after widening the area and height and narrowing the gaps to the safest minimum.");
            }
            Msg.info(s, "They cannot use the portals yet. /pd sequence stop sends everyone back where they were; /pd clear removes the portals.");
        }
    }
}
