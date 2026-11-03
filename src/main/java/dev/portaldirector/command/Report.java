package dev.portaldirector.command;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** What to tell the admin after portals were built. */
final class Report {

    private Report() {
    }

    static void formation(CommandSender s, PortalDirectorPlugin pl, Scene sc) {
        if (sc.portals().isEmpty()) return;
        Msg.info(s, "/pd list shows the id and coordinates of every portal in scene " + sc.name() + ".");
        World w = Bukkit.getWorld(sc.worldName());
        if (w == null) return;
        double fromX = 0;
        double fromZ = 0;
        if (s instanceof Player p && p.getWorld() == w) {
            fromX = p.getLocation().getX();
            fromZ = p.getLocation().getZ();
        } else {
            for (PortalSpec p : sc.portals().values()) {
                fromX += p.centerX() / sc.portals().size();
                fromZ += p.centerZ() / sc.portals().size();
            }
        }
        double farthest = 0;
        for (PortalSpec p : sc.portals().values()) farthest = Math.max(farthest, Math.hypot(p.centerX() - fromX, p.centerZ() - fromZ));
        int visible = w.getViewDistance() * 16;
        if (farthest > visible - 16) {
            Msg.info(s, "Note: this server only sends land up to about " + visible + " blocks away (view distance " + w.getViewDistance()
                    + " chunks), so portals farther than that are not visible until you get closer. Raise the server's view-distance or use a smaller /pd set radius.");
        }
    }
}
