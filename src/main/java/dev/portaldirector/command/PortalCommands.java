package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.NaturalBlocks;
import dev.portaldirector.gen.PortalEdits;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.BlockLog;
import dev.portaldirector.scene.RemovalJob;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** /pd create portal and /pd remove: single named portals at exact coordinates. */
final class PortalCommands {

    private static final List<String> FACES = List.of("east", "north", "south", "west");

    private final PortalDirectorPlugin pl;

    PortalCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    /** Everything after "/pd create portal". */
    Sub create() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                createPortal(s, a);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                // a: name, then here|x, ...
                if (a.size() == 1) return List.of();
                if (a.size() == 2) return s instanceof Player p
                        ? List.of("here", String.valueOf(p.getLocation().getBlockX()), "~") : List.of("here");
                if (a.get(1).equalsIgnoreCase("here")) return a.size() == 3 ? FACES : List.of("force");
                if (a.size() == 3 && s instanceof Player p) return List.of(String.valueOf(p.getLocation().getBlockY()), "~");
                if (a.size() == 4 && s instanceof Player p) return List.of(String.valueOf(p.getLocation().getBlockZ()), "~");
                if (a.size() == 5) return FACES;
                if (a.size() == 6) return Bukkit.getWorlds().stream().map(World::getName).toList();
                return List.of("force");
            }
        };
    }

    /** /pd remove <name> or /pd remove <x> <y> <z>. */
    Sub remove() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                removePortal(s, a);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                Scene sc = pl.scenes().current();
                if (sc == null) return List.of();
                if (a.size() == 1) return sc.portals().keySet().stream().map(sc::nameOf).toList();
                return List.of();
            }
        };
    }

    private static BlockFace face(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "north", "n" -> BlockFace.NORTH;
            case "south", "s" -> BlockFace.SOUTH;
            case "east", "e" -> BlockFace.EAST;
            case "west", "w" -> BlockFace.WEST;
            default -> throw new IllegalArgumentException("'" + text + "' is not a direction. Use north, south, east or west.");
        };
    }

    private static int coord(String text, int base, boolean relativeOk) {
        try {
            if (text.startsWith("~")) {
                if (!relativeOk) throw new IllegalArgumentException("~ only works for players.");
                return base + (text.length() == 1 ? 0 : Integer.parseInt(text.substring(1)));
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a whole number coordinate.");
        }
    }

    private void createPortal(CommandSender s, List<String> a) {
        String usage = "Use /pd create portal <name> here <direction>, or /pd create portal <name> <x> <y> <z> <direction>. Example: /pd create portal EntranceA here north";
        if (a.size() < 2) throw new IllegalArgumentException(usage);
        String name = a.get(0);
        Scene known = pl.scenes().current();
        if (known != null) known.checkName(name);
        Player p = s instanceof Player pp ? pp : null;
        int x;
        int y;
        int z;
        BlockFace facing;
        int next;
        World world = p == null ? null : p.getWorld();
        if (a.get(1).equalsIgnoreCase("here")) {
            if (p == null) throw new IllegalArgumentException("Only players can use 'here'. From the console give x y z and a world.");
            if (a.size() < 3) throw new IllegalArgumentException("Which way should it face? /pd create portal " + name + " here north");
            x = p.getLocation().getBlockX();
            y = p.getLocation().getBlockY();
            z = p.getLocation().getBlockZ();
            facing = face(a.get(2));
            next = 3;
        } else {
            if (a.size() < 5) throw new IllegalArgumentException(usage);
            x = coord(a.get(1), p == null ? 0 : p.getLocation().getBlockX(), p != null);
            y = coord(a.get(2), p == null ? 0 : p.getLocation().getBlockY(), p != null);
            z = coord(a.get(3), p == null ? 0 : p.getLocation().getBlockZ(), p != null);
            facing = face(a.get(4));
            next = 5;
        }
        boolean force = false;
        for (int i = next; i < a.size(); i++) {
            if (a.get(i).equalsIgnoreCase("force")) {
                force = true;
            } else {
                World w = Bukkit.getWorld(a.get(i));
                if (w == null) throw new IllegalArgumentException("'" + a.get(i) + "' is neither a loaded world nor 'force'.");
                world = w;
            }
        }
        if (world == null) throw new IllegalArgumentException("Name the world: /pd create portal " + name + " " + x + " " + y + " " + z + " " + facing.name().toLowerCase(Locale.ROOT) + " <world>");
        if (pl.activeJob() != null) throw pl.busy();

        Scene scene = pl.scenes().currentOrCreate(world);
        if (!scene.portals().isEmpty() && !scene.worldName().equals(world.getName())) {
            throw new IllegalArgumentException("The existing portals are in world " + scene.worldName() + ". Run /pd clear first to build in " + world.getName() + ".");
        }
        scene.checkName(name);
        if (scene.portals().size() >= pl.settings().maxPortals) {
            throw new IllegalArgumentException("There are already " + pl.settings().maxPortals + " portals, the most allowed. /pd clear removes them.");
        }
        PortalSpec probe = PortalSpec.fromAnchor(scene.nextId(), x, y, z, facing, false);
        if (probe.y() < world.getMinHeight() + 1 || probe.y() + PortalSpec.HEIGHT >= world.getMaxHeight() - 1) {
            throw new IllegalArgumentException("y " + y + " is outside the build limits (" + (world.getMinHeight() + 2) + " to " + (world.getMaxHeight() - 6) + ").");
        }
        int[] box = probe.box();
        for (int bx : new int[]{box[0], box[3]}) {
            for (int bz : new int[]{box[2], box[5]}) {
                if (!world.getWorldBorder().isInside(new org.bukkit.Location(world, bx, y, bz))) {
                    throw new IllegalArgumentException("That is outside the world border.");
                }
            }
        }
        checkSpacing(scene, world, probe);
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) loads.add(world.getChunkAtAsync(cx, cz, true));
        }
        final World w = world;
        final boolean forced = force;
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) ->
                pl.getServer().getScheduler().runTask(pl, () -> {
                    try {
                        build(s, w, scene, name, x, y, z, facing, forced);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        Msg.err(s, e.getMessage());
                    }
                }));
    }

    private void checkSpacing(Scene scene, World world, PortalSpec probe) {
        for (PortalSpec other : pl.scenes().portalsIn(world.getName())) {
            if (!probe.separatedFrom(other, 1)) {
                String near = pl.scenes().current() == null ? "another portal" : pl.scenes().current().nameOf(other.id());
                throw new IllegalArgumentException("Too close to " + near + " (" + other.anchorX() + ", " + other.anchorY() + ", "
                        + other.anchorZ() + "). Leave at least one empty block between portals.");
            }
        }
    }

    private void build(CommandSender s, World world, Scene scene, String name, int x, int y, int z, BlockFace facing, boolean force) {
        scene.checkName(name);
        PortalSpec probe = PortalSpec.fromAnchor(scene.nextId(), x, y, z, facing, false);
        checkSpacing(scene, world, probe);
        List<int[]> cells = new ArrayList<>(probe.frameBlocks());
        cells.addAll(probe.interiorBlocks());
        cells.addAll(probe.corridorBlocks());
        if (!force) {
            for (int[] c : cells) {
                var type = world.getBlockAt(c[0], c[1], c[2]).getType();
                if (!NaturalBlocks.isNatural(type)) {
                    throw new IllegalArgumentException("That would replace " + type.name().toLowerCase(Locale.ROOT) + " at " + c[0] + ", " + c[1] + ", " + c[2]
                            + ", which looks built by a player. Move the portal, or add 'force' to replace it anyway.");
                }
            }
        }
        BukkitTerrain terrain = new BukkitTerrain(world);
        int maxTop = Integer.MIN_VALUE;
        for (int i = 0; i < PortalSpec.WIDTH; i++) maxTop = Math.max(maxTop, terrain.topY(probe.cellX(i), probe.cellZ(i)));
        int support = PortalPlanner.MAX_SUPPORT;
        boolean floating = probe.y() - 1 - maxTop > support;
        PortalSpec spec = new PortalSpec(probe.id(), probe.x(), probe.y(), probe.z(), probe.axis(), probe.facing(), floating);
        for (PortalEdits.Edit e : PortalEdits.forPortal(world, spec, support)) PortalEdits.apply(world, scene.blocks(), e);
        scene.addPortal(spec, name);
        pl.scenes().select(scene);
        pl.scenes().save(scene);
        pl.saveSettings();
        Msg.ok(s, "Portal " + name + " built at " + x + ", " + y + ", " + z + " facing " + facing.name().toLowerCase(Locale.ROOT)
                + (floating ? " (floating)" : " (on the ground)") + ". /pd remove " + name + " takes it down again.");
    }

    private void removePortal(CommandSender s, List<String> a) {
        String usage = "Use /pd remove <name> or /pd remove <x> <y> <z>. /pd list shows the names and coordinates.";
        if (a.isEmpty()) throw new IllegalArgumentException(usage);
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) throw new IllegalArgumentException("There are no portals to remove.");
        PortalSpec p;
        if (a.size() == 1) {
            p = sc.byName(a.get(0));
            if (p == null) throw new IllegalArgumentException(noSuchName(sc, a.get(0)));
        } else if (a.size() == 3) {
            int[] c = new int[3];
            for (int i = 0; i < 3; i++) {
                try {
                    c[i] = Integer.parseInt(a.get(i));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + a.get(i) + "' is not a whole number coordinate. " + usage);
                }
            }
            p = sc.atAnchor(c[0], c[1], c[2]);
            if (p == null) throw new IllegalArgumentException(noPortalAt(sc, c[0], c[1], c[2]));
        } else {
            throw new IllegalArgumentException(usage);
        }
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        final int id = p.id();
        final String name = sc.nameOf(id);
        RemovalJob job = new RemovalJob(pl, w, sc, pl.settings().blocksPerTick, recordsOf(sc, p), result -> {
            pl.activeJob(null);
            sc.dropPortal(id);
            pl.groups().forgetPortal(name);
            pl.netherLinks().unlink(sc.name(), id);
            pl.scenes().save(sc);
            Msg.ok(s, "Portal " + name + " removed: " + result.restored() + " blocks restored"
                    + (result.kept() > 0 ? ", " + result.kept() + " left alone because they were changed since." : "."));
        });
        pl.activeJob(job);
        job.start();
    }

    private static String noSuchName(Scene sc, String typed) {
        List<String> close = sc.portals().keySet().stream().map(sc::nameOf)
                .filter(n -> n.toLowerCase(Locale.ROOT).contains(typed.toLowerCase(Locale.ROOT)) || typed.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT)))
                .sorted(String.CASE_INSENSITIVE_ORDER).limit(5).toList();
        return "There is no portal called " + typed + "."
                + (close.isEmpty() ? "" : " Did you mean " + String.join(", ", close) + "?") + " /pd list shows every name.";
    }

    private static String noPortalAt(Scene sc, int x, int y, int z) {
        PortalSpec nearest = null;
        double best = Double.MAX_VALUE;
        for (PortalSpec p : sc.portals().values()) {
            double d = Math.pow(p.anchorX() - x, 2) + Math.pow(p.anchorY() - y, 2) + Math.pow(p.anchorZ() - z, 2);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        return "No portal has its anchor at " + x + " " + y + " " + z + "." + (nearest == null ? "" : " The closest is " + sc.nameOf(nearest.id()) + " at "
                + nearest.anchorX() + " " + nearest.anchorY() + " " + nearest.anchorZ() + ".")
                + " The anchor is the lower bottom block inside the doorway; /pd list shows it for every portal. Nothing was removed.";
    }

    /** The logged block changes that belong to one portal: its volume plus the support under its frame. */
    static List<BlockLog.Rec> recordsOf(Scene sc, PortalSpec p) {
        List<BlockLog.Rec> mine = new ArrayList<>();
        int[] box = p.box();
        for (BlockLog.Rec r : sc.blocks().all()) {
            BlockLog.Pos q = r.pos();
            boolean support = q.y() < p.y() && q.y() >= p.y() - 5 && q.x() >= box[0] && q.x() <= box[3] && q.z() >= box[2] && q.z() <= box[5]
                    && onFrameColumn(p, q.x(), q.z());
            if (p.contains(q.x(), q.y(), q.z()) || support) mine.add(r);
        }
        return mine;
    }

    private static boolean onFrameColumn(PortalSpec p, int x, int z) {
        for (int i = 0; i < PortalSpec.WIDTH; i++) {
            if (p.cellX(i) == x && p.cellZ(i) == z) return true;
        }
        return false;
    }
}
