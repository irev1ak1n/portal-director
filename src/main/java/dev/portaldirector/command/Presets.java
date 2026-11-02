package dev.portaldirector.command;

import dev.portaldirector.Settings;
import dev.portaldirector.gen.FacingMode;
import dev.portaldirector.gen.Layout;
import dev.portaldirector.gen.Orientation;
import dev.portaldirector.gen.Placement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Ready-to-use looks. Each one sets the generator (and TNT) options and names the scene after itself. */
final class Presets {

    record Preset(String description, Consumer<Settings> apply) {
    }

    static final Map<String, Preset> ALL = new LinkedHashMap<>();

    static {
        ALL.put("invasion", new Preset("Random portals on the ground and in the air, all facing the middle.", s -> {
            s.gen.count = 40;
            s.gen.radius = 80;
            s.gen.spacing = 8;
            s.gen.placement = Placement.MIXED;
            s.gen.airRatio = 0.5;
            s.gen.heightMin = 12;
            s.gen.heightMax = 50;
            s.gen.layout = Layout.RANDOM;
            s.gen.orientation = Orientation.RANDOM;
            s.gen.facing = FacingMode.AUTO;
        }));
        ALL.put("sky-army", new Preset("Floating portals at many different heights.", s -> {
            s.gen.count = 40;
            s.gen.radius = 70;
            s.gen.spacing = 6;
            s.gen.placement = Placement.AIR;
            s.gen.heightMin = 15;
            s.gen.heightMax = 70;
            s.gen.layout = Layout.RANDOM;
            s.gen.orientation = Orientation.RANDOM;
            s.gen.facing = FacingMode.AUTO;
        }));
        ALL.put("formation", new Preset("A tidy grid on the ground, everyone walking out the same way.", s -> {
            s.gen.count = 30;
            s.gen.radius = 60;
            s.gen.spacing = 4;
            s.gen.placement = Placement.GROUND;
            s.gen.layout = Layout.GRID;
            s.gen.orientation = Orientation.X;
            s.gen.facing = FacingMode.AUTO;
            s.walkTicks = 30;
        }));
        ALL.put("tnt-barrage", new Preset("A ring of portals around you with 2 minecarts each and 3 timed waves ready.", s -> {
            s.gen.count = 24;
            s.gen.radius = 45;
            s.gen.spacing = 5;
            s.gen.placement = Placement.MIXED;
            s.gen.airRatio = 0.3;
            s.gen.heightMin = 8;
            s.gen.heightMax = 30;
            s.gen.layout = Layout.CIRCLE;
            s.gen.orientation = Orientation.RANDOM;
            s.gen.facing = FacingMode.INWARD;
            s.tntPerPortal = 2;
            s.tntWaves = 3;
            s.tntWaveDelayTicks = 60;
            s.tntStaggerTicks = 2;
        }));
    }

    private Presets() {
    }
}
