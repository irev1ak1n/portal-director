package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.entrance.EntranceDirector;
import dev.portaldirector.group.Assigner;
import dev.portaldirector.group.Group;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Durations;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** /pd group ...: membership, join block, assignments and the scripted entrance. */
final class GroupCommands {

    private final PortalDirectorPlugin pl;
    private final Map<String, String> selected = new HashMap<>();

    GroupCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private record Res(Group group, int next) {
    }

    private static String who(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId().toString() : "console";
    }

    /** Uses the group named at {@code idx} if there is one, otherwise the selected or only group. */
    private Res resolve(CommandSender s, List<String> a, int idx) {
        if (idx < a.size()) {
            Group g = pl.groups().get(a.get(idx));
            if (g != null) {
                selected.put(who(s), g.name());
                return new Res(g, idx + 1);
            }
        }
        String sel = selected.get(who(s));
        Group g = sel == null ? null : pl.groups().get(sel);
        if (g == null && pl.groups().all().size() == 1) g = pl.groups().all().get(0);
        if (g == null) {
            throw new IllegalArgumentException(pl.groups().all().isEmpty()
                    ? "There are no groups yet. Create one with /pd group create <name>."
                    : "Which group? Add its name, for example /pd group release " + pl.groups().all().get(0).name() + ".");
        }
        return new Res(g, idx);
    }

    private Scene scene() {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) {
            throw new IllegalArgumentException("There are no portals yet. Generate some first: /pd generate 30 mixed");
        }
        return sc;
    }

    Sub group() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty()) {
                    help(s);
                    return;
                }
                String sub = a.get(0).toLowerCase(Locale.ROOT);
                List<String> r = a.subList(1, a.size());
                switch (sub) {
                    case "create" -> create(s, r);
                    case "delete", "disband" -> disband(s, r);
                    case "list" -> list(s);
                    case "info", "members" -> info(s, r);
                    case "add" -> add(s, r);
                    case "remove", "kick" -> remove(s, r);
                    case "block" -> block(s, r);
                    case "assign" -> assign(s, r);
                    case "assignments", "slots" -> assignments(s, r);
                    case "prepare" -> prepare(s, r);
                    case "release" -> release(s, r);
                    case "reset", "cancel" -> reset(s, r);
                    case "status" -> status(s, r);
                    default -> help(s);
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return GroupCommands.this.tab(s, a);
            }
        };
    }

    private void help(CommandSender s) {
        Msg.head(s, "Groups and entrances");
        Msg.cmd(s, "/pd group create <name>", "make a group");
        Msg.cmd(s, "/pd group block set <block>", "players who step on it join");
        Msg.cmd(s, "/pd group info [name]", "members and portals");
        Msg.cmd(s, "/pd group add|remove <player>", "manual membership");
        Msg.cmd(s, "/pd group assign <name> auto", "portal 1 -> player 1, ...");
        Msg.cmd(s, "/pd group assign <name> <portal> <player>", "manual slot");
        Msg.cmd(s, "/pd group assignments", "who is at which portal");
        Msg.cmd(s, "/pd group prepare <name>", "stage everyone at their portal");
        Msg.cmd(s, "/pd group release <name> [staggered <secs> [size]]", "everyone walks out");
        Msg.cmd(s, "/pd group reset <name>", "cancel and send players back");
        Msg.cmd(s, "/pd group disband <name>", "delete the group");
    }

    private void create(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Give it a name: /pd group create NULL");
        Group g = pl.groups().create(a.get(0));
        selected.put(who(s), g.name());
        Msg.ok(s, "Group " + g.name() + " created. Set the block players step on with /pd group block set emerald_block");
    }

    private void disband(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        pl.entrance().reset(g, true);
        pl.groups().delete(g);
        selected.remove(who(s));
        Msg.ok(s, "Group " + g.name() + " disbanded.");
    }

    private void list(CommandSender s) {
        Msg.head(s, "Groups");
        if (pl.groups().all().isEmpty()) Msg.line(s, "  none yet. /pd group create <name>");
        for (Group g : pl.groups().all()) {
            Msg.line(s, "  " + g.name() + ": " + g.members().size() + " players, join block "
                    + (g.joinBlock() == null ? "not set" : g.joinBlock().name().toLowerCase(Locale.ROOT)) + ", " + pl.entrance().describe(g));
        }
    }

    private void info(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        Scene sc = pl.scenes().current();
        TreeMap<Integer, UUID> map = sc == null ? new TreeMap<>() : sc.assignmentsOf(g.name());
        Msg.head(s, "Group " + g.name());
        Msg.line(s, "  Join block: " + (g.joinBlock() == null ? "not set (/pd group block set <block>)" : g.joinBlock().name().toLowerCase(Locale.ROOT)));
        Msg.line(s, "  State: " + pl.entrance().describe(g));
        if (g.members().isEmpty()) Msg.line(s, "  No players yet.");
        int n = 1;
        for (Map.Entry<UUID, String> m : g.members().entrySet()) {
            int portal = map.entrySet().stream().filter(e -> e.getValue().equals(m.getKey())).map(Map.Entry::getKey).findFirst().orElse(0);
            boolean online = Bukkit.getPlayer(m.getKey()) != null;
            Msg.line(s, "  " + n++ + ". " + m.getValue() + (online ? "" : " (offline)") + (portal > 0 ? " -> portal " + portal : " -> no portal"));
        }
    }

    private OfflinePlayer player(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null) throw new IllegalArgumentException("I don't know a player called " + name + " (they need to have joined once).");
        return off;
    }

    private void add(CommandSender s, List<String> a) {
        Res r = resolve(s, a, 0);
        if (r.next() >= a.size()) throw new IllegalArgumentException("Which player? /pd group add " + r.group().name() + " <player>");
        OfflinePlayer p = player(a.get(r.next()));
        Group other = pl.groups().groupOf(p.getUniqueId());
        if (other != null && other != r.group()) {
            throw new IllegalArgumentException(p.getName() + " is already in group " + other.name() + ". Remove them there first.");
        }
        int slot = pl.groups().join(r.group(), p.getUniqueId(), p.getName() == null ? a.get(r.next()) : p.getName());
        Msg.ok(s, slot == -1 ? p.getName() + " is already in " + r.group().name() + "."
                : p.getName() + " added to " + r.group().name() + (slot > 0 ? " (portal " + slot + ")." : "."));
    }

    private void remove(CommandSender s, List<String> a) {
        Res r = resolve(s, a, 0);
        if (r.next() >= a.size()) throw new IllegalArgumentException("Which player? /pd group remove " + r.group().name() + " <player>");
        String name = a.get(r.next());
        UUID id = r.group().members().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (id == null) throw new IllegalArgumentException(name + " is not in group " + r.group().name() + ".");
        pl.groups().remove(r.group(), id);
        Msg.ok(s, name + " removed from " + r.group().name() + ".");
    }

    private void block(CommandSender s, List<String> a) {
        String sub = a.isEmpty() ? "show" : a.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (a.size() < 2) throw new IllegalArgumentException("Which block? /pd group block set emerald_block");
                Material m = Material.matchMaterial(a.get(1));
                if (m == null || !m.isBlock() || !m.isSolid()) {
                    throw new IllegalArgumentException(a.get(1) + " is not a solid block players can stand on.");
                }
                Group g = resolve(s, a, 2).group();
                pl.groups().setJoinBlock(g, m);
                Msg.ok(s, "Players who step on " + m.name().toLowerCase(Locale.ROOT) + " now join " + g.name() + ".");
            }
            case "clear", "off" -> {
                Group g = resolve(s, a, 1).group();
                pl.groups().setJoinBlock(g, null);
                Msg.ok(s, "Group " + g.name() + " no longer has a join block.");
            }
            default -> {
                Group g = resolve(s, a, a.isEmpty() ? 0 : 1).group();
                Msg.info(s, g.name() + " join block: " + (g.joinBlock() == null ? "not set" : g.joinBlock().name().toLowerCase(Locale.ROOT)));
            }
        }
    }

    private void assign(CommandSender s, List<String> a) {
        Res r = resolve(s, a, 0);
        Group g = r.group();
        List<String> t = a.subList(r.next(), a.size());
        if (t.isEmpty()) throw new IllegalArgumentException("Use /pd group assign " + g.name() + " auto, or " + g.name() + " <portal> <player>.");
        Scene sc = scene();
        TreeMap<Integer, UUID> map = sc.assignmentsOf(g.name());
        String first = t.get(0).toLowerCase(Locale.ROOT);
        switch (first) {
            case "auto" -> {
                boolean fill = t.size() > 1 && t.get(1).equalsIgnoreCase("fill");
                TreeMap<Integer, UUID> res = pl.groups().autoAssign(g, sc, fill);
                int players = g.members().size();
                Msg.ok(s, res.size() + " players assigned to " + sc.portals().size() + " portals"
                        + (players > res.size() ? "; " + (players - res.size()) + " players have no portal (not enough portals)" : "")
                        + (sc.portals().size() > res.size() ? "; " + (sc.portals().size() - res.size()) + " portals stay empty" : "") + ".");
            }
            case "clear" -> {
                if (t.size() > 1) {
                    map.remove(portalId(t.get(1)));
                } else {
                    map.clear();
                }
                pl.scenes().save(sc);
                Msg.ok(s, "Assignments cleared.");
            }
            case "swap" -> {
                if (t.size() < 3) throw new IllegalArgumentException("Use /pd group assign " + g.name() + " swap <portal> <portal>");
                int x = portalId(t.get(1));
                int y = portalId(t.get(2));
                UUID ux = map.remove(x);
                UUID uy = map.remove(y);
                if (uy != null) map.put(x, uy);
                if (ux != null) map.put(y, ux);
                pl.scenes().save(sc);
                Msg.ok(s, "Swapped portals " + x + " and " + y + ".");
            }
            default -> {
                if (t.size() < 2) throw new IllegalArgumentException("Use /pd group assign " + g.name() + " <portal> <player>");
                int id = portalId(t.get(0));
                if (!sc.portals().containsKey(id)) throw new IllegalArgumentException("There is no portal " + id + " (1-" + sc.portals().lastKey() + ").");
                UUID member = g.members().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(t.get(1)))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (member == null) {
                    throw new IllegalArgumentException(t.get(1) + " is not in " + g.name() + ". Add them with /pd group add " + g.name() + " " + t.get(1));
                }
                UUID previous = Assigner.assign(map, id, member);
                pl.scenes().save(sc);
                Msg.ok(s, t.get(1) + " will come out of portal " + id
                        + (previous != null && !previous.equals(member) ? " (replacing " + g.members().getOrDefault(previous, "someone") + ")." : "."));
            }
        }
    }

    private int portalId(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a portal id.");
        }
    }

    private void assignments(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        Scene sc = scene();
        TreeMap<Integer, UUID> map = sc.assignmentsOf(g.name());
        Msg.head(s, g.name() + " in scene " + sc.name());
        StringBuilder line = new StringBuilder("  ");
        int col = 0;
        for (int id : sc.portals().keySet()) {
            UUID u = map.get(id);
            String name = u == null ? "-" : g.members().getOrDefault(u, "?");
            line.append(id).append(": ").append(name).append("   ");
            if (++col % 4 == 0) {
                Msg.line(s, line.toString());
                line = new StringBuilder("  ");
            }
        }
        if (col % 4 != 0) Msg.line(s, line.toString());
        List<String> loose = new ArrayList<>();
        for (Map.Entry<UUID, String> m : g.members().entrySet()) {
            if (!map.containsValue(m.getKey())) loose.add(m.getValue());
        }
        Msg.line(s, "  Empty portals: " + (sc.portals().size() - map.size()) + (loose.isEmpty() ? "" : "   Without a portal: " + String.join(", ", loose)));
    }

    private void prepare(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        Scene sc = scene();
        if (sc.assignmentsOf(g.name()).isEmpty()) {
            throw new IllegalArgumentException("Nobody has a portal yet. Run /pd group assign " + g.name() + " auto first.");
        }
        EntranceDirector.Report r = pl.entrance().prepare(g, sc);
        Msg.ok(s, r.staged() + " players are in place at their portals" + (pl.settings().staging != null ? " (staging area)" : "") + ". Release with /pd group release " + g.name());
        if (!r.offline().isEmpty()) Msg.info(s, "Not online: " + String.join(", ", r.offline()) + ". They are placed if they join before release.");
        if (r.emptyPortals() > 0) Msg.info(s, r.emptyPortals() + " portals have no player and stay empty.");
    }

    private void release(CommandSender s, List<String> a) {
        Res r = resolve(s, a, 0);
        Group g = r.group();
        Scene sc = scene();
        if (sc.assignmentsOf(g.name()).isEmpty()) {
            throw new IllegalArgumentException("Nobody has a portal yet. Run /pd group assign " + g.name() + " auto first.");
        }
        List<String> t = a.subList(r.next(), a.size());
        int waveSize = 0;
        int delay = 0;
        if (!t.isEmpty()) {
            if (!t.get(0).equalsIgnoreCase("staggered") && !t.get(0).equalsIgnoreCase("waves")) {
                throw new IllegalArgumentException("Use /pd group release " + g.name() + " or /pd group release " + g.name() + " staggered <seconds> [players per wave].");
            }
            delay = t.size() > 1 ? Durations.parseTicks(t.get(1)) : 100;
            waveSize = pl.settings().waveSize;
            if (t.size() > 2) {
                try {
                    waveSize = Integer.parseInt(t.get(2));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + t.get(2) + "' is not a number of players per wave.");
                }
                if (waveSize < 1) throw new IllegalArgumentException("A wave needs at least 1 player.");
            }
        }
        int waves = pl.entrance().release(g, sc, waveSize, delay);
        int assigned = sc.assignmentsOf(g.name()).size();
        if (waveSize <= 0) {
            Msg.ok(s, "Release! " + assigned + " players walk out of their portals now.");
        } else {
            Msg.ok(s, "Releasing " + assigned + " players in " + waves + " waves of " + waveSize + ", one every " + Durations.format(delay) + ".");
        }
    }

    private void reset(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        EntranceDirector.Phase before = pl.entrance().phase(g);
        int back = pl.entrance().reset(g, true);
        Msg.ok(s, before == EntranceDirector.Phase.IDLE ? "Group " + g.name() + " had nothing running."
                : "Group " + g.name() + " reset" + (back > 0 ? ", " + back + " players sent back." : "."));
    }

    private void status(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        Msg.info(s, g.name() + ": " + pl.entrance().describe(g));
    }

    // Tab completion

    private List<String> tab(CommandSender s, List<String> a) {
        List<String> groups = pl.groups().all().stream().map(Group::name).toList();
        if (a.size() == 1) {
            return List.of("create", "disband", "list", "info", "add", "remove", "block", "assign", "assignments", "prepare", "release", "reset", "status");
        }
        String sub = a.get(0).toLowerCase(Locale.ROOT);
        String last = a.get(a.size() - 1).toLowerCase(Locale.ROOT);
        if (a.size() == 2) {
            return switch (sub) {
                case "block" -> List.of("set", "clear", "show");
                case "add", "remove", "kick" -> mixed(groups, sub.equals("add") ? onlineNames() : memberNames(selectedGroup(s)));
                case "assign", "info", "members", "disband", "delete", "assignments", "slots", "prepare", "release", "reset", "cancel", "status" -> groups;
                default -> List.of();
            };
        }
        if (a.size() == 3) {
            Group g = pl.groups().get(a.get(1));
            return switch (sub) {
                case "block" -> a.get(1).equalsIgnoreCase("set") ? blockNames(last) : groups;
                case "add" -> g != null ? onlineNames() : List.of();
                case "remove", "kick" -> g != null ? memberNames(g) : List.of();
                case "assign" -> g != null ? List.of("auto", "clear", "swap") : portalNumbers(selectedGroup(s) != null ? a.get(2) : "");
                case "release" -> g != null ? List.of("staggered") : List.of();
                default -> List.of();
            };
        }
        if (a.size() == 4) {
            Group g = pl.groups().get(a.get(1));
            if (sub.equals("assign") && g != null) {
                return a.get(2).equalsIgnoreCase("auto") ? List.of("fill") : a.get(2).equalsIgnoreCase("clear") ? List.of() : memberNames(g);
            }
            if (sub.equals("assign")) return memberNames(selectedGroup(s));
            if (sub.equals("block") && a.get(1).equalsIgnoreCase("set")) return groups;
            if (sub.equals("release") && g != null) return List.of("5", "10", "20");
        }
        if (a.size() == 5 && sub.equals("assign") && pl.groups().get(a.get(1)) != null) return memberNames(pl.groups().get(a.get(1)));
        return List.of();
    }

    private Group selectedGroup(CommandSender s) {
        String sel = selected.get(who(s));
        Group g = sel == null ? null : pl.groups().get(sel);
        if (g == null && pl.groups().all().size() == 1) g = pl.groups().all().get(0);
        return g;
    }

    private List<String> memberNames(Group g) {
        return g == null ? List.of() : new ArrayList<>(g.members().values());
    }

    private List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private List<String> portalNumbers(String ignored) {
        Scene sc = pl.scenes().current();
        return sc == null ? List.of() : sc.portals().keySet().stream().limit(60).map(String::valueOf).toList();
    }

    private static List<String> mixed(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> blockNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isBlock() && m.isSolid() && m.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(m.name().toLowerCase(Locale.ROOT));
                if (out.size() >= 60) break;
            }
        }
        return out;
    }
}
