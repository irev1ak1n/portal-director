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
        Sub portalSub = portal.portal();

        // The everyday commands
        subs.put("generate", new Entry(gen.generate(), g));
        subs.put("set", new Entry(gen.set(), g));
        subs.put("settings", new Entry(gen.settings(), g));
        subs.put("portal", new Entry(portalSub, g));
        subs.put("view", new Entry(forward(portalSub, "view"), g));
        subs.put("where", new Entry(gen.where(), g));
        subs.put("clear", new Entry(scene.clearShortcut(), "portaldirector.scene"));
        subs.put("scene", new Entry(scene.scene(), "portaldirector.scene"));
        subs.put("group", new Entry(group.group(), "portaldirector.group"));
        subs.put("tnt", new Entry(tnt.tnt(), "portaldirector.tnt"));
        subs.put("preset", new Entry(gen.preset(), g));
        subs.put("square", new Entry(square.square(), g));
        subs.put("goto", new Entry(gen.gotoCmd(), g));
        subs.put("stop", new Entry(scene.stop(), g));
        subs.put("center", new Entry(gen.centerCmd(), g));
        subs.put("staging", new Entry(staging(), "portaldirector.group"));
        subs.put("help", new Entry((s, a) -> help(s, a.isEmpty() ? "" : a.get(0)), null));
        subs.put("reload", new Entry((s, a) -> {
            pl.reload();
            Msg.ok(s, "Config and groups reloaded.");
        }, "portaldirector.admin"));

        // Older commands, still working as shortcuts
        subs.put("tp", new Entry(forward(portalSub, "tp"), g));
        subs.put("list", new Entry(forward(portalSub, "list"), g));
        subs.put("count", new Entry(gen.numeric("generate.count", "Portal count", "count 30"), g));
        subs.put("radius", new Entry(gen.numeric("generate.radius", "Radius", "radius 100"), g));
        subs.put("spacing", new Entry(gen.numeric("generate.spacing", "Spacing", "spacing 10"), g));
        subs.put("height", new Entry(gen.height(), g));
        subs.put("mode", new Entry(gen.choice("generate.placement", "Mode"), g));
        subs.put("placement", new Entry(gen.choice("generate.placement", "Mode"), g));
        subs.put("orientation", new Entry(gen.choice("generate.orientation", "Orientation"), g));
        subs.put("layout", new Entry(gen.choice("generate.layout", "Layout"), g));
        subs.put("facing", new Entry(gen.choice("generate.facing", "Facing"), g));
    }

    private static Sub forward(Sub target, String word) {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                List<String> all = new ArrayList<>();
                all.add(word);
                all.addAll(a);
                target.run(s, all);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                List<String> all = new ArrayList<>();
                all.add(word);
                all.addAll(a);
                return target.tab(s, all);
            }
        };
    }

    private Sub staging() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "show" : a.get(0).toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "set" -> {
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
                        Msg.info(s, st == null ? "No staging area: prepared players wait inside their own portal. /pd staging set uses your position instead (for example a hidden spot or the Nether)."
                                : "Staging area at " + st.getBlockX() + ", " + st.getBlockY() + ", " + st.getBlockZ() + " in " + st.getWorld().getName() + ".");
                    }
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                return a.size() == 1 ? List.of("set", "clear", "show") : List.of();
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
            case "portals", "generate" -> {
                Msg.head(s, "Building portals");
                Msg.cmd(s, "/pd generate 30", "build 30 portals around you (add ground, air or mixed)");
                Msg.cmd(s, "/pd generate 30 add", "build 30 more and keep the ones you have");
                Msg.cmd(s, "/pd generate 30 replace", "remove the old ones and build new ones");
                Msg.cmd(s, "/pd settings", "see every setting and its value");
                Msg.cmd(s, "/pd set radius 50", "how far from you portals may be");
                Msg.cmd(s, "/pd set spacing 5", "empty blocks between portals");
                Msg.cmd(s, "/pd set mode ground|air|mixed", "on the ground, floating, or both");
                Msg.cmd(s, "/pd set layout random|grid|circle|wave", "arrangement");
                Msg.cmd(s, "/pd set height min 10  /pd set height max 40", "floating portals hang this far above the ground");
                Msg.cmd(s, "/pd set orientation x|z|random  /pd set facing ...", "frame direction and exit direction");
                Msg.cmd(s, "/pd portal create here north", "one portal exactly where you stand");
                Msg.cmd(s, "/pd square create 60", "portals along the four sides of a square (/pd help square)");
                Msg.cmd(s, "/pd preset list", "ready-made looks");
            }
            case "find", "finding" -> {
                Msg.head(s, "Finding your portals");
                Msg.cmd(s, "/pd where", "how many portals, and which direction the nearest and farthest are");
                Msg.cmd(s, "/pd view", "float above the middle of the formation (you glide down slowly)");
                Msg.cmd(s, "/pd portal list", "every portal, nearest first");
                Msg.cmd(s, "/pd portal tp 3", "stand safely in front of portal 3 (also: nearest, farthest)");
                Msg.cmd(s, "/pd goto plains", "go to the nearest grassy plains to test in");
                Msg.line(s, "If you cannot see portals that exist, your server's view distance is probably smaller than the radius.");
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
            case "scene", "scenes" -> {
                Msg.head(s, "Scenes");
                Msg.line(s, "  A scene is one set of portals. Commands work on the selected scene.");
                Msg.cmd(s, "/pd scene list", "all scenes, the selected one is marked");
                Msg.cmd(s, "/pd scene new heist", "start an empty scene");
                Msg.cmd(s, "/pd scene select invasion", "switch to another scene");
                Msg.cmd(s, "/pd clear", "remove the selected scene's portals and put the terrain back");
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
                Msg.line(s, " 2. /pd view                see them from above   (/pd portal tp 1 visits a portal, /pd where tells you where they are)");
                Msg.line(s, " 3. players: /pd group create team, /pd group block set emerald_block, then /pd group prepare team and /pd group release team");
                Msg.line(s, "    TNT: /pd tnt launch all   (/pd tnt mode real makes them explode)");
                Msg.line(s, " 4. /pd clear               remove everything and restore the ground, then generate something new");
                Msg.line(s, "More help: /pd help portals | find | players | tnt | scene | square");
            }
        }
    }
}
