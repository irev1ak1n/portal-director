package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.group.Cinematic;
import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /pd group: a few members, each linked to one portal; prepare stands them in their portals; visibility and Nether travel
 * decide what the scene looks like. Commands that don't name a group use the only group.
 */
final class GroupCommands {

    private final PortalDirectorPlugin pl;

    GroupCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub group() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (a.isEmpty()) {
                    help(s);
                    return;
                }
                List<String> r = a.subList(1, a.size());
                switch (a.get(0).toLowerCase(Locale.ROOT)) {
                    case "assign-to-group" -> assignToGroup(s, r);
                    case "block" -> block(s, r);
                    case "create" -> create(s, r);
                    case "delete" -> delete(s, r);
                    case "info" -> info(s, r);
                    case "link-to-portal" -> linkToPortal(s, r);
                    case "list" -> list(s);
                    case "nether-travel" -> netherTravel(s, r);
                    case "prepare" -> prepare(s, r);
                    case "remove-from-group" -> removeFromGroup(s, r);
                    case "unlink-from-portal" -> unlinkFromPortal(s, r);
                    case "visibility-in-portal" -> visibility(s, r);
                    case "visibility-in-portal-delay" -> visibilityDelay(s, r);
                    default -> throw new IllegalArgumentException("There is no '/pd group " + a.get(0) + "'. /pd help group explains the commands.");
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return GroupCommands.this.tab(a);
            }
        };
    }

    private void help(CommandSender s) {
        Msg.head(s, "Groups");
        Msg.cmd(s, "/pd group assign-to-group <player> <group>", "add a player");
        Msg.cmd(s, "/pd group create Group1", "make a group");
        Msg.cmd(s, "/pd group link-to-portal <player> <portal>", "give them a portal");
        Msg.cmd(s, "/pd group prepare", "stand everyone in their portal");
        Msg.line(s, "More: /pd help group");
    }

    // Finding things

    /** The named group, or the only one. */
    private Group group(List<String> a, int index, String example) {
        if (index < a.size()) {
            Group g = pl.groups().get(a.get(index));
            if (g == null) {
                throw new IllegalArgumentException("There is no group called " + a.get(index) + "." + (pl.groups().all().isEmpty()
                        ? " Make one with /pd group create Group1." : " Your groups: " + names() + "."));
            }
            return g;
        }
        Group only = pl.groups().only();
        if (only != null) return only;
        if (pl.groups().all().isEmpty()) throw new IllegalArgumentException("There are no groups yet. Make one with /pd group create Group1.");
        throw new IllegalArgumentException("Which group? You have " + names() + ". Add its name, for example " + example + " " + pl.groups().all().get(0).name() + ".");
    }

    private String names() {
        return String.join(", ", pl.groups().all().stream().map(Group::name).toList());
    }

    private record Member(Group group, UUID id, String name) {
    }

    /** A player who is in a group (the named one, if a name is given), by name. */
    private Member member(String player, Group within) {
        for (Group g : pl.groups().all()) {
            if (within != null && g != within) continue;
            for (Map.Entry<UUID, String> m : g.members().entrySet()) {
                if (m.getValue().equalsIgnoreCase(player)) return new Member(g, m.getKey(), m.getValue());
            }
        }
        if (within != null) throw new IllegalArgumentException(player + " is not in " + within.name() + ". Add them with /pd group assign-to-group " + player + " " + within.name() + ".");
        throw new IllegalArgumentException(player + " is not in a group yet. Add them with /pd group assign-to-group " + player + " <group>.");
    }

    private OfflinePlayer player(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null) throw new IllegalArgumentException("I don't know a player called " + name + ". They need to have joined this server once.");
        return off;
    }

    private static String need(List<String> a, int index, String usage) {
        if (index >= a.size()) throw new IllegalArgumentException("Use " + usage);
        return a.get(index);
    }

    // Groups

    private void create(CommandSender s, List<String> a) {
        Group g = pl.groups().create(need(a, 0, "/pd group create Group1"));
        Msg.ok(s, "Group " + g.name() + " created. Add players with /pd group assign-to-group <player> " + g.name() + ".");
    }

    private void delete(CommandSender s, List<String> a) {
        Group g = group(List.of(need(a, 0, "/pd group delete <group>")), 0, "/pd group delete");
        pl.cinematic().forgetGroup(g);
        pl.groups().delete(g);
        Msg.ok(s, "Group " + g.name() + " deleted with its players' links. No portals were removed.");
    }

    private void block(CommandSender s, List<String> a) {
        String name = need(a, 0, "/pd group block <block>, for example /pd group block obsidian");
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isBlock() || !m.isSolid()) throw new IllegalArgumentException(name + " is not a solid block players can stand on.");
        Group g = group(a, 1, "/pd group block " + name);
        pl.groups().setJoinBlock(g, m);
        Msg.ok(s, "Players who step on " + m.name().toLowerCase(Locale.ROOT) + " now join " + g.name() + ".");
    }

    private void list(CommandSender s) {
        Msg.head(s, "Groups");
        if (pl.groups().all().isEmpty()) {
            Msg.line(s, "  none yet. /pd group create Group1");
            return;
        }
        for (Group g : pl.groups().all()) {
            Msg.line(s, "  " + g.name() + ": " + (g.members().isEmpty() ? "(no players)" : String.join(", ", g.members().values())));
        }
    }

    private void info(CommandSender s, List<String> a) {
        List<Group> shown = a.isEmpty() ? pl.groups().all() : List.of(group(a, 0, "/pd group info"));
        if (shown.isEmpty()) throw new IllegalArgumentException("There are no groups yet. Make one with /pd group create Group1.");
        Scene sc = pl.scenes().current();
        for (Group g : shown) {
            int n = g.members().size();
            Msg.line(s, g.name() + " — " + n + (n == 1 ? " player" : " players"));
            for (Map.Entry<UUID, String> m : g.members().entrySet()) {
                String portal = g.portals().get(m.getKey());
                String where = portal == null ? "Not assigned" : sc != null && sc.byName(portal) != null ? sc.nameOf(sc.byName(portal).id()) : portal + " (missing)";
                Msg.line(s, "  " + m.getValue() + (Bukkit.getPlayer(m.getKey()) == null ? " (offline)" : "") + " → " + where);
            }
            Msg.line(s, "  Nether travel " + (g.netherTravel() ? "on" : "off") + ", visibility in portal " + (g.visibilityInPortal() ? "on" : "off")
                    + ", delay " + trim(g.visibilityDelay()) + " s");
        }
    }

    // Members and portals

    private void assignToGroup(CommandSender s, List<String> a) {
        String name = need(a, 0, "/pd group assign-to-group <player> <group>");
        Group g = group(a, 1, "/pd group assign-to-group " + name);
        OfflinePlayer p = player(name);
        Group other = pl.groups().groupOf(p.getUniqueId());
        if (other != null && other != g) {
            throw new IllegalArgumentException(p.getName() + " is already in " + other.name() + ". Take them out first: /pd group remove-from-group " + p.getName() + " " + other.name() + ".");
        }
        boolean added = pl.groups().join(g, p.getUniqueId(), p.getName() == null ? name : p.getName());
        Msg.ok(s, added ? p.getName() + " added to " + g.name() + ". Give them a portal: /pd group link-to-portal " + p.getName() + " <portal>."
                : p.getName() + " is already in " + g.name() + ".");
    }

    private void removeFromGroup(CommandSender s, List<String> a) {
        String name = need(a, 0, "/pd group remove-from-group <player> <group>");
        Group g = group(a, 1, "/pd group remove-from-group " + name);
        Member m = member(name, g);
        pl.cinematic().forget(m.id());
        pl.groups().remove(g, m.id());
        Msg.ok(s, m.name() + " removed from " + g.name() + ", and their portal link cleared.");
    }

    /** A portal given by name, or by the anchor coordinates x y z. Returns the portal and how many arguments it used. */
    private record PortalRef(PortalSpec spec, int used) {
    }

    private PortalRef portal(List<String> a, int index, String usage) {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) throw new IllegalArgumentException("There are no portals yet. /pd create builds some.");
        String first = need(a, index, usage);
        if (index + 2 < a.size() && first.matches("-?\\d+") && a.get(index + 1).matches("-?\\d+") && a.get(index + 2).matches("-?\\d+")) {
            int x = Integer.parseInt(first);
            int y = Integer.parseInt(a.get(index + 1));
            int z = Integer.parseInt(a.get(index + 2));
            PortalSpec p = sc.atAnchor(x, y, z);
            if (p == null) throw new IllegalArgumentException("No portal has its anchor at " + x + " " + y + " " + z + ". /pd list shows the anchor of every portal.");
            return new PortalRef(p, 3);
        }
        PortalSpec p = sc.byName(first);
        if (p == null) throw new IllegalArgumentException("There is no portal called " + first + ". /pd list shows the names.");
        return new PortalRef(p, 1);
    }

    private void linkToPortal(CommandSender s, List<String> a) {
        String usage = "/pd group link-to-portal <player> <portal>, for example /pd group link-to-portal Steve portal-1";
        Member m = member(need(a, 0, usage), null);
        PortalRef ref = portal(a, 1, usage);
        Scene sc = pl.scenes().current();
        String portalName = sc.nameOf(ref.spec().id());
        String before = pl.groups().link(m.group(), m.id(), portalName);
        pl.cinematic().forget(m.id());
        Msg.ok(s, m.name() + " is now linked to " + portalName + (before != null && !before.equalsIgnoreCase(portalName) ? " (was " + before + ")" : "") + ".");
    }

    private void unlinkFromPortal(CommandSender s, List<String> a) {
        String usage = "/pd group unlink-from-portal <player> <portal>";
        Member m = member(need(a, 0, usage), null);
        PortalRef ref = portal(a, 1, usage);
        String portalName = pl.scenes().current().nameOf(ref.spec().id());
        String has = m.group().portals().get(m.id());
        if (has == null || !has.equalsIgnoreCase(portalName)) {
            throw new IllegalArgumentException(m.name() + " is not linked to " + portalName + "." + (has == null ? " They have no portal." : " Their portal is " + has + "."));
        }
        pl.groups().unlink(m.group(), m.id());
        pl.cinematic().forget(m.id());
        Msg.ok(s, m.name() + " is no longer linked to " + portalName + ".");
    }

    // Cinematic

    private void prepare(CommandSender s, List<String> a) {
        Group g = group(a, 0, "/pd group prepare");
        Cinematic.Report r = pl.cinematic().prepare(g);
        if (r.placed().isEmpty()) {
            Msg.err(s, "Nobody was placed in " + g.name() + "." + (g.members().isEmpty() ? " The group has no players: /pd group assign-to-group <player> " + g.name() + "." : ""));
        } else {
            Msg.ok(s, r.placed().size() + (r.placed().size() == 1 ? " player is" : " players are") + " standing in their portals: " + String.join(", ", r.placed())
                    + (g.visibilityInPortal() ? "." : ", hidden until /pd group visibility-in-portal true."));
        }
        if (!r.unassigned().isEmpty()) Msg.info(s, "Skipped, no portal yet: " + String.join(", ", r.unassigned()) + ". Use /pd group link-to-portal <player> <portal>.");
        if (!r.offline().isEmpty()) Msg.info(s, "Skipped, not online: " + String.join(", ", r.offline()) + ".");
        if (!r.unavailable().isEmpty()) Msg.info(s, "Skipped, portal or world not available: " + String.join(", ", r.unavailable()) + ".");
    }

    private static Boolean flag(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> true;
            case "false", "off", "no" -> false;
            default -> null;
        };
    }

    private void netherTravel(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Group g = group(a, 0, "/pd group nether-travel true");
            Msg.info(s, "Nether travel is " + (g.netherTravel() ? "on" : "off") + " for " + g.name() + ". Change it with /pd group nether-travel true|false.");
            return;
        }
        Boolean on = flag(a.get(0));
        if (on == null) throw new IllegalArgumentException("Use /pd group nether-travel true or /pd group nether-travel false.");
        Group g = group(a, 1, "/pd group nether-travel " + a.get(0));
        g.netherTravel(on);
        pl.groups().save();
        if (on) pl.cinematic().reenterPortals(g);
        Msg.ok(s, on ? g.name() + " members can now travel to the Nether through their portals."
                : g.name() + " members stay inside their own portals instead of traveling.");
    }

    private void visibility(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Group g = group(a, 0, "/pd group visibility-in-portal true");
            Msg.info(s, "Visibility in portal is " + (g.visibilityInPortal() ? "on" : "off") + " for " + g.name() + ". Change it with /pd group visibility-in-portal true|false.");
            return;
        }
        Boolean on = flag(a.get(0));
        if (on == null) throw new IllegalArgumentException("Use /pd group visibility-in-portal true or /pd group visibility-in-portal false.");
        Group g = group(a, 1, "/pd group visibility-in-portal " + a.get(0));
        pl.cinematic().setVisibility(g, on);
        pl.groups().save();
        Msg.ok(s, on ? "Prepared players of " + g.name() + " appear in small groups, " + trim(g.visibilityDelay()) + " seconds apart."
                : "Prepared players of " + g.name() + " are now hidden from everyone else. /pd group visibility-in-portal true brings them back.");
    }

    private void visibilityDelay(CommandSender s, List<String> a) {
        String usage = "/pd group visibility-in-portal-delay <seconds>, for example /pd group visibility-in-portal-delay 0.35";
        double seconds;
        try {
            seconds = Double.parseDouble(need(a, 0, usage));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + a.get(0) + "' is not a number of seconds. Use " + usage);
        }
        if (seconds < 0.05 || seconds > 10) throw new IllegalArgumentException("The delay must be between 0.05 and 10 seconds.");
        Group g = group(a, 1, "/pd group visibility-in-portal-delay " + a.get(0));
        g.visibilityDelay(seconds);
        pl.groups().save();
        Msg.ok(s, "Players of " + g.name() + " will appear in small groups " + trim(seconds) + " seconds apart.");
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // Tab completion

    private List<String> tab(List<String> a) {
        if (a.size() == 1) {
            return List.of("assign-to-group", "block", "create", "delete", "info", "link-to-portal", "list", "nether-travel", "prepare",
                    "remove-from-group", "unlink-from-portal", "visibility-in-portal", "visibility-in-portal-delay");
        }
        String sub = a.get(0).toLowerCase(Locale.ROOT);
        List<String> groups = pl.groups().all().stream().map(Group::name).toList();
        int n = a.size();
        Scene sc = pl.scenes().current();
        List<String> portals = sc == null ? List.of() : sc.portals().keySet().stream().limit(80).map(sc::nameOf).toList();
        List<String> members = pl.groups().all().stream().flatMap(g -> g.members().values().stream()).toList();
        switch (sub) {
            case "assign-to-group":
                if (n == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                return n == 3 ? groups : List.of();
            case "remove-from-group":
                if (n == 2) return members;
                return n == 3 ? groups : List.of();
            case "link-to-portal":
                if (n == 2) return members;
                return n == 3 ? portals : List.of();
            case "unlink-from-portal":
                if (n == 2) {
                    return pl.groups().all().stream().flatMap(g -> g.portals().keySet().stream().map(u -> g.members().get(u))).toList();
                }
                if (n == 3) {
                    Group g = pl.groups().all().stream().filter(x -> x.members().containsValue(a.get(1))).findFirst().orElse(null);
                    if (g == null) return List.of();
                    return g.members().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(a.get(1))).map(e -> g.portals().get(e.getKey()))
                            .filter(x -> x != null).toList();
                }
                return List.of();
            case "create":
                if (n != 2) return List.of();
                for (int i = 1; i < 100; i++) {
                    if (pl.groups().get("Group" + i) == null) return List.of("Group" + i);
                }
                return List.of();
            case "delete", "info", "prepare":
                return n == 2 ? groups : List.of();
            case "block":
                if (n == 2) return blockNames(a.get(1).toLowerCase(Locale.ROOT));
                return n == 3 ? groups : List.of();
            case "nether-travel", "visibility-in-portal":
                if (n == 2) return List.of("true", "false");
                return n == 3 ? groups : List.of();
            case "visibility-in-portal-delay":
                if (n == 2) return List.of("0.2", "0.35", "0.5", "1");
                return n == 3 ? groups : List.of();
            default:
                return List.of();
        }
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
