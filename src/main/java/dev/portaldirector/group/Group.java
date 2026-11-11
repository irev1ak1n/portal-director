package dev.portaldirector.group;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A named set of players. Each member may be linked to one portal (by its name), and the group carries the three
 * cinematic switches: whether its linked members may use Nether travel, whether prepared members are visible, and the
 * delay between appearance batches.
 */
public final class Group {

    public static final double DEFAULT_VISIBILITY_DELAY = 0.35;

    private final String name;
    private Material joinBlock;
    private final Map<UUID, String> members = new LinkedHashMap<>();
    /** Member uuid to the name of the portal they are linked to. Only members appear here. */
    private final Map<UUID, String> portals = new LinkedHashMap<>();
    private boolean netherTravel = false;
    private boolean visibilityInPortal = true;
    /** Whether stepping on the join block adds players. Off pauses joining; members and the block are kept. */
    private boolean enrollment = true;
    private double visibilityDelay = DEFAULT_VISIBILITY_DELAY;

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

    public Map<UUID, String> portals() {
        return portals;
    }

    public boolean has(UUID id) {
        return members.containsKey(id);
    }

    public boolean netherTravel() {
        return netherTravel;
    }

    public void netherTravel(boolean netherTravel) {
        this.netherTravel = netherTravel;
    }

    public boolean visibilityInPortal() {
        return visibilityInPortal;
    }

    public void visibilityInPortal(boolean visibilityInPortal) {
        this.visibilityInPortal = visibilityInPortal;
    }

    public boolean enrollment() {
        return enrollment;
    }

    public void enrollment(boolean enrollment) {
        this.enrollment = enrollment;
    }

    public double visibilityDelay() {
        return visibilityDelay;
    }

    public void visibilityDelay(double seconds) {
        this.visibilityDelay = seconds;
    }
}
