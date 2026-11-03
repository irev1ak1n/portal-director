package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.gen.BukkitTerrain;
import dev.portaldirector.gen.GenerationJob;
import dev.portaldirector.gen.PortalPlanner;
import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.gen.SquarePlanner;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.BlockLog;
import dev.portaldirector.scene.RemovalJob;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SquareInfo;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** /pd square ...: portals along the four sides of a square around an open middle. */
final class SquareCommands {

    private final PortalDirectorPlugin pl;

    SquareCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private record Request(World world, int centerX, int centerZ, int perSide, int gap, int size, SquareFacing facing,
                           Integer elevation, boolean partial, int requestedSize) {
    }

    Sub square() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "" : a.get(0).toLowerCase(Locale.ROOT);
                List<String> r = a.isEmpty() ? List.of() : a.subList(1, a.size());
                switch (sub) {
                    case "create" -> plan(s, r, false);
                    case "preview" -> plan(s, r, true);
                    case "facing" -> facing(s, r);
                    case "remove" -> remove(s);
                    case "info" -> info(s);
                    default -> help(s);
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return SquareCommands.this.tab(a);
            }
        };
    }

    private void help(CommandSender s) {
        Msg.head(s, "Square formation");
        Msg.cmd(s, "/pd square create 60", "sides about 60 blocks long, around you");
        Msg.cmd(s, "/pd square create 8 portals-per-side", "8 portals on each of the 4 sides");
        Msg.cmd(s, "/pd square create 100 spacing 3", "3 empty blocks between portals");
        Msg.cmd(s, "/pd square create 60 elevation 25", "floating 25 blocks above the highest ground");
        Msg.cmd(s, "/pd square create 60 facing outward", "inward (default) or outward");
        Msg.cmd(s, "/pd square create 60 center 100 -40 world", "exact center instead of your position");
        Msg.cmd(s, "/pd square preview 60", "show the plan and outline it with particles, build nothing");
        Msg.cmd(s, "/pd square facing inward|outward", "set the default, and turn an existing square");
        Msg.cmd(s, "/pd square remove  /pd square info", "take it down, or see what it is");
        Msg.line(s, "Add 'partial' to build the positions that fit when some are blocked by water or buildings.");
        Msg.line(s, "Release patterns: /pd group release <name> clockwise|opposite-sides|side-by-side|all-at-once [secs] [size]");
    }

    // Planning

    private Request parse(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("How big? /pd square create 60 (blocks per side) or /pd square create 8 portals-per-side");
        Settings st = pl.settings();
        int number;
        try {
            number = Integer.parseInt(a.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + a.get(0) + "' is not a size. Try /pd square create 60.");
        }
        boolean perSideMode = false;
        int gap = st.squareSpacing;
        SquareFacing facing = st.squareFacing;
        Integer elevation = null;
        boolean partial = false;
        Player p = s instanceof Player pp ? pp : null;
        World world = p == null ? null : p.getWorld();
        Integer cx = null;
        Integer cz = null;
        int i = 1;
        while (i < a.size()) {
            String t = a.get(i).toLowerCase(Locale.ROOT);
            switch (t) {
                case "portals-per-side", "per-side" -> {
                    perSideMode = true;
                    i++;
                }
                case "partial" -> {
                    partial = true;
                    i++;
                }
                case "spacing", "gap" -> {
                    gap = intArg(a, i, "spacing");
                    if (gap < 0 || gap > 20) throw new IllegalArgumentException("Spacing must be between 0 and 20 blocks.");
                    i += 2;
                }
                case "elevation", "elevated", "height" -> {
                    elevation = intArg(a, i, "elevation");
                    if (elevation < 2 || elevation > 300) throw new IllegalArgumentException("Elevation must be between 2 and 300 blocks above the ground.");
                    i += 2;
                }
                case "facing" -> {
                    if (i + 1 >= a.size()) throw new IllegalArgumentException("facing needs inward or outward.");
                    facing = GenerateSquareFacing.parse(a.get(i + 1));
                    i += 2;
                }
                case "center" -> {
                    if (i + 1 < a.size() && a.get(i + 1).equalsIgnoreCase("here")) {
                        if (p == null) throw new IllegalArgumentException("Only players can use 'here'.");
                        cx = p.getLocation().getBlockX();
                        cz = p.getLocation().getBlockZ();
                        i += 2;
                    } else {
                        cx = intArg(a, i, "center x");
                        if (i + 2 >= a.size()) throw new IllegalArgumentException("center needs x and z: center 100 -40 [world]");
                        try {
                            cz = Integer.parseInt(a.get(i + 2));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("'" + a.get(i + 2) + "' is not a coordinate.");
                        }
                        i += 3;
                        if (i < a.size() && Bukkit.getWorld(a.get(i)) != null) world = Bukkit.getWorld(a.get(i++));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option '" + a.get(i) + "'. /pd square shows the options.");
            }
        }
        if (cx == null) {
            if (st.centerPinned) {
                World w = Bukkit.getWorld(st.centerWorld == null ? "" : st.centerWorld);
                if (w == null) throw new IllegalArgumentException("The pinned center is in a world that is not loaded. Use /pd set center reset.");
                world = w;
                cx = (int) Math.floor(st.centerX);
                cz = (int) Math.floor(st.centerZ);
            } else if (p != null) {
                cx = p.getLocation().getBlockX();
                cz = p.getLocation().getBlockZ();
            } else {
                throw new IllegalArgumentException("From the console give a center: /pd square create 60 center <x> <z> <world>");
            }
        }
        if (world == null) throw new IllegalArgumentException("Name the world: ... center <x> <z> <world>");
        int perSide;
        int size;
        if (perSideMode) {
            perSide = number;
            if (perSide < 2 || perSide > 60) throw new IllegalArgumentException("Portals per side must be between 2 and 60.");
            size = SquarePlanner.sizeFor(perSide, gap);
        } else {
            perSide = SquarePlanner.perSideFor(number, gap);
            size = SquarePlanner.sizeFor(perSide, gap);
            if (number < SquarePlanner.MIN_SIZE) {
                throw new IllegalArgumentException("A side of " + number + " blocks is too small: the middle must stay open. Use at least " + SquarePlanner.MIN_SIZE + ".");
            }
            if (perSide < 2) throw new IllegalArgumentException("With " + gap + " blocks between portals not even 2 portals fit on a side of " + number + ".");
        }
        if (size < SquarePlanner.MIN_SIZE) {
            throw new IllegalArgumentException("That square would be only " + size + " blocks wide. The middle must stay open, so use more portals or more spacing (minimum " + SquarePlanner.MIN_SIZE + ").");
        }
        if (size > 400) throw new IllegalArgumentException("That square would be " + size + " blocks wide; the limit is 400.");
        if (4 * perSide > pl.settings().maxPortals) {
            throw new IllegalArgumentException(4 * perSide + " portals is more than the limit of " + pl.settings().maxPortals + " in total.");
        }
        return new Request(world, cx, cz, perSide, gap, size, facing, elevation, partial, number);
    }

    private static int intArg(List<String> a, int i, String what) {
        if (i + 1 >= a.size()) throw new IllegalArgumentException(what + " needs a number.");
        try {
            return Integer.parseInt(a.get(i + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + a.get(i + 1) + "' is not a number for " + what + ".");
        }
    }

    private void plan(CommandSender s, List<String> a, boolean previewOnly) {
        Request rq = parse(s, a);
        if (pl.activeJob() != null) throw pl.busy();
        Scene scene = pl.scenes().currentOrCreate(rq.world());
        if (!scene.portals().isEmpty() && !scene.worldName().equals(rq.world().getName())) {
            throw new IllegalArgumentException("The existing portals are in world " + scene.worldName() + ". Run /pd clear first to build in " + rq.world().getName() + ".");
        }
        if (!previewOnly && scene.square() != null) {
            throw new IllegalArgumentException("There is already a square. /pd square remove takes it down first.");
        }
        if (scene.portals().size() + 4 * rq.perSide() > pl.settings().maxPortals) {
            throw new IllegalArgumentException("That would be more than " + pl.settings().maxPortals + " portals in total.");
        }
        int x0 = rq.centerX() - rq.size() / 2;
        int z0 = rq.centerZ() - rq.size() / 2;
        List<CompletableFuture<?>> loads = new ArrayList<>();
        int margin = 8;
        int chunks = ((rq.size() + 2 * margin) / 16 + 2) * ((rq.size() + 2 * margin) / 16 + 2);
        if (chunks > pl.settings().maxChunks) {
            throw new IllegalArgumentException("That square touches about " + chunks + " chunks, more than the limit of " + pl.settings().maxChunks + ".");
        }
        for (int cx = (x0 - margin) >> 4; cx <= (x0 + rq.size() + margin) >> 4; cx++) {
            for (int cz = (z0 - margin) >> 4; cz <= (z0 + rq.size() + margin) >> 4; cz++) loads.add(rq.world().getChunkAtAsync(cx, cz, true));
        }
        Msg.info(s, "Checking the ground for a " + rq.size() + " x " + rq.size() + " square...");
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((v, err) ->
                pl.getServer().getScheduler().runTask(pl, () -> {
                    try {
                        evaluate(s, rq, scene, x0, z0, previewOnly);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        Msg.err(s, e.getMessage());
                    }
                }));
    }

    private void evaluate(CommandSender s, Request rq, Scene scene, int x0, int z0, boolean previewOnly) {
        if (pl.activeJob() != null) throw pl.busy();
        List<PortalSpec> layout = SquarePlanner.layout(scene.nextId(), x0, z0, rq.perSide(), rq.gap(), rq.facing());
        List<PortalSpec> existing = new ArrayList<>(pl.scenes().portalsIn(rq.world().getName()));
        SquarePlanner.Fit fit = SquarePlanner.fit(new BukkitTerrain(rq.world()), layout, rq.perSide(), pl.settings().gen.maxSlope,
                rq.elevation(), existing);
        int total = layout.size();
        String sizeNote = rq.requestedSize() == rq.size() ? "" : " (a side of " + rq.requestedSize() + " fits " + rq.perSide() + " portals; the outer size is " + rq.size() + ")";
        Msg.ok(s, "Square " + rq.size() + " x " + rq.size() + sizeNote + ": " + rq.perSide() + " portals per side, " + total + " in all, "
                + rq.gap() + " blocks between them, facing " + rq.facing().name().toLowerCase(Locale.ROOT)
                + (rq.elevation() != null ? ", floating " + rq.elevation() + " blocks above the ground" : ", on the ground")
                + ". Fits: " + fit.usable().size() + " of " + total + ".");
        if (!fit.problems().isEmpty()) {
            for (String p : fit.problems().stream().limit(4).toList()) Msg.info(s, "  blocked - " + p);
            if (fit.problems().size() > 4) Msg.info(s, "  ... and " + (fit.problems().size() - 4) + " more");
        }
        if (previewOnly) {
            if (s instanceof Player pl2) outline(pl2, fit.usable());
            Msg.info(s, "Nothing was built. Run /pd square create with the same options to build it.");
            return;
        }
        if (!fit.problems().isEmpty() && !rq.partial()) {
            throw new IllegalArgumentException("Not built. Move the center, change the size, or add 'partial' to build only the positions that fit.");
        }
        if (fit.usable().isEmpty()) throw new IllegalArgumentException("Nothing fits here.");
        for (PortalSpec p : fit.usable()) scene.portals().put(p.id(), p);
        List<Integer> ids = fit.usable().stream().map(PortalSpec::id).toList();
        scene.square(new SquareInfo(rq.centerX(), rq.centerZ(), rq.size(), rq.perSide(), rq.gap(), rq.facing(), new ArrayList<>(layout.stream().map(PortalSpec::id).toList())));
        pl.scenes().select(scene);
        pl.saveSettings();
        GenerationJob job = new GenerationJob(pl, rq.world(), scene, pl.settings(), fit.usable(), true, result -> {
            pl.activeJob(null);
            pl.groups().fillAll(scene);
            pl.scenes().save(scene);
            if (result.cancelled()) {
                Msg.info(s, "Stopped. " + result.placed() + " portals were finished.");
            } else {
                Msg.ok(s, "Square built: " + result.placed() + " portals (ids " + ids.get(0) + "-" + ids.get(ids.size() - 1) + ").");
            }
        });
        pl.activeJob(job);
        job.start();
    }

    private void outline(Player player, List<PortalSpec> specs) {
        int stride = Math.max(1, specs.size() / 60);
        BukkitTask[] holder = new BukkitTask[1];
        int[] pulses = {0};
        holder[0] = pl.getServer().getScheduler().runTaskTimer(pl, () -> {
            if (!player.isOnline() || pulses[0]++ > 30) {
                holder[0].cancel();
                return;
            }
            for (int k = 0; k < specs.size(); k += stride) {
                PortalSpec p = specs.get(k);
                for (int i : new int[]{0, PortalSpec.WIDTH - 1}) {
                    for (int j = 0; j < PortalSpec.HEIGHT; j += 2) {
                        player.spawnParticle(Particle.END_ROD, p.cellX(i) + 0.5, p.y() + j + 0.5, p.cellZ(i) + 0.5, 1, 0, 0, 0, 0);
                    }
                }
                player.spawnParticle(Particle.HAPPY_VILLAGER, p.centerX() + p.stepX() * 2.0, p.standY() + 0.2, p.centerZ() + p.stepZ() * 2.0, 1, 0, 0, 0, 0);
            }
        }, 0L, 10L);
        Msg.info(player, "Outline shown for a few seconds: white = frames, green = where players come out.");
    }

    // Facing, remove, info

    private SquareInfo requireSquare() {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.square() == null) throw new IllegalArgumentException("There is no square. /pd square create 60 builds one.");
        return sc.square();
    }

    private void facing(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Msg.info(s, "New squares face " + pl.settings().squareFacing.name().toLowerCase(Locale.ROOT) + ". Use /pd square facing inward|outward.");
            return;
        }
        SquareFacing f = GenerateSquareFacing.parse(a.get(0));
        pl.settings().squareFacing = f;
        pl.saveSettings();
        Scene sc = pl.scenes().current();
        if (sc == null || sc.square() == null || sc.square().facing() == f) {
            Msg.ok(s, "New squares will face " + f.name().toLowerCase(Locale.ROOT) + ".");
            return;
        }
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        BukkitTerrain terrain = new BukkitTerrain(w);
        List<PortalSpec> flipped = new ArrayList<>();
        for (int id : sc.square().ids()) {
            PortalSpec p = sc.portals().get(id);
            if (p == null) continue;
            PortalSpec q = new PortalSpec(p.id(), p.x(), p.y(), p.z(), p.axis(), p.facing().getOppositeFace(), p.floating());
            for (int[] c : q.corridorBlocks()) {
                if (!terrain.isNatural(c[0], c[1], c[2])) {
                    throw new IllegalArgumentException("Portal " + id + " cannot turn around: a building is in the way on its other side. Nothing was changed.");
                }
            }
            flipped.add(q);
        }
        for (PortalSpec q : flipped) sc.portals().put(q.id(), q);
        sc.square(sc.square().withFacing(f));
        GenerationJob job = new GenerationJob(pl, w, sc, pl.settings(), flipped, false, result -> {
            pl.activeJob(null);
            pl.scenes().save(sc);
            Msg.ok(s, "The square now faces " + f.name().toLowerCase(Locale.ROOT) + " (" + result.placed() + " portals turned).");
        });
        pl.activeJob(job);
        job.start();
    }

    private void remove(CommandSender s) {
        SquareInfo sq = requireSquare();
        Scene sc = pl.scenes().current();
        if (pl.activeJob() != null) throw pl.busy();
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        List<Integer> ids = new ArrayList<>(sq.ids());
        List<BlockLog.Rec> mine = new ArrayList<>();
        for (int id : ids) {
            PortalSpec p = sc.portals().get(id);
            if (p != null) mine.addAll(PortalCommands.recordsOf(sc, p, true));
        }
        pl.tnt().stop();
        pl.entrance().resetScene(sc);
        List<BlockLog.Rec> unique = new ArrayList<>(new java.util.LinkedHashSet<>(mine));
        RemovalJob job = new RemovalJob(pl, w, sc, pl.settings().blocksPerTick, unique, result -> {
            pl.activeJob(null);
            if (!result.cancelled()) {
                for (int id : ids) sc.dropPortal(id);
                sc.square(null);
            }
            pl.scenes().save(sc);
            Msg.ok(s, "Square removed: " + result.restored() + " blocks restored"
                    + (result.kept() > 0 ? ", " + result.kept() + " left alone because they were changed since." : "."));
        });
        pl.activeJob(job);
        Msg.ok(s, "Removing the square and restoring the terrain...");
        job.start();
    }

    private void info(CommandSender s) {
        SquareInfo sq = requireSquare();
        Scene sc = pl.scenes().current();
        Msg.head(s, "Square");
        Msg.line(s, "  " + sq.size() + " x " + sq.size() + " around " + sq.centerX() + ", " + sq.centerZ() + ", " + sq.perSide() + " portals per side, "
                + sq.spacing() + " blocks between them, facing " + sq.facing().name().toLowerCase(Locale.ROOT));
        for (int i = 0; i < 4; i++) {
            List<Integer> ids = sq.side(i);
            Msg.line(s, "  " + SquarePlanner.SIDE_NAMES[i] + " side: " + (ids.isEmpty() ? "none" : "portals " + ids.get(0) + "-" + ids.get(ids.size() - 1)));
        }
    }

    private List<String> tab(List<String> a) {
        if (a.size() == 1) return List.of("create", "preview", "facing", "remove", "info");
        String sub = a.get(0).toLowerCase(Locale.ROOT);
        if (sub.equals("facing")) return a.size() == 2 ? List.of("inward", "outward") : List.of();
        if (!sub.equals("create") && !sub.equals("preview")) return List.of();
        if (a.size() == 2) return List.of("40", "60", "100", "8", "12");
        String prev = a.get(a.size() - 2).toLowerCase(Locale.ROOT);
        return switch (prev) {
            case "spacing", "gap" -> List.of("0", "2", "3", "5");
            case "elevation", "elevated", "height" -> List.of("10", "25", "50");
            case "facing" -> List.of("inward", "outward");
            case "center" -> List.of("here");
            default -> List.of("portals-per-side", "spacing", "elevation", "facing", "center", "partial");
        };
    }

    /** Parses inward / outward. */
    private static final class GenerateSquareFacing {
        static SquareFacing parse(String text) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "inward", "in", "inwards" -> SquareFacing.INWARD;
                case "outward", "out", "outwards" -> SquareFacing.OUTWARD;
                default -> throw new IllegalArgumentException("Facing is inward (toward the middle) or outward.");
            };
        }
    }
}
