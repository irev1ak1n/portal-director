package dev.portaldirector.group;

import dev.portaldirector.scene.Scene;
import dev.portaldirector.scene.SceneManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Creates and stores groups: members, the join block, each member's portal and the cinematic switches. */
public final class GroupManager {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    private final File file;
    private final Logger log;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<Material, Group> byBlock = new EnumMap<>(Material.class);

    public GroupManager(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        groups.clear();
        byBlock.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("groups");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection g = root.getConfigurationSection(key);
            if (g == null) continue;
            Group group = new Group(key);
            Material m = g.getString("join-block") == null ? null : Material.matchMaterial(g.getString("join-block"));
            group.joinBlock(m);
            group.netherTravel(g.getBoolean("nether-travel", false));
            group.visibilityInPortal(g.getBoolean("visibility-in-portal", true));
            double delay = g.getDouble("visibility-in-portal-delay", Group.DEFAULT_VISIBILITY_DELAY);
            group.visibilityDelay(delay >= 0.05 && delay <= 10 ? delay : Group.DEFAULT_VISIBILITY_DELAY);
            ConfigurationSection members = g.getConfigurationSection("members");
            if (members != null) {
                for (String id : members.getKeys(false)) {
                    try {
                        group.members().put(UUID.fromString(id), members.getString(id, "?"));
                    } catch (IllegalArgumentException ignored) {
                        log.warning("Ignoring bad member id " + id + " in group " + key);
                    }
                }
            }
            ConfigurationSection portals = g.getConfigurationSection("portals");
            if (portals != null) {
                for (String id : portals.getKeys(false)) {
                    try {
                        UUID u = UUID.fromString(id);
                        if (group.has(u)) group.portals().put(u, portals.getString(id, ""));
                    } catch (IllegalArgumentException ignored) {
                        log.warning("Ignoring bad portal link " + id + " in group " + key);
                    }
                }
            }
            groups.put(key.toLowerCase(Locale.ROOT), group);
            if (m != null) byBlock.put(m, group);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Group g : groups.values()) {
            String base = "groups." + g.name();
            y.set(base + ".join-block", g.joinBlock() == null ? null : g.joinBlock().name());
            y.set(base + ".nether-travel", g.netherTravel());
            y.set(base + ".visibility-in-portal", g.visibilityInPortal());
            y.set(base + ".visibility-in-portal-delay", g.visibilityDelay());
            for (Map.Entry<UUID, String> e : g.members().entrySet()) y.set(base + ".members." + e.getKey(), e.getValue());
            if (g.members().isEmpty()) y.createSection(base + ".members");
            for (Map.Entry<UUID, String> e : g.portals().entrySet()) y.set(base + ".portals." + e.getKey(), e.getValue());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            log.severe("Could not save groups: " + e.getMessage());
        }
    }

    public List<Group> all() {
        return new ArrayList<>(groups.values());
    }

    public Group get(String name) {
        return groups.get(name.toLowerCase(Locale.ROOT));
    }

    /** The only group, or null when there are none or several. */
    public Group only() {
        return groups.size() == 1 ? groups.values().iterator().next() : null;
    }

    public Group create(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Group names use letters, digits, - and _ (up to 24 characters), for example Group1.");
        }
        if (groups.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("A group called " + get(name).name() + " already exists.");
        }
        Group g = new Group(name);
        groups.put(name.toLowerCase(Locale.ROOT), g);
        save();
        return g;
    }

    /** Removes the group with its members' links. Portals are not touched. */
    public void delete(Group g) {
        groups.remove(g.name().toLowerCase(Locale.ROOT));
        if (g.joinBlock() != null) byBlock.remove(g.joinBlock());
        save();
    }

    /** The group a player belongs to, if any. */
    public Group groupOf(UUID player) {
        for (Group g : groups.values()) {
            if (g.has(player)) return g;
        }
        return null;
    }

    public Group groupForBlock(Material m) {
        return byBlock.get(m);
    }

    public void setJoinBlock(Group g, Material m) {
        Group other = byBlock.get(m);
        if (other != null && other != g) {
            throw new IllegalArgumentException(m.name().toLowerCase(Locale.ROOT) + " already adds players to group " + other.name() + ".");
        }
        if (g.joinBlock() != null) byBlock.remove(g.joinBlock());
        g.joinBlock(m);
        if (m != null) byBlock.put(m, g);
        save();
    }

    /** Adds the player once. Returns false when they were already a member. */
    public boolean join(Group g, UUID player, String name) {
        boolean added = !g.has(player);
        g.members().put(player, name);
        if (added) save();
        return added;
    }

    /** Takes the player out of the group and clears their portal link. */
    public void remove(Group g, UUID player) {
        g.members().remove(player);
        g.portals().remove(player);
        save();
    }

    /** The member of {@code g} linked to this portal name (not case sensitive), or null. */
    public UUID ownerOf(Group g, String portalName) {
        for (Map.Entry<UUID, String> e : g.portals().entrySet()) {
            if (e.getValue().equalsIgnoreCase(portalName)) return e.getKey();
        }
        return null;
    }

    /** Links a member to a portal; returns the portal they had before, or null. The portal must be free. */
    public String link(Group g, UUID player, String portalName) {
        UUID owner = ownerOf(g, portalName);
        if (owner != null && !owner.equals(player)) {
            throw new IllegalArgumentException(portalName + " already belongs to " + g.members().getOrDefault(owner, "another player")
                    + ". Unlink them first with /pd group unlink-from-portal " + g.members().getOrDefault(owner, "<player>") + " " + portalName + ".");
        }
        String before = g.portals().put(player, portalName);
        save();
        return before;
    }

    public void unlink(Group g, UUID player) {
        g.portals().remove(player);
        save();
    }

    /** A portal was removed: every link to it goes. */
    public void forgetPortal(String portalName) {
        boolean changed = false;
        for (Group g : groups.values()) changed |= g.portals().values().removeIf(n -> n.equalsIgnoreCase(portalName));
        if (changed) save();
    }

    public void forgetAllPortals() {
        boolean changed = false;
        for (Group g : groups.values()) {
            changed |= !g.portals().isEmpty();
            g.portals().clear();
        }
        if (changed) save();
    }

    /**
     * Scenes saved by 1.2.0 and older keep player assignments as portal id to player. They become portal-name links on
     * the groups, once, and the scene files lose them the next time they are saved.
     */
    public int migrateLegacy(SceneManager scenes) {
        int moved = 0;
        List<Scene> order = new ArrayList<>(scenes.all());
        Scene current = scenes.current();
        if (current != null) {
            order.remove(current);
            order.add(0, current);
        }
        for (Scene sc : order) {
            if (sc.legacyAssignments().isEmpty()) continue;
            for (Map.Entry<String, TreeMap<Integer, UUID>> e : sc.legacyAssignments().entrySet()) {
                Group g = get(e.getKey());
                if (g == null) continue;
                for (Map.Entry<Integer, UUID> a : e.getValue().entrySet()) {
                    if (!sc.portals().containsKey(a.getKey()) || !g.has(a.getValue()) || g.portals().containsKey(a.getValue())) continue;
                    String name = sc.nameOf(a.getKey());
                    if (ownerOf(g, name) != null) continue;
                    g.portals().put(a.getValue(), name);
                    moved++;
                }
            }
            sc.legacyAssignments().clear();
            scenes.save(sc);
        }
        if (moved > 0) {
            save();
            log.info("Moved " + moved + " old player-to-portal assignments onto their groups.");
        }
        return moved;
    }
}
