package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.NaturalBlocks;
import dev.portaldirector.gen.PortalEdits;
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

/** /pd portal create|remove|list: single portals at exact coordinates. */
final class PortalCommands {

    private final PortalDirectorPlugin pl;

    PortalCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub portal() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "" : a.get(0).toLowerCase(Locale.ROOT);
                List<String> r = a.isEmpty() ? List.of() : a.subList(1, a.size());
                switch (sub) {
                    case "create", "add" -> create(s, r);
                    case "remove", "delete" -> remove(s, r);
                    case "list" -> list(s);
                    default -> {
                        Msg.head(s, "Single portals");
                        Msg.cmd(s, "/pd portal create <x> <y> <z> <north|south|east|west> [world] [force]", "build one portal");
                        Msg.cmd(s, "/pd portal create here <north|south|east|west>", "at your feet");
                        Msg.cmd(s, "/pd portal remove <id>", "take one down and restore the blocks");
                        Msg.cmd(s, "/pd portal list", "all portals with their coordinates");
                        Msg.line(s, "x y z is the lower of the two bottom portal blocks (the block you stand in). The obsidian floor is the block below it.");
                    }
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("create", "remove", "list");
                String sub = a.get(0).toLowerCase(Locale.ROOT);
                if (sub.equals("remove") || sub.equals("delete")) {
                    Scene sc = pl.scenes().current();
                    return a.size() == 2 && sc != null ? sc.portals().keySet().stream().limit(60).map(String::valueOf).toList() : List.of();
                }
                if (!sub.equals("create") && !sub.equals("add")) return List.of();
                List<String> faces = List.of("north", "south", "east", "west");
                if (a.size() == 2) return s instanceof Player p
                        ? List.of("here", String.valueOf(p.getLocation().getBlockX()), "~") : List.of("here");
                if (a.get(1).equalsIgnoreCase("here")) return a.size() == 3 ? faces : List.of("force");
                if (a.size() == 3 && s instanceof Player p) return List.of(String.valueOf(p.getLocation().getBlockY()), "~");
                if (a.size() == 4 && s instanceof Player p) return List.of(String.valueOf(p.getLocation().getBlockZ()), "~");
                if (a.size() == 5) return faces;
                if (a.size() == 6) return Bukkit.getWorlds().stream().map(World::getName).toList();
                return List.of("force");
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

    private void create(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Use /pd portal create <x> <y> <z> <north|south|east|west>, or /pd portal create here <direction>.");
        Player p = s instanceof Player pp ? pp : null;
        int x;
        int y;
        int z;
        BlockFace facing;
        int next;
        World world = p == null ? null : p.getWorld();
        if (a.get(0).equalsIgnoreCase("here")) {
            if (p == null) throw new IllegalArgumentException("Only players can use 'here'. From the console give x y z and a world.");
            if (a.size() < 2) throw new IllegalArgumentException("Which way should it face? /pd portal create here north");
            x = p.getLocation().getBlockX();
            y = p.getLocation().getBlockY();
            z = p.getLocation().getBlockZ();
            facing = face(a.get(1));
            next = 2;
        } else {
            if (a.size() < 4) throw new IllegalArgumentException("Use /pd portal create <x> <y> <z> <north|south|east|west>");
            x = coord(a.get(0), p == null ? 0 : p.getLocation().getBlockX(), p != null);
            y = coord(a.get(1), p == null ? 0 : p.getLocation().getBlockY(), p != null);
            z = coord(a.get(2), p == null ? 0 : p.getLocation().getBlockZ(), p != null);
            facing = face(a.get(3));
            next = 4;
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
        if (world == null) throw new IllegalArgumentException("Name the world: /pd portal create " + x + " " + y + " " + z + " " + facing.name().toLowerCase(Locale.ROOT) + " <world>");
        if (pl.activeJob() != null) throw new IllegalArgumentException("Busy: " + pl.activeJob().describe() + ".");

        Scene scene = pl.scenes().currentOrCreate(world);
        if (!scene.portals().isEmpty() && !scene.worldName().equals(world.getName())) {
            throw new IllegalArgumentException("Scene " + scene.name() + " is in world " + scene.worldName() + ". Start a new one with /pd scene new <name>.");
        }
        if (scene.portals().size() >= pl.settings().maxPortals) {
            throw new IllegalArgumentException("Scene " + scene.name() + " already has the maximum of " + pl.settings().maxPortals + " portals.");
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
        for (PortalSpec other : pl.scenes().portalsIn(world.getName())) {
            if (!probe.separatedFrom(other, 1)) {
                throw new IllegalArgumentException("Too close to portal " + other.id() + " (" + other.anchorX() + ", " + other.anchorY() + ", "
                        + other.anchorZ() + "). Leave at least one empty block between portals.");
            }
        }
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) loads.add(world.getChunkAtAsync(cx, cz, true));
        }
        final World w = world;
        final boolean forced = force;
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) ->
                pl.getServer().getScheduler().runTask(pl, () -> {
                    try {
                        build(s, w, scene, x, y, z, facing, forced);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        Msg.err(s, e.getMessage());
                    }
                }));
    }

    private void build(CommandSender s, World world, Scene scene, int x, int y, int z, BlockFace facing, boolean force) {
        PortalSpec probe = PortalSpec.fromAnchor(scene.nextId(), x, y, z, facing, false);
        for (PortalSpec other : pl.scenes().portalsIn(world.getName())) {
            if (!probe.separatedFrom(other, 1)) throw new IllegalArgumentException("Too close to portal " + other.id() + ".");
        }
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
        int support = pl.settings().gen.maxSlope + 1;
        boolean floating = probe.y() - 1 - maxTop > support;
        PortalSpec spec = new PortalSpec(probe.id(), probe.x(), probe.y(), probe.z(), probe.axis(), probe.facing(), floating);
        for (PortalEdits.Edit e : PortalEdits.forPortal(world, spec, support)) PortalEdits.apply(world, scene.blocks(), e);
        scene.portals().put(spec.id(), spec);
        pl.scenes().select(scene);
        pl.groups().fillAll(scene);
        pl.scenes().save(scene);
        pl.saveSettings();
        Msg.ok(s, "Portal " + spec.id() + " built at " + x + ", " + y + ", " + z + " facing " + facing.name().toLowerCase(Locale.ROOT)
                + (floating ? " (floating)" : " (on the ground)") + " in scene " + scene.name() + ".");
    }

    private void remove(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Which portal? /pd portal remove <id>");
        Scene sc = pl.scenes().current();
        if (sc == null) throw new IllegalArgumentException("There is no scene yet.");
        int id;
        try {
            id = Integer.parseInt(a.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Portal ids are numbers, like /pd portal remove 7.");
        }
        PortalSpec p = sc.portals().get(id);
        if (p == null) throw new IllegalArgumentException("There is no portal " + id + " in scene " + sc.name() + ". /pd portal list shows them.");
        if (pl.activeJob() != null) throw new IllegalArgumentException("Busy: " + pl.activeJob().describe() + ".");
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        List<BlockLog.Rec> mine = new ArrayList<>();
        int[] box = p.box();
        for (BlockLog.Rec r : sc.blocks().all()) {
            BlockLog.Pos q = r.pos();
            boolean inBox = p.contains(q.x(), q.y(), q.z());
            boolean support = q.y() < p.y() && q.y() >= p.y() - 5 && q.x() >= box[0] && q.x() <= box[3] && q.z() >= box[2] && q.z() <= box[5]
                    && onFrameColumn(p, q.x(), q.z());
            if (inBox || support) mine.add(r);
        }
        RemovalJob job = new RemovalJob(pl, w, sc, pl.settings().blocksPerTick, mine, result -> {
            pl.activeJob(null);
            sc.dropPortal(id);
            pl.scenes().save(sc);
            Msg.ok(s, "Portal " + id + " removed: " + result.restored() + " blocks restored"
                    + (result.kept() > 0 ? ", " + result.kept() + " left alone because they were changed since." : "."));
        });
        pl.activeJob(job);
        job.start();
    }

    private static boolean onFrameColumn(PortalSpec p, int x, int z) {
        for (int i = 0; i < PortalSpec.WIDTH; i++) {
            if (p.cellX(i) == x && p.cellZ(i) == z) return true;
        }
        return false;
    }

    private void list(CommandSender s) {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) {
            Msg.info(s, "No portals yet. /pd generate 30 mixed, or /pd portal create here north.");
            return;
        }
        Msg.head(s, sc.portals().size() + " portals in " + sc.name());
        for (PortalSpec p : sc.portals().values()) {
            Msg.line(s, "  #" + p.id() + "  " + p.anchorX() + " " + p.anchorY() + " " + p.anchorZ() + "  facing "
                    + p.facing().name().toLowerCase(Locale.ROOT) + (p.floating() ? "  floating" : "  ground"));
        }
    }
}
