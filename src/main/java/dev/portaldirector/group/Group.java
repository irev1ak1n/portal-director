package dev.portaldirector.group;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A named set of players. Members keep their join order, which decides automatic portal assignment. */
public final class Group {

    private final String name;
    private Material joinBlock;
    private final Map<UUID, String> members = new LinkedHashMap<>();

    public Group(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Material joinBlock() {
        return joinBlock;
    }

    public void joinBlock(Material joinBlock) {
        this.joinBlock = joinBlock;
    }

    /** Member uuid to last known name, in join order. */
    public Map<UUID, String> members() {
        return members;
    }

    public boolean has(UUID id) {
        return members.containsKey(id);
    }
}
