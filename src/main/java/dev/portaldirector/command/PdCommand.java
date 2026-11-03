package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Routes /pd to its subcommands, checks permissions and turns mistakes into friendly messages. */
public final class PdCommand implements CommandExecutor, TabCompleter {

    private record Entry(Sub sub, String permission) {
    }

    private final PortalDirectorPlugin pl;
    private final Map<String, Entry> subs = new LinkedHashMap<>();

    public PdCommand(PortalDirectorPlugin pl) {
        this.pl = pl;
        SceneCommands scene = new SceneCommands(pl);
        GenerateCommands gen = new GenerateCommands(pl, scene);
        GroupCommands group = new GroupCommands(pl);
        TntCommands tnt = new TntCommands(pl);
        PortalCommands portal = new PortalCommands(pl);
        SquareCommands square = new SquareCommands(pl);
        String g = "portaldirector.generate";

        subs.put("generate", new Entry(gen.generate(), g));
        subs.put("set", new Entry(setCommand(gen.set(), gen.centerCmd(), staging()), g));
        subs.put("settings", new Entry(gen.settings(), g));
        subs.put("list", new Entry(scene.portals(), g));
        subs.put("portal", new Entry(portal.portal(), g));
        subs.put("clear", new Entry(scene.clearShortcut(), "portaldirector.scene"));
        subs.put("group", new Entry(group.group(), "portaldirector.group"));
        subs.put("tnt", new Entry(tnt.tnt(), "portaldirector.tnt"));
        subs.put("square", new Entry(square.square(), g));
        subs.put("stop", new Entry(scene.stop(), g));
        subs.put("help", new Entry((s, a) -> help(s, a.isEmpty() ? "" : a.get(0)), null));
        subs.put("reload", new Entry((s, a) -> {
            pl.reload();
            Msg.ok(s, "Config and groups reloaded.");
        }, "portaldirector.admin"));
    }

    /** /pd set is the one place for configuration: generation settings, the center and the staging area. */
    private Sub setCommand(Sub settings, Sub center, Sub staging) {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (!a.isEmpty() && a.get(0).equalsIgnoreCase("center")) {
                    center.run(s, a.subList(1, a.size()));
                } else if (!a.isEmpty() && a.get(0).equalsIgnoreCase("staging")) {
                    staging.run(s, a.subList(1, a.size()));
                } else {
                    settings.run(s, a);
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() > 1 && a.get(0).equalsIgnoreCase("center")) return center.tab(s, a.subList(1, a.size()));
                if (a.size() > 1 && a.get(0).equalsIgnoreCase("staging")) return staging.tab(s, a.subList(1, a.size()));
                List<String> out = new ArrayList<>(settings.tab(s, a));
                if (a.size() == 1) {
                    out.add("center");
                    out.add("staging");
                }
                return out;
            }
        };
    }

    private Sub staging() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "show" : a.get(0).toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "here", "set" -> {
                        if (!(s instanceof Player p)) throw new IllegalArgumentException("Stand where you want the staging area, as a player.");
                        pl.settings().staging = p.getLocation().clone();
                        pl.saveSettings();
                        Msg.ok(s, "Staging area set here. /pd group prepare now holds players here until you release them.");
                    }
                    case "clear", "reset" -> {
                        pl.settings().staging = null;
                        pl.saveSettings();
                        Msg.ok(s, "Staging area cleared: players are held at their own portals.");
                    }
                    default -> {
                        var st = pl.settings().staging;
                        Msg.info(s, st == null ? "No staging area: prepared players wait inside their own portal. /pd set staging here uses your position instead (for example a hidden spot or the Nether)."
                                : "Staging area at " + st.getBlockX() + ", " + st.getBlockY() + ", " + st.getBlockZ() + " in " + st.getWorld().getName() + ".");
                    }
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("here", "clear", "show") : List.of();
            }
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            help(sender, "");
            return true;
        }
        Entry e = subs.get(args[0].toLowerCase(Locale.ROOT));
        if (e == null) {
            Msg.err(sender, "I don't know '/pd " + args[0] + "'. " + suggestion(args[0]) + "/pd help lists everything.");
            return true;
        }
        if (!allowed(sender, e)) {
            Msg.err(sender, "You need the " + e.permission() + " permission for that. Operators have it by default.");
            return true;
        }
        try {
            e.sub().run(sender, List.of(args).subList(1, args.length));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Msg.err(sender, ex.getMessage());
        } catch (RuntimeException ex) {
            pl.getLogger().warning("Command failed: " + ex);
            ex.printStackTrace();
            Msg.err(sender, "Something unexpected went wrong (" + ex.getClass().getSimpleName() + "). Nothing is lost; the details are in the server log.");
        }
        return true;
    }

    private String suggestion(String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        if (List.of("radius", "spacing", "height", "mode", "placement", "layout", "orientation", "facing", "count", "center", "staging").contains(t)) {
            return "Settings are changed with /pd set, for example /pd set " + t + " <value>. ";
        }
        for (String known : subs.keySet()) {
            if (known.startsWith(t.substring(0, Math.min(2, t.length()))) || t.startsWith(known.substring(0, Math.min(3, known.length())))) {
                return "Did you mean /pd " + known + "? ";
            }
        }
        return "";
    }

    private boolean allowed(CommandSender s, Entry e) {
        return e.permission() == null || s.hasPermission(e.permission()) || s.hasPermission("portaldirector.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (Map.Entry<String, Entry> e : subs.entrySet()) {
                if (allowed(sender, e.getValue())) out.add(e.getKey());
            }
        } else {
            Entry e = subs.get(args[0].toLowerCase(Locale.ROOT));
            if (e == null || !allowed(sender, e)) return List.of();
            try {
                out.addAll(e.sub().tab(sender, List.of(args).subList(1, args.length)));
            } catch (RuntimeException ex) {
                return List.of();
            }
        }
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }

    private void help(CommandSender s, String topic) {
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "portals", "generate", "settings" -> {
                Msg.head(s, "Building portals");
                Msg.cmd(s, "/pd generate 30", "build 30 portals around you (add ground, air or mixed)");
                Msg.cmd(s, "/pd generate 30 add", "build 30 more and keep the ones you have");
                Msg.cmd(s, "/pd generate 30 replace", "remove the old ones and build new ones");
                Msg.cmd(s, "/pd list", "the id and coordinates of every portal");
                Msg.cmd(s, "/pd settings", "see every setting and its value");
                Msg.cmd(s, "/pd set radius 50", "how far from you portals may be");
                Msg.cmd(s, "/pd set spacing horizontal 10", "least empty blocks between portals, measured across the ground");
                Msg.cmd(s, "/pd set spacing vertical 5", "least empty blocks between floating portals above each other");
                Msg.cmd(s, "/pd set mode ground|air|mixed", "on the ground, floating, or both");
                Msg.cmd(s, "/pd set layout random|grid|circle|wave", "arrangement");
                Msg.cmd(s, "/pd set height min 10  /pd set height max 40", "floating portals hang this far above the ground");
                Msg.cmd(s, "/pd set orientation x|z|random", "which way the frame runs");
                Msg.cmd(s, "/pd set facing auto|inward|outward|north|...", "which way players and minecarts leave");
                Msg.cmd(s, "/pd set center here|<x> <z> [world]|reset", "where portals are built around");
                Msg.cmd(s, "/pd portal create here north", "one portal exactly where you stand");
                Msg.cmd(s, "/pd portal remove 3", "take one portal down");
                Msg.line(s, "  If you cannot see portals that exist, the server's view distance is probably smaller than the radius.");
            }
            case "players", "group", "groups", "entrance" -> {
                Msg.head(s, "Players walking out of portals");
                Msg.cmd(s, "/pd group create team", "make a group (then /pd group block set emerald_block)");
                Msg.line(s, "  Players who step on that block join by themselves and get the next free portal.");
                Msg.cmd(s, "/pd group info", "who is in the group and which portal is theirs");
                Msg.cmd(s, "/pd group assign team auto", "portal 1 for player 1, portal 2 for player 2, ...");
                Msg.cmd(s, "/pd group prepare team", "move everyone into their portal and hold them there");
                Msg.cmd(s, "/pd group release team", "everyone walks out at the same moment");
                Msg.cmd(s, "/pd group release team staggered 10", "a few players every 10 seconds");
                Msg.cmd(s, "/pd group reset team", "cancel and send everybody back");
                Msg.cmd(s, "/pd set staging here", "hold prepared players at your position instead of in their portals");
                Msg.line(s, "  Square formations can also release clockwise, opposite-sides or side-by-side.");
                Msg.line(s, "  Everyone else can still use the portals normally to travel to the Nether.");
            }
            case "tnt" -> {
                Msg.head(s, "TNT minecarts");
                Msg.cmd(s, "/pd tnt launch all", "one minecart out of every portal");
                Msg.cmd(s, "/pd tnt wave 5 20", "5 waves, 20 seconds apart");
                Msg.cmd(s, "/pd tnt mode safe|real", "safe: nothing explodes; real: they explode");
                Msg.cmd(s, "/pd tnt fuse 3", "explode 3 seconds after launch");
                Msg.cmd(s, "/pd tnt status", "what is on, how many, which mode");
                Msg.cmd(s, "/pd tnt clear", "stop and remove every TNT minecart");
                Msg.line(s, "  /pd tnt shows the full list. Nothing ever launches unless you type a launch command.");
            }
            case "square" -> {
                Msg.head(s, "Square formation");
                Msg.cmd(s, "/pd square preview 60", "see how many portals fit, build nothing");
                Msg.cmd(s, "/pd square create 60", "portals along four sides of a square around you");
                Msg.cmd(s, "/pd square create 8 portals-per-side", "choose the number per side");
                Msg.cmd(s, "/pd square facing inward|outward", "toward the middle or away from it");
                Msg.cmd(s, "/pd square remove", "take it down");
            }
            default -> {
                Msg.head(s, "Portal Director - quick start");
                Msg.line(s, " 1. /pd generate 30         build 30 portals around you (optional first: /pd settings, /pd set radius 50)");
                Msg.line(s, " 2. /pd list                the id and coordinates of every portal");
                Msg.line(s, " 3. players: /pd group create team, /pd group block set emerald_block, then /pd group prepare team and /pd group release team");
                Msg.line(s, "    TNT: /pd tnt launch all   (/pd tnt mode real makes them explode)");
                Msg.line(s, " 4. /pd clear               remove everything and restore the ground, then generate something new");
                Msg.line(s, "More help: /pd help portals | players | tnt | square");
            }
        }
    }
}
