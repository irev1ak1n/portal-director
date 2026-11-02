package dev.portaldirector.scene;

import dev.portaldirector.model.PortalSpec;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Owns all scenes, the one being worked on, and their files. */
public final class SceneManager {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final String DEFAULT_NAME = "default";

    private final SceneStore store;
    private final Logger log;
    private final Map<String, Scene> scenes = new LinkedHashMap<>();
    private Scene current;

    public SceneManager(SceneStore store, Logger log) {
        this.store = store;
        this.log = log;
    }

    public void loadAll(String currentName) {
        for (String name : store.names()) {
            try {
                scenes.put(name, store.load(name));
            } catch (IOException | RuntimeException e) {
                log.warning("Could not load scene " + name + ": " + e.getMessage());
            }
        }
        current = currentName == null ? null : scenes.get(currentName);
    }

    public static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!VALID_NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Scene names use letters, digits, - and _ (up to 32 characters).");
        }
        return n;
    }

    public Scene current() {
        return current;
    }

    public Scene get(String name) {
        return scenes.get(name.toLowerCase(Locale.ROOT));
    }

    public List<Scene> all() {
        return new ArrayList<>(scenes.values());
    }

    public boolean exists(String name) {
        return scenes.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Scene create(String name, World world) {
        String n = normalize(name);
        if (scenes.containsKey(n)) throw new IllegalArgumentException("A scene called " + n + " already exists.");
        Scene scene = new Scene(n, world.getName());
        scenes.put(n, scene);
        save(scene);
        return scene;
    }

    public void select(Scene scene) {
        current = scene;
    }

    /** The scene commands work on; created in the given world on first use. */
    public Scene currentOrCreate(World world) {
        if (current == null) {
            Scene s = scenes.get(DEFAULT_NAME);
            current = s != null ? s : create(DEFAULT_NAME, world);
        }
        return current;
    }

    public void save(Scene scene) {
        try {
            store.save(scene);
        } catch (IOException e) {
            log.severe("Could not save scene " + scene.name() + ": " + e.getMessage());
        }
    }

    public void saveAll() {
        for (Scene s : scenes.values()) save(s);
    }

    public Scene rename(Scene scene, String newName) {
        String n = normalize(newName);
        if (scenes.containsKey(n)) throw new IllegalArgumentException("A scene called " + n + " already exists.");
        String old = scene.name();
        save(scene);
        try {
            store.rename(old, n);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not rename: " + e.getMessage());
        }
        scenes.remove(old);
        scene.rename(n);
        scenes.put(n, scene);
        return scene;
    }

    public void delete(Scene scene) {
        scenes.remove(scene.name());
        store.delete(scene.name());
        if (current == scene) current = null;
    }

    /** Every portal in a world, across all scenes. */
    public List<PortalSpec> portalsIn(String worldName) {
        List<PortalSpec> out = new ArrayList<>();
        for (Scene s : scenes.values()) {
            if (s.worldName().equals(worldName)) out.addAll(s.portals().values());
        }
        return out;
    }

    /** True when the block is inside a scene portal's frame or walkway. Used to stop vanilla portal travel. */
    public boolean inPortalArea(Location loc) {
        if (loc.getWorld() == null) return false;
        String world = loc.getWorld().getName();
        for (Scene s : scenes.values()) {
            if (!s.worldName().equals(world)) continue;
            for (PortalSpec p : s.portals().values()) {
                if (p.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) return true;
            }
        }
        return false;
    }
}
