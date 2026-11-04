package dev.portaldirector.group;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Adds a player to a group the first time they step on the group's join block. */
public final class JoinBlockListener implements Listener {

    private final GroupManager groups;

    public JoinBlockListener(GroupManager groups) {
        this.groups = groups;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        Material below = to.getWorld().getBlockAt(to.getBlockX(), (int) Math.floor(to.getY() - 0.05), to.getBlockZ()).getType();
        Group g = groups.groupForBlock(below);
        if (g == null) return;
        Player p = e.getPlayer();
        if (groups.groupOf(p.getUniqueId()) != null) return;
        if (groups.join(g, p.getUniqueId(), p.getName())) {
            p.sendActionBar(Component.text("Joined " + g.name(), NamedTextColor.LIGHT_PURPLE));
        }
    }
}
