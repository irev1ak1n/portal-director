package dev.portaldirector.sequence;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.group.Group;
import dev.portaldirector.util.PlayerTarget;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/** A built-in, reusable scene: what it is, what it needs to run, its own settings, and how it runs. */
public abstract class Sequence {

    /** Where a sequence happens: a world and a block, and the index of the first argument after it. */
    public record Target(World world, int x, int y, int z, int next) {
    }

    protected final PortalDirectorPlugin pl;
    protected final SequenceManager mgr;

    protected Sequence(SequenceManager mgr) {
        this.mgr = mgr;
        this.pl = mgr.plugin();
    }

    public abstract String name();

    /** The name people read: "Nether Arrival". */
    public abstract String title();

    /** One sentence for /pd sequence list. */
    public abstract String summary();

    /** The settings, A to Z. */
    public abstract List<SeqOption> options();

    /** What follows "/pd sequence <name> run". */
    public abstract String usage();

    public abstract String example();

    /** Plain answers to: are players moved, is TNT or portal travel involved. */
    public abstract List<String> facts();

    public abstract void run(CommandSender sender, List<String> args);

    /** Suggestions for the arguments after "run". */
    public abstract List<String> runTab(CommandSender sender, List<String> args);

    /** Pairs of (smallest, largest) options that must stay in order. */
    public List<String[]> pairs() {
        return List.of();
    }

    protected static List<SeqOption> sorted(List<SeqOption> options) {
        List<SeqOption> out = new ArrayList<>(options);
        out.sort(Comparator.comparing(o -> o.key().toLowerCase(Locale.ROOT)));
        return out;
    }

    public SeqOption option(String key) {
        for (SeqOption o : options()) {
            if (o.key().equalsIgnoreCase(key)) return o;
        }
        return null;
    }

    public String value(String key) {
        SeqOption o = option(key);
        if (o == null) throw new IllegalStateException("no option " + key);
        return mgr.settings().get(name(), o);
    }

    public int whole(String key) {
        return Integer.parseInt(value(key));
    }

    public double decimal(String key) {
        return Double.parseDouble(value(key));
    }

    /** Checks and stores one setting for this sequence only; returns the value as stored. */
    public String set(String key, String raw) {
        SeqOption o = option(key);
        if (o == null) {
            throw new IllegalArgumentException(name() + " has no setting called '" + key + "'. Its settings: "
                    + String.join(", ", options().stream().map(SeqOption::key).toList()) + ".");
        }
        String v = o.normalize(raw);
        for (String[] pair : pairs()) {
            if (o.key().equals(pair[0])) {
                SeqOption big = option(pair[1]);
                if (Double.parseDouble(v) > Double.parseDouble(mgr.settings().get(name(), big))) {
                    throw new IllegalArgumentException(pair[0] + " (" + v + ") cannot be above " + pair[1] + " (" + mgr.settings().get(name(), big) + "). Raise " + pair[1] + " first.");
                }
            } else if (o.key().equals(pair[1])) {
                SeqOption small = option(pair[0]);
                if (Double.parseDouble(v) < Double.parseDouble(mgr.settings().get(name(), small))) {
                    throw new IllegalArgumentException(pair[1] + " (" + v + ") cannot be below " + pair[0] + " (" + mgr.settings().get(name(), small) + "). Lower " + pair[0] + " first.");
                }
            }
        }
        if (o.key().equals("group") && !v.isEmpty() && pl.groups().get(v) == null) {
            throw new IllegalArgumentException("There is no group called " + v + "." + groupHint());
        }
        if (o.key().equals("group") && !v.isEmpty()) v = pl.groups().get(v).name();
        mgr.settings().set(name(), o, v);
        return v;
    }

    public void reset() {
        mgr.settings().reset(name());
    }

    // Shared helpers

    protected String groupHint() {
        List<Group> all = pl.groups().all();
        return all.isEmpty() ? " Make one with /pd group create Group1." : " Your groups: " + String.join(", ", all.stream().map(Group::name).sorted(String.CASE_INSENSITIVE_ORDER).toList()) + ".";
    }

    /** The group named in the settings, or the only group when none is named. */
    protected Group resolveGroup() {
        String chosen = value("group");
        if (!chosen.isEmpty()) {
            Group g = pl.groups().get(chosen);
            if (g == null) throw new IllegalArgumentException("The group " + chosen + " no longer exists." + groupHint() + " Choose one with /pd sequence " + name() + " set group <name>.");
            return g;
        }
        Group only = pl.groups().only();
        if (only != null) return only;
        if (pl.groups().all().isEmpty()) throw new IllegalArgumentException("There are no groups yet. Make one with /pd group create Group1, add players with /pd group assign-to-group, then choose it with /pd sequence " + name() + " set group Group1.");
        throw new IllegalArgumentException("There is more than one group, so say which one: /pd sequence " + name() + " set group <name>." + groupHint());
    }

    /** The group's members who are online now, in a stable order. */
    protected List<Player> online(Group g) {
        List<Player> out = new ArrayList<>();
        for (var id : g.members().keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) out.add(p);
        }
        out.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        return out;
    }

    protected void requireIdle() {
        mgr.requireIdle();
        if (pl.activeJob() != null) throw pl.busy();
    }

    protected static BlockFace face(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "north", "n" -> BlockFace.NORTH;
            case "south", "s" -> BlockFace.SOUTH;
            case "east", "e" -> BlockFace.EAST;
            case "west", "w" -> BlockFace.WEST;
            default -> throw new IllegalArgumentException("'" + text + "' is not a direction. Use north, south, east or west.");
        };
    }

    private static int coord(String text, int base, boolean relativeOk) {
        try {
            if (text.startsWith("~")) {
                if (!relativeOk) throw new IllegalArgumentException("~ only works for players.");
                return base + (text.length() == 1 ? 0 : Integer.parseInt(text.substring(1)));
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a whole number coordinate.");
        }
    }

    /**
     * "here", the name of an online player (where they are right now), or "x y z", then the index of the next argument. A world
     * name goes after everything else, for the console.
     */
    protected Target target(CommandSender s, List<String> a, String missing) {
        if (a.isEmpty()) throw new IllegalArgumentException(missing);
        Player p = s instanceof Player pp ? pp : null;
        if (a.get(0).equalsIgnoreCase("here")) {
            if (p == null) throw new IllegalArgumentException("Only players can use 'here'. From the console give a player's name, or x y z and a world name at the end.");
            return new Target(p.getWorld(), p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ(), 1);
        }
        if (PlayerTarget.looksLikeName(a.get(0))) {
            Player at = PlayerTarget.online(a.get(0));
            return new Target(at.getWorld(), at.getLocation().getBlockX(), at.getLocation().getBlockY(), at.getLocation().getBlockZ(), 1);
        }
        if (a.size() < 3) throw new IllegalArgumentException(missing);
        int x = coord(a.get(0), p == null ? 0 : p.getLocation().getBlockX(), p != null);
        int y = coord(a.get(1), p == null ? 0 : p.getLocation().getBlockY(), p != null);
        int z = coord(a.get(2), p == null ? 0 : p.getLocation().getBlockZ(), p != null);
        return new Target(p == null ? null : p.getWorld(), x, y, z, 3);
    }

    /** The world: an optional last word, otherwise the sender's own. */
    protected World worldFor(Target t, List<String> a, int index) {
        World w = t.world();
        if (index < a.size()) {
            w = Bukkit.getWorld(a.get(index));
            if (w == null) throw new IllegalArgumentException("'" + a.get(index) + "' is not a loaded world.");
        }
        if (w == null) throw new IllegalArgumentException("Name the world at the end: /pd sequence " + name() + " run " + t.x() + " " + t.y() + " " + t.z() + " ... <world>");
        return w;
    }

    /** Tab completion for "here | a player | x y z" followed by whatever {@code rest} offers at each later position. */
    protected static List<String> targetTab(CommandSender s, List<String> a, IntFunction<List<String>> rest) {
        int i = a.size() - 1;
        Player p = s instanceof Player pp ? pp : null;
        if (i == 0) {
            List<String> out = new ArrayList<>();
            if (p != null) {
                out.add("here");
                out.add(String.valueOf(p.getLocation().getBlockX()));
                out.add("~");
            }
            out.addAll(PlayerTarget.names());
            return out;
        }
        if (a.get(0).equalsIgnoreCase("here") || PlayerTarget.looksLikeName(a.get(0))) return rest.apply(i - 1);
        if (i == 1) return p == null ? List.of() : List.of(String.valueOf(p.getLocation().getBlockY()), "~");
        if (i == 2) return p == null ? List.of() : List.of(String.valueOf(p.getLocation().getBlockZ()), "~");
        return rest.apply(i - 3);
    }

    protected static List<String> worlds() {
        return Bukkit.getWorlds().stream().map(World::getName).toList();
    }
}
