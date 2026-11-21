package dev.portaldirector.scene;

import dev.portaldirector.model.PortalSpec;

import java.util.List;

/** An immutable copy of everything a scene file holds, taken on the main thread so a background thread can write it safely. */
public record SceneSnapshot(String name, String world, List<Portal> portals, SquareInfo square, List<BlockLog.Rec> blocks) {

    public record Portal(PortalSpec spec, String name) {
    }

    public static SceneSnapshot of(Scene scene) {
        List<Portal> portals = scene.portals().values().stream()
                .map(p -> new Portal(p, scene.nameOf(p.id())))
                .toList();

        SquareInfo sq = scene.square();
        SquareInfo square = sq == null ? null : new SquareInfo(sq.centerX(), sq.centerZ(), sq.size(), sq.perSide(), sq.layers(), sq.spacing(), sq.facing(), List.copyOf(sq.ids()));

        return new SceneSnapshot(scene.name(), scene.worldName(), portals, square, scene.blocks().snapshot());
    }
}
