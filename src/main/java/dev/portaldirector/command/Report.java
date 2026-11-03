package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Compass;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;

/** Tells the admin where their portals are, in plain words. */
final class Report {

    private Report() {
    }

    /** Where the scene's portals are seen from this sender: the player's position, or the scene's middle. */
    static double[] reference(CommandSender s, Scene sc) {
        if (s instanceof Player p && p.getWorld().getName().equals(sc.worldName())) {
            return new double[]{p.getLocation().getX(), p.getLocation().getZ()};
        }
        double x = 0;
        double z = 0;
        for (PortalSpec p : sc.portals().values()) {
            x += p.centerX();
            z += p.centerZ();
        }
        int n = Math.max(1, sc.portals().size());
        return new double[]{x / n, z / n};
    }

    static String where(double[] from, PortalSpec p) {
        return Compass.describe(p.centerX() - from[0], p.centerZ() - from[1]);
    }

    /** The "where is everything" lines shown after generating and by /pd where. */
    static void formation(CommandSender s, PortalDirectorPlugin pl, Scene sc) {
        if (sc.portals().isEmpty()) {
            Msg.info(s, "Scene " + sc.name() + " has no portals yet. /pd generate 30 builds some.");
            return;
        }
        double[] from = reference(s, sc);
        List<PortalSpec> sorted = sc.portals().values().stream()
                .sorted(Comparator.comparingDouble(p -> Math.hypot(p.centerX() - from[0], p.centerZ() - from[1]))).toList();
        PortalSpec near = sorted.get(0);
        PortalSpec far = sorted.get(sorted.size() - 1);
        String fromWhat = s instanceof Player ? "you" : "the middle of the scene";
        Msg.info(s, "Scene " + sc.name() + ": " + sc.portals().size() + " portals. Seen from " + fromWhat + ": nearest is portal " + near.id() + ", "
                + where(from, near) + "; farthest is portal " + far.id() + ", " + where(from, far) + ".");
        Msg.info(s, "See them: /pd view    Visit one: /pd portal tp " + near.id() + "    List them: /pd portal list");
        World w = Bukkit.getWorld(sc.worldName());
        if (w != null) {
            double farthest = Math.hypot(far.centerX() - from[0], far.centerZ() - from[1]);
            int visible = w.getViewDistance() * 16;
            if (farthest > visible - 16) {
                Msg.info(s, "Note: this server only sends land up to about " + visible + " blocks away (view distance " + w.getViewDistance()
                        + " chunks), so portals farther than that are invisible until you get closer. /pd view shows an overview.");
            }
        }
    }
}
