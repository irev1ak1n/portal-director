package dev.portaldirector.command;

import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.PortalDirectorPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Reads portal selections: all, floating, ground, group NAME, or ids such as 1,4,10-15. */
final class TargetParser {

    record Result(List<PortalSpec> portals, int used) {
    }

    private TargetParser() {
    }

    static Result parse(PortalDirectorPlugin pl, Scene scene, List<String> args, int from) {
        if (from >= args.size()) throw new IllegalArgumentException("Say which portals: all, floating, ground, square, side <north|east|south|west>, group <name> or ids like 1,4,10-15.");
        String t = args.get(from).toLowerCase(Locale.ROOT);
        List<PortalSpec> all = new ArrayList<>(scene.portals().values());
        switch (t) {
            case "all":
                return new Result(all, 1);
            case "floating":
                return new Result(all.stream().filter(PortalSpec::floating).toList(), 1);
            case "ground":
                return new Result(all.stream().filter(p -> !p.floating()).toList(), 1);
            case "square":
                return new Result(squarePortals(scene, null), 1);
            case "side": {
                if (from + 1 >= args.size()) throw new IllegalArgumentException("Which side? north, east, south or west.");
                int side = java.util.Arrays.asList(dev.portaldirector.gen.SquarePlanner.SIDE_NAMES).indexOf(args.get(from + 1).toLowerCase(Locale.ROOT));
                if (side < 0) throw new IllegalArgumentException("A side is north, east, south or west.");
                return new Result(squarePortals(scene, side), 2);
            }
            case "group": {
                if (from + 1 >= args.size()) throw new IllegalArgumentException("Which group? /pd tnt launch group <name>");
                Group g = pl.groups().get(args.get(from + 1));
                if (g == null) throw new IllegalArgumentException("No group called " + args.get(from + 1) + ".");
                TreeMap<Integer, UUID> map = scene.assignmentsOf(g.name());
                return new Result(map.keySet().stream().map(id -> scene.portals().get(id)).filter(p -> p != null).toList(), 2);
            }
            default:
                return new Result(ids(scene, t), 1);
        }
    }

    private static List<PortalSpec> squarePortals(Scene scene, Integer side) {
        if (scene.square() == null) throw new IllegalArgumentException("Scene " + scene.name() + " has no square. /pd square create 60 builds one.");
        List<Integer> ids = side == null ? scene.square().ids() : scene.square().side(side);
        return ids.stream().map(id -> scene.portals().get(id)).filter(p -> p != null).toList();
    }

    private static List<PortalSpec> ids(Scene scene, String text) {
        Set<Integer> ids = new LinkedHashSet<>();
        try {
            for (String part : text.split(",")) {
                if (part.contains("-")) {
                    String[] r = part.split("-");
                    int lo = Integer.parseInt(r[0]);
                    int hi = Integer.parseInt(r[1]);
                    for (int i = Math.min(lo, hi); i <= Math.max(lo, hi); i++) ids.add(i);
                } else {
                    ids.add(Integer.parseInt(part));
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("'" + text + "' is not a portal selection. Use all, floating, ground, group <name> or ids like 1,4,10-15.");
        }
        List<PortalSpec> out = new ArrayList<>();
        for (int id : ids) {
            PortalSpec p = scene.portals().get(id);
            if (p != null) out.add(p);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("None of those portal ids exist in scene " + scene.name() + ".");
        return out;
    }
}
