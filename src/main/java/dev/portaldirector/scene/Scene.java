package dev.portaldirector.scene;

import dev.portaldirector.model.PortalSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A named set of portals in one world, with the block log for safe removal. */
public final class Scene {

    private String name;
    private String worldName;
    private final TreeMap<Integer, PortalSpec> portals = new TreeMap<>();
    /** portal id to its display name. The id stays the internal key; people use the name. */
    private final Map<Integer, String> names = new LinkedHashMap<>();
    private final BlockLog blocks = new BlockLog();
    private SquareInfo square;

    public Scene(String name, String worldName) {
        this.name = name;
        this.worldName = worldName;
    }

    public String name() {
        return name;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public String worldName() {
        return worldName;
    }

    public void worldName(String worldName) {
        this.worldName = worldName;
    }

    public TreeMap<Integer, PortalSpec> portals() {
        return portals;
    }

    public List<PortalSpec> portalList() {
        return new ArrayList<>(portals.values());
    }

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,23}");
    /** Words that already mean something where a portal can be named, such as /pd tnt launch all. */
    private static final Set<String> RESERVED = Set.of("all", "floating", "ground", "square", "side", "group", "here", "auto", "clear", "swap", "force");

    /** The name a portal gets when nobody chose one. */
    public static String defaultName(int id) {
        return "portal-" + id;
    }

    /** Checks a name a person typed; throws with a helpful reason. Names are not case sensitive. */
    public String checkName(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a usable name. Start with a letter, then use letters, digits, - or _ (up to 24 characters), for example EntranceA.");
        }
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("'" + name + "' is a command word. Pick another name, for example " + name + "1.");
        }
        PortalSpec taken = byName(name);
        if (taken != null) {
            throw new IllegalArgumentException("A portal called " + nameOf(taken.id()) + " already exists at " + taken.anchorX() + " " + taken.anchorY() + " " + taken.anchorZ()
                    + ". Names are not case sensitive, so pick a different one (or /pd remove " + nameOf(taken.id()) + " first).");
        }
        return name;
    }

    /** Adds a portal under a default name. */
    public void addPortal(PortalSpec spec) {
        addPortal(spec, null);
    }

    /** Adds a portal; with no name it gets the default, or a variant of it if that is taken. */
    public void addPortal(PortalSpec spec, String name) {
        portals.put(spec.id(), spec);
        String n = name != null ? name : defaultName(spec.id());
        while (name == null && byName(n) != null && byName(n).id() != spec.id()) n = n + "-b";
        names.put(spec.id(), n);
    }

    public String nameOf(int id) {
        return names.getOrDefault(id, defaultName(id));
    }

    /** The portal with this name, ignoring case, or null. */
    public PortalSpec byName(String name) {
        for (PortalSpec p : portals.values()) {
            if (nameOf(p.id()).equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /** The portal whose anchor (the lower bottom interior block) is exactly this block, or null. */
    public PortalSpec atAnchor(int x, int y, int z) {
        for (PortalSpec p : portals.values()) {
            if (p.anchorX() == x && p.anchorY() == y && p.anchorZ() == z) return p;
        }
        return null;
    }

    public void clearPortals() {
        portals.clear();
        names.clear();
    }

    public int nextId() {
        return portals.isEmpty() ? 1 : portals.lastKey() + 1;
    }

    public SquareInfo square() {
        return square;
    }

    public void square(SquareInfo square) {
        this.square = square;
    }

    public BlockLog blocks() {
        return blocks;
    }

    public void dropPortal(int id) {
        portals.remove(id);
        names.remove(id);
    }
}
