package dev.portaldirector.nether;

import dev.portaldirector.PortalDirectorPlugin;
import dev.portaldirector.group.Group;
import dev.portaldirector.model.PortalSpec;
import dev.portaldirector.scene.Scene;
import dev.portaldirector.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;

/**
 * Nether travel through PortalDirector portals. Only portals PortalDirector built are touched; every other portal keeps
 * vanilla behaviour.
 *
 * <ul>
 *   <li>A member of a group whose Nether travel is off, standing in the portal linked to them, stays where they are.</li>
 *   <li>Everyone else goes to the partner of the portal they entered, and comes back to the portal they left from. The
 *       teleport is still vanilla's; only its destination is changed to the partner, with a search radius of one block and
 *       no portal creation, so the wrong portal can never be picked and no extra portal is made.</li>
 * </ul>
 */
public final class PortalTravelListener implements Listener {

    private final PortalDirectorPlugin pl;

    public PortalTravelListener(PortalDirectorPlugin pl) {
        this.pl = pl;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        Player player = e.getPlayer();
        World from = player.getWorld();
        BoundingBox body = player.getBoundingBox();

        Scene hitScene = null;
        PortalSpec hit = null;
        for (Scene sc : pl.scenes().all()) {
            if (!sc.worldName().equals(from.getName())) continue;
            for (PortalSpec spec : sc.portals().values()) {
                if (spec.interiorBox().overlaps(body)) {
                    hitScene = sc;
                    hit = spec;
                    break;
                }
            }
            if (hit != null) break;
        }

        if (hit != null) {
            Group g = pl.groups().groupOf(player.getUniqueId());
            String linked = g == null ? null : g.portals().get(player.getUniqueId());
            if (g != null && !g.netherTravel() && linked != null && linked.equalsIgnoreCase(hitScene.nameOf(hit.id()))) {
                e.setCancelled(true);
                pl.getLogger().fine(player.getName() + " stays in " + linked + " (Nether travel is off for " + g.name() + ")");
                return;
            }
            World nether = NetherLinks.netherWorld(from);
            if (nether == null) return;
            PortalSpec partner;
            try {
                partner = pl.netherLinks().ensure(from, hitScene, hit);
            } catch (IllegalStateException ex) {
                e.setCancelled(true);
                Msg.err(player, ex.getMessage());
                pl.getLogger().warning("Nether link for " + hitScene.nameOf(hit.id()) + ": " + ex.getMessage());
                return;
            }
            pl.getLogger().info(player.getName() + " enters " + hitScene.nameOf(hit.id()) + " and is sent to its Nether partner at " + partner.anchorX() + " " + partner.anchorY() + " " + partner.anchorZ() + ".");
            send(e, nether, partner);
            return;
        }

        Object[] origin = pl.netherLinks().originOf(body, from.getName());
        if (origin == null) return;
        Scene scene = pl.scenes().get((String) origin[0]);
        PortalSpec back = scene == null ? null : scene.portals().get((Integer) origin[1]);
        World overworld = scene == null ? null : org.bukkit.Bukkit.getWorld(scene.worldName());
        if (back == null || overworld == null) return;
        pl.getLogger().info(player.getName() + " leaves the Nether partner of " + scene.nameOf(back.id()) + " and is sent back to it.");
        send(e, overworld, back);
    }

    /** Points the vanilla teleport at exactly this portal. */
    private static void send(PlayerPortalEvent e, World world, PortalSpec target) {
        Location to = new Location(world, target.centerX(), target.standY(), target.centerZ(), target.yaw(), 0f);
        e.setTo(to);
        e.setSearchRadius(1);
        e.setCreationRadius(1);
        e.setCanCreatePortal(false);
    }
}
