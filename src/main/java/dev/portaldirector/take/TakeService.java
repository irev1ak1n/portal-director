package dev.portaldirector.take;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.Settings;
import dev.portaldirector.db.Take;
import dev.portaldirector.db.TakeRepository;
import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.sequence.SeqOption;
import dev.portaldirector.sequence.Sequence;
import dev.portaldirector.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Saves recording setups and runs them again. Saving only writes the setup down. Preparing checks it and puts settings and group
 * assignments back, but never moves a player or starts a sequence. Starting runs the sequence. Resetting stops it and clears what
 * the run built. Only one take is worked on at a time.
 */
public final class TakeService {

    /** What this server knows about a take that was prepared or started since the last restart. */
    private static final class Live {

        final Take take;
        final long runId;
        final boolean sceneWasEmpty;
        final List<String> portalsBefore;
        boolean started;

        Live(Take take, long runId, boolean sceneWasEmpty, List<String> portalsBefore) {
            this.take = take;
            this.runId = runId;
            this.sceneWasEmpty = sceneWasEmpty;
            this.portalsBefore = portalsBefore;
        }
    }

    private final PortalDirectorPlugin pl;
    private Live live;

    public TakeService(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    private void needDatabase() {
        if (!pl.database().available()) {
            throw new IllegalArgumentException("Takes need the database, and it is off (" + pl.database().status().detail() + "). /pd diagnostics shows why.");
        }
    }

    private static String clean(String name) {
        if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("A take name is 1 to 32 characters.");
        if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("A take name can only use letters, numbers, - and _.");

        return name;
    }

    private Take find(String name) throws java.sql.SQLException {
        return pl.database().await(pl.database().read(c -> TakeRepository.find(c, name))).orElseThrow(() -> new IllegalArgumentException("There is no take called '" + name + "'. /pd take list shows them."));
    }

    // Save

    public void save(CommandSender sender, String rawName, String sequenceName, boolean withPositions) throws java.sql.SQLException {
        needDatabase();
        String name = clean(rawName);
        World home = sender instanceof Player who ? who.getWorld() : Bukkit.getWorlds().get(0);
        Scene scene = pl.scenes().currentOrCreate(home);

        Sequence seq = null;
        if (sequenceName != null) {
            seq = pl.sequences().get(sequenceName);
            if (seq == null) throw new IllegalArgumentException("There is no sequence called '" + sequenceName + "'.");
        }

        Map<String, String> formation = new LinkedHashMap<>();
        for (Map.Entry<String, Settings.Option> e : pl.settings().options().entrySet()) {
            if (e.getKey().startsWith("generate.")) formation.put(e.getKey(), e.getValue().display());
        }

        Map<String, String> sequenceSettings = new LinkedHashMap<>();
        Group group = null;
        if (seq != null) {
            for (SeqOption o : seq.options()) sequenceSettings.put(o.key(), seq.value(o.key()));
            String groupName = sequenceSettings.getOrDefault("group", "");
            group = groupName.isEmpty() ? pl.groups().only() : pl.groups().get(groupName);
        }

        List<Take.Assignment> assignments = new ArrayList<>();
        List<Take.Position> positions = new ArrayList<>();
        if (group != null) {
            for (Map.Entry<UUID, String> m : group.members().entrySet()) {
                assignments.add(new Take.Assignment(m.getKey().toString(), m.getValue(), group.portals().get(m.getKey())));
                Player p = Bukkit.getPlayer(m.getKey());
                if (withPositions && p != null) {
                    Location l = p.getLocation();
                    positions.add(new Take.Position(m.getKey().toString(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
                }
            }
        }

        Take.Target target = null;
        if (sender instanceof Player p) {
            Location l = p.getLocation();
            target = new Take.Target(l.getBlockX(), l.getBlockY(), l.getBlockZ(), p.getFacing().name().toLowerCase(Locale.ROOT));
        }

        long now = System.currentTimeMillis();
        Take take = new Take(0, name, scene.worldName(), scene.name(), seq == null ? null : seq.name(), target, group == null ? null : group.name(),
                formation, sequenceSettings, assignments, positions, null, now, now);

        pl.database().await(pl.database().write(c -> TakeRepository.save(c, take)));
        dev.portaldirector.util.Msg.ok(sender, "Saved take " + name + ": scene " + scene.name() + (seq == null ? ", no sequence" : ", " + seq.name()) + ", " + assignments.size()
                + " assignments" + (withPositions ? ", " + positions.size() + " positions" : "") + (target == null ? ", no target (save it as a player to get one)" : "") + ". Nothing was started or moved.");
    }

    // Prepare

    public void prepare(CommandSender sender, String name) throws java.sql.SQLException {
        needDatabase();
        requireFree();
        Take take = find(name);
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        World world = Bukkit.getWorld(take.world());
        if (world == null) problems.add("the world " + take.world() + " is not loaded");

        Scene scene = pl.scenes().get(take.scene());
        if (scene == null) problems.add("the scene " + take.scene() + " no longer exists");

        Sequence seq = take.sequence() == null ? null : pl.sequences().get(take.sequence());
        if (take.sequence() != null && seq == null) problems.add("the sequence " + take.sequence() + " does not exist");

        Group group = take.group() == null ? null : pl.groups().get(take.group());
        if (take.group() != null && group == null) problems.add("the group " + take.group() + " no longer exists");

        if (!problems.isEmpty()) throw new IllegalArgumentException("Take " + take.name() + " cannot be prepared: " + String.join("; ", problems) + ".");

        pl.sequences().requireIdle();

        for (Map.Entry<String, String> e : take.formation().entrySet()) {
            Settings.Option o = pl.settings().options().get(e.getKey());
            if (o == null) continue;

            try {
                o.set(e.getValue());
            } catch (IllegalArgumentException ex) {
                notes.add("setting " + e.getKey() + " was kept as it is (" + ex.getMessage() + ")");
            }
        }
        pl.saveSettings();

        if (seq != null) {
            for (Map.Entry<String, String> e : take.sequenceSettings().entrySet()) {
                try {
                    seq.set(e.getKey(), e.getValue());
                } catch (IllegalArgumentException ex) {
                    notes.add(seq.name() + " " + e.getKey() + " was kept as it is (" + ex.getMessage() + ")");
                }
            }
        }

        int online = 0;
        for (Take.Assignment a : take.assignments()) {
            UUID id = UUID.fromString(a.uuid());
            if (Bukkit.getPlayer(id) != null) online++;
            else notes.add(a.name() + " is offline");

            if (group == null) continue;
            pl.groups().join(group, id, a.name());

            if (a.portal() == null) continue;
            if (scene.byName(a.portal()) == null) {
                notes.add("portal " + a.portal() + " of " + a.name() + " is not in the scene");
                continue;
            }

            try {
                pl.groups().link(group, id, a.portal());
            } catch (IllegalArgumentException ex) {
                notes.add(ex.getMessage());
            }
        }

        pl.scenes().select(scene);
        pl.scenes().save(scene);

        List<String> before = scene.portalList().stream().map(p -> scene.nameOf(p.id())).toList();
        long now = System.currentTimeMillis();
        long runId = pl.database().await(pl.database().write(c -> TakeRepository.startRun(c, take.id(), "PREPARED", now)));
        live = new Live(take, runId, before.isEmpty(), before);

        dev.portaldirector.util.Msg.ok(sender, "Take " + take.name() + " is prepared: settings and assignments are back, " + online + " of " + take.assignments().size() + " players are online. Nobody was moved and nothing started.");
        for (String n : notes) dev.portaldirector.util.Msg.info(sender, "  Note: " + n + ".");
        dev.portaldirector.util.Msg.info(sender, "  /pd take start " + take.name() + " runs it. /pd take stage " + take.name() + " first moves players to their saved spots, if you saved positions.");
    }

    // Stage (explicit position restore)

    public void stage(CommandSender sender, String name) throws java.sql.SQLException {
        needDatabase();
        Take take = find(name);
        if (take.positions().isEmpty()) throw new IllegalArgumentException("Take " + take.name() + " has no saved positions. Save it again with: /pd take save " + take.name() + " ... positions");

        int moved = 0;
        List<String> skipped = new ArrayList<>();
        for (Take.Position pos : take.positions()) {
            Player p = Bukkit.getPlayer(UUID.fromString(pos.uuid()));
            World w = Bukkit.getWorld(pos.world());
            if (p == null || w == null) {
                skipped.add(p == null ? "a player is offline" : "world " + pos.world() + " is missing");
                continue;
            }
            p.teleport(new Location(w, pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch()));
            p.setFallDistance(0f);
            moved++;
        }

        dev.portaldirector.util.Msg.ok(sender, "Moved " + moved + " players to their saved spots" + (skipped.isEmpty() ? "." : ". Skipped " + skipped.size() + ": " + String.join(", ", new TreeSet<>(skipped)) + "."));
    }

    // Start

    public void start(CommandSender sender, String name) throws java.sql.SQLException {
        needDatabase();
        Take take = find(name);

        if (live == null || !live.take.name().equalsIgnoreCase(take.name())) {
            throw new IllegalArgumentException("Prepare it first: /pd take prepare " + take.name() + ". Start never runs a take that was not prepared on this server run.");
        }
        if (live.started) throw new IllegalArgumentException("Take " + take.name() + " already started. /pd take reset " + take.name() + " first.");
        if (take.sequence() == null) throw new IllegalArgumentException("Take " + take.name() + " has no sequence to run.");
        if (take.target() == null) throw new IllegalArgumentException("Take " + take.name() + " has no target. Save it again as a player standing where it should happen.");

        pl.sequences().requireIdle();
        Take.Target t = take.target();
        String args = t.x() + " " + t.y() + " " + t.z() + (take.sequence().equals("nether-arrival") ? " " + t.facing() : "") + " " + take.world();
        live.started = true;

        long runId = live.runId;
        pl.database().write(c -> {
            TakeRepository.setRunStatus(c, runId, "RUNNING", List.of(), null);

            return null;
        });

        try {
            Sequence seq = pl.sequences().get(take.sequence());
            seq.run(sender, List.of(args.split(" ")));
        } catch (RuntimeException e) {
            live.started = false;
            pl.database().write(c -> {
                TakeRepository.setRunStatus(c, runId, "FAILED", List.of(), System.currentTimeMillis());

                return null;
            });

            throw e;
        }
    }

    // Reset

    public void reset(CommandSender sender, String name) throws java.sql.SQLException {
        needDatabase();
        Take take = find(name);

        if (live == null || !live.take.name().equalsIgnoreCase(take.name())) {
            throw new IllegalArgumentException("Take " + take.name() + " was not prepared or started on this server run, so there is nothing of it to reset.");
        }

        Live run = live;
        pl.tnt().stop();
        pl.sequences().stop(sender);

        Scene scene = pl.scenes().get(take.scene());
        List<String> built = new ArrayList<>();
        if (scene != null) {
            for (PortalSpec p : scene.portalList()) {
                String n = scene.nameOf(p.id());
                if (!run.portalsBefore.contains(n)) built.add(n);
            }
        }

        long runId = run.runId;
        pl.database().write(c -> {
            TakeRepository.setRunStatus(c, runId, "RESET", built, System.currentTimeMillis());

            return null;
        });
        live = null;

        if (built.isEmpty()) {
            dev.portaldirector.util.Msg.ok(sender, "Take " + take.name() + " is reset: nothing was built, so there is nothing to clear.");
        } else if (run.sceneWasEmpty) {
            dev.portaldirector.util.Msg.ok(sender, "Take " + take.name() + " is reset: effects stopped. Clearing the " + built.size() + " portals it built.");
            Bukkit.dispatchCommand(sender, "pd clear");
        } else {
            dev.portaldirector.util.Msg.ok(sender, "Take " + take.name() + " is reset: effects stopped. The scene had other portals before this take, so nothing was cleared. It built: " + String.join(", ", built)
                    + ". Remove them with /pd remove <name>.");
        }
    }

    private void requireFree() {
        if (live != null && live.started) throw new IllegalArgumentException("Take " + live.take.name() + " is running. /pd take reset " + live.take.name() + " first.");
    }

    // Listing

    public CompletableFuture<List<String>> names() {
        return pl.database().available() ? pl.database().read(TakeRepository::names) : CompletableFuture.completedFuture(List.of());
    }

    public void list(CommandSender sender) throws java.sql.SQLException {
        needDatabase();
        List<String> names = pl.database().await(pl.database().read(TakeRepository::names));
        dev.portaldirector.util.Msg.head(sender, "Takes");

        if (names.isEmpty()) {
            dev.portaldirector.util.Msg.line(sender, "  None yet. /pd take save <name> [sequence <name>] [positions] saves one.");

            return;
        }

        for (String n : names) dev.portaldirector.util.Msg.line(sender, "  " + n + (live != null && live.take.name().equalsIgnoreCase(n) ? (live.started ? "  (running)" : "  (prepared)") : ""));
    }

    public void info(CommandSender sender, String name) throws java.sql.SQLException {
        needDatabase();
        Take take = find(name);
        List<TakeRepository.Run> runs = pl.database().await(pl.database().read(c -> TakeRepository.runs(c, take.id(), 3)));

        dev.portaldirector.util.Msg.head(sender, "Take " + take.name());
        dev.portaldirector.util.Msg.line(sender, "  Scene " + take.scene() + " in " + take.world() + ", sequence " + (take.sequence() == null ? "none" : take.sequence()) + ", group " + (take.group() == null ? "none" : take.group()));
        dev.portaldirector.util.Msg.line(sender, "  Target " + (take.target() == null ? "none" : take.target().x() + " " + take.target().y() + " " + take.target().z() + " facing " + take.target().facing()));
        dev.portaldirector.util.Msg.line(sender, "  " + take.assignments().size() + " assignments, " + take.positions().size() + " saved positions, " + take.formation().size() + " formation settings");

        for (Take.Assignment a : take.assignments()) dev.portaldirector.util.Msg.line(sender, "    " + a.name() + " -> " + (a.portal() == null ? "no portal" : a.portal()));

        for (TakeRepository.Run r : runs) dev.portaldirector.util.Msg.line(sender, "  Run " + r.id() + ": " + r.status());
    }

    public void delete(CommandSender sender, String name) throws java.sql.SQLException {
        needDatabase();
        if (live != null && live.take.name().equalsIgnoreCase(name)) throw new IllegalArgumentException("That take is prepared or running. /pd take reset " + name + " first.");

        boolean gone = pl.database().await(pl.database().write(c -> TakeRepository.delete(c, name)));
        if (!gone) throw new IllegalArgumentException("There is no take called '" + name + "'.");

        dev.portaldirector.util.Msg.ok(sender, "Deleted take " + name + ". Scenes, groups and portals were not touched.");
    }

    /** Drops what this server run remembers; takes in the database are untouched. */
    public void shutdown() {
        live = null;
    }
}
