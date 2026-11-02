package dev.portaldirector.scene;

import dev.portaldirector.model.PortalSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** A named set of portals in one world, with group assignments and the block log for safe removal. */
public final class Scene {

    private String name;
    private String worldName;
    private final TreeMap<Integer, PortalSpec> portals = new TreeMap<>();
    /** group name (lower case) to portal id to player. */
    private final Map<String, TreeMap<Integer, UUID>> assignments = new LinkedHashMap<>();
    private final BlockLog blocks = new BlockLog();

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

    public int nextId() {
        return portals.isEmpty() ? 1 : portals.lastKey() + 1;
    }

    public BlockLog blocks() {
        return blocks;
    }

    public Map<String, TreeMap<Integer, UUID>> assignments() {
        return assignments;
    }

    public TreeMap<Integer, UUID> assignmentsOf(String group) {
        return assignments.computeIfAbsent(group.toLowerCase(), k -> new TreeMap<>());
    }

    public void dropPortal(int id) {
        portals.remove(id);
        for (TreeMap<Integer, UUID> map : assignments.values()) map.remove(id);
    }
}
