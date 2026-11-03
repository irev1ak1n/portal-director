package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

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
        GenerateCommands gen = new GenerateCommands(pl);
        SceneCommands scene = new SceneCommands(pl);
        GroupCommands group = new GroupCommands(pl);
        TntCommands tnt = new TntCommands(pl);
        PortalCommands portal = new PortalCommands(pl);
        SquareCommands square = new SquareCommands(pl);
        String g = "portaldirector.generate";
        subs.put("generate", new Entry(gen.generate(), g));
        subs.put("preset", new Entry(gen.preset(), g));
        subs.put("count", new Entry(gen.numeric("generate.count", "Portal count", "count 30"), g));
        subs.put("radius", new Entry(gen.numeric("generate.radius", "Radius", "radius 100"), g));
        subs.put("spacing", new Entry(gen.numeric("generate.spacing", "Spacing", "spacing 10"), g));
        subs.put("height", new Entry(gen.height(), g));
        subs.put("placement", new Entry(gen.choice("generate.placement", "Placement"), g));
        subs.put("orientation", new Entry(gen.choice("generate.orientation", "Orientation"), g));
        subs.put("layout", new Entry(gen.choice("generate.layout", "Layout"), g));
        subs.put("facing", new Entry(gen.choice("generate.facing", "Facing"), g));
        subs.put("center", new Entry(gen.centerCmd(), g));
        subs.put("settings", new Entry(gen.settings(), g));
        subs.put("set", new Entry(gen.set(), g));
        subs.put("clear", new Entry(scene.clearShortcut(), "portaldirector.scene"));
        subs.put("stop", new Entry(scene.stop(), g));
        subs.put("scene", new Entry(scene.scene(), "portaldirector.scene"));
        subs.put("list", new Entry(scene.portals(), g));
        subs.put("tp", new Entry(scene.tp(), g));
        subs.put("portal", new Entry(portal.portal(), g));
        subs.put("square", new Entry(square.square(), g));
        subs.put("group", new Entry(group.group(), "portaldirector.group"));
        subs.put("tnt", new Entry(tnt.tnt(), "portaldirector.tnt"));
        subs.put("staging", new Entry(staging(), "portaldirector.group"));
        subs.put("reload", new Entry((s, a) -> {
            pl.reload();
            Msg.ok(s, "Config and groups reloaded.");
        }, "portaldirector.admin"));
        subs.put("help", new Entry((s, a) -> help(s, a.isEmpty() ? "" : a.get(0)), null));
    }

    private Sub staging() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "show" : a.get(0).toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "set" -> {
                        if (!(s instanceof org.bukkit.entity.Player p)) throw new IllegalArgumentException("Stand where you want the staging area, as a player.");
                        pl.settings().staging = p.getLocation().clone();
                        pl.saveSettings();
                        Msg.ok(s, "Staging area set here. /pd group prepare now holds players here until release.");
                    }
                    case "clear", "reset" -> {
                        pl.settings().staging = null;
                        pl.saveSettings();
                        Msg.ok(s, "Staging area cleared: players are held at their own portals.");
                    }
                    default -> {
                        var st = pl.settings().staging;
                        Msg.info(s, st == null ? "No staging area: players are held at their own portals. /pd staging set uses your position (for example a spot in the Nether)."
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
            Msg.err(sender, "Unknown command '" + args[0] + "'. /pd help lists everything.");
            return true;
        }
        if (!allowed(sender, e)) {
            Msg.err(sender, "You need the " + e.permission() + " permission for that.");
            return true;
        }
        try {
            e.sub().run(sender, List.of(args).subList(1, args.length));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Msg.err(sender, ex.getMessage());
        } catch (RuntimeException ex) {
            pl.getLogger().warning("Command failed: " + ex);
            ex.printStackTrace();
            Msg.err(sender, "Something went wrong (" + ex.getClass().getSimpleName() + "). Details are in the server log.");
        }
        return true;
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
            case "generate", "portals" -> {
                Msg.head(s, "Generating portals");
                Msg.cmd(s, "/pd generate [count] [ground|air|mixed] [add]", "build portals around you");
                Msg.cmd(s, "/pd preset <invasion|sky-army|formation|tnt-barrage>", "one-command looks");
                Msg.cmd(s, "/pd radius <n>  /pd spacing <n>  /pd count <n>", "size of the area and gaps");
                Msg.cmd(s, "/pd height <min> <max>", "floating portals, blocks above ground");
                Msg.cmd(s, "/pd orientation <x|z|random>", "which way the frame runs");
                Msg.cmd(s, "/pd facing <auto|inward|outward|random|north|...>", "which way players exit");
                Msg.cmd(s, "/pd layout <random|grid|circle|wave>", "arrangement");
                Msg.cmd(s, "/pd center <here|x z [world]|reset>", "pin the middle of the scene");
                Msg.cmd(s, "/pd settings [all]  /pd set <name> <value>", "see and change everything");
                Msg.cmd(s, "/pd list [page]  /pd tp <id>  /pd stop", "browse, visit, cancel");
                Msg.cmd(s, "/pd portal create <x y z|here> <north|south|east|west>", "one portal at exact coordinates");
                Msg.cmd(s, "/pd portal remove <id>  /pd portal list", "single portals");
                Msg.cmd(s, "/pd square create <size> [portals-per-side] [spacing n] [elevation n]", "portals along the 4 sides of a square");
                Msg.cmd(s, "/pd square preview|facing|remove|info", "plan it, turn it, take it down");
            }
            case "group", "groups", "entrance" -> {
                Msg.head(s, "Groups and entrances");
                Msg.cmd(s, "/pd group create <name>", "then /pd group block set emerald_block");
                Msg.cmd(s, "/pd group assign <name> auto", "portal 1 -> first player ...");
                Msg.cmd(s, "/pd group prepare <name>", "stage players");
                Msg.cmd(s, "/pd group release <name> [staggered <secs> [size]]", "everyone walks out");
                Msg.cmd(s, "/pd group reset <name>", "cancel, send players back");
                Msg.cmd(s, "/pd group", "all group commands");
                Msg.cmd(s, "/pd staging set|clear", "hold players somewhere else, such as the Nether");
            }
            case "tnt" -> {
                Msg.head(s, "TNT minecarts");
                Msg.cmd(s, "/pd tnt launch <all|1,5-9|floating|ground|group name> [per portal] [stagger secs]", "fire");
                Msg.cmd(s, "/pd tnt wave <waves> <secs between> [per portal] [portals]", "timed waves");
                Msg.cmd(s, "/pd tnt preview on|off", "off-by-default safe mode: no explosions");
                Msg.cmd(s, "/pd tnt stop  /pd tnt clear  /pd tnt status", "stop, remove, check");
            }
            case "scene", "scenes" -> {
                Msg.head(s, "Scenes");
                Msg.cmd(s, "/pd scene new|save|load|info|list <name>", "named, saved scenes");
                Msg.cmd(s, "/pd scene clear [name]  /pd clear", "remove portals, restore the terrain");
                Msg.cmd(s, "/pd scene rebuild [name]", "put missing portals back");
                Msg.cmd(s, "/pd scene delete <name>", "forget an empty scene");
            }
            default -> {
                Msg.head(s, "Portal Director");
                Msg.line(s, "Quick start: /pd generate 30 mixed, /pd group create NULL, /pd group block set emerald_block,");
                Msg.line(s, "players step on the block, /pd group assign NULL auto, /pd group prepare NULL, /pd group release NULL");
                Msg.cmd(s, "/pd help generate", "making portals");
                Msg.cmd(s, "/pd help group", "players and entrances");
                Msg.cmd(s, "/pd help tnt", "TNT minecart attacks");
                Msg.cmd(s, "/pd help scene", "saving, loading, removing");
                Msg.cmd(s, "/pd preset list", "ready-made scenes");
            }
        }
    }
}
