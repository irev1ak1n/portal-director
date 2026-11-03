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
import org.bukkit.Location;
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
                    case "list" -> list(s, r);
                    case "tp", "teleport", "go" -> tp(s, r);
                    case "view" -> view(s, r);
                    default -> {
                        Msg.head(s, "Single portals");
                        Msg.cmd(s, "/pd portal create <x> <y> <z> <north|south|east|west> [world] [force]", "build one portal");
                        Msg.cmd(s, "/pd portal create here <north|south|east|west>", "at your feet");
                        Msg.cmd(s, "/pd portal remove <id>", "take one down and restore the blocks");
                        Msg.cmd(s, "/pd portal list", "all portals, nearest first, with distance and direction");
                        Msg.cmd(s, "/pd portal tp <id|nearest|farthest>", "stand safely near a portal and look at it");
                        Msg.cmd(s, "/pd view", "see the whole formation from above");
                        Msg.line(s, "x y z is the lower of the two bottom portal blocks (the block you stand in). The obsidian floor is the block below it.");
                    }
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("create", "remove", "list", "tp", "view");
                String sub = a.get(0).toLowerCase(Locale.ROOT);
                if (sub.equals("remove") || sub.equals("delete") || sub.equals("tp") || sub.equals("teleport") || sub.equals("go")) {
                    Scene sc = pl.scenes().current();
                    if (a.size() != 2 || sc == null) return List.of();
                    List<String> ids = new ArrayList<>(sub.startsWith("t") || sub.equals("go") ? List.of("nearest", "farthest") : List.of());
                    sc.portals().keySet().stream().limit(60).forEach(id -> ids.add(String.valueOf(id)));
                    return ids;
                }
                if (sub.equals("view")) return a.size() == 2 ? List.of("high", "ground") : List.of();
                if (sub.equals("list")) return List.of("1", "2", "3");
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
        if (pl.activeJob() != null) throw pl.busy();

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
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        List<BlockLog.Rec> mine = recordsOf(sc, p);
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

    /** The logged block changes that belong to one portal: its volume plus the support under its frame. */
    static List<BlockLog.Rec> recordsOf(Scene sc, PortalSpec p) {
        List<BlockLog.Rec> mine = new ArrayList<>();
        int[] box = p.box();
        for (BlockLog.Rec r : sc.blocks().all()) {
            BlockLog.Pos q = r.pos();
            boolean inBox = p.contains(q.x(), q.y(), q.z());
            boolean support = q.y() < p.y() && q.y() >= p.y() - 5 && q.x() >= box[0] && q.x() <= box[3] && q.z() >= box[2] && q.z() <= box[5]
                    && onFrameColumn(p, q.x(), q.z());
            if (inBox || support) mine.add(r);
        }
        return mine;
    }

    private static boolean onFrameColumn(PortalSpec p, int x, int z) {
        for (int i = 0; i < PortalSpec.WIDTH; i++) {
            if (p.cellX(i) == x && p.cellZ(i) == z) return true;
        }
        return false;
    }

    private void list(CommandSender s, List<String> args) {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) {
            Msg.info(s, "There are no portals yet" + (sc == null ? "" : " in scene " + sc.name()) + ". /pd generate 30 builds some, /pd portal create here north builds one.");
            return;
        }
        int page = 1;
        if (!args.isEmpty()) {
            try {
                page = Math.max(1, Integer.parseInt(args.get(0)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Use /pd portal list or /pd portal list 2 for the second page.");
            }
        }
        double[] from = Report.reference(s, sc);
        List<PortalSpec> sorted = sc.portals().values().stream()
                .sorted(java.util.Comparator.comparingDouble(p -> Math.hypot(p.centerX() - from[0], p.centerZ() - from[1]))).toList();
        int per = 12;
        int pages = Math.max(1, (sorted.size() + per - 1) / per);
        page = Math.min(page, pages);
        Msg.head(s, "Scene " + sc.name() + ": " + sorted.size() + " portals, nearest first (page " + page + " of " + pages + ")");
        for (PortalSpec p : sorted.subList((page - 1) * per, Math.min(sorted.size(), page * per))) {
            Msg.line(s, "  #" + p.id() + "  " + Report.where(from, p) + "  at " + p.anchorX() + " " + p.anchorY() + " " + p.anchorZ()
                    + "  " + (p.floating() ? "floating" : "ground") + ", exit " + p.facing().name().toLowerCase(Locale.ROOT));
        }
        Msg.line(s, "  Visit one with /pd portal tp <id>. Coordinates are what /pd portal create takes.");
    }

    // Finding portals

    private PortalSpec pick(Scene sc, String what, CommandSender s) {
        if (what.equalsIgnoreCase("nearest") || what.equalsIgnoreCase("near") || what.equalsIgnoreCase("farthest") || what.equalsIgnoreCase("far")) {
            double[] from = Report.reference(s, sc);
            var cmp = java.util.Comparator.<PortalSpec>comparingDouble(p -> Math.hypot(p.centerX() - from[0], p.centerZ() - from[1]));
            return what.toLowerCase(Locale.ROOT).startsWith("n") ? sc.portals().values().stream().min(cmp).orElseThrow()
                    : sc.portals().values().stream().max(cmp).orElseThrow();
        }
        int id;
        try {
            id = Integer.parseInt(what);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Which portal? Use a number like /pd portal tp 3, or nearest or farthest.");
        }
        PortalSpec p = sc.portals().get(id);
        if (p == null) throw new IllegalArgumentException("There is no portal " + id + " in scene " + sc.name() + ". /pd portal list shows them.");
        return p;
    }

    private Scene needScene() {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) {
            throw new IllegalArgumentException("There are no portals to go to. Build some first: /pd generate 30");
        }
        return sc;
    }

    /** A spot on the ground in front of the portal where you can stand safely and look at it. */
    static Location viewSpot(World w, PortalSpec p) {
        BukkitTerrain t = new BukkitTerrain(w);
        double dist = p.floating() ? 9 : 5;
        int x = (int) Math.floor(p.centerX() + p.stepX() * dist);
        int z = (int) Math.floor(p.centerZ() + p.stepZ() * dist);
        int top = t.topY(x, z);
        int y;
        if (top < w.getMinHeight() || t.isLiquid(x, top, z)) {
            x = (int) Math.floor(p.centerX() + p.stepX() * 3);
            z = (int) Math.floor(p.centerZ() + p.stepZ() * 3);
            y = p.floating() ? Math.max(t.topY(x, z), w.getMinHeight()) + 1 : (int) p.standY();
        } else {
            y = top + 1;
        }
        for (int i = 0; i < 8 && !(t.isAir(x, y, z) && t.isAir(x, y + 1, z)); i++) y++;
        double eyeToPortal = (p.y() + 2.0) - (y + 1.6);
        float pitch = (float) -Math.toDegrees(Math.atan2(eyeToPortal, dist));
        return new Location(w, x + 0.5, y, z + 0.5, p.yaw() + 180f, Math.max(-60f, Math.min(30f, pitch)));
    }

    private void tp(CommandSender s, List<String> a) {
        if (!(s instanceof Player player)) throw new IllegalArgumentException("Only players can teleport.");
        Scene sc = needScene();
        PortalSpec p = pick(sc, a.isEmpty() ? "nearest" : a.get(0), s);
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("The world " + sc.worldName() + " is not loaded.");
        int cx = (int) Math.floor(p.centerX() + p.stepX() * 9) >> 4;
        int cz = (int) Math.floor(p.centerZ() + p.stepZ() * 9) >> 4;
        w.getChunkAtAsync(cx, cz, true).thenCompose(c -> w.getChunkAtAsync((int) Math.floor(p.centerX()) >> 4, (int) Math.floor(p.centerZ()) >> 4, true))
                .whenComplete((c, err) -> pl.getServer().getScheduler().runTask(pl, () -> {
                    if (!player.isOnline()) return;
                    Location spot = viewSpot(w, p);
                    player.teleportAsync(spot);
                    Msg.ok(player, "Standing " + (p.floating() ? "below and in front of" : "in front of") + " portal " + p.id()
                            + (p.floating() ? ", which floats " + (p.anchorY() - spot.getBlockY()) + " blocks above you." : ".")
                            + " Walk through it to use it, or look around.");
                }));
    }

    private void view(CommandSender s, List<String> a) {
        if (!(s instanceof Player player)) throw new IllegalArgumentException("Only players can teleport.");
        Scene sc = needScene();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("The world " + sc.worldName() + " is not loaded.");
        boolean high = a.isEmpty() || !a.get(0).equalsIgnoreCase("ground");
        double x = 0;
        double z = 0;
        if (sc.square() != null) {
            x = sc.square().centerX() + 0.5;
            z = sc.square().centerZ() + 0.5;
        } else {
            for (PortalSpec p : sc.portals().values()) {
                x += p.centerX();
                z += p.centerZ();
            }
            x /= sc.portals().size();
            z /= sc.portals().size();
        }
        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        w.getChunkAtAsync(bx >> 4, bz >> 4, true).whenComplete((c, err) -> pl.getServer().getScheduler().runTask(pl, () -> {
            if (!player.isOnline()) return;
            BukkitTerrain t = new BukkitTerrain(w);
            int top = t.topY(bx, bz);
            if (!high && (top < w.getMinHeight() || t.isLiquid(bx, top, bz))) {
                PortalSpec fallback = sc.portals().values().iterator().next();
                player.teleportAsync(viewSpot(w, fallback));
                Msg.ok(player, "The middle of the formation is water, so you are at portal " + fallback.id() + " instead.");
                return;
            }
            int y = Math.max(top, w.getSeaLevel()) + 1 + (high ? 40 : 0);
            player.teleportAsync(new Location(w, bx + 0.5, y, bz + 0.5, player.getLocation().getYaw(), high ? 35f : 0f));
            if (high) {
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING, 20 * 45, 0, false, false, true));
                Msg.ok(player, "You are 40 blocks above the middle of the formation and float down slowly. Look around; /pd portal tp <id> goes to one portal.");
            } else {
                Msg.ok(player, "You are in the middle of the formation.");
            }
        }));
    }
}
