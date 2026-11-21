package dev.portaldirector.scene;

import dev.portaldirector.gen.SquareFacing;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.util.AtomicFiles;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Reads and writes scenes under {@code scenes/}: a readable .yml and a compact .blocks file per scene. */
public final class SceneStore {

    private final File dir;

    public SceneStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    public boolean exists(String name) {
        return yml(name).exists();
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        }
        out.sort(String::compareTo);
        return out;
    }

    public void save(Scene scene) throws IOException {
        save(SceneSnapshot.of(scene));
    }

    /** Blocks first, then the portal list: if a crash falls in between, the log has too much (safe to restore) and never too little. */
    public void save(SceneSnapshot scene) throws IOException {
        AtomicFiles.write(blocksFile(scene.name()).toPath(), out -> BlockLog.write(new DataOutputStream(out), scene.blocks()));

        YamlConfiguration y = new YamlConfiguration();
        y.set("world", scene.world());
        List<Map<String, Object>> list = new ArrayList<>();
        for (SceneSnapshot.Portal portal : scene.portals()) {
            PortalSpec p = portal.spec();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", portal.name());
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("z", p.z());
            m.put("axis", p.axis().name());
            m.put("facing", p.facing().name());
            m.put("floating", p.floating());
            list.add(m);
        }
        y.set("portals", list);
        SquareInfo sq = scene.square();

        if (sq != null) {
            y.set("square.center-x", sq.centerX());
            y.set("square.center-z", sq.centerZ());
            y.set("square.size", sq.size());
            y.set("square.per-side", sq.perSide());
            y.set("square.layers", sq.layers());
            y.set("square.spacing", sq.spacing());
            y.set("square.facing", sq.facing().name());
            y.set("square.ids", sq.ids());
        }
        AtomicFiles.writeString(yml(scene.name()).toPath(), y.saveToString());
    }

    public Scene load(String name) throws IOException {
        File f = yml(name);
        if (!f.exists()) throw new IOException("No scene named " + name);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Scene scene = new Scene(name, y.getString("world", "world"));
        for (Map<?, ?> m : y.getMapList("portals")) {
            PortalSpec p = new PortalSpec(
                    num(m.get("id")), num(m.get("x")), num(m.get("y")), num(m.get("z")),
                    Axis.valueOf(String.valueOf(m.get("axis"))), BlockFace.valueOf(String.valueOf(m.get("facing"))),
                    Boolean.parseBoolean(String.valueOf(m.get("floating"))));
            // Scenes saved before portals had names have no "name": they get portal-<id>. Old player assignments in the file are ignored.
            Object portalName = m.get("name");
            scene.addPortal(p, portalName == null ? null : String.valueOf(portalName));
        }
        if (y.contains("square.size")) {
            scene.square(new SquareInfo(y.getInt("square.center-x"), y.getInt("square.center-z"), y.getInt("square.size"),
                    y.getInt("square.per-side"), y.getInt("square.layers", 1), y.getInt("square.spacing"),
                    SquareFacing.valueOf(y.getString("square.facing", "INWARD")), new ArrayList<>(y.getIntegerList("square.ids"))));
        }
        ConfigurationSection legacy = y.getConfigurationSection("assignments");
        if (legacy != null) {
            for (String group : legacy.getKeys(false)) {
                ConfigurationSection g = legacy.getConfigurationSection(group);
                if (g == null) continue;
                for (String id : g.getKeys(false)) {
                    try {
                        scene.legacyAssignments().computeIfAbsent(group, k -> new TreeMap<>()).put(Integer.parseInt(id), UUID.fromString(g.getString(id, "")));
                    } catch (IllegalArgumentException ignored) {
                        // a damaged entry is dropped
                    }
                }
            }
        }
        File blocks = blocksFile(name);
        if (blocks.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(blocks.toPath())))) {
                BlockLog log = BlockLog.read(in);
                for (BlockLog.Rec r : log.all()) {
                    scene.blocks().record(r.pos().x(), r.pos().y(), r.pos().z(), r.original(), r.placed());
                }
            }
        }
        return scene;
    }

    public void delete(String name) {
        yml(name).delete();
        blocksFile(name).delete();
    }

    public void rename(String from, String to) throws IOException {
        Files.move(yml(from).toPath(), yml(to).toPath());
        if (blocksFile(from).exists()) Files.move(blocksFile(from).toPath(), blocksFile(to).toPath());
    }

    private static int num(Object o) {
        return ((Number) o).intValue();
    }

    private File yml(String name) {
        return new File(dir, name + ".yml");
    }

    private File blocksFile(String name) {
        return new File(dir, name + ".blocks");
    }
}
