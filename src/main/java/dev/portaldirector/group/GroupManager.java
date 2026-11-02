package dev.portaldirector.group;

import dev.portaldirector.Settings;
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

/** Creates and stores groups and keeps portal assignments in the current scene. */
public final class GroupManager {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    private final File file;
    private final Logger log;
    private final Settings settings;
    private final SceneManager scenes;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<Material, Group> byBlock = new EnumMap<>(Material.class);

    public GroupManager(File file, Logger log, Settings settings, SceneManager scenes) {
        this.file = file;
        this.log = log;
        this.settings = settings;
        this.scenes = scenes;
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
        for (Scene s : scenes.all()) {
            if (s.assignments().remove(g.name().toLowerCase(Locale.ROOT)) != null) scenes.save(s);
        }
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

    /** Adds the player once. Returns the assigned portal id, 0 when no portal was given, -1 if already a member. */
    public int join(Group g, UUID player, String name) {
        if (g.has(player)) {
            g.members().put(player, name);
            return -1;
        }
        g.members().put(player, name);
        save();
        Scene scene = scenes.current();
        if (settings.autoAssignJoiners && scene != null && !scene.portals().isEmpty()) {
            TreeMap<Integer, UUID> map = scene.assignmentsOf(g.name());
            int free = Assigner.firstFree(new ArrayList<>(scene.portals().keySet()), map);
            if (free > 0) {
                Assigner.assign(map, free, player);
                scenes.save(scene);
                return free;
            }
        }
        return 0;
    }

    public void remove(Group g, UUID player) {
        g.members().remove(player);
        for (Scene s : scenes.all()) {
            TreeMap<Integer, UUID> map = s.assignments().get(g.name().toLowerCase(Locale.ROOT));
            if (map != null && map.values().removeIf(player::equals)) scenes.save(s);
        }
        save();
    }

    /** Assigns the whole group in the current scene. Returns the resulting map. */
    public TreeMap<Integer, UUID> autoAssign(Group g, Scene scene, boolean keepExisting) {
        TreeMap<Integer, UUID> existing = scene.assignmentsOf(g.name());
        TreeMap<Integer, UUID> result = Assigner.auto(new ArrayList<>(scene.portals().keySet()),
                new ArrayList<>(g.members().keySet()), existing, keepExisting);
        existing.clear();
        existing.putAll(result);
        scenes.save(scene);
        return result;
    }

    /** Fills free portals for members of auto-assign groups, used after a scene gets new portals. */
    public void fillAll(Scene scene) {
        if (!settings.autoAssignJoiners) return;
        for (Group g : groups.values()) {
            if (!g.members().isEmpty()) autoAssign(g, scene, true);
        }
    }
}
