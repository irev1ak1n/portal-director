package dev.portaldirector.db;

import java.util.List;
import java.util.Map;

/**
 * A saved recording setup: where a scene is, how its portals are laid out, who stands in which portal, which sequence runs and,
 * if wanted, where everyone starts and which arena is restored between takes. It describes a setup; it never starts anything.
 */
public record Take(
        long id,
        String name,
        String world,
        String scene,
        String sequence,
        Target target,
        String group,
        Map<String, String> formation,
        Map<String, String> sequenceSettings,
        List<Assignment> assignments,
        List<Position> positions,
        Arena arena,
        long createdMs,
        long updatedMs) {

    /** Where the sequence runs; facing is only used by sequences that need one. */
    public record Target(int x, int y, int z, String facing) {
    }

    public record Assignment(String uuid, String name, String portal) {
    }

    public record Position(String uuid, String world, double x, double y, double z, float yaw, float pitch) {
    }

    public record Arena(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String snapshot) {

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    public Take {
        formation = Map.copyOf(formation);
        sequenceSettings = Map.copyOf(sequenceSettings);
        assignments = List.copyOf(assignments);
        positions = List.copyOf(positions);
    }

    public Take withId(long newId) {
        return new Take(newId, name, world, scene, sequence, target, group, formation, sequenceSettings, assignments, positions, arena, createdMs, updatedMs);
    }

    public Take withArena(Arena newArena, long now) {
        return new Take(id, name, world, scene, sequence, target, group, formation, sequenceSettings, assignments, positions, newArena, createdMs, now);
    }

    public Take withPositions(List<Position> newPositions, long now) {
        return new Take(id, name, world, scene, sequence, target, group, formation, sequenceSettings, assignments, newPositions, arena, createdMs, now);
    }
}
