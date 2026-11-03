package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.tnt.TntDirector;
import dev.portaldirector.util.Durations;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/** /pd tnt ...: nothing here runs unless an admin types it. */
final class TntCommands {

    private final PortalDirectorPlugin pl;

    TntCommands(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    Sub tnt() {
        return new Sub() {
            @Override
            public void run(CommandSender s, List<String> a) {
                String sub = a.isEmpty() ? "status" : a.get(0).toLowerCase(Locale.ROOT);
                List<String> r = a.isEmpty() ? List.of() : a.subList(1, a.size());
                switch (sub) {
                    case "launch" -> launch(s, r);
                    case "wave", "waves" -> wave(s, r);
                    case "stop" -> {
                        int n = pl.tnt().stop();
                        Msg.ok(s, n == 0 ? "No TNT sequence was running." : "Stopped " + n + " running TNT sequences. Minecarts already out keep going; /pd tnt clear removes them.");
                    }
                    case "clear" -> Msg.ok(s, "Removed " + pl.tnt().clear() + " TNT minecarts.");
                    case "preview" -> preview(s, r);
                    case "status" -> status(s);
                    default -> throw new IllegalArgumentException("TNT commands: launch, wave, stop, clear, preview, status.");
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) return List.of("launch", "wave", "stop", "clear", "preview", "status");
                String sub = a.get(0).toLowerCase(Locale.ROOT);
                if (a.size() == 2 && sub.equals("preview")) return List.of("on", "off");
                if (sub.equals("launch") && a.size() == 2) return List.of("all", "floating", "ground", "square", "side", "group", "1-10");
                if (sub.equals("launch") && a.size() == 3 && a.get(1).equalsIgnoreCase("group")) {
                    return pl.groups().all().stream().map(g -> g.name()).toList();
                }
                if (sub.equals("launch")) return List.of("1", "2", "3", "stagger");
                if (sub.equals("wave") && a.size() == 2) return List.of("3", "5", "10");
                if (sub.equals("wave") && a.size() == 3) return List.of("5", "10", "20");
                if (sub.equals("wave") && a.size() == 4) return List.of("1", "2", "3");
                if (sub.equals("wave") && a.size() == 5) return List.of("all", "floating", "ground", "group");
                return List.of();
            }
        };
    }

    private void launch(CommandSender s, List<String> a) {
        Scene sc = scene();
        TargetParser.Result t = TargetParser.parse(pl, sc, a, 0);
        int per = pl.settings().tntPerPortal;
        int stagger = pl.settings().tntStaggerTicks;
        int i = t.used();
        if (i < a.size() && a.get(i).matches("\\d+")) {
            per = Integer.parseInt(a.get(i));
            i++;
        }
        if (i < a.size() && a.get(i).equalsIgnoreCase("stagger")) {
            if (i + 1 >= a.size()) throw new IllegalArgumentException("How long between portals? /pd tnt launch all stagger 0.5");
            stagger = Durations.parseTicks(a.get(i + 1));
        }
        start(s, sc, t, per, pl.settings().tntWaves, pl.settings().tntWaveDelayTicks, stagger);
    }

    private void wave(CommandSender s, List<String> a) {
        if (a.size() < 2) throw new IllegalArgumentException("Use /pd tnt wave <waves> <seconds between waves> [per portal] [portals]. Example: /pd tnt wave 5 20");
        int waves;
        try {
            waves = Integer.parseInt(a.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + a.get(0) + "' is not a number of waves.");
        }
        if (waves < 1 || waves > 100) throw new IllegalArgumentException("Waves must be between 1 and 100.");
        int delay = Math.max(1, Durations.parseTicks(a.get(1)));
        int per = pl.settings().tntPerPortal;
        int i = 2;
        if (i < a.size() && a.get(i).matches("\\d+")) {
            per = Integer.parseInt(a.get(i));
            i++;
        }
        Scene sc = scene();
        TargetParser.Result t = i < a.size() ? TargetParser.parse(pl, sc, a, i)
                : new TargetParser.Result(List.copyOf(sc.portals().values()), 0);
        start(s, sc, t, per, waves, delay, pl.settings().tntStaggerTicks);
    }

    private Scene scene() {
        Scene sc = pl.scenes().current();
        if (sc == null || sc.portals().isEmpty()) {
            throw new IllegalArgumentException("There are no portals yet. Generate some first: /pd generate 30 mixed");
        }
        return sc;
    }

    private void start(CommandSender s, Scene sc, TargetParser.Result t, int per, int waves, int delay, int stagger) {
        if (t.portals().isEmpty()) throw new IllegalArgumentException("That selection has no portals.");
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("World " + sc.worldName() + " is not loaded.");
        if (per < 1 || per > 50) throw new IllegalArgumentException("Minecarts per portal must be between 1 and 50.");
        TntDirector.Summary r = pl.tnt().launch(w, t.portals(), per, waves, delay, stagger);
        Msg.ok(s, "Launching " + r.minecarts() + " TNT minecarts from " + t.portals().size() + " portals"
                + (waves > 1 ? " in " + waves + " waves, " + Durations.format(delay) + " apart" : "")
                + (stagger > 0 ? ", " + Durations.format(stagger) + " between portals" : ", all portals at once")
                + ". Done in about " + Durations.format(r.durationTicks()) + ".");
        if (r.preview()) {
            Msg.info(s, "Preview mode is on: nothing will explode. /pd tnt preview off turns real explosions on.");
        } else if (pl.settings().protectTerrain) {
            Msg.info(s, "Terrain protection is on: explosions hurt entities but break no blocks.");
        }
        if (r.minecarts() + pl.tnt().activeCount() > pl.settings().tntMaxActive) {
            Msg.info(s, "More than the limit of " + pl.settings().tntMaxActive + " minecarts at once: extra ones are skipped (/pd set tnt.max-active).");
        }
    }

    private void preview(CommandSender s, List<String> a) {
        Settings st = pl.settings();
        if (a.isEmpty()) {
            st.tntPreview = !st.tntPreview;
        } else {
            st.options().get("tnt.preview").set(a.get(0));
        }
        pl.saveSettings();
        Msg.ok(s, st.tntPreview ? "Preview mode on: TNT minecarts fly but never explode." : "Preview mode off: TNT minecarts explode normally.");
    }

    private void status(CommandSender s) {
        Settings st = pl.settings();
        Msg.head(s, "TNT");
        Msg.line(s, "  Active minecarts: " + pl.tnt().activeCount() + " (limit " + st.tntMaxActive + "), running sequences: " + pl.tnt().runningSequences());
        Msg.line(s, "  Preview: " + (st.tntPreview ? "on" : "off") + ", terrain protection: " + (st.protectTerrain ? "on" : "off"));
        Msg.line(s, "  Defaults: " + st.tntPerPortal + " per portal, " + st.tntWaves + " wave(s), speed " + st.tntSpeed
                + ". Change with /pd set tnt.<name> <value>.");
    }
}
