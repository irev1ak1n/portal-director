package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Routes /pd to its subcommands, checks permissions and turns mistakes into friendly messages. */
public final class PdCommand implements CommandExecutor, TabCompleter {

    private record Entry(Sub sub, String permission) {
    }

    private final PortalDirectorPlugin pl;
    private final Map<String, Entry> subs = new TreeMap<>();

    public PdCommand(PortalDirectorPlugin pl) {
        this.pl = pl;
        SceneCommands scene = new SceneCommands(pl);
        GenerateCommands gen = new GenerateCommands(pl, scene);
        GroupCommands group = new GroupCommands(pl);
        TntCommands tnt = new TntCommands(pl);
        SequenceCommands sequence = new SequenceCommands(pl);
        PortalCommands portal = new PortalCommands(pl);
        String g = "portaldirector.generate";

        subs.put("create", new Entry(createCommand(gen.generate(), portal.create()), g));
        subs.put("remove", new Entry(portal.remove(), g));
        subs.put("set", new Entry(setCommand(gen.set(), gen.centerCmd()), g));
        subs.put("settings", new Entry(gen.settings(), g));
        subs.put("list", new Entry(scene.portals(), g));
        subs.put("clear", new Entry(scene.clearShortcut(), "portaldirector.scene"));
        subs.put("group", new Entry(group.group(), "portaldirector.group"));
        subs.put("tnt", new Entry(tnt.tnt(), "portaldirector.tnt"));
        subs.put("sequence", new Entry(sequence.sequence(), "portaldirector.sequence"));
        subs.put("stop", new Entry(scene.stop(), g));
        subs.put("help", new Entry(new HelpCommand(), null));
        subs.put("reload", new Entry((s, a) -> {
            pl.reload();
            Msg.ok(s, "Config and groups reloaded.");
        }, "portaldirector.admin"));
    }

    /** /pd create builds the selected layout; /pd create portal <name> ... builds one named portal. */
    private Sub createCommand(Sub layout, Sub portal) {
        return new Sub() {
            private boolean single(List<String> a) {
                return !a.isEmpty() && a.get(0).equalsIgnoreCase("portal");
            }

            @Override
            public void run(CommandSender s, List<String> a) {
                if (single(a)) portal.run(s, a.subList(1, a.size()));
                else layout.run(s, a);
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (single(a)) return a.size() > 1 ? portal.tab(s, a.subList(1, a.size())) : List.of();
                List<String> out = new ArrayList<>(layout.tab(s, a));
                if (a.size() == 1) out.add("portal");
                return out;
            }
        };
    }

    /** Alphabetical, ignoring case: the order Minecraft shows suggestions in. */
    public static final Comparator<String> ALPHABETICAL = (x, y) -> {
        int c = x.compareToIgnoreCase(y);
        return c != 0 ? c : x.compareTo(y);
    };

    /** /pd set is the one place for configuration: generation settings and the center. */
    private Sub setCommand(Sub settings, Sub center) {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                if (!a.isEmpty() && a.get(0).equalsIgnoreCase("center")) {
                    center.run(s, a.subList(1, a.size()));
                } else {
                    settings.run(s, a);
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() > 1 && a.get(0).equalsIgnoreCase("center")) return center.tab(s, a.subList(1, a.size()));
                List<String> out = new ArrayList<>(settings.tab(s, a));
                if (a.size() == 1) out.add("center");
                return out;
            }
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            new HelpCommand().run(sender, List.of());
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
        if (List.of("radius", "spacing", "altitude-min", "altitude-max", "world-y-min", "world-y-max", "mode", "layout", "facing", "count", "center").contains(t)) {
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
        return out.stream().distinct().sorted(ALPHABETICAL).toList();
    }
}
