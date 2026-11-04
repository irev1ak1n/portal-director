package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.group.Group;
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
import java.util.UUID;

/** /pd group ...: a roster of players and the block that adds them. */
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
        Group g = selectedGroup(s);
        if (g == null) {
            throw new IllegalArgumentException(pl.groups().all().isEmpty()
                    ? "There are no groups yet. Create one with /pd group create <name>."
                    : "Which group? Add its name, for example /pd group info " + pl.groups().all().get(0).name() + ".");
        }
        return new Res(g, idx);
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
                    case "add" -> add(s, r);
                    case "block" -> block(s, r);
                    case "create" -> create(s, r);
                    case "disband", "delete" -> disband(s, r);
                    case "info", "members" -> info(s, r);
                    case "list" -> list(s);
                    case "remove", "kick" -> remove(s, r);
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
        Msg.head(s, "Groups");
        Msg.cmd(s, "/pd group add <player>", "put a player in the group");
        Msg.cmd(s, "/pd group block set <block>", "players who step on it join");
        Msg.cmd(s, "/pd group create <name>", "make a group");
        Msg.cmd(s, "/pd group disband <name>", "delete the group");
        Msg.cmd(s, "/pd group info [name]", "who is in it");
        Msg.cmd(s, "/pd group list", "all groups");
        Msg.cmd(s, "/pd group remove <player>", "take a player out");
    }

    private void create(CommandSender s, List<String> a) {
        if (a.isEmpty()) throw new IllegalArgumentException("Give it a name: /pd group create Cast");
        Group g = pl.groups().create(a.get(0));
        selected.put(who(s), g.name());
        Msg.ok(s, "Group " + g.name() + " created. Set the block players step on with /pd group block set emerald_block");
    }

    private void disband(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        pl.groups().delete(g);
        selected.remove(who(s));
        Msg.ok(s, "Group " + g.name() + " disbanded.");
    }

    private void list(CommandSender s) {
        Msg.head(s, "Groups");
        if (pl.groups().all().isEmpty()) Msg.line(s, "  none yet. /pd group create <name>");
        for (Group g : pl.groups().all()) {
            Msg.line(s, "  " + g.name() + ": " + g.members().size() + " players, join block "
                    + (g.joinBlock() == null ? "not set" : g.joinBlock().name().toLowerCase(Locale.ROOT)));
        }
    }

    private void info(CommandSender s, List<String> a) {
        Group g = resolve(s, a, 0).group();
        Msg.head(s, "Group " + g.name());
        Msg.line(s, "  Join block: " + (g.joinBlock() == null ? "not set (/pd group block set <block>)" : g.joinBlock().name().toLowerCase(Locale.ROOT)));
        if (g.members().isEmpty()) Msg.line(s, "  No players yet.");
        int n = 1;
        for (Map.Entry<UUID, String> m : g.members().entrySet()) {
            Msg.line(s, "  " + n++ + ". " + m.getValue() + (Bukkit.getPlayer(m.getKey()) != null ? "" : " (offline)"));
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
        boolean added = pl.groups().join(r.group(), p.getUniqueId(), p.getName() == null ? a.get(r.next()) : p.getName());
        Msg.ok(s, added ? p.getName() + " added to " + r.group().name() + "." : p.getName() + " is already in " + r.group().name() + ".");
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

    // Tab completion

    private List<String> tab(CommandSender s, List<String> a) {
        List<String> groups = pl.groups().all().stream().map(Group::name).toList();
        if (a.size() == 1) return List.of("add", "block", "create", "disband", "info", "list", "remove");
        String sub = a.get(0).toLowerCase(Locale.ROOT);
        String last = a.get(a.size() - 1).toLowerCase(Locale.ROOT);
        if (a.size() == 2) {
            return switch (sub) {
                case "block" -> List.of("clear", "set", "show");
                case "add" -> mixed(groups, onlineNames());
                case "remove", "kick" -> mixed(groups, memberNames(selectedGroup(s)));
                case "disband", "delete", "info", "members" -> groups;
                default -> List.of();
            };
        }
        if (a.size() == 3) {
            Group g = pl.groups().get(a.get(1));
            return switch (sub) {
                case "block" -> a.get(1).equalsIgnoreCase("set") ? blockNames(last) : groups;
                case "add" -> g != null ? onlineNames() : List.of();
                case "remove", "kick" -> g != null ? memberNames(g) : List.of();
                default -> List.of();
            };
        }
        if (a.size() == 4 && sub.equals("block") && a.get(1).equalsIgnoreCase("set")) return groups;
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
