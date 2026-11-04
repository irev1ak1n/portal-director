package dev.portaldirector.group;

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
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Creates and stores groups: a name, a join block and a list of players. */
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
            groups.put(key.toLowerCase(Locale.ROOT), group);
            if (m != null) byBlock.put(m, group);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Group g : groups.values()) {
            String base = "groups." + g.name();
            y.set(base + ".join-block", g.joinBlock() == null ? null : g.joinBlock().name());
            for (Map.Entry<UUID, String> e : g.members().entrySet()) y.set(base + ".members." + e.getKey(), e.getValue());
            if (g.members().isEmpty()) y.createSection(base + ".members");
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

    public Group create(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Group names use letters, digits, - and _ (up to 24 characters).");
        }
        if (groups.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("A group called " + get(name).name() + " already exists.");
        }
        Group g = new Group(name);
        groups.put(name.toLowerCase(Locale.ROOT), g);
        save();
        return g;
    }

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

    public void remove(Group g, UUID player) {
        g.members().remove(player);
        save();
    }
}
