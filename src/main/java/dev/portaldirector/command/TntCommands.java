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
                        Msg.ok(s, n == 0 ? "Nothing was being launched." : "Stopped. No more minecarts will be launched from that sequence.");
                        Msg.info(s, "Minecarts that are already out keep going. /pd tnt clear removes them.");
                    }
                    case "clear" -> {
                        int n = pl.tnt().clear();
                        Msg.ok(s, "Stopped all launches and removed " + n + " TNT minecart" + (n == 1 ? "" : "s") + ".");
                    }
                    case "status" -> status(s);
                    case "mode" -> mode(s, r);
                    case "preview" -> preview(s, r);
                    case "fuse" -> fuse(s, r);
                    case "limit" -> limit(s, r);
                    case "terrain-damage", "terrain" -> terrain(s, r);
                    case "portal-protection" -> toggle(s, r, "tnt.protect-portals", "Portals are now protected from explosions.", "Portals can now be destroyed by explosions.");
                    case "speed" -> number(s, r, "tnt.speed", "Launch speed");
                    default -> help(s);
                }
            }

            @Override
            public List<String> tab(CommandSender s, List<String> a) {
                if (a.size() == 1) {
                    return List.of("launch", "wave", "stop", "clear", "status", "mode", "fuse", "limit", "terrain-damage", "portal-protection", "speed");
                }
                String sub = a.get(0).toLowerCase(Locale.ROOT);
                if (a.size() == 2) {
                    return switch (sub) {
                        case "mode" -> List.of("safe", "real");
                        case "fuse" -> List.of("off", "1", "2", "3", "5");
                        case "limit" -> List.of("50", "100", "300", "600");
                        case "terrain-damage", "terrain", "portal-protection", "preview" -> List.of("on", "off");
                        case "speed" -> List.of("0.8", "1.3", "2");
                        case "launch" -> launchTargets();
                        case "wave" -> List.of("3", "5", "10");
                        default -> List.of();
                    };
                }
                if (sub.equals("launch") && a.size() == 3 && a.get(1).equalsIgnoreCase("group")) {
                    return pl.groups().all().stream().map(g -> g.name()).toList();
                }
                if (sub.equals("launch") && a.size() == 3 && a.get(1).equalsIgnoreCase("side")) return List.of("north", "east", "south", "west");
                if (sub.equals("launch")) return List.of("1", "2", "3", "stagger");
                if (sub.equals("wave") && a.size() == 3) return List.of("5", "10", "20");
                if (sub.equals("wave") && a.size() == 4) return List.of("1", "2", "3");
                if (sub.equals("wave") && a.size() == 5) return List.of("all", "floating", "ground", "square", "group");
                return List.of();
            }
        };
    }

    private void help(CommandSender s) {
        Msg.head(s, "TNT minecarts");
        Msg.cmd(s, "/pd tnt launch all", "one minecart out of every portal, all at once");
        Msg.cmd(s, "/pd tnt launch all 3", "three per portal");
        Msg.cmd(s, "/pd tnt launch 1,4,10-15", "only those portals (also: floating, ground, square, side east, group NAME)");
        Msg.cmd(s, "/pd tnt launch all 2 stagger 0.5", "half a second between portals");
        Msg.cmd(s, "/pd tnt wave 5 20", "5 waves, 20 seconds apart");
        Msg.cmd(s, "/pd tnt mode safe|real", "safe: nothing explodes, real: they explode");
        Msg.cmd(s, "/pd tnt fuse 3", "seconds after launch until they explode (off = only when they crash)");
        Msg.cmd(s, "/pd tnt limit 300", "most minecarts alive at once");
        Msg.cmd(s, "/pd tnt terrain-damage on|off", "should explosions break blocks");
        Msg.cmd(s, "/pd tnt stop", "stop launching (minecarts already out keep going)");
        Msg.cmd(s, "/pd tnt clear", "stop and remove every TNT minecart");
        Msg.cmd(s, "/pd tnt status", "see all of these settings");
    }

    private String modeLine() {
        Settings st = pl.settings();
        if (st.tntPreview) {
            return "SAFE MODE is on: minecarts fly but will NOT explode. Turn real explosions on with /pd tnt mode real.";
        }
        String when = st.tntFuseSeconds > 0
                ? "explode " + trim(st.tntFuseSeconds) + " s after launch"
                : "explode only when they crash (no fuse; /pd tnt fuse 3 makes them explode after 3 s)";
        return "REAL EXPLOSIONS are on: minecarts " + when + "; "
                + (st.protectTerrain ? "blocks are protected (/pd tnt terrain-damage on lets them break blocks)." : "blocks WILL be destroyed (/pd tnt terrain-damage off protects them).");
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private List<String> launchTargets() {
        List<String> out = new java.util.ArrayList<>(List.of("all", "floating", "ground", "square", "side", "group"));
        Scene sc = pl.scenes().current();
        if (sc != null) sc.portals().keySet().stream().limit(60).map(sc::nameOf).forEach(out::add);
        return out;
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
            if (i + 1 >= a.size()) throw new IllegalArgumentException("How long between portals? Example: /pd tnt launch all stagger 0.5");
            stagger = Durations.parseTicks(a.get(i + 1));
        }
        start(s, sc, t, per, pl.settings().tntWaves, pl.settings().tntWaveDelayTicks, stagger);
    }

    private void wave(CommandSender s, List<String> a) {
        if (a.size() < 2) throw new IllegalArgumentException("Say how many waves and how many seconds apart, for example /pd tnt wave 5 20 (5 waves, 20 seconds apart).");
        int waves;
        try {
            waves = Integer.parseInt(a.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + a.get(0) + "' is not a number of waves. Example: /pd tnt wave 5 20");
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
            throw new IllegalArgumentException("There are no portals to launch from. Build some first with /pd create 30.");
        }
        return sc;
    }

    private void start(CommandSender s, Scene sc, TargetParser.Result t, int per, int waves, int delay, int stagger) {
        if (t.portals().isEmpty()) throw new IllegalArgumentException("That selection has no portals. /pd list shows what exists.");
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) throw new IllegalArgumentException("The world " + sc.worldName() + " is not loaded.");
        if (per < 1 || per > 50) throw new IllegalArgumentException("Minecarts per portal must be between 1 and 50.");
        TntDirector.Summary r = pl.tnt().launch(w, t.portals(), per, waves, delay, stagger, s);
        Msg.ok(s, "Launching " + r.minecarts() + " TNT minecarts from " + t.portals().size() + " portals"
                + (waves > 1 ? " in " + waves + " waves, " + Durations.format(delay) + " apart" : "")
                + (stagger > 0 ? ", " + Durations.format(stagger) + " between portals" : ", all portals at once") + ".");
        Msg.info(s, modeLine());
        if (r.minecarts() + pl.tnt().activeCount() > pl.settings().tntMaxActive) {
            Msg.info(s, "Heads up: at most " + pl.settings().tntMaxActive + " minecarts can exist at once, so some will be skipped. /pd tnt limit " + (r.minecarts() + pl.tnt().activeCount()) + " raises it.");
        }
    }

    private void mode(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Msg.info(s, modeLine());
            return;
        }
        String m = a.get(0).toLowerCase(Locale.ROOT);
        if (m.equals("safe") || m.equals("preview")) pl.settings().tntPreview = true;
        else if (m.equals("real") || m.equals("explode") || m.equals("live")) pl.settings().tntPreview = false;
        else throw new IllegalArgumentException("Use /pd tnt mode safe (nothing explodes) or /pd tnt mode real (they explode).");
        pl.saveSettings();
        Msg.ok(s, modeLine());
    }

    private void preview(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            pl.settings().tntPreview = !pl.settings().tntPreview;
        } else {
            pl.settings().options().get("tnt.preview").set(a.get(0));
        }
        pl.saveSettings();
        Msg.ok(s, modeLine());
    }

    private void fuse(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Msg.info(s, pl.settings().tntFuseSeconds > 0
                    ? "Minecarts explode " + trim(pl.settings().tntFuseSeconds) + " s after launch. Change it with /pd tnt fuse <seconds>, or /pd tnt fuse off."
                    : "No fuse: minecarts explode only when they crash. /pd tnt fuse 3 makes them explode after 3 seconds.");
            return;
        }
        String v = a.get(0).toLowerCase(Locale.ROOT);
        pl.settings().options().get("tnt.fuse-seconds").set(v.equals("off") || v.equals("none") ? "0" : v);
        pl.saveSettings();
        Msg.ok(s, pl.settings().tntFuseSeconds > 0 ? "Minecarts will explode " + trim(pl.settings().tntFuseSeconds) + " s after launch." : "Fuse off: minecarts explode only when they crash.");
        if (pl.settings().tntPreview) Msg.info(s, "SAFE MODE is on, so nothing explodes yet: /pd tnt mode real");
    }

    private void limit(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Msg.info(s, "At most " + pl.settings().tntMaxActive + " TNT minecarts can be alive at once. Change it with /pd tnt limit <number>.");
            return;
        }
        pl.settings().options().get("tnt.max-active").set(a.get(0));
        pl.saveSettings();
        Msg.ok(s, "At most " + pl.settings().tntMaxActive + " TNT minecarts can be alive at once now.");
    }

    private void terrain(CommandSender s, List<String> a) {
        if (a.isEmpty()) {
            Msg.info(s, "Explosions " + (pl.settings().protectTerrain ? "do NOT break blocks" : "DO break blocks") + ". Change it with /pd tnt terrain-damage on|off.");
            return;
        }
        String v = a.get(0).toLowerCase(Locale.ROOT);
        boolean damage = v.equals("on") || v.equals("true") || v.equals("yes");
        if (!damage && !(v.equals("off") || v.equals("false") || v.equals("no"))) throw new IllegalArgumentException("Use /pd tnt terrain-damage on or off.");
        pl.settings().protectTerrain = !damage;
        pl.saveSettings();
        Msg.ok(s, damage ? "Explosions will now break blocks (portals stay protected unless you turn that off)." : "Explosions no longer break blocks.");
    }

    private void toggle(CommandSender s, List<String> a, String key, String on, String off) {
        if (a.isEmpty()) throw new IllegalArgumentException("Use on or off.");
        Settings.Option o = pl.settings().options().get(key);
        o.set(a.get(0));
        pl.saveSettings();
        Msg.ok(s, Boolean.TRUE.equals(o.get()) ? on : off);
    }

    private void number(CommandSender s, List<String> a, String key, String what) {
        Settings.Option o = pl.settings().options().get(key);
        if (a.isEmpty()) {
            Msg.info(s, what + " is " + o.get() + ".");
            return;
        }
        o.set(a.get(0));
        pl.saveSettings();
        Msg.ok(s, what + " is now " + o.get() + ".");
    }

    private void status(CommandSender s) {
        Settings st = pl.settings();
        Msg.head(s, "TNT minecarts");
        Msg.line(s, "  " + modeLine());
        Msg.line(s, "  Alive now: " + pl.tnt().activeCount() + " of at most " + st.tntMaxActive + " (/pd tnt limit), running launches: " + pl.tnt().runningSequences());
        Msg.line(s, "  Portals are " + (st.protectPortals ? "protected" : "NOT protected") + " from explosions (/pd tnt portal-protection on|off)");
        Msg.line(s, "  Each launch: " + st.tntPerPortal + " per portal, " + st.tntWaves + " wave(s), speed " + st.tntSpeed
                + ". More settings: /pd settings tnt");
    }
}
